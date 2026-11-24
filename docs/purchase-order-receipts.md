# Purchase-order receiving

## Problem and boundary

Previously, a manager could label an order RECEIVED without changing stock or recording
delivered line quantities. `receive-order-line` now accepts one delivery against one order
line. The PostgreSQL function and JDBC transaction commit these changes together:

1. Lock the order row, which serializes receipts, draft-line additions and cancellation.
2. Look up `(order_id, request_uuid)`. Return the stored result for an identical retry;
   reject a reused key with a different product, quantity or actor.
3. Validate the order status and outstanding quantity while holding the line row lock.
4. Call `receive_inventory`, reusing its stock locking, ledger, audit and alert triggers.
5. Increment received quantity and derive the order status from all its lines.
6. Store the receipt and its original cumulative line quantity, then commit through JDBC.

A failure in any step rolls back all these writes. A retry key is recorded only in the
same commit as stock. Losing the response after commit therefore permits a safe retry.
This is an idempotent database operation; it is not a claim of exactly-once network delivery.

## State and locking decisions

New receipts are accepted for SUBMITTED or PARTIALLY_RECEIVED orders. Exact retries are
accepted after completion or cancellation because they do not change stock. New receipts
cannot exceed a line's remaining quantity. An order becomes RECEIVED only when all lines
are complete. Manual application transitions allow submission and cancellation; receipt
statuses are derived by the receiving function.

The lock order is order, line, inventory. Receipts for the same order intentionally
serialize, even for different products, so aggregate order status cannot race. Different
orders may proceed together and serialize on inventory rows if they share a product/location.
This design favors correctness over maximum per-order throughput. It has not been load benchmarked.

Application permission checks require an active manager or admin, including retries.
Database login credentials and the signed-in session (or demo mode) are trusted in this CLI prototype;
direct SQL by a database owner can bypass application permissions and manual-transition rules.
Do not expose this CLI/database as an authenticated public service.

Receipt UUIDs are scoped to an order and retained indefinitely. The `PO:<orderId>:<uuid>`
stock reference connects receipts to movement history. No retention/deletion policy is
implemented. Ordinary stock receipts/sales/transfers are not idempotent.

## Upgrade an existing database

Back up the database, then run the version-tracked runner while the application is stopped:

```powershell
mvn -q compile exec:java "-Dexec.args=migrate"
```

It applies `005_purchase_order_receipts.sql` once in a transaction and records it in
`schema_migration`. The migration creates one table and function. It does not modify
historical orders. Older manually assigned receipt statuses may have no supporting quantities;
review those records before treating them as delivery evidence. A database created before
version tracking needs a one-time `baseline` first; see the README.

## Reproducible evidence

Run `mvn test`. The same suite runs in GitHub Actions against PostgreSQL 16.
`PurchaseOrderReceiptIntegrationTest` exercises the CLI, real PostgreSQL functions and JDBC:

- Partial receipt, final receipt, multi-line completion, stock quantities and actor-aware audit.
- Historical replay after later receipts, completion or cancellation; no duplicate ledger/audit.
- Changed request payload, missing line/order, invalid quantity and over-receiving rejection.
- Two receipts competing for the outstanding balance and two identical simultaneous requests.
- A trigger-injected failure at the final receipt insert, after stock and order updates,
  proving rollback of quantities, ledger, audit, alerts and receipt key, followed by a successful retry.

The concurrency helper first locks the order on a third connection. It observes both
workers blocked in PostgreSQL before releasing that lock, then asserts results and committed
state. Query and future timeouts bound failures; no particular worker is assumed to win.
The service tests check rejected roles and validation before any database call.

Tests establish these scenarios, not production capacity, authentication, or fault tolerance
outside a single PostgreSQL transaction. Publish test results only after a successful run.
