# Local Development — Using the VPS PostgreSQL via SSH Tunnel

This guide walks you through running the Dompet Digital API and Worker on your local machine while using the **PostgreSQL database that lives on the VPS** (instead of spinning up a local Docker container).

> **When to use this guide:** you want to test against real production data, or you don't have Docker Desktop running locally, or you just don't want to manage a separate local Postgres instance.

---

## Prerequisites

- Java 21+ installed locally (`java -version`)
- Maven wrapper in the repo (`./mvnw` — no separate Maven install needed)
- SSH access to the VPS with your personal key (`~/.ssh/id_ed25519` or similar)
- VPS host address and your SSH username (ask the team lead if unsure)
- The plaintext values of `ADMIN_API_TOKEN`, `SERVER_SIGNING_KEY`, and `POUCH_MAX_AMOUNT_IDR` from the VPS `.env` file

---

## Step 1 — Open the SSH tunnel

The VPS Postgres listens on port `5432` **inside Docker** and is not exposed publicly. You reach it by forwarding a local port through SSH to the Postgres container's internal address.

```bash
ssh -N -L 5434:localhost:5432 <VPS_USER>@<VPS_HOST>
```

| Part | What it means |
|------|---------------|
| `-N` | No remote command — keep the tunnel open without a shell |
| `-L 5434:localhost:5432` | Forward your local port **5434** → VPS localhost port **5432** (the Postgres container) |
| `<VPS_USER>@<VPS_HOST>` | e.g. `ubuntu@203.0.113.42` |

Leave this terminal open for as long as you need the tunnel. To keep it running in the background add `-f`:

```bash
ssh -fN -L 5434:localhost:5432 <VPS_USER>@<VPS_HOST>
```

To kill a background tunnel later:

```bash
# Find the PID
lsof -i :5434

# Kill it
kill <PID>
```

### Verify the tunnel works

```bash
psql -h localhost -p 5434 -U dompet -d dompet
# Enter the database password when prompted
```

You should see the `dompet=#` prompt. Type `\q` to exit.

---

## Step 2 — Configure your local `.env`

Copy the example file if you haven't already:

```bash
cp .env.example .env
```

Edit `.env` so the datasource points to the tunnel:

```dotenv
# Tunnel endpoint — local port 5434 forwards to VPS Postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/dompet
SPRING_DATASOURCE_USERNAME=dompet
SPRING_DATASOURCE_PASSWORD=<POSTGRES_PASSWORD_FROM_VPS>

# Leave POSTGRES_PASSWORD blank — it's only needed for the local Docker Postgres container
POSTGRES_PASSWORD=

# Copy these exact values from the VPS .env file
ADMIN_API_TOKEN=<VALUE_FROM_VPS>
SERVER_SIGNING_KEY=<VALUE_FROM_VPS>
POUCH_MAX_AMOUNT_IDR=500000
```

> **Important:** never commit `.env`. It is listed in `.gitignore`.

---

## Step 3 — Export environment variables

Spring Boot reads its datasource config from environment variables. Export them from `.env` into your current shell:

```bash
export $(grep -v '^#' .env | grep -v '^$' | xargs)
```

Verify the key variables are set:

```bash
echo $SPRING_DATASOURCE_URL
# Should print: jdbc:postgresql://localhost:5434/dompet
```

---

## Step 4 — Run the API

The API profile enables REST endpoints and runs Flyway migrations on startup. If this is the first time you're connecting to this database, Flyway will apply all pending migrations automatically.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=api
```

Expected output on first boot (Flyway applies migrations):

```
Flyway Community Edition ... by Redgate
Database: jdbc:postgresql://localhost:5434/dompet (PostgreSQL 16)
Successfully applied 2 migrations to schema "public"
Started DompetGarudaApplication in X.Xs
```

Expected output on subsequent boots (schema already up to date):

```
Successfully validated 2 migrations (execution time ...).
Schema is up to date. No migration necessary.
Started DompetGarudaApplication in X.Xs
```

The API is now live on **http://localhost:8080**.

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health check: http://localhost:8080/actuator/health

---

## Step 5 — Run the Worker (optional, separate terminal)

The worker profile has no HTTP server. It polls `sync_inbox` and settles offline transactions. Run it in a **separate terminal** after the API has started (so migrations have already been applied).

```bash
# In a new terminal — export env vars again for this shell
export $(grep -v '^#' .env | grep -v '^$' | xargs)

./mvnw spring-boot:run -Dspring-boot.run.profiles=worker
```

The worker has no HTTP interface. It logs startup and then waits for work.

---

## Step 6 — Confirm everything is working

```bash
# Health check
curl http://localhost:8080/actuator/health

# List users (requires your ADMIN_API_TOKEN)
curl http://localhost:8080/admin/users \
  -H "Authorization: Bearer $ADMIN_API_TOKEN"
```

---

## Stopping

1. Stop the API (`Ctrl+C` in the API terminal)
2. Stop the Worker (`Ctrl+C` in the worker terminal, if running)
3. Close the SSH tunnel (`Ctrl+C` if foreground, or `kill <PID>` if background)

---

## Running tests against the tunnel

Tests use Testcontainers and spin up their own isolated Postgres container — they do **not** use the tunnel or the VPS database. Run them normally:

```bash
./mvnw clean verify
```

Docker Desktop must be running for Testcontainers to work.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `Connection refused: localhost:5434` | Tunnel not open | Run Step 1 first |
| `password authentication failed for user "dompet"` | Wrong `SPRING_DATASOURCE_PASSWORD` | Copy the exact value from the VPS `.env` |
| `PlaceholderResolutionException: admin.api-token` | Env var not exported | Re-run the `export $(...)` command in your current shell |
| `FATAL: database "dompet" does not exist` | Tunnel pointing at wrong host/port | Verify the VPS Postgres container is running: `docker compose -f docker-compose.prod.yml ps` |
| Flyway errors on startup | Migration mismatch | Never edit applied migration files. Add a new `V3__...sql` file instead |
| SSH tunnel disconnects after idle | SSH keepalive not configured | Add `ServerAliveInterval 60` to `~/.ssh/config` for the VPS host |

### Keeping the tunnel stable (`~/.ssh/config`)

Add this block to prevent the tunnel from dropping on idle connections:

```
Host dompet-vps
    HostName <VPS_HOST>
    User <VPS_USER>
    IdentityFile ~/.ssh/id_ed25519
    ServerAliveInterval 60
    ServerAliveCountMax 3
```

Then open the tunnel with the alias:

```bash
ssh -fN -L 5434:localhost:5432 dompet-vps
```
