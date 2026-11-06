# NetBanking

[![CI](https://github.com/Thahir-programmer/NetBanking/actions/workflows/ci.yml/badge.svg)](https://github.com/Thahir-programmer/NetBanking/actions/workflows/ci.yml)

A banking REST API built to answer one question: **how do you keep money correct when many requests hit the same account at the same time?**

Users register, get an account, and can deposit, withdraw and transfer. An admin can freeze accounts. The interesting part is the backend: transactions, locking, safe retries, and tests that prove it holds up under concurrency.

**Stack:** Java 17 · Spring Boot 4.1 · Spring Security (JWT) · Spring Data JPA / Hibernate · PostgreSQL · Testcontainers · Docker · GitHub Actions

## Features

- Register and log in with JWT authentication (BCrypt password hashing)
- Every user gets an account with a unique 12-digit number
- Deposit, withdraw, and transfer between accounts
- Paged transaction history
- Admin role: list all accounts, freeze and unfreeze an account
- Safe retries: a transfer sent twice with the same `Idempotency-Key` moves money once

## Run it

**With Docker (recommended).** Starts the app and its own Postgres:

```bash
cp .env.example .env        # then set JWT_SECRET (32+ characters) in .env
docker compose up --build
```

The API is then on `http://localhost:8080`. The compose database is throwaway; `docker compose down -v` deletes it.

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

## Tests

56 tests, run on every push by GitHub Actions.

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
```

Each feature follows controller, service, repository, entity.

## Known limitations and next steps

- Tokens last one hour and cannot be revoked; a demoted admin keeps access until expiry. Fix: short-lived access tokens with refresh tokens.
- A `409` from an optimistic-lock collision is returned to the client. Fix: retry automatically a few times with jittered backoff.
- Deposit and withdraw do not take an idempotency key yet (transfers do).
- Schema is created by Hibernate (`ddl-auto`). Fix: Flyway migrations.
- No rate limiting on login.
