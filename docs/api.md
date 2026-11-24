# HTTP API

`serve` exposes the same services the CLI uses as JSON over HTTP. The API layer only handles
transport: routing, JSON, body limits, security headers and status codes. Permissions,
validation, locking and auditing stay in the services and PostgreSQL, so both interfaces
enforce identical rules.

```powershell
mvn -q compile exec:java "-Dexec.args=serve"
```

| Variable | Default | Purpose |
| --- | --- | --- |
| `INVENTORY_API_HOST` | `127.0.0.1` | Bind address. Loopback only unless you change it. |
| `INVENTORY_API_PORT` | `8080` | Port. |

It is built on the JDK's `com.sun.net.httpserver` with Jackson for JSON; no web framework is
needed for a dozen endpoints.

## Authentication

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"username":"manager.demo","password":"<password>"}'
# -> 201 {"token":"...","tokenType":"Bearer","expiresAt":"...","user":{...}}

curl -s http://127.0.0.1:8080/api/v1/inventory?lowStock=true -H "Authorization: Bearer $TOKEN"
```

Tokens are the same server-side sessions the CLI uses (see [Authentication](authentication.md)):
8-hour lifetime, stored only as SHA-256 digests, revoked by `DELETE /api/v1/sessions/current`
or by a password change. Login failures return one generic `401` message.

## Endpoints

All paths start with `/api/v1`. Bodies are JSON objects with `Content-Type: application/json`.

| Method | Path | Body | Role | Success |
| --- | --- | --- | --- | --- |
| GET | `/health` | | public | 200, or 503 if the database is down |
| POST | `/sessions` | `username`, `password` | public | 201 token |
| DELETE | `/sessions/current` | | any | 204 |
| GET | `/me` | | any | 200 user |
| GET | `/inventory?q=&lowStock=true` | | any | 200 list |
| POST | `/inventory/{productId}/{warehouseId}/receipts` | `quantity`, `reference`? | any | 200 new quantity |
| POST | `/inventory/{productId}/{warehouseId}/sales` | `quantity`, `reference`? | any | 200 new quantity |
| POST | `/inventory/{productId}/{warehouseId}/adjustments` | `change`, `reason` | manager, admin | 200 new quantity |
| POST | `/inventory/{productId}/{warehouseId}/counts` | `countedQuantity`, `notes`? | manager, admin | 201 count id |
| PUT | `/inventory/{productId}/{warehouseId}/threshold` | `threshold` (integer or `null`) | manager, admin | 200 effective threshold |
| POST | `/transfers` | `productId`, `fromWarehouseId`, `toWarehouseId`, `quantity`, `reference`? | manager, admin | 201 transfer id |
| GET | `/products/{productId}/transactions?page=&pageSize=` | | any | 200 page |
| GET | `/alerts?status=open\|all&limit=` | | any | 200 list |
| POST | `/alerts/{alertId}/resolution` | `note` | manager, admin | 200 |
| GET | `/notifications?status=pending\|sent\|dead\|all&limit=` | | any | 200 list |

## Errors

Every error has the same shape:

```json
{"error": {"status": 409, "message": "Insufficient stock: requested 1000, available 30"}}
```

| Status | Cause |
| --- | --- |
| 400 | Invalid JSON, wrong field type, missing field, bad path or query parameter, failed validation |
| 401 | Missing, invalid, expired or revoked token; failed login (`WWW-Authenticate: Bearer`) |
| 403 | Signed in, but the role lacks the permission |
| 404 | Unknown endpoint |
| 405 | Known path, wrong method |
| 409 | A business rule refused the change: insufficient stock, unknown product or warehouse, alert already resolved |
| 413 | Body larger than 64 KiB |
| 415 | Body sent without `application/json` |
| 500 | Unexpected failure. The response says only "Internal server error"; details go to the server log |

## Security notes

- Responses carry `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
- No CORS headers are sent, so browsers on other origins cannot call the API. This is a
  service-to-service and tooling API; add an explicit allow-list before serving a browser app.
- The access log records method, path, status and duration only: no query strings, headers
  or bodies, so tokens and passwords never reach it.
- Plain HTTP on loopback by default. Put TLS in front (a reverse proxy) before binding to a
  network interface.
- Account lockout limits guessing per account; per-address rate limiting belongs in that proxy.

## Evidence

`InventoryApiIntegrationTest` starts the real server on a random port against PostgreSQL and
checks health and headers, login and generic failures, token enforcement, role enforcement
on adjustments, thresholds and alert resolution, oversell and unknown-product conflicts,
transfers and paginated history, every client-error status above, a 500 that hides SQL details,
logout revocation, that passwords and tokens never reach the log, and **12 simultaneous HTTP
sales of 4 units against 30 in stock: exactly 7 succeed, 5 get 409, and 2 units remain**.
