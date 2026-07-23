# BUILD_PLAN — Dompet Digital Backend (Claude Code kickoff & sequence)

This is how to hand the project to Claude Code so it builds **incrementally**, one reviewable PR at
a time, instead of generating the whole MVP in one blob. Hand out tasks **one at a time**, reviewing
and merging each before the next. Claude Code reads `CLAUDE.md` automatically; point it at the PRD
for *scope*, not as a build list.

**Progress:** PR1–PR11 ✅ all merged and verified in production.
Infrastructure complete: VPS hardened, Docker Compose stack (postgres, api, worker, caddy,
mosquitto), CI/CD via GitHub Actions, TLS on `api.dompetgaruda.com` and `mqtt.dompetgaruda.com`,
backoffice frontend deployed at `backoffice.dompetgaruda.com`.

**Next: PR12 — replace static `ADMIN_API_TOKEN` with real per-user accounts (`feat/admin-user-auth`)**

This is required before the writer role (articles, landing page) can exist — a single shared
token can't distinguish who is logged in, which the writer feature needs from day one.

---

## Completed build order (for reference)

**API service (Phase 4)** — all merged
1. ✅ PR1 — Scaffold (profiles, Flyway, Testcontainers smoke test)
2. ✅ PR2 — Auth + device registration (FR1)
3. ✅ PR3 — Ledger core (double-entry posting, balance derivation)
4. ✅ PR4 — Online top-up (FR2)
5. ✅ PR4b — Balance enquiry / Cek Saldo (FR14)
6. ✅ PR5 — Pouch provisioning + certificate (FR3, FR13)
7. ✅ PR6 — Sync ingest endpoint (FR5)

**Worker service (Phase 5)** — all merged
8. ✅ PR7 — Worker bootstrap + inbox poller
9. ✅ PR8 — Settlement (FR4, FR6-FR8, FR11, FR12)
10. ✅ PR9 — Reconciliation job (FR9)
11. ✅ PR10 — MQTT publisher
12. ✅ PR11 — Admin read endpoints (FR10)

**Infrastructure** — all done
- ✅ VPS hardened, Docker Compose, CI/CD
- ✅ Caddy + TLS for api.dompetgaruda.com
- ✅ Mosquitto + TLS for mqtt.dompetgaruda.com (shared cert via Caddy)
- ✅ Backoffice frontend deployed at backoffice.dompetgaruda.com

---

## Current phase — real auth + writer role foundation

12. **PR12 — Real per-user admin/writer accounts (`feat/admin-user-auth`).** Replaces
    `ADMIN_API_TOKEN` with `admin_users` table (BCrypt password, role), JWT-based login and
    session validation. Two accounts seeded: `rizki@dompetgaruda.com`, `faisal@dompetgaruda.com`
    (both role `ADMIN`, temporary passwords — rotate after first login, see CLAUDE.md §14).
    *This PR is the prerequisite for the writer role and article management — do not skip.*
13. **PR13 (future, not yet scoped) — Password change endpoint.** `PATCH /admin/auth/password`
    so seeded temporary passwords can be rotated without a new migration.
14. **PR14 (future, not yet scoped) — Writer role + article endpoints.** `articles` table,
    CRUD gated by `role == WRITER || ADMIN`, image upload for cover images. Confirm full scope
    with Faisal before starting — this is a meaningfully larger PR than prior ones.

**Parallel, ongoing:** device simulator (for testing settlement without ESP32 hardware),
backup setup (restic → R2/B2 + one test restore) — both still outstanding from the original
demo-readiness checklist, independent of the auth/writer work above.

---

## Next prompt to paste — PR12 (real per-user auth)

```
Read CLAUDE.md fully — especially the updated §4 (admin/writer authentication),
§7 invariants, and §14 follow-ups. This replaces the static ADMIN_API_TOKEN auth
model with real per-user accounts. Work on branch feat/admin-user-auth, open a
PR against main.

1. Flyway migration V(next)__admin_users.sql:
   CREATE TABLE admin_users (
     id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
     username VARCHAR(120) UNIQUE NOT NULL,
     password_hash VARCHAR(255) NOT NULL,
     role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','WRITER')),
     created_at TIMESTAMPTZ NOT NULL DEFAULT now()
   );
   Seed two rows in the same migration with BCrypt hashes (cost factor 10):
   - username 'rizki@dompetgaruda.com', role ADMIN
   - username 'faisal@dompetgaruda.com', role ADMIN
   Generate real BCrypt hashes for temporary passwords and note the plaintext
   temporary passwords ONLY in the PR description — never commit them anywhere
   in code, docs, or commit messages.

2. Add BCryptPasswordEncoder bean (@Profile("api")) if not already present.

3. Add a JWT library (io.jsonwebtoken: jjwt-api, jjwt-impl, jjwt-jackson) to
   pom.xml. Add ADMIN_JWT_SECRET to .env.example (generate with
   openssl rand -hex 32) and application-api.yml.

4. Rewrite POST /admin/auth/login:
   Body: { "username": "...", "password": "..." }
   - Look up admin_users by username (treat as case-sensitive email string).
   - Verify password against password_hash with BCrypt.
   - On success: issue JWT { sub: userId, username, role }, signed with
     ADMIN_JWT_SECRET, 24h expiry. Return { "token": "...", "type": "Bearer",
     "username": "...", "role": "..." }.
   - On failure: 401 { "message": "Invalid username or password" }.
   - Keep existing brute-force protection (5 attempts/5min/IP → 429).

5. Rewrite AdminTokenFilter (@Profile("api")):
   - Parse and verify JWT signature + expiry instead of comparing a static string.
   - On valid token: set authenticated principal (userId + role) in the
     security context so controllers can access it later if needed.
   - On invalid/expired/missing: 401.

6. Remove ADMIN_API_TOKEN entirely — from .env.example, application-api.yml,
   docker-compose.prod.yml, and any remaining code references. Search the
   whole repo for the string to confirm nothing is missed.

7. Tests (extend ApiIntegrationTestBase):
   - Login with correct username/password → 200 + valid JWT.
   - Login with wrong password → 401.
   - Login with unknown username → 401.
   - 5 failed attempts → 6th returns 429.
   - Existing admin endpoints (e.g. GET /admin/users) reject requests with no
     token, expired token, or malformed token → 401. Accept valid JWT → 200.

8. §13 deliverables:
   a. Swagger: update login endpoint docs to reflect username+password body.
   b. docs/api-examples/13-admin-login.sh: update to use username+password
      with the seeded email-style usernames as example values.
   c. README.md: grep -n "^## " first. Update the auth section and
      milestones. Update in-place.

Open PR with the two seeded usernames and their temporary passwords in the
PR description (not committed elsewhere), and confirm ADMIN_API_TOKEN is
fully removed from the codebase (grep for it and show zero matches).
```

---

## After PR12 merges — verification checklist before moving to the backoffice

```bash
# Confirm ADMIN_API_TOKEN is gone
docker compose -f docker-compose.prod.yml exec api \
  printenv | grep ADMIN_API_TOKEN
# Must return nothing

# Login with seeded account
curl -sf -X POST https://api.dompetgaruda.com/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"rizki@dompetgaruda.com","password":"<temp password from PR description>"}' \
  | python3 -m json.tool
# Must return 200 with a JWT, username, and role

# Confirm old-style token no longer works
curl -s -o /dev/null -w "%{http_code}" \
  -X GET https://api.dompetgaruda.com/admin/users \
  -H "Authorization: Bearer <old ADMIN_API_TOKEN value>"
# Must return 401

# Confirm new JWT works on an existing endpoint
curl -sf https://api.dompetgaruda.com/admin/users \
  -H "Authorization: Bearer <new JWT from login>" | python3 -m json.tool
# Must return 200 with the users list
```

Only after all four checks pass, move to the backoffice repo's PR8 (login form update to
collect username + password instead of password-only).

---

## Standing reminders for every task

- One PR per task; keep them small and reviewable. Stop and ask if scope is unclear — don't guess on money logic.
- Money tests (Testcontainers, real Postgres) are part of the PR, not a follow-up.
- Never push to main; never commit as the AI — commits are authored by your GitHub account.
- If a request seems to conflict with a money-safety invariant (CLAUDE.md §7), stop and flag it.
- Never commit plaintext passwords, JWT secrets, or API tokens anywhere in the repo —
  only in PR descriptions when explicitly required for a one-time handoff (e.g. seeded accounts).