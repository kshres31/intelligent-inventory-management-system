BEGIN;

CREATE OR REPLACE FUNCTION inventory_audit_trigger() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_actor_id BIGINT;
BEGIN
    v_actor_id := NULLIF(current_setting('app.current_user_id', TRUE), '')::BIGINT;

    INSERT INTO audit_log (
        entity_type, entity_key, action, old_values, new_values, actor_user_id
    ) VALUES (
        'inventory',
        OLD.product_id::TEXT || ':' || OLD.warehouse_id::TEXT,
        'UPDATE',
        jsonb_build_object('quantity', OLD.quantity),
        jsonb_build_object(
            'quantity', NEW.quantity,
            'change', NEW.quantity - OLD.quantity
        ),
        v_actor_id
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inventory_audit
AFTER UPDATE OF quantity ON inventory
FOR EACH ROW
WHEN (OLD.quantity IS DISTINCT FROM NEW.quantity)
EXECUTE FUNCTION inventory_audit_trigger();

CREATE OR REPLACE FUNCTION low_stock_alert_trigger() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_threshold INTEGER;
    v_product_name VARCHAR(150);
BEGIN
    SELECT reorder_threshold, name INTO v_threshold, v_product_name
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

CREATE TRIGGER trg_low_stock_alert
AFTER UPDATE OF quantity ON inventory
FOR EACH ROW
EXECUTE FUNCTION low_stock_alert_trigger();

CREATE OR REPLACE FUNCTION discrepancy_alert_trigger() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_threshold INTEGER;
    v_product_name VARCHAR(150);
BEGIN
    SELECT discrepancy_threshold, name INTO v_threshold, v_product_name
    FROM product
    WHERE id = NEW.product_id;

    IF abs(NEW.variance) > v_threshold THEN
        INSERT INTO inventory_alert (
            alert_type, severity, product_id, warehouse_id, message,
            expected_quantity, actual_quantity
        ) VALUES (
            'DISCREPANCY',
            CASE WHEN abs(NEW.variance) > greatest(v_threshold * 2, 1) THEN 'CRITICAL' ELSE 'WARNING' END,
            NEW.product_id,
            NEW.warehouse_id,
            format('%s count differs by %s units', v_product_name, NEW.variance),
            NEW.expected_quantity,
            NEW.counted_quantity
        )
        ON CONFLICT (product_id, warehouse_id)
            WHERE alert_type = 'DISCREPANCY' AND resolved_at IS NULL
        DO UPDATE SET
            severity = EXCLUDED.severity,
            message = EXCLUDED.message,
            expected_quantity = EXCLUDED.expected_quantity,
            actual_quantity = EXCLUDED.actual_quantity,
            created_at = CURRENT_TIMESTAMP;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_discrepancy_alert
AFTER INSERT ON inventory_count
FOR EACH ROW
EXECUTE FUNCTION discrepancy_alert_trigger();

COMMIT;
