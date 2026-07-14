# Glossary — Dompet Digital

> Place at `docs/GLOSSARY.md` in the backend repo.
> A reference for all technical terms, acronyms, and domain concepts used in this project.
> When in doubt about what something means, check here first.

---

## A

**ACL (Access Control List)**
A configuration file for Mosquitto that defines which MQTT clients (devices or the worker)
can publish or subscribe to which topics. In this project, each device can only access its
own `wallet/{device_id}/#` topics. The worker can access all topics.

**Admin API Token (`ADMIN_API_TOKEN`)**
A static secret string configured on the server that protects all `/admin/**` endpoints.
Any HTTP request to admin endpoints must include it as a `Bearer` token in the
`Authorization` header. Stored as an environment variable, never in code or git.

**Append-only**
A design constraint on the `ledger_entries` table — rows are never `UPDATE`d or `DELETE`d.
Money is corrected by posting new balancing entries, not by modifying old ones. This
preserves the full audit trail of every transaction.

---

## B

**Backoffice**
The web-based admin panel at `backoffice.dompetgaruda.com`. Used by Faisal (and future
admins) to manage users, devices, top-ups, sync batches, and flagged transactions.
Built with Next.js 16 + shadcn/ui. Separate codebase from the backend.

**Batch**
See **Sync Batch**.

**Bearer Token**
An HTTP authentication scheme where the client includes a token in the request header:
`Authorization: Bearer <token>`. Used for both admin auth (`ADMIN_API_TOKEN`) and device
auth (hashed device token). Called "Bearer" because whoever "bears" (holds) the token
is authorized.

**BLE (Bluetooth Low Energy)**
The wireless protocol used by two ESP32 devices to communicate during an offline transfer.
BLE is short-range (~10 metres), low-power, and does not require internet. All offline
device-to-device transactions in Dompet Digital happen over BLE.

**BUILD_PLAN.md**
A planning document (not read by Claude Code automatically) that defines the PR-by-PR
build sequence and contains the Claude Code prompts for each PR. Kept in `docs/`.

---

## C

**Caddy**
A modern reverse proxy and web server used in this project to:
1. Terminate HTTPS (TLS) for `api.dompetgaruda.com` and `backoffice.dompetgaruda.com`.
2. Automatically obtain and renew Let's Encrypt TLS certificates (no manual cert management).
3. Proxy requests to the Spring Boot API container on the internal Docker network.
Runs as a Docker container; configured by the `Caddyfile`.

**Caddyfile**
The configuration file for Caddy (`caddy/Caddyfile` in the repo). Defines which domains
Caddy serves and where it proxies traffic. Currently handles three domains:
`api.dompetgaruda.com`, `mqtt.dompetgaruda.com`, and `backoffice.dompetgaruda.com`.

**Certificate**
See **Offline Certificate**.

**Cek Saldo**
Indonesian for "Check Balance." The first menu item on the Dompet device screen.
A standalone feature (FR14) that lets the user view their current balance without
performing any transaction. Online: shows authoritative ledger balance + pouch committed.
Offline: shows the device's local pouch balance only.

**CLAUDE.md**
A context file placed at the root of each repository. Claude Code reads this automatically
at the start of every session. Contains all architecture decisions, invariants, coding rules,
and "don't do" lists that Claude Code must follow. There are two: one for the backend
(Spring Boot) and one for the frontend (Next.js).

**Clean Session**
An MQTT connection setting. When `cleanSession = false`, the broker remembers the client's
subscriptions and queues QoS 1 messages while the client is offline. Used by devices so
they don't miss `sync-result` notifications published while they were disconnected.

**CORS (Cross-Origin Resource Sharing)**
A browser security mechanism that blocks JavaScript from making HTTP requests to a different
domain than the page's origin. Relevant when the backoffice (`backoffice.dompetgaruda.com`)
calls the API (`api.dompetgaruda.com`) — the browser enforces CORS. Fixed by adding
`Access-Control-Allow-Origin` response headers on the Spring Boot backend.

**Counter**
A monotonically increasing integer stored on each device, incremented with every offline
transaction the device sends. The server tracks `last_counter` per device. Any transaction
with a counter ≤ `last_counter` is rejected as a replay. This is the primary mechanism
preventing double-spending in offline transactions. More reliable than timestamps because
device clocks can't be trusted.

---

## D

**DEBIT / CREDIT**
In double-entry accounting, every transaction has two sides: a DEBIT (money leaving an
account) and a CREDIT (money entering an account). The sum of all DEBITs must equal the
sum of all CREBITs for every transaction. In this project, `direction` column in
`ledger_entries` is either `DEBIT` or `CREDIT`.

**Device**
A physical Dompet hardware unit (ESP32-based). Has its own Ed25519 keypair, belongs to
one user, and can hold an offline pouch certificate. Max 3 devices per user.

**Device Simulator**
A planned software tool (not yet built) that generates real Ed25519-signed sync batches
and calls `POST /device/sync`. Allows full end-to-end testing of the settlement flow
without requiring real ESP32 hardware.

**Device Token**
A secret credential issued to a device at registration. Used by the device to authenticate
its HTTP requests to the API (pouch load, sync upload, balance enquiry). The server stores
only a hash (SHA-256) of the token — the plaintext is returned once at registration and
never stored.

**Docker Compose**
A tool for defining and running multi-container Docker applications. This project uses two
compose files: `docker-compose.yml` (local dev, Postgres only) and `docker-compose.prod.yml`
(VPS production: Postgres + API + Worker + Caddy + Mosquitto).

**Double-Entry Ledger**
The accounting model used for all money in this project. Every financial event creates
at least two `ledger_entries` rows — one DEBIT and one CREDIT — that sum to zero. The
user's balance is always derived by summing their entries, never stored as a single column.
This makes the audit trail tamper-evident and prevents silent balance corruption.

---

## E

**Ed25519**
A modern elliptic curve digital signature algorithm. Used in two places:
1. **Device keypairs:** each device generates an Ed25519 keypair. The private key never
   leaves the device. The public key is registered with the server. Used to sign offline
   transactions.
2. **Server signing key (`SERVER_SIGNING_KEY`):** the server uses an Ed25519 key to sign
   offline certificates issued to devices, so devices can verify them without internet.

**ERD (Entity Relationship Diagram)**
A diagram showing the database tables and the relationships between them. The Dompet Digital
ERD is on the Miro board and documented in `docs/DATABASE.md`.

---

## F

**Firmware**
The C/C++ code that runs on the ESP32 device. Handles BLE communication, Ed25519 signing,
the local offline transaction log, PIN entry, and the device screen. Separate from the
backend codebase.

**Flagged Transaction**
A row in `flagged_transactions` representing an anomaly caught during settlement or
reconciliation. Reasons include: `OVER_LIMIT`, `BAD_SIGNATURE`, `COUNTER_REPLAY`,
`EXPIRED_CERT_LATE_SYNC`, `RECON_MISMATCH`, `MALFORMED`. Flagged transactions are never
silently dropped — they're always recorded with a reason and must be reviewed by an admin.

**Flyway**
A database migration tool. Manages schema changes through versioned SQL files
(`V1__init.sql`, `V2__...`) applied in order. In this project, the API container runs
Flyway migrations on startup; the worker container never runs migrations. Never edit an
already-applied migration file — always add a new one.

**FOR UPDATE SKIP LOCKED**
A PostgreSQL row-locking mechanism used in the worker's inbox poller. When the worker
selects a `PENDING` row from `sync_inbox`, it locks that row with `FOR UPDATE SKIP LOCKED`.
This means if two worker instances run simultaneously, each picks a different row — they
can't process the same batch twice. This is how Postgres itself acts as a safe job queue
without needing Kafka or RabbitMQ.

**FR (Functional Requirement)**
A numbered, testable requirement from the PRD (Product Requirements Document). For example,
FR5 says "Sync ingest responds 202 within ~200ms and performs no ledger writes." FRs are
how features are traced from product spec through code to tests.

---

## G

**GHCR (GitHub Container Registry)**
Where Docker images for this project are stored. The CI/CD pipeline builds the image and
pushes it to `ghcr.io/fai88al/dompet-garuda:latest` on every push to `main`. The VPS
pulls this image during deployment.

---

## H

**Hardening**
The process of securing the VPS before deploying any services. Includes: creating a
non-root `deploy` user, disabling password SSH login, configuring UFW firewall, installing
fail2ban, adding swap, and applying kernel security settings. Documented in
`docs/VPS_HARDENING.md`.

---

## I

**Idempotency**
The property of an operation that produces the same result whether performed once or
multiple times. In this project: uploading the same sync batch twice must not create
duplicate ledger entries. Enforced at the database level by a `UNIQUE(sender_device_id, counter)`
constraint — the database physically rejects the duplicate insert.

**Inbox Poller**
The `@Scheduled` method in the worker that continuously polls `sync_inbox` for `PENDING`
batches using `FOR UPDATE SKIP LOCKED`. Added in PR7. This is what keeps the worker JVM
alive (it no longer exits immediately after startup).

**ISRG Root X1**
The root Certificate Authority (CA) used by Let's Encrypt. When devices connect to
`mqtt.dompetgaruda.com:8883`, they verify the broker's TLS certificate against this root CA.
Available at `https://letsencrypt.org/certs/isrgrootx1.pem`. Embedded in ESP32 firmware
for the TLS verification.

---

## J

**JdbcTemplate**
A Spring JDBC helper used for all money-related database writes in this project. Unlike JPA/
Hibernate (which generates SQL automatically), JdbcTemplate requires you to write the exact
SQL. This makes the queries visible and reviewable — important for a payments system where
you need to know exactly what SQL is executing.

---

## K

**KVM2**
The Hostinger VPS (Virtual Private Server) tier used for this project. 2 vCPU, 8GB RAM,
Ubuntu 24.04. Runs all production services in Docker containers.

---

## L

**Last Will and Testament (LWT)**
An MQTT feature configured at connection time. If a device disconnects unexpectedly (power
loss, network drop), the broker automatically publishes the LWT message on the device's
behalf. Used to set device status to `"offline"` when a device drops without a clean
disconnect.

**Ledger**
The central accounting system. All money in the project is recorded as immutable rows in
`ledger_transactions` and `ledger_entries`. A user's balance is always derived from the
ledger (sum of credits minus debits) — never stored as a single column. See also:
**Double-Entry Ledger**.

**Let's Encrypt**
A free, automated Certificate Authority (CA) that issues TLS certificates. Caddy uses
Let's Encrypt automatically — it handles the HTTP-01 challenge, certificate issuance, and
renewal with no manual steps. Rate limit: 5 failed issuances per domain per hour.

**LWT**
See **Last Will and Testament**.

---

## M

**Modular Monolith**
The architecture style used in this project. One Spring Boot codebase with clearly separated
packages (modules), deployed as two runtime containers (api + worker) distinguished by
Spring profile. Opposite of microservices. Benefits: simpler deployment, no distributed
transaction complexity, easier refactoring. The module boundaries are drawn to make future
service extraction possible if needed.

**Monotonic Counter**
See **Counter**.

**Mosquitto**
The open-source MQTT broker used in this project. Runs as a Docker container
(`dompet-mosquitto`). Configured with TLS on port 8883, per-device ACLs, and password
authentication. The worker connects as `dompet-worker`; devices connect with their
`device_id` as the username.

**MQTT (Message Queuing Telemetry Transport)**
A lightweight publish-subscribe messaging protocol designed for constrained devices.
In this project, MQTT carries **notifications only** — never financial data or decisions.
The broker is Mosquitto at `mqtt.dompetgaruda.com:8883`. Three topics are used:
`wallet/{deviceId}/status`, `wallet/{deviceId}/sync-result`,
`wallet/{deviceId}/cert-refresh`.

---

## N

**NG (Non-Goal)**
A numbered item in the PRD explicitly stating what the prototype will NOT do. For example,
NG7 means "Balance enquiry returns current figures only — no transaction history." Non-goals
are as important as goals — they protect the timeline by explicitly permitting the team to
say "no" when scope creep occurs.

---

## O

**Offline Certificate**
A server-signed document that authorizes a device to hold a specific amount of money
offline for 24 hours. Stored in `offline_certificates`. Contains: device ID, issued amount,
expiry timestamp, server signature. Issued when a device loads a pouch. At most one
`ACTIVE` certificate per device at a time. See also: **Pouch Provisioning**.

**Offline Transaction**
A signed money transfer between two devices over BLE, with no server involved. Recorded
in `offline_transactions` after settlement. The integrity of an offline transaction rests
on three things: the **signatures** (proves who authorized it), the **counter** (prevents
replay), and the **certificate** (caps the damage).

**ONLINE account**
The server-side ledger account representing a user's spendable balance that is visible and
accessible when the device is connected to the internet. Each user has exactly one ONLINE
account. Balance is derived from `ledger_entries` — never stored as a column.

---

## P

**Paho**
Eclipse Paho — the MQTT client library used in the worker service
(`org.eclipse.paho.client.mqttv3`). Connects to the Mosquitto broker and publishes
`sync-result` and `cert-refresh` notifications to devices.

**Poison Pill**
A deliberately malformed message used in testing to verify that a worker can handle bad
input without crashing or blocking. In this project, a malformed JSON batch in `sync_inbox`
is the poison pill — the worker must mark it `FAILED` and continue processing other batches.

**POUCH account**
The server-side ledger account representing the funds currently held in a device's offline
pouch. Created when a device loads a pouch. Debited when offline transactions are settled;
any unspent amount is refunded to the user's ONLINE account at sync.

**Pouch**
The fixed amount of money a device holds locally for offline spending. Loaded from the
user's online balance via **Pouch Provisioning**. Capped at Rp 3,000,000. Valid for 24
hours. Only one active pouch per device at a time.

**Pouch Limit**
The maximum amount a device can load into an offline pouch in a single provisioning call.
Set to **Rp 3,000,000** (configured as `pouch.max-amount-idr` in `application.yml`).

**Pouch Provisioning**
The process of moving money from a user's online balance into a device's offline pouch.
Involves: debiting the ONLINE account, crediting the POUCH account (one balanced
`POUCH_LOAD` ledger entry), and issuing a signed **Offline Certificate**. FR3 and FR13.

**POUCH_LOAD**
A `ledger_transactions.type` value. Represents a pouch provisioning event.
Posting: DEBIT user.ONLINE → CREDIT device.POUCH.

**POUCH_REFUND**
A `ledger_transactions.type` value. Represents the return of unspent pouch funds to
the user's online balance at sync time.
Posting: DEBIT device.POUCH → CREDIT user.ONLINE.

**PR (Pull Request)**
A unit of code change submitted for review on GitHub before merging to `main`. In this
project, features are built one PR at a time — each PR is reviewed and merged before
the next begins. Never push directly to `main`.

**PRD (Product Requirements Document)**
The source of truth for what the prototype must do. Contains goals, non-goals, in-scope
features, functional requirements (FRs), technical constraints, decisions, and milestones.
Located at `docs/PRD.md` in the backend repo.

**Profile (Spring)**
A Spring Boot mechanism for conditionally loading beans and configuration. This project
uses two profiles:
- `api` — enables REST endpoints, Flyway, Swagger. Disables scheduling.
- `worker` — enables scheduling, inbox polling. Disables web server and Flyway.
One Docker image, two containers, each started with a different profile.

---

## Q

**QoS (Quality of Service)**
MQTT delivery guarantee level:
- QoS 0: fire and forget — no guarantee
- QoS 1: at-least-once — may duplicate, will not lose
- QoS 2: exactly-once — most reliable, most overhead
This project uses QoS 1 for all worker-to-device and device-to-broker messages.

**QRIS (Quick Response Code Indonesian Standard)**
Indonesia's national QR code payment standard. In this prototype, QRIS compatibility is
cosmetic — the receiver displays a QR encoding a payment request (device ID, amount,
nonce), but the actual transfer still happens over BLE. Not a real QRIS settlement (NG2).

---

## R

**Reconciliation Job**
A scheduled worker job (FR9) that runs every hour. For each `ACTIVE` or `SETTLED`
certificate, it checks that the math is consistent: issued amount minus settled outflows
minus refunds should equal the current POUCH account balance. Mismatches are written to
`flagged_transactions` with reason `RECON_MISMATCH`.

**Replay Attack**
An attack where a previously valid signed transaction is re-submitted to be processed
again. Prevented in this project by the `UNIQUE(sender_device_id, counter)` database
constraint — the same `(device, counter)` pair cannot be inserted twice.

**Restic**
A backup tool used for nightly encrypted `pg_dump` uploads to off-site storage
(Cloudflare R2 or Backblaze B2). Handles encryption, deduplication, and retention.

**Retained Message**
An MQTT message stored by the broker and immediately delivered to any new subscriber.
Used for the device `status` topic — so the admin dashboard always sees a device's
last known status even if it connects after the device published.

---

## S

**SERVER_SIGNING_KEY**
The Ed25519 private key seed used by the API service to sign offline certificates.
Base64-encoded, 32 bytes (44 base64 characters). Stored as an environment variable,
`@Profile("api")` only — the worker never loads it. Must be generated with:
`openssl genpkey -algorithm ed25519 | openssl pkcs8 -topk8 -nocrypt -outform der | tail -c 32 | base64`

**Settlement**
The process of validating an uploaded sync batch and posting the corresponding ledger
entries. Done by the worker (never the API). Steps: verify signatures, check counters,
enforce pouch limits, post `OFFLINE_TRANSFER` entries, post `POUCH_REFUND`, close the
certificate, notify the device via MQTT.

**ShedLock**
A Java library that ensures a `@Scheduled` method runs exactly once across multiple
instances of the same service. Uses a `shedlock` table in Postgres to coordinate locks.
Every scheduled job in this project (`sync-inbox-poller`, `reconciliation-job`) uses
ShedLock.

**Sync Batch**
A collection of signed offline transactions uploaded by a device when it reconnects.
Stored as a raw JSONB payload in `sync_inbox` with status `PENDING`. The worker processes
it asynchronously. A batch can contain multiple transactions from a single device.

**sync_inbox**
The PostgreSQL table that acts as the job queue between the API (which accepts raw batches)
and the worker (which settles them). The API writes `PENDING` rows; the worker polls and
processes them. The queue uses `FOR UPDATE SKIP LOCKED` for safe concurrent processing.

**SYSTEM account**
A special ledger account (fixed UUID: `00000000-0000-0000-0000-000000000001`) that
represents the "house" or funding source. Every top-up debits the SYSTEM account and
credits the user's ONLINE account, keeping the ledger balanced. Has no `user_id` or
`device_id`.

---

## T

**Testcontainers**
A Java testing library that starts real Docker containers (e.g. PostgreSQL) during test
execution. Used in this project for all money-related tests — no mocking the database
for ledger or settlement logic. Ensures tests run against the real PostgreSQL behavior.

**TOPUP**
A `ledger_transactions.type` value. Represents an admin credit to a user's online balance.
Posting: DEBIT SYSTEM → CREDIT user.ONLINE.

**Transactional Inbox**
The architectural pattern used for sync batch processing. The API writes the raw batch to
`sync_inbox` and returns `202 Accepted` immediately (fast, no ledger writes). The worker
processes the inbox asynchronously. This decouples ingest from settlement, ensuring the API
stays responsive even if settlement is slow.

---

## U

**UFW (Uncomplicated Firewall)**
The Linux firewall used on the VPS. Configured to allow only ports 22 (SSH), 80 (HTTP for
Let's Encrypt), 443 (HTTPS), and 8883 (MQTT TLS). All other inbound traffic is denied.
Important caveat: Docker bypasses UFW for published ports — the compose file must use
`expose:` (not `ports:`) for internal services to prevent accidental exposure.

---

## V

**VPS (Virtual Private Server)**
The cloud server running all production services. Hostinger KVM2, Ubuntu 24.04, located
at IP `72.60.74.117`. Accessed via SSH as the `deploy` user.

---

## W

**Worker**
The Spring Boot service running with `SPRING_PROFILES_ACTIVE=worker`. Has no web server.
Runs scheduled jobs: the inbox poller (every 5 seconds) and the reconciliation job (every
hour). Settles offline transactions, posts ledger entries, publishes MQTT notifications.
Same Docker image as the API, different runtime behavior via Spring profile.

---

## Z

**Zero-Write Test**
A test that asserts a read-only endpoint (e.g. balance enquiry, admin list) makes zero
writes to `ledger_entries` and `ledger_transactions`. Implemented by counting rows before
and after the call and asserting the count is unchanged. Required by CLAUDE.md §10 for
all read-only endpoints.