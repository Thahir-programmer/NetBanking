# NetBanking

[![CI](https://github.com/Thahir-programmer/NetBanking/actions/workflows/ci.yml/badge.svg)](https://github.com/Thahir-programmer/NetBanking/actions/workflows/ci.yml)

A banking REST API built to answer one question: **how do you keep money correct when many requests hit the same account at the same time?**

Users register, get an account, and can deposit, withdraw and transfer. An admin can freeze accounts. The interesting part is the backend: transactions, locking, safe retries, and tests that prove it holds up under concurrency.

**Live demo:** https://netbanking-44es.onrender.com (free hosting: the first request after a quiet period can take about a minute while it wakes up). Register a customer, deposit, and send money to a second account.

**Stack:** Java 17 · Spring Boot 4.1 · Spring Security (JWT) · Spring Data JPA / Hibernate · PostgreSQL · plain HTML/CSS/JS frontend · Testcontainers · Docker · GitHub Actions · Render + Neon

## Features

- Register and log in with JWT authentication (BCrypt password hashing)
- Every user gets an account with a unique 12-digit number
- Deposit, withdraw, and transfer between accounts
- Paged transaction history
- Admin role: list all accounts, freeze and unfreeze an account
- Safe retries: a transfer sent twice with the same `Idempotency-Key` moves money once
- A small web UI: login and register, dashboard, transfer, history, and an admin page

## Run it

**With Docker (recommended).** Starts the app and its own Postgres:

```bash
cp .env.example .env        # then set JWT_SECRET (32+ characters) in .env
docker compose up --build
```

The app is then on `http://localhost:8080`: open it in a browser for the web UI, or call the API directly. The compose database is throwaway; `docker compose down -v` deletes it.

**Without Docker.** You need Java 17 and a PostgreSQL database:

```bash
cp .env.example .env        # fill in DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET
./mvnw spring-boot:run
```

To create the first admin, uncomment and set `ADMIN_EMAIL` and `ADMIN_PASSWORD` (8+ characters) in `.env` before starting. They are commented out in `.env.example` on purpose, so nobody gets an admin with a placeholder password. There is deliberately no HTTP endpoint that can create an admin.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | none | Create a user and account, returns a JWT |
| POST | `/api/auth/login` | none | Returns a JWT |
| GET | `/api/accounts/me` | user | Your balance and status |
| POST | `/api/accounts/deposit` | user | `{"amount": 100.00}` |
| POST | `/api/accounts/withdraw` | user | `{"amount": 40.25}` |
| POST | `/api/transfers` | user | Needs an `Idempotency-Key` header |
| GET | `/api/accounts/me/transactions?page=&size=` | user | Your history, newest first |
| GET | `/api/admin/accounts?page=&size=` | admin | All accounts |
| POST | `/api/admin/accounts/{id}/freeze` | admin | Freeze an account |
| POST | `/api/admin/accounts/{id}/unfreeze` | admin | Unfreeze an account |

Example:

```bash
# register, then use the returned token
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com","password":"password123"}'

# transfer: the same key on a retry returns the original result and moves no money
curl -X POST localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: 7f3c-unique-per-transfer' \
  -H 'Content-Type: application/json' \
  -d '{"toAccountNumber":"103925791548","amount":25.00}'
```

Errors are JSON with a status and message: `400` validation, `401` bad or missing token, `403` wrong role or frozen account, `404` not found, `409` conflict, `422` insufficient funds.

## Design decisions

**Money is `BigDecimal` / `NUMERIC(19,2)`, never `double`.** A `double` cannot represent 0.1 exactly. A unit test adds 0.10 ten times and checks the result is exactly 1.00.

**Every money movement is one database transaction.** A transfer debits one account, credits another and writes a history row inside a single `@Transactional` method. Any failure rolls back all of it.

**Two kinds of locking, used where each fits.**
- *Optimistic* (`@Version` on `Account`): deposits and withdrawals read the balance, change it, and save. If another request changed the row first, the update matches zero rows and the loser gets a `409` instead of silently overwriting. No lost updates.
- *Pessimistic* (`SELECT ... FOR UPDATE`): transfers lock both accounts so concurrent transfers queue up.

**Deadlocks are prevented by lock ordering.** Transfer A to B and B to A at the same moment would each hold one lock and wait for the other. Transfers always lock the account with the lower id first, so a circular wait cannot form.

**Locked reads must be fresh.** An early version loaded the accounts before locking them, so the "locked" read returned stale cached data and most concurrent transfers failed. Transfers now look up ids only and load entities through the locking query.

**Idempotency.** `POST /api/transfers` requires an `Idempotency-Key`. The key is stored (scoped to the sender) in a unique column. The check happens *after* taking the sender's lock, so simultaneous duplicates queue and the later ones replay the first result. Reusing a key with a different amount or destination returns `409`.

**Security.**
- Stateless JWT (HS256, 1 hour). The role is a signed claim.
- The account always comes from the token, never from the request body, so users can only reach their own data.
- The registration request has no role field, so a client cannot make itself admin.
- Wrong email and wrong password return the identical `401`, so attackers cannot discover which emails exist.
- Freeze takes the same row lock as transfers, so a freeze cannot race an in-flight transfer.

## Frontend

Deliberately simple: plain HTML, CSS and JavaScript in `src/main/resources/static`, served by the same Spring Boot app (one deploy, no CORS). The backend is the point of the project; the UI exists so the behaviour can be seen and clicked.

| Page | What it does |
|---|---|
| `index.html` | Log in or register (with a "Show password" option) |
| `dashboard.html` | Balance, deposit, withdraw |
| `transfer.html` | Send money to an account number |
| `history.html` | Paged history; money in is green, money out is red |
| `admin.html` | List accounts, freeze and unfreeze (admins only) |

Design points worth knowing:

- **Safe retries in the browser.** The transfer page creates one `Idempotency-Key` per intended transfer. If the outcome is uncertain (network drop, server error, conflict) the retry reuses the same key, so the server replays the first result instead of sending money twice. A different recipient or amount is a new transfer and gets a new key. After an uncertain failure it reloads the real balance instead of showing a stale one.
- **No `innerHTML`.** The JWT is kept in `localStorage`, so any injected script could read it. Every page therefore builds its DOM with `textContent` only. This was checked by planting a hostile `<img onerror=...>` value as an owner email and confirming the admin table shows it as plain text.
- **The UI is not the security boundary.** Hiding the Admin link, or redirecting a non-admin away from `admin.html`, is convenience. The server checks the token signature and the role on every `/api/admin/**` call.
- **Admins have no bank account.** After login they land on the Admin page.

## Tests

56 tests, run on every push by GitHub Actions. They cover the backend; the frontend was checked by hand in a browser.

```bash
./mvnw test        # needs Docker running (Testcontainers starts a real PostgreSQL)
```

- **Unit tests** (Mockito): business rules, lock order, idempotent replay.
- **API tests** (MockMvc on real Postgres): auth, security, validation, admin flows, history.
- **Concurrency tests** (many threads released at the same instant): 40 opposing transfers never deadlock; 12 duplicate idempotency keys move money once; concurrent withdrawals never overdraw; concurrent deposits lose no updates; 60 random transfers across 6 accounts conserve the total.

The tests run on real PostgreSQL rather than H2 because row locks and isolation behave differently, and a test could pass on H2 while the real database fails.

**The concurrency tests were checked by breaking the code.** Removing the lock ordering produced real deadlocks and failing tests; removing `@Version` made the lost-update and overdraft tests fail. Both changes were reverted.

## Project layout

```
src/main/java/com/thahirudeen/netbanking/
  auth/         register, login, JWT issuing
  account/      accounts, deposit, withdraw
  transaction/  transfers, idempotency, history
  admin/        list, freeze, unfreeze
  config/       security config, JWT, admin seeding
  common/       exceptions and the global error handler
  user/         user entity and roles
src/main/resources/static/   the web UI (HTML, CSS, JS)
```

Each feature follows controller, service, repository, entity.

## Known limitations and next steps

- Tokens last one hour and cannot be revoked; a demoted admin keeps access until expiry. Fix: short-lived access tokens with refresh tokens.
- A `409` from an optimistic-lock collision is returned to the client. Fix: retry automatically a few times with jittered backoff.
- Deposit and withdraw do not take an idempotency key yet (transfers do).
- Schema is created by Hibernate (`ddl-auto`). Fix: Flyway migrations.
- No rate limiting on login.
- The token is stored in `localStorage`, which survives a refresh but is readable by any script on the page. It is mitigated by never inserting server text as HTML. Fix: an `HttpOnly` cookie, with CSRF protection added back.
- The frontend has no automated tests. Fix: browser tests (for example Playwright) run in CI.
