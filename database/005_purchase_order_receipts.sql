BEGIN;

CREATE TABLE purchase_order_receipt (
    purchase_order_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    actor_user_id BIGINT NOT NULL REFERENCES app_user(id),
    received_quantity_after INTEGER NOT NULL CHECK (received_quantity_after >= quantity),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (purchase_order_id, request_id),
    FOREIGN KEY (purchase_order_id, product_id)
        REFERENCES purchase_order_line(purchase_order_id, product_id)
);

-- One receipt, including its retry record, commits with stock, alerts and audit.
CREATE FUNCTION receive_purchase_order_line(
    p_order_id BIGINT, p_product_id BIGINT, p_quantity INTEGER,
    p_actor_user_id BIGINT, p_request_id UUID
) RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_order purchase_order%ROWTYPE;
    v_line purchase_order_line%ROWTYPE;
    v_receipt purchase_order_receipt%ROWTYPE;
    v_received INTEGER;
BEGIN
    IF p_quantity IS NULL OR p_quantity <= 0 OR p_request_id IS NULL THEN
        RAISE EXCEPTION 'Positive receipt quantity and request UUID are required' USING ERRCODE = 'P0001';
    END IF;

    -- Serialize receipts, line edits and cancellation for this order.
    SELECT * INTO v_order FROM purchase_order WHERE id = p_order_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Purchase order does not exist' USING ERRCODE = 'P0001';
    END IF;

    SELECT * INTO v_receipt FROM purchase_order_receipt
    WHERE purchase_order_id = p_order_id AND request_id = p_request_id;
    IF FOUND THEN
        IF v_receipt.product_id IS DISTINCT FROM p_product_id
           OR v_receipt.quantity IS DISTINCT FROM p_quantity
           OR v_receipt.actor_user_id IS DISTINCT FROM p_actor_user_id THEN
            RAISE EXCEPTION 'Request UUID was already used with different receipt details' USING ERRCODE = 'P0001';
        END IF;
        RETURN v_receipt.received_quantity_after;
    END IF;

    IF v_order.status NOT IN ('SUBMITTED', 'PARTIALLY_RECEIVED') THEN
        RAISE EXCEPTION 'Only submitted or partially received orders accept new receipts' USING ERRCODE = 'P0001';
    END IF;

    SELECT * INTO v_line FROM purchase_order_line
    WHERE purchase_order_id = p_order_id AND product_id = p_product_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Product is not a line on this purchase order' USING ERRCODE = 'P0001';
    END IF;
    IF p_quantity > v_line.ordered_quantity - v_line.received_quantity THEN
        RAISE EXCEPTION 'Receipt exceeds the outstanding ordered quantity' USING ERRCODE = 'P0001';
    END IF;

    PERFORM receive_inventory(p_product_id, v_order.warehouse_id, p_quantity, p_actor_user_id,
                              ('PO:' || p_order_id || ':' || p_request_id)::VARCHAR);
    v_received := v_line.received_quantity + p_quantity;
    UPDATE purchase_order_line SET received_quantity = v_received WHERE id = v_line.id;
    UPDATE purchase_order
    SET status = CASE WHEN EXISTS (
            SELECT 1 FROM purchase_order_line
            WHERE purchase_order_id = p_order_id AND received_quantity < ordered_quantity
        ) THEN 'PARTIALLY_RECEIVED' ELSE 'RECEIVED' END,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_order_id;

    INSERT INTO purchase_order_receipt (
        purchase_order_id, request_id, product_id, quantity, actor_user_id, received_quantity_after
    ) VALUES (p_order_id, p_request_id, p_product_id, p_quantity, p_actor_user_id, v_received);
    RETURN v_received;
END;
$$;

COMMIT;
