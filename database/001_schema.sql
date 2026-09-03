BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_user (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(254) UNIQUE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'EMPLOYEE')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE category (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE supplier (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(150) NOT NULL UNIQUE,
    contact_email VARCHAR(254),
    phone VARCHAR(30),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE warehouse (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    address TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE product (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    category_id BIGINT REFERENCES category(id),
    preferred_supplier_id BIGINT REFERENCES supplier(id),
    reorder_threshold INTEGER NOT NULL DEFAULT 0 CHECK (reorder_threshold >= 0),
    discrepancy_threshold INTEGER NOT NULL DEFAULT 0 CHECK (discrepancy_threshold >= 0),
    unit_price NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inventory (
    product_id BIGINT NOT NULL REFERENCES product(id),
    warehouse_id BIGINT NOT NULL REFERENCES warehouse(id),
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id, warehouse_id)
);

CREATE TABLE purchase_order (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number VARCHAR(40) NOT NULL UNIQUE,
    supplier_id BIGINT NOT NULL REFERENCES supplier(id),
    warehouse_id BIGINT NOT NULL REFERENCES warehouse(id),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    ordered_by BIGINT NOT NULL REFERENCES app_user(id),
    expected_at DATE,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE purchase_order_line (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES product(id),
    ordered_quantity INTEGER NOT NULL CHECK (ordered_quantity > 0),
    received_quantity INTEGER NOT NULL DEFAULT 0
        CHECK (received_quantity >= 0 AND received_quantity <= ordered_quantity),
    unit_cost NUMERIC(12, 2) NOT NULL CHECK (unit_cost >= 0),
    UNIQUE (purchase_order_id, product_id)
);

CREATE TABLE stock_transaction (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    transaction_type VARCHAR(20) NOT NULL
        CHECK (transaction_type IN ('RECEIVE', 'SALE', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUSTMENT')),
    quantity_change INTEGER NOT NULL CHECK (quantity_change <> 0),
    quantity_before INTEGER NOT NULL CHECK (quantity_before >= 0),
    quantity_after INTEGER NOT NULL CHECK (quantity_after >= 0),
    actor_user_id BIGINT NOT NULL REFERENCES app_user(id),
    transfer_id UUID,
    reference VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (product_id, warehouse_id) REFERENCES inventory(product_id, warehouse_id),
    CHECK (quantity_after = quantity_before + quantity_change)
);

CREATE TABLE inventory_alert (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_type VARCHAR(20) NOT NULL CHECK (alert_type IN ('LOW_STOCK', 'DISCREPANCY')),
    severity VARCHAR(20) NOT NULL DEFAULT 'WARNING' CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    product_id BIGINT NOT NULL REFERENCES product(id),
    warehouse_id BIGINT NOT NULL REFERENCES warehouse(id),
    message TEXT NOT NULL,
    expected_quantity INTEGER,
    actual_quantity INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    resolved_by BIGINT REFERENCES app_user(id),
    resolution_note TEXT,
    CHECK ((resolved_at IS NULL AND resolved_by IS NULL) OR resolved_at IS NOT NULL)
);

CREATE TABLE inventory_count (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES product(id),
    warehouse_id BIGINT NOT NULL REFERENCES warehouse(id),
    expected_quantity INTEGER NOT NULL CHECK (expected_quantity >= 0),
    counted_quantity INTEGER NOT NULL CHECK (counted_quantity >= 0),
    variance INTEGER GENERATED ALWAYS AS (counted_quantity - expected_quantity) STORED,
    counted_by BIGINT NOT NULL REFERENCES app_user(id),
    notes TEXT,
    counted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_key VARCHAR(150) NOT NULL,
    action VARCHAR(20) NOT NULL CHECK (action IN ('INSERT', 'UPDATE', 'DELETE')),
    old_values JSONB,
    new_values JSONB,
    actor_user_id BIGINT REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_product_name ON product (name);
CREATE INDEX idx_product_category ON product (category_id);
CREATE INDEX idx_stock_transaction_product_time
    ON stock_transaction (product_id, created_at DESC);
CREATE INDEX idx_stock_transaction_warehouse_time
    ON stock_transaction (warehouse_id, created_at DESC);
CREATE INDEX idx_inventory_alert_recent ON inventory_alert (created_at DESC);
CREATE INDEX idx_inventory_count_product_time ON inventory_count (product_id, counted_at DESC);
CREATE INDEX idx_audit_log_entity_time ON audit_log (entity_type, created_at DESC);

CREATE UNIQUE INDEX uq_open_low_stock_alert
    ON inventory_alert (product_id, warehouse_id)
    WHERE alert_type = 'LOW_STOCK' AND resolved_at IS NULL;

CREATE UNIQUE INDEX uq_open_discrepancy_alert
    ON inventory_alert (product_id, warehouse_id)
    WHERE alert_type = 'DISCREPANCY' AND resolved_at IS NULL;

COMMIT;
