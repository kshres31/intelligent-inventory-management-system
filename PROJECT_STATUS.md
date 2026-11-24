# Project status

Last updated: 2026-11-24. This file lets work resume across sessions without losing context.

## Snapshot

| Item | Value |
| --- | --- |
| Repository | https://github.com/kshres31/intelligent-inventory-management-system |
| Default branch | `main` |
| Phase | Implementation (alerts and migrations complete; authentication next) |
| Open work | Stacked PRs: #1 `feat/purchase-order-receipts` → #2 `feature/alert-notifications` → #3 `feature/migration-runner` |
| Latest feature commits | `176860a` alert notifications (PR #2); `2c1bac6` migration runner (PR #3) |
| Tests | `mvn test`: 70 run, 0 failures, 0 skipped, on embedded PostgreSQL and on an external PostgreSQL 16 database (2026-11-24) |

Merge order: #1, then #2, then #3. After each merge GitHub retargets the next PR to `main`
only if the merged branch is deleted; otherwise change the base by hand.

## Completed functionality

Mapped to the project brief.

- **Inventory:** products with unique SKUs, categories, suppliers, warehouses; receive, sell,
  transfer, adjust, physical counts; stock-movement ledger with paginated history.
- **Database engineering:** relational schema with foreign keys, check and partial unique
  constraints; PL/pgSQL transaction functions; audit, alert, and notification triggers;
  indexes for the actual report and claim queries.
- **Concurrency:** row locks (`SELECT ... FOR UPDATE`), ordered locks for transfers,
  optimistic status checks for purchase orders, idempotent receipts by request UUID,
  `SKIP LOCKED` leases for notification workers. Non-negative stock enforced in SQL.
- **Alerts:** product default and per-location low-stock thresholds; automatic raise,
  escalation, and resolution; alert history; notification outbox with webhook or console
  delivery, retries, backoff, dead-lettering, and requeue.
- **Security and auditing:** ADMIN / MANAGER / EMPLOYEE permissions checked in services;
  actor recorded on stock, threshold, and receipt changes through `app.current_user_id`.
- **Testing:** concurrent sales, transfers and receipts; rollback; alert lifecycle;
  concurrent dispatchers; permission enforcement; data-integrity constraints; webhook HTTP.
- **Migrations:** version-tracked runner (`migrate`, `migration-status`, `baseline`) with
  checksums, per-file transactions, advisory lock, and out-of-order protection.
- **Tooling:** Docker Compose PostgreSQL, GitHub Actions against PostgreSQL 16.

## Remaining functionality (next milestones, in order)

1. **Real authentication.** Password hashing (PBKDF2 from the JDK), login for the CLI or API,
   and removal of the "username as actor" demo shortcut.
2. **HTTP API.** A small REST layer over the existing services (no business logic in
   handlers), with API tests for status codes and authorization.
3. **Least-privilege database role.** Separate owner and application roles with grants.
4. Report pagination beyond transaction history.

## Known issues and limitations

- The CLI identifies the actor by username; this is not authentication.
- Notification delivery is at-least-once. Receivers must de-duplicate by
  `X-Inventory-Notification-Id`.
- Databases created by the old Compose init scripts need a one-time `baseline <version>`.
- No load or latency measurements have been taken; none are claimed.

## How to resume

```bash
git fetch origin
git checkout feature/migration-runner      # newest stacked branch, or main once merged
mvn test                                   # needs only a JDK 17+ and Maven
# Running app: docker compose up -d, set INVENTORY_DB_* vars, then migrate (see README)
```

Design notes: `docs/architecture.md`, `docs/database-design.md`, `docs/concurrency.md`,
`docs/purchase-order-receipts.md`, `docs/alert-notifications.md`.
