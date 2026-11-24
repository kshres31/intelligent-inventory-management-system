# Project status

Last updated: 2026-11-24. This file lets work resume across sessions without losing context.

## Snapshot

| Item | Value |
| --- | --- |
| Repository | https://github.com/kshres31/intelligent-inventory-management-system |
| Default branch | `main` |
| Phase | Complete against the brief; optional hardening listed below |
| Open work | None. PRs #1–#5 merged to `main` on 2026-11-24; Docker packaging in PR #6 |
| Merged to `main` | `0dab8cf` (merge of #5: HTTP API) |
| Tests | `mvn test`: 91 run, 0 failures, 0 skipped, on embedded PostgreSQL and on an external PostgreSQL 16 database (2026-11-24) |


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
- **Authentication:** PBKDF2 password hashes, lockout after 5 failures, hashed server-side
  session tokens, `login` / `logout` / `set-password` / `bootstrap-admin`; demo mode is opt-in.
- **HTTP API:** `serve` exposes inventory, stock moves, transfers, counts, thresholds, alerts
  and notifications as JSON with bearer-token auth and consistent error codes.
- **Security and auditing:** ADMIN / MANAGER / EMPLOYEE permissions checked in services;
  actor recorded on stock, threshold, and receipt changes through `app.current_user_id`.
- **Testing:** concurrent sales, transfers and receipts; rollback; alert lifecycle;
  concurrent dispatchers; permission enforcement; data-integrity constraints; webhook HTTP.
- **Migrations:** version-tracked runner (`migrate`, `migration-status`, `baseline`) with
  checksums, per-file transactions, advisory lock, and out-of-order protection.
- **Tooling:** Dockerfile (non-root, multi-stage) and Compose `app` profile (postgres → migrate → api);
  GitHub Actions runs tests against PostgreSQL 16 and builds the image.

## Optional follow-ups (not required by the brief)

1. **Least-privilege database role.** Separate owner and application roles with grants.
2. Report pagination beyond transaction history.
3. Product, user and purchase-order endpoints in the API (CLI-only today).

## Known issues and limitations

- The Docker image is built in CI; it has not been run end-to-end in a container from this
  workspace (no Docker daemon). The packaged jar + lib layout was run locally against PostgreSQL 16.
- The notification worker is not a Compose service: it needs a session token, which expires after
  8 hours. Run `dispatch-notifications` from a scheduler with a fresh login instead.
- Demo mode (`INVENTORY_DEMO_MODE=true`) skips passwords by design; it is off by default.
- No MFA, SSO or email password reset (out of scope for a CLI).
- Notification delivery is at-least-once. Receivers must de-duplicate by
  `X-Inventory-Notification-Id`.
- Databases created by the old Compose init scripts need a one-time `baseline <version>`.
- No load or latency measurements have been taken; none are claimed.

## How to resume

```bash
git fetch origin
git checkout main
mvn test                                   # needs only a JDK 17+ and Maven
# Running app: docker compose up -d, set INVENTORY_DB_* vars, then migrate (see README)
```

Design notes: `docs/architecture.md`, `docs/database-design.md`, `docs/concurrency.md`,
`docs/purchase-order-receipts.md`, `docs/alert-notifications.md`, `docs/authentication.md`, `docs/api.md`.
