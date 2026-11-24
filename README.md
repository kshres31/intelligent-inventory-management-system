# Intelligent Inventory Management System

A Java and PostgreSQL warehouse inventory application built to explore a deceptively difficult question: how does a multi-user system keep stock correct when people act at the same time?

I built this as a student portfolio project around the parts of inventory software that are easy to get wrong: atomic updates, overselling, partial transfers, auditability, and useful operational alerts. The interface is intentionally a small command-line application so the design stays focused on database behavior and testable business rules.

## What it does

- Manages active and inactive products with unique SKUs.
- Models users with `ADMIN`, `MANAGER`, and `EMPLOYEE` roles.
- Receives, sells, transfers, and manually adjusts stock.
- Records physical counts without silently changing system quantity.
- Prevents negative inventory and lost updates.
- Creates and resolves low-stock and discrepancy alerts, with per-location threshold overrides.
- Delivers alert notifications through a transactional outbox to a webhook (HMAC-signed) or the console, with retries, backoff, and dead-lettering.
- Keeps stock-transaction and inventory-audit histories.
- Creates purchase orders, adds draft lines, and guards status transitions.
- Receives purchase-order lines atomically with stock, audit entries and alerts; repeated request UUIDs return the original result without receiving twice.
- Reports current inventory, low stock, recent movements, discrepancies, alerts, and audits.

## Technology

- Java 17
- Maven 3.9+
- JDBC with prepared statements
- PostgreSQL 14+ (Docker Compose uses PostgreSQL 16)
- JUnit 5
- Embedded PostgreSQL for default local integration tests
- GitHub Actions with a PostgreSQL service

## Architecture

```mermaid
flowchart LR
    CLI[Inventory CLI] --> Services[Service layer]
    Services --> Repositories[JDBC repositories]
    Repositories --> Functions[PostgreSQL functions]
    Repositories --> Queries[Report and CRUD queries]
    Functions --> Inventory[(Inventory tables)]
    Inventory --> Triggers[Audit and alert triggers]
```

The responsibilities are deliberately separated:

- `controller` parses commands and formats output.
- `service` validates input and checks application permissions.
- `repository` owns parameterized SQL and JDBC transaction boundaries.
- `model` contains domain records and enums.
- `database` defines tables, constraints, functions, triggers, and demo seed data.

See [Architecture](docs/architecture.md) and [Database design](docs/database-design.md) for the detailed decisions and ER diagram.

## Database design

The main inventory key is `(product_id, warehouse_id)`, so the same product can have an independent quantity at each location. Check, foreign-key, unique, and not-null constraints provide a final safety net even if a caller bypasses the Java services.

Core tables:

- `app_user`
- `category`
- `supplier`
- `warehouse`
- `product`
- `inventory`
- `stock_transaction`
- `purchase_order` and `purchase_order_line`
- `inventory_count`
- `inventory_alert` and `alert_notification`
- `audit_log`

Products and users are deactivated rather than deleted so historical foreign keys remain meaningful.

## Transactions and concurrency

Each stock-changing JDBC method disables auto-commit, uses PostgreSQL's `READ COMMITTED` isolation level, calls one transaction function, and then explicitly commits or rolls back.

For a sale, `process_stock_sale`:

1. Locks the product/location row with `SELECT ... FOR UPDATE`.
2. Checks the now-current available quantity.
3. Updates inventory.
4. Inserts the movement record.
5. Returns the new quantity.

Those steps share one transaction. If any step fails, none of them remain committed.

Transfers lock both warehouses in ascending warehouse-ID order. That stable order prevents two opposite-direction transfers from each holding one lock while waiting forever for the other.

Purchase-order status updates use a different technique: the `UPDATE` includes the expected current status. If another user changed it first, zero rows update and the caller must refresh rather than overwrite the newer state.

The concurrency integration test starts with 10 units and releases two real database connections together. One tries to sell 7 and the other 6. It verifies that exactly one commits, the other rolls back, one sale ledger row exists, and final stock matches the winner. See [Concurrency](docs/concurrency.md).

## Triggers and stored functions

### Triggers

- `trg_inventory_audit` stores old quantity, new quantity, change, time, and application actor after a real quantity update.
- `trg_low_stock_alert` creates or refreshes one unresolved low-stock alert per product/location and resolves it after recovery.
- `trg_discrepancy_alert` compares a physical count with the expected quantity and raises an alert when the configured threshold is exceeded.
- `trg_alert_notification` queues a notification in the same transaction when an alert is raised, escalated to critical, or resolved. See [Alert notifications](docs/alert-notifications.md).
- `trg_product_threshold_reevaluation` re-checks locations when a product's default threshold changes.

### Transaction functions

- `receive_inventory`
- `process_stock_sale`
- `transfer_inventory`
- `perform_inventory_adjustment`
- `reconcile_inventory_count`
- `set_low_stock_threshold`

The functions contain short sequences where locking and all-or-nothing behavior matter. Search and reporting remain ordinary SQL in repositories because moving every query into a stored function would add complexity without improving consistency.

## Roles

| Capability | Admin | Manager | Employee |
| --- | --- | --- | --- |
| View inventory and history | Yes | Yes | Yes |
| Receive and sell stock | Yes | Yes | Yes |
| Transfer or adjust stock | Yes | Yes | No |
| Record physical counts | Yes | Yes | No |
| Manage purchase orders | Yes | Yes | No |
| Resolve alerts | Yes | Yes | No |
| Configure thresholds and dispatch notifications | Yes | Yes | No |
| Manage products and users | Yes | No | No |

Users sign in with a password. Passwords are stored only as salted PBKDF2-HMAC-SHA256 hashes (600,000 iterations); five consecutive failures lock the account for 15 minutes; each login creates a server-side session whose token is kept by the client and stored in the database only as a SHA-256 digest. Every command resolves its actor from that session, so roles and audit entries reflect who actually signed in. See [Authentication](docs/authentication.md).

For quick demos, `INVENTORY_DEMO_MODE=true` restores the older form where the first argument names the acting seeded user without a password. Never enable it outside a local demo.

## Project structure

```text
database/                         numbered migrations: schema, functions, triggers, seed data
docs/                             architecture and concurrency notes
src/main/java/.../config/         database configuration and connections
src/main/java/.../controller/     CLI argument handling
src/main/java/.../model/          domain records and enums
src/main/java/.../repository/     JDBC and SQL
src/main/java/.../service/        permissions and use cases
src/test/java/                    unit and PostgreSQL integration tests
docker-compose.yml                reproducible local PostgreSQL
```

## Local setup

### Prerequisites

- JDK 17 or newer
- Maven 3.9 or newer
- Docker Desktop, or another PostgreSQL 14+ server

Check them with:

```powershell
java -version
javac -version
mvn -version
docker --version
```

On a Windows installation where Maven comes with Apache NetBeans but is not on `PATH`, use:

```powershell
& 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' -version
```

### Start PostgreSQL

```powershell
docker compose up -d
docker compose ps
```

### Configure the application

Copy `.env.example` to `.env` for Docker Compose. The Java process reads environment variables directly:

```powershell
$env:INVENTORY_DB_URL = 'jdbc:postgresql://localhost:5432/inventory'
$env:INVENTORY_DB_USER = 'inventory_app'
$env:INVENTORY_DB_PASSWORD = 'local_inventory_password'
```

Optional alert delivery settings are `INVENTORY_ALERT_WEBHOOK_URL`, `INVENTORY_ALERT_WEBHOOK_SECRET`, and `INVENTORY_ALERT_WEBHOOK_TIMEOUT_SECONDS`. Without a webhook URL, notifications print to the console.

`.env` is ignored by Git. Do not commit real passwords.

### Apply migrations

Schema changes live in numbered files in `database/` (`001_schema.sql`, `002_functions.sql`, ...). With PostgreSQL running and the variables above set, apply them with the version-tracked runner:

```powershell
mvn -q compile exec:java "-Dexec.args=migrate"
mvn -q exec:java "-Dexec.args=migration-status"
```

`migrate` records every applied file, its SHA-256 checksum, and duration in `schema_migration`. It is safe to run on every deploy: already-applied files are skipped, each new file runs in its own transaction with its version record, a PostgreSQL advisory lock serializes concurrent runners, and edited or out-of-order files are refused. `004_seed.sql` loads a small demo dataset.

A volume created by an older version of this project (when Compose ran the SQL files as init scripts) already has the schema but no history. Record what it already contains once, then migrate normally:

```powershell
mvn -q exec:java "-Dexec.args=baseline 4"   # newest file that volume already has (4, 5 or 6)
mvn -q exec:java "-Dexec.args=migrate"
```

To deliberately discard the local database and rebuild it:

```powershell
docker compose down -v
docker compose up -d
mvn -q compile exec:java "-Dexec.args=migrate"
```

`down -v` permanently removes this Compose project's local database volume, so do not use it when the data matters.

## Run the application

### Sign in

A fresh database has users but no passwords. Give the first administrator a password once, then sign in. Passwords are read without echo from the terminal (or one line per prompt from piped standard input), never from arguments:

```powershell
mvn -q exec:java "-Dexec.args=bootstrap-admin admin.demo"
mvn -q exec:java "-Dexec.args=login admin.demo"
mvn -q exec:java "-Dexec.args=whoami"
mvn -q exec:java "-Dexec.args=set-password manager.demo"   # admins can set anyone's password
mvn -q exec:java "-Dexec.args=logout"
```

`login` saves the session token to `~/.inventory/session` (owner-only permissions; override with `INVENTORY_SESSION_FILE`, or pass a token in `INVENTORY_SESSION_TOKEN`). Signed in, commands drop the username: `mvn exec:java -Dexec.args="low-stock"`.

### Demo commands

The examples below use demo mode so several roles can be shown quickly without switching sessions:

```powershell
$env:INVENTORY_DEMO_MODE = 'true'
```

Show help:

```powershell
mvn exec:java -Dexec.args="help"
```

List current inventory and low stock:

```powershell
mvn exec:java -Dexec.args="employee.demo inventory"
mvn exec:java -Dexec.args="employee.demo low-stock"
```

Record a sale and inspect its history on a fresh seed database:

```powershell
mvn exec:java -Dexec.args="employee.demo sell 1 1 3 SALE-DEMO-001"
mvn exec:java -Dexec.args="employee.demo transactions 1 1 20"
```

Manager workflows:

```powershell
mvn exec:java -Dexec.args="manager.demo transfer 1 1 2 4 MOVE-DEMO-001"
mvn exec:java -Dexec.args="manager.demo count 1 1 20 Cycle count"
mvn exec:java -Dexec.args="manager.demo alerts"
mvn exec:java -Dexec.args="manager.demo discrepancies 20"
```

Alert thresholds and notifications:

```powershell
mvn exec:java -Dexec.args="manager.demo set-threshold 1 1 30"
mvn exec:java -Dexec.args="manager.demo notifications pending"
mvn exec:java -Dexec.args="manager.demo dispatch-notifications"
mvn exec:java -Dexec.args="manager.demo notification-worker 15"
```

Administrator workflows:

```powershell
mvn exec:java -Dexec.args="admin.demo add-product STAND-01 LaptopStand 29.95 4 1"
mvn exec:java -Dexec.args="admin.demo create-user receiver.demo Receiver EMPLOYEE -"
mvn exec:java -Dexec.args="admin.demo users all"
```

Run `mvn exec:java -Dexec.args="help"` for every supported command and argument shape. Numeric IDs are shown by the inventory, product, user, and purchase-order list commands.

## Tests

### Receive a purchase order

After creating an order and adding its lines, submit it with `order-status <orderId> DRAFT SUBMITTED`.
Then receive a delivery using its order and product IDs:

```powershell
$receiptId = [guid]::NewGuid().ToString()
mvn exec:java "-Dexec.args=manager.demo receive-order-line 1 1 4 $receiptId"
```

Use the same UUID and identical arguments to retry that delivery after an uncertain result.
Use a new UUID for a different delivery. Receipt keys are scoped to an order and must not
be reused with a different product, quantity, or actor. The response is the line's cumulative
received quantity **at the original receipt**, even if later deliveries have arrived.

The application derives `PARTIALLY_RECEIVED` and `RECEIVED` from line quantities.
They cannot be assigned through `order-status`. Cancelling a partially received order
preserves delivered stock and rejects further deliveries; reversing stock is a separate operation.
Ordinary `receive` commands remain standalone stock movements and do not have this retry guarantee.
See [receipt design and upgrade instructions](docs/purchase-order-receipts.md).

### Run the suite

Run all tests with an embedded PostgreSQL instance:

```powershell
mvn test
```

To run them against Docker, first create a dedicated disposable test database:

```powershell
docker compose exec postgres createdb -U inventory_app inventory_test
mvn "-Dtest.database.url=jdbc:postgresql://localhost:5432/inventory_test" `
    "-Dtest.database.user=inventory_app" `
    "-Dtest.database.password=local_inventory_password" test
```

External integration tests drop and recreate the `public` schema. As a safety check, the test harness refuses database names that do not contain `test`. Never point it at a database that contains data you want to keep.

The suite covers:

- domain input validation;
- product and user repositories;
- unique SKU behavior;
- schema objects and seed data;
- non-negative inventory constraints;
- successful and failed transfers;
- rollback behavior;
- simultaneous sales through separate connections;
- low-stock alert deduplication and automatic resolution;
- discrepancy alerts and actor-aware audit entries;
- role permissions;
- report queries;
- purchase-order state changes.
- stable, bounded transaction-history pagination.
- partial and complete purchase-order receiving, duplicate delivery requests, forced contention,
  cancellation, and rollback after stock, audit, alert, and order writes.
- authentication: PBKDF2 hashing, lockout, indistinguishable failures, session expiry and
  revocation, concurrent bootstrap, password-change authorization and audit;
- migration runner: one-time application, rollback of a failing file, checksum and
  out-of-order protection, baseline adoption, and three concurrent runners;
- alert notification lifecycle, rollback, threshold overrides, retries, dead-lettering,
  concurrent dispatchers, stale leases, and signed webhook delivery.

GitHub Actions runs the suite against a clean PostgreSQL 16 service on pushes and pull requests.

## Security and privacy notes

- SQL values use prepared-statement parameters.
- Secrets come from environment variables, and `.env` is ignored.
- PostgreSQL constraints defend critical invariants if Java validation is bypassed.
- The demo stores no customer data and collects no analytics.
- Passwords are PBKDF2-hashed with per-user salts; session tokens are stored only as SHA-256 digests; login failures never reveal whether a username exists.
- Demo mode (`INVENTORY_DEMO_MODE=true`) bypasses passwords by design; keep it off outside local demos.
- This is a CLI, not a network service; do not expose the database to the internet.
- A production database account should receive only the required table/function privileges instead of broad schema ownership.

## Lessons learned

- Lock the row that contains the decision-making value, not just the row eventually written.
- A database constraint is a useful final guard, but it does not replace a clear service error.
- Transfers need a consistent lock order as well as a transaction.
- Audit context can cross the JDBC/PostgreSQL boundary with a transaction-local setting.
- Test data must account for trigger side effects; the seed process legitimately creates its own low-stock alert.
- Optimistic and pessimistic concurrency controls solve different problems and can coexist in one system.

## Limitations and future improvements

- Add an HTTP API over the existing services, reusing the session tokens.
- Add email or chat channels; the webhook channel can already feed a relay service.
- Add receipt browsing and supplier shipment identifiers.
- Add barcode scanning and a web interface without moving business rules into UI code.
- Add configurable per-role permissions and least-privilege PostgreSQL grants.
- Extend pagination from transaction history to the remaining reports.
- Measure query plans with realistic data volumes before adding more indexes.

No production usage, external users, performance metrics, or field-study results are claimed for this project.
