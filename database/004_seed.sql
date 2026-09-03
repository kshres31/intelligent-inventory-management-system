BEGIN;

INSERT INTO app_user (username, display_name, email, role) VALUES
    ('admin.demo', 'Demo Administrator', 'admin@example.test', 'ADMIN'),
    ('manager.demo', 'Demo Manager', 'manager@example.test', 'MANAGER'),
    ('employee.demo', 'Demo Employee', 'employee@example.test', 'EMPLOYEE');

INSERT INTO category (name, description) VALUES
    ('Electronics', 'Small electronic devices and accessories'),
    ('Office Supplies', 'Everyday desk and shipping supplies');

INSERT INTO supplier (name, contact_email, phone) VALUES
    ('Northstar Components', 'orders@northstar.example.test', '555-0101'),
    ('Lakeside Office Supply', 'sales@lakeside.example.test', '555-0102');

INSERT INTO warehouse (code, name, address) VALUES
    ('CHI-01', 'Chicago Main Warehouse', '1000 Demo Avenue, Chicago, IL'),
    ('MAD-01', 'Madison Overflow Warehouse', '200 Demo Street, Madison, WI');

INSERT INTO product (
    sku, name, description, category_id, preferred_supplier_id,
    reorder_threshold, discrepancy_threshold, unit_price
) VALUES
    ('USB-C-2M', 'USB-C Cable 2m', 'Braided charging and data cable',
     (SELECT id FROM category WHERE name = 'Electronics'),
     (SELECT id FROM supplier WHERE name = 'Northstar Components'), 10, 2, 14.99),
    ('KB-WL-01', 'Wireless Keyboard', 'Compact wireless keyboard',
     (SELECT id FROM category WHERE name = 'Electronics'),
     (SELECT id FROM supplier WHERE name = 'Northstar Components'), 5, 1, 39.50),
    ('LABEL-4X6', 'Shipping Labels 4x6', 'Pack of 250 thermal labels',
     (SELECT id FROM category WHERE name = 'Office Supplies'),
     (SELECT id FROM supplier WHERE name = 'Lakeside Office Supply'), 8, 3, 18.25);

INSERT INTO inventory (product_id, warehouse_id, quantity) VALUES
    ((SELECT id FROM product WHERE sku = 'USB-C-2M'),
     (SELECT id FROM warehouse WHERE code = 'CHI-01'), 25),
    ((SELECT id FROM product WHERE sku = 'USB-C-2M'),
     (SELECT id FROM warehouse WHERE code = 'MAD-01'), 8),
    ((SELECT id FROM product WHERE sku = 'KB-WL-01'),
     (SELECT id FROM warehouse WHERE code = 'CHI-01'), 7),
    ((SELECT id FROM product WHERE sku = 'LABEL-4X6'),
     (SELECT id FROM warehouse WHERE code = 'CHI-01'), 20);

-- Initial inserts avoid audit noise; this no-op update evaluates alert thresholds.
UPDATE inventory SET quantity = quantity;

INSERT INTO purchase_order (
    order_number, supplier_id, warehouse_id, status, ordered_by, expected_at, notes
) VALUES (
    'PO-DEMO-001',
    (SELECT id FROM supplier WHERE name = 'Northstar Components'),
    (SELECT id FROM warehouse WHERE code = 'CHI-01'),
    'SUBMITTED',
    (SELECT id FROM app_user WHERE username = 'manager.demo'),
    CURRENT_DATE + 7,
    'Demo replenishment order'
);

INSERT INTO purchase_order_line (
    purchase_order_id, product_id, ordered_quantity, unit_cost
) VALUES (
    (SELECT id FROM purchase_order WHERE order_number = 'PO-DEMO-001'),
    (SELECT id FROM product WHERE sku = 'USB-C-2M'),
    40,
    7.25
);

COMMIT;
