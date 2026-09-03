BEGIN;

CREATE OR REPLACE FUNCTION receive_inventory(
    p_product_id BIGINT,
    p_warehouse_id BIGINT,
    p_quantity INTEGER,
    p_actor_user_id BIGINT,
    p_reference VARCHAR DEFAULT NULL
) RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_before INTEGER;
    v_after INTEGER;
BEGIN
    IF p_quantity <= 0 THEN
        RAISE EXCEPTION 'Received quantity must be positive' USING ERRCODE = 'P0001';
    END IF;

    PERFORM set_config('app.current_user_id', p_actor_user_id::TEXT, TRUE);
    INSERT INTO inventory (product_id, warehouse_id, quantity)
    VALUES (p_product_id, p_warehouse_id, 0)
    ON CONFLICT (product_id, warehouse_id) DO NOTHING;

    SELECT quantity INTO v_before
    FROM inventory
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id
    FOR UPDATE;

    v_after := v_before + p_quantity;
    UPDATE inventory
    SET quantity = v_after, updated_at = CURRENT_TIMESTAMP
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id;

    INSERT INTO stock_transaction (
        product_id, warehouse_id, transaction_type, quantity_change,
        quantity_before, quantity_after, actor_user_id, reference
    ) VALUES (
        p_product_id, p_warehouse_id, 'RECEIVE', p_quantity,
        v_before, v_after, p_actor_user_id, p_reference
    );

    RETURN v_after;
END;
$$;

CREATE OR REPLACE FUNCTION process_stock_sale(
    p_product_id BIGINT,
    p_warehouse_id BIGINT,
    p_quantity INTEGER,
    p_actor_user_id BIGINT,
    p_reference VARCHAR DEFAULT NULL
) RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_before INTEGER;
    v_after INTEGER;
BEGIN
    IF p_quantity <= 0 THEN
        RAISE EXCEPTION 'Sale quantity must be positive' USING ERRCODE = 'P0001';
    END IF;

    PERFORM set_config('app.current_user_id', p_actor_user_id::TEXT, TRUE);
    SELECT quantity INTO v_before
    FROM inventory
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'No inventory exists for product % at warehouse %', p_product_id, p_warehouse_id
            USING ERRCODE = 'P0001';
    END IF;
    IF v_before < p_quantity THEN
        RAISE EXCEPTION 'Insufficient stock: requested %, available %', p_quantity, v_before
            USING ERRCODE = 'P0001';
    END IF;

    v_after := v_before - p_quantity;
    UPDATE inventory
    SET quantity = v_after, updated_at = CURRENT_TIMESTAMP
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id;

    INSERT INTO stock_transaction (
        product_id, warehouse_id, transaction_type, quantity_change,
        quantity_before, quantity_after, actor_user_id, reference
    ) VALUES (
        p_product_id, p_warehouse_id, 'SALE', -p_quantity,
        v_before, v_after, p_actor_user_id, p_reference
    );

    RETURN v_after;
END;
$$;

CREATE OR REPLACE FUNCTION transfer_inventory(
    p_product_id BIGINT,
    p_source_warehouse_id BIGINT,
    p_destination_warehouse_id BIGINT,
    p_quantity INTEGER,
    p_actor_user_id BIGINT,
    p_reference VARCHAR DEFAULT NULL
) RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    v_source_before INTEGER;
    v_destination_before INTEGER;
    v_transfer_id UUID := gen_random_uuid();
BEGIN
    IF p_quantity <= 0 THEN
        RAISE EXCEPTION 'Transfer quantity must be positive' USING ERRCODE = 'P0001';
    END IF;
    IF p_source_warehouse_id = p_destination_warehouse_id THEN
        RAISE EXCEPTION 'Source and destination warehouses must differ' USING ERRCODE = 'P0001';
    END IF;

    PERFORM set_config('app.current_user_id', p_actor_user_id::TEXT, TRUE);
    INSERT INTO inventory (product_id, warehouse_id, quantity)
    VALUES (p_product_id, p_destination_warehouse_id, 0)
    ON CONFLICT (product_id, warehouse_id) DO NOTHING;

    -- A stable lock order prevents opposite-direction transfers from deadlocking.
    PERFORM 1
    FROM inventory
    WHERE product_id = p_product_id
      AND warehouse_id IN (p_source_warehouse_id, p_destination_warehouse_id)
    ORDER BY warehouse_id
    FOR UPDATE;

    SELECT quantity INTO v_source_before
    FROM inventory
    WHERE product_id = p_product_id AND warehouse_id = p_source_warehouse_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No source inventory exists for product % at warehouse %',
            p_product_id, p_source_warehouse_id USING ERRCODE = 'P0001';
    END IF;
    IF v_source_before < p_quantity THEN
        RAISE EXCEPTION 'Insufficient stock: requested %, available %', p_quantity, v_source_before
            USING ERRCODE = 'P0001';
    END IF;

    SELECT quantity INTO v_destination_before
    FROM inventory
    WHERE product_id = p_product_id AND warehouse_id = p_destination_warehouse_id;

    UPDATE inventory
    SET quantity = v_source_before - p_quantity, updated_at = CURRENT_TIMESTAMP
    WHERE product_id = p_product_id AND warehouse_id = p_source_warehouse_id;
    UPDATE inventory
    SET quantity = v_destination_before + p_quantity, updated_at = CURRENT_TIMESTAMP
    WHERE product_id = p_product_id AND warehouse_id = p_destination_warehouse_id;

    INSERT INTO stock_transaction (
        product_id, warehouse_id, transaction_type, quantity_change,
        quantity_before, quantity_after, actor_user_id, transfer_id, reference
    ) VALUES
        (p_product_id, p_source_warehouse_id, 'TRANSFER_OUT', -p_quantity,
         v_source_before, v_source_before - p_quantity, p_actor_user_id, v_transfer_id, p_reference),
        (p_product_id, p_destination_warehouse_id, 'TRANSFER_IN', p_quantity,
         v_destination_before, v_destination_before + p_quantity, p_actor_user_id, v_transfer_id, p_reference);

    RETURN v_transfer_id;
END;
$$;

CREATE OR REPLACE FUNCTION perform_inventory_adjustment(
    p_product_id BIGINT,
    p_warehouse_id BIGINT,
    p_quantity_change INTEGER,
    p_actor_user_id BIGINT,
    p_reason VARCHAR
) RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_before INTEGER;
    v_after INTEGER;
BEGIN
    IF p_quantity_change = 0 THEN
        RAISE EXCEPTION 'Adjustment must change the quantity' USING ERRCODE = 'P0001';
    END IF;
    IF p_reason IS NULL OR btrim(p_reason) = '' THEN
        RAISE EXCEPTION 'Adjustment reason is required' USING ERRCODE = 'P0001';
    END IF;

    PERFORM set_config('app.current_user_id', p_actor_user_id::TEXT, TRUE);
    SELECT quantity INTO v_before
    FROM inventory
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No inventory exists for product % at warehouse %', p_product_id, p_warehouse_id
            USING ERRCODE = 'P0001';
    END IF;

    v_after := v_before + p_quantity_change;
    IF v_after < 0 THEN
        RAISE EXCEPTION 'Adjustment would make stock negative' USING ERRCODE = 'P0001';
    END IF;

    UPDATE inventory
    SET quantity = v_after, updated_at = CURRENT_TIMESTAMP
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id;

    INSERT INTO stock_transaction (
        product_id, warehouse_id, transaction_type, quantity_change,
        quantity_before, quantity_after, actor_user_id, reference
    ) VALUES (
        p_product_id, p_warehouse_id, 'ADJUSTMENT', p_quantity_change,
        v_before, v_after, p_actor_user_id, p_reason
    );

    RETURN v_after;
END;
$$;

CREATE OR REPLACE FUNCTION reconcile_inventory_count(
    p_product_id BIGINT,
    p_warehouse_id BIGINT,
    p_counted_quantity INTEGER,
    p_actor_user_id BIGINT,
    p_notes TEXT DEFAULT NULL
) RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_expected INTEGER;
    v_count_id BIGINT;
BEGIN
    IF p_counted_quantity < 0 THEN
        RAISE EXCEPTION 'Counted quantity cannot be negative' USING ERRCODE = 'P0001';
    END IF;

    PERFORM set_config('app.current_user_id', p_actor_user_id::TEXT, TRUE);
    SELECT quantity INTO v_expected
    FROM inventory
    WHERE product_id = p_product_id AND warehouse_id = p_warehouse_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No inventory exists for product % at warehouse %', p_product_id, p_warehouse_id
            USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO inventory_count (
        product_id, warehouse_id, expected_quantity, counted_quantity, counted_by, notes
    ) VALUES (
        p_product_id, p_warehouse_id, v_expected, p_counted_quantity, p_actor_user_id, p_notes
    ) RETURNING id INTO v_count_id;

    RETURN v_count_id;
END;
$$;

COMMIT;
