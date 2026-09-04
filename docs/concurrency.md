# Concurrency and Transaction Safety

## The problem

Suppose an inventory row contains 10 units. Two employees read 10 at nearly the same time. One wants to sell 7 and the other wants to sell 6. If both clients validate their stale reads and then write independently, the system can oversell or lose one update.

The relevant state is the database row, so the database coordinates access to it.

## Sale sequence

```mermaid
sequenceDiagram
    participant A as Connection A
    participant DB as PostgreSQL inventory row
    participant B as Connection B

    A->>DB: SELECT quantity FOR UPDATE
    DB-->>A: lock acquired; quantity = 10
    B->>DB: SELECT quantity FOR UPDATE
    Note over B,DB: waits for A's row lock
    A->>DB: validate 7, update to 3, insert ledger row
    A->>DB: COMMIT
    DB-->>B: lock acquired; current quantity = 3
    B->>DB: validate 6; reject
    B->>DB: ROLLBACK
```

The second connection does not continue with the quantity it might have seen earlier. `SELECT ... FOR UPDATE` waits, then returns the current committed row after the first transaction finishes.

## JDBC boundary

`JdbcInventoryRepository` uses this pattern:

1. Open a dedicated connection.
2. Disable auto-commit.
3. Select `READ COMMITTED` isolation.
4. Invoke one PostgreSQL transaction function.
5. Commit the result, or roll back on any SQL/runtime exception.
6. Close the connection.

PostgreSQL functions do not start independent nested transactions. They execute inside the transaction created by JDBC, which lets the Java caller control success or rollback while the function keeps the critical SQL sequence together.

## Why `READ COMMITTED` is enough here

`READ COMMITTED` allows a later statement to see data committed after the transaction began. That behavior is useful after waiting for a row lock: the sale function must validate the latest committed quantity, not a transaction-start snapshot.

This is safe only because every quantity-changing path follows the locking rule. Raising isolation to `SERIALIZABLE` would add retry requirements and broader conflict detection without replacing the need for a clear stock check.

## Transfer deadlocks

A transfer changes two inventory rows. If one transaction locks Chicago then Madison while another locks Madison then Chicago, they can form a cycle. `transfer_inventory` locks both rows in ascending `warehouse_id` order, so all transfers acquire the pair in the same order.

The function then updates the source and destination and inserts two ledger records with one UUID. Any failure rolls back all four writes.

## Purchase-order status changes

Purchase-order status is changed less frequently and is not a numeric balance. The repository uses an expected-state update:

```sql
UPDATE purchase_order
SET status = :next
WHERE id = :id AND status = :expected;
```

Zero updated rows means the order no longer matches the caller's view. The service asks the caller to refresh rather than overwriting another manager's decision.

## What the tests prove

`InventoryTransactionIntegrationTest` uses separate JDBC connections and a start latch to create real contention. It verifies:

- two sales totaling more than available stock cannot both commit;
- final quantity matches the one successful sale;
- stock never becomes negative;
- a failed transfer changes neither warehouse and writes no ledger rows;
- a successful transfer changes both warehouses and creates two correlated ledger rows;
- triggers run within the transaction and preserve actor context.

The test does not claim a specific thread wins. Scheduling is nondeterministic, so it derives the expected final quantity from the operation that actually committed.
