BEGIN;

-- Optional per-location override. NULL means "use the product's reorder threshold".
ALTER TABLE inventory
    ADD COLUMN low_stock_threshold INTEGER CHECK (low_stock_threshold >= 0);

-- Transactional outbox: rows commit or roll back together with the alert change
-- that produced them, so a notification is never sent for a change that did not
-- happen and never lost for one that did.
CREATE TABLE alert_notification (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id BIGINT NOT NULL REFERENCES inventory_alert(id) ON DELETE CASCADE,
    event_type VARCHAR(20) NOT NULL CHECK (event_type IN ('RAISED', 'ESCALATED', 'RESOLVED')),
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    locked_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CHECK ((claim_token IS NULL) = (locked_until IS NULL))
);

-- The dispatcher's claim query scans only undelivered rows in due order.
CREATE INDEX idx_alert_notification_due
    ON alert_notification (next_attempt_at, id)
    WHERE status = 'PENDING';
CREATE INDEX idx_alert_notification_alert ON alert_notification (alert_id);
CREATE INDEX idx_alert_notification_recent ON alert_notification (created_at DESC, id DESC);

CREATE FUNCTION alert_notification_payload(p_alert inventory_alert, p_event VARCHAR)
RETURNS JSONB
LANGUAGE sql
STABLE
AS $$
    SELECT jsonb_build_object(
        'event', p_event,
        'alertId', p_alert.id,
        'alertType', p_alert.alert_type,
        'severity', p_alert.severity,
        'productId', p_alert.product_id,
        'sku', p.sku,
        'productName', p.name,
        'warehouseId', p_alert.warehouse_id,
        'warehouseCode', w.code,
        'message', p_alert.message,
        'expectedQuantity', p_alert.expected_quantity,
        'actualQuantity', p_alert.actual_quantity,
        'resolutionNote', p_alert.resolution_note,
        'occurredAt', CURRENT_TIMESTAMP
    )
    FROM product p, warehouse w
    WHERE p.id = p_alert.product_id AND w.id = p_alert.warehouse_id;
$$;

-- Enqueue only meaningful state changes. The low-stock trigger refreshes an
-- open alert on every sale below threshold; those refreshes are not new events
-- unless the alert escalates from WARNING to CRITICAL.
CREATE FUNCTION enqueue_alert_notification() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_event VARCHAR(20);
BEGIN
    IF TG_OP = 'INSERT' THEN
        v_event := 'RAISED';
    ELSIF OLD.resolved_at IS NULL AND NEW.resolved_at IS NOT NULL THEN
        v_event := 'RESOLVED';
    ELSIF NEW.resolved_at IS NULL AND OLD.severity <> 'CRITICAL' AND NEW.severity = 'CRITICAL' THEN
        v_event := 'ESCALATED';
    ELSE
        RETURN NULL;
    END IF;

    INSERT INTO alert_notification (alert_id, event_type, payload)
    VALUES (NEW.id, v_event, alert_notification_payload(NEW, v_event));
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_alert_notification
AFTER INSERT OR UPDATE ON inventory_alert
FOR EACH ROW
EXECUTE FUNCTION enqueue_alert_notification();

-- Low-stock evaluation now honours the per-location override.
CREATE OR REPLACE FUNCTION low_stock_alert_trigger() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_threshold INTEGER;
    v_product_name VARCHAR(150);
BEGIN
    SELECT COALESCE(NEW.low_stock_threshold, reorder_threshold), name
    INTO v_threshold, v_product_name
    FROM product
    WHERE id = NEW.product_id;

    IF NEW.quantity < v_threshold THEN
        INSERT INTO inventory_alert (
            alert_type, severity, product_id, warehouse_id, message,
            expected_quantity, actual_quantity
        ) VALUES (
            'LOW_STOCK',
            CASE WHEN NEW.quantity = 0 THEN 'CRITICAL' ELSE 'WARNING' END,
            NEW.product_id,
            NEW.warehouse_id,
            format('%s has %s units; reorder threshold is %s', v_product_name, NEW.quantity, v_threshold),
            v_threshold,
            NEW.quantity
        )
        ON CONFLICT (product_id, warehouse_id)
            WHERE alert_type = 'LOW_STOCK' AND resolved_at IS NULL
        DO UPDATE SET
            severity = EXCLUDED.severity,
            message = EXCLUDED.message,
            expected_quantity = EXCLUDED.expected_quantity,
            actual_quantity = EXCLUDED.actual_quantity,
            created_at = CURRENT_TIMESTAMP;
    ELSE
        UPDATE inventory_alert
        SET resolved_at = CURRENT_TIMESTAMP,
            resolution_note = 'Automatically resolved after stock recovery'
        WHERE product_id = NEW.product_id
          AND warehouse_id = NEW.warehouse_id
          AND alert_type = 'LOW_STOCK'
          AND resolved_at IS NULL;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER trg_low_stock_alert ON inventory;
CREATE TRIGGER trg_low_stock_alert
AFTER UPDATE OF quantity, low_stock_threshold ON inventory
FOR EACH ROW
EXECUTE FUNCTION low_stock_alert_trigger();

-- Changing a product's default threshold re-evaluates every location that has
-- no override, so alerts reflect the new rule without waiting for a stock move.
CREATE FUNCTION product_threshold_reevaluation_trigger() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE inventory
    SET low_stock_threshold = low_stock_threshold
    WHERE product_id = NEW.id AND low_stock_threshold IS NULL;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_product_threshold_reevaluation
AFTER UPDATE OF reorder_threshold ON product
FOR EACH ROW
WHEN (OLD.reorder_threshold IS DISTINCT FROM NEW.reorder_threshold)
EXECUTE FUNCTION product_threshold_reevaluation_trigger();

-- Sets or clears (p_threshold NULL) a location override, audits the change,
-- and lets the low-stock trigger re-evaluate in the same transaction.
CREATE FUNCTION set_low_stock_threshold(
    p_product_id BIGINT, p_warehouse_id BIGINT, p_threshold INTEGER, p_actor_user_id BIGINT
) RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_old INTEGER;
    v_effective INTEGER;
BEGIN
    IF p_threshold IS NOT NULL AND p_threshold < 0 THEN
        RAISE EXCEPTION 'Low-stock threshold cannot be negative' USING ERRCODE = 'P0001';
    END IF;

    SELECT low_stock_threshold INTO v_old
    FROM inventory
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Inventory location does not exist' USING ERRCODE = 'P0001';
    END IF;

    PERFORM set_config('app.current_user_id', p_actor_user_id::TEXT, TRUE);
    UPDATE inventory
    SET low_stock_threshold = p_threshold
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id;

    INSERT INTO audit_log (entity_type, entity_key, action, old_values, new_values, actor_user_id)
    VALUES (
        'inventory_threshold',
        p_product_id::TEXT || ':' || p_warehouse_id::TEXT,
        'UPDATE',
        jsonb_build_object('lowStockThreshold', v_old),
        jsonb_build_object('lowStockThreshold', p_threshold),
        p_actor_user_id
    );

    SELECT COALESCE(p_threshold, reorder_threshold) INTO v_effective
    FROM product WHERE id = p_product_id;
    RETURN v_effective;
END;
$$;

-- Existing installations: open alerts raised before the outbox existed still
-- deserve one notification.
INSERT INTO alert_notification (alert_id, event_type, payload)
SELECT a.id, 'RAISED', alert_notification_payload(a, 'RAISED')
FROM inventory_alert a
WHERE a.resolved_at IS NULL;

COMMIT;
