# Project status

Last updated: 2026-11-24. This file lets work resume across sessions without losing context.

## Snapshot

| Item | Value |
| --- | --- |
| Repository | https://github.com/kshres31/intelligent-inventory-management-system |
| Default branch | `main` |
| Phase | Implementation (alerts complete, hardening next) |
| Open work | PR #1 `feat/purchase-order-receipts` (receipts); `feature/alert-notifications` stacked on it |
| Latest feature commit | `176860a` feat: deliver alert notifications through a transactional outbox |
| Tests | `mvn test`: 60 run, 0 failures, 0 skipped (embedded PostgreSQL 16, 2026-11-24) |

Merge order: PR #1 first, then the alert-notifications PR (its base retargets to `main`).

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
- **Tooling:** Docker Compose PostgreSQL, GitHub Actions against PostgreSQL 16.

## Remaining functionality (next milestones, in order)

1. **Migration version tracking.** A Java migration runner with a `schema_migration` table
   and checksums, so existing databases upgrade with one command instead of manual `psql`.
   The test harness should use the same runner.
2. **Real authentication.** Password hashing (PBKDF2 from the JDK), login for the CLI or API,
   and removal of the "username as actor" demo shortcut.
3. **HTTP API.** A small REST layer over the existing services (no business logic in
   handlers), with API tests for status codes and authorization.
4. **Least-privilege database role.** Separate owner and application roles with grants.
5. Report pagination beyond transaction history.

## Known issues and limitations

- The CLI identifies the actor by username; this is not authentication.
- Notification delivery is at-least-once. Receivers must de-duplicate by
  `X-Inventory-Notification-Id`.
- New SQL files must be applied manually to existing databases (documented per migration).
- No load or latency measurements have been taken; none are claimed.

## How to resume

```bash
git fetch origin
git checkout feature/alert-notifications   # or main once merged
mvn test                                   # needs only a JDK 17+ and Maven
```

Design notes: `docs/architecture.md`, `docs/database-design.md`, `docs/concurrency.md`,
`docs/purchase-order-receipts.md`, `docs/alert-notifications.md`.
