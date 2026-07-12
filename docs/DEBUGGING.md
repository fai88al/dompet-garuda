# VPS Debugging Runbook — Dompet Digital

> Place at `docs/DEBUGGING.md`. A practical reference for diagnosing and fixing
> production issues on the Hostinger KVM2 (Ubuntu 24.04, Docker Compose).
> Written from real incidents encountered during this project.

---

## 0. Golden rules before you touch anything

1. **Identify before you fix.** Never run `docker compose restart` or `docker compose down`
   before you know what's wrong. Restarting destroys the logs you need to diagnose.
2. **Read the logs first.** 90% of issues are diagnosed in 30 seconds by reading logs.
3. **One change at a time.** If you change two things and the problem goes away, you
   don't know which one fixed it — and you don't know if the other one broke something else.
4. **Never delete volumes unless you mean it.** `docker compose down -v` deletes all data
   including the database and TLS certificates. Use `docker compose down` (no `-v`).
5. **Keep the VNC console open.** The Hostinger browser console doesn't depend on SSH.
   Always verify you can reach it before making changes that could lock you out.

---

## 1. Quick health check — run this first every time

```bash
cd ~/dompet

# See all container states at a glance
docker compose -f docker-compose.prod.yml ps

# Check system resources
free -h           # RAM — alert if "available" < 500MB
df -h /           # Disk — alert if "Use%" > 80%
uptime            # CPU load — alert if load > 2.0 on 2 vCPU
```

**Expected healthy output:**
```
NAME              STATUS
dompet-postgres   Up X hours (healthy)
dompet-api        Up X hours (healthy)
dompet-caddy      Up X hours
dompet-worker     Up X hours
dompet-mosquitto  Up X hours    ← only after PR10 is deployed
```

---

## 2. SSH won't connect

### Symptom
```
ssh deploy@72.60.74.117
# Hangs or: Operation timed out
```

### Diagnosis — work through in order

**Step 1 — Is the VPS reachable at all?**
```bash
ping -c 4 72.60.74.117
```
If ping times out → VPS is down. Go to Hostinger panel and check/start the VPS.
If ping works → VPS is up, SSH is the problem. Continue.

**Step 2 — Is port 22 reachable?**
```bash
nc -vz 72.60.74.117 22
```
If times out but ping works → firewall or fail2ban issue. Go to VNC console.

**Step 3 — Check fail2ban (most common cause)**

Your public IP may have been banned due to repeated failed login attempts
(e.g. from GitHub Actions SSH timeouts). Find your public IP first:
```bash
# On your Mac
curl -s ifconfig.me
```

In VNC console as deploy:
```bash
sudo fail2ban-client status sshd
# Look for "Banned IP list" — check if your IP is there

# Unban your IP
sudo fail2ban-client set sshd unbanip YOUR_PUBLIC_IP
```

**Step 4 — Check UFW**
```bash
# In VNC console
sudo ufw status | grep -i "22\|ssh"
# Must show OpenSSH ALLOW

# If SSH rule is missing (rare):
sudo ufw allow OpenSSH
sudo ufw reload
```

**Step 5 — Check if SSH daemon is running**
```bash
sudo systemctl status ssh
sudo ss -tlnp | grep :22
# Must show sshd listening on 0.0.0.0:22
```

If SSH daemon died:
```bash
sudo systemctl restart ssh
```

**Step 6 — If all else fails, reboot**

A reboot clears iptables corruption from Docker crash-loops and resets everything:
```bash
sudo reboot
```
VPS back online in ~60 seconds. All containers with `restart: unless-stopped` come back
automatically. Volumes are never affected by a reboot.

---

## 3. Container is Restarting

### Symptom
```
docker compose -f docker-compose.prod.yml ps
# Shows: Restarting (exit code) X seconds ago
```

### Diagnosis

**Step 1 — Read the logs (do this FIRST)**
```bash
docker compose -f docker-compose.prod.yml logs <service> --tail=100
```
The last lines before the crash contain the actual error.

**Step 2 — Common crash causes and fixes**

| Error in logs | Cause | Fix |
|---|---|---|
| `FlywayException: Found non-empty schema` | DB has tables but no flyway_schema_history | Reset volume (§4) |
| `PlaceholderResolutionException: ADMIN_API_TOKEN` | Missing env var, wrong Spring profile | Check `.env`, check `@Profile("api")` annotation |
| `PlaceholderResolutionException: SERVER_SIGNING_KEY` | Missing env var in worker profile | Add `@Profile("api")` to the bean that injects it |
| `Connection refused localhost:5432` | Postgres not healthy yet | Add `depends_on: postgres: condition: service_healthy` |
| `Unable to obtain connection` | Wrong DB password or URL | Check `.env` POSTGRES_PASSWORD matches |
| Single log line then exits (worker) | No scheduled tasks yet | Expected until PR7 inbox poller — not a bug |
| TLS cert file not found (Mosquitto) | Caddy hasn't obtained cert yet, or path wrong | Check cert exists in caddy_data (§6) |

**Step 3 — For a silent crash (only one log line before exit)**

The crash is happening before logging is fully initialized. Run a debug container:
```bash
docker run --rm \
  --env-file ~/dompet/.env \
  -e SPRING_PROFILES_ACTIVE=api \
  ghcr.io/fai88al/dompet-garuda:latest \
  java -jar /app/app.jar 2>&1 | head -50
```
This shows the full startup output including the crash reason.

**Step 4 — Stop a crash-looping container immediately**

A crash-looping container with `restart: unless-stopped` and no backoff can starve the
system of resources and block SSH. Stop it fast:
```bash
docker compose -f docker-compose.prod.yml stop <service>
docker compose -f docker-compose.prod.yml rm -f <service>
```
Fix the root cause, then start it again:
```bash
docker compose -f docker-compose.prod.yml up -d <service>
```

---

## 4. Flyway migration conflict

### Symptom
```
Found non-empty schema(s) "public" but no schema history table.
Use baseline() or set baselineOnMigrate to true.
```

### Cause
Tables exist in the database (from a manual `psql < V1__init.sql` during setup)
but Flyway has never run — so it sees tables it didn't create and refuses to proceed.

### Fix (safe — no real data exists yet in prototype)
```bash
cd ~/dompet
docker compose -f docker-compose.prod.yml down
docker volume rm dompet_pgdata
docker compose -f docker-compose.prod.yml up -d postgres

# Verify DB is empty
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet -c "\dt"
# Must print: "Did not find any relations."

# Now start the API — Flyway will create everything cleanly
docker compose -f docker-compose.prod.yml up -d api
```

### Prevention
Never run `psql < V1__init.sql` manually on a database that the app will use.
The only time to apply the schema manually is for exploration — and only on a volume
you intend to wipe before the first real deploy.

---

## 5. API is healthy but returning wrong responses

### Diagnosis

**Check environment variables are loaded correctly:**
```bash
docker compose -f docker-compose.prod.yml exec api \
  printenv | grep -E "SPRING|ADMIN|POUCH|SERVER" | sort
```
Never log `SERVER_SIGNING_KEY` or `ADMIN_API_TOKEN` — just confirm they're set (non-empty).

**Check which Spring profile is active:**
```bash
docker compose -f docker-compose.prod.yml logs api | grep "profile is active"
# Must show: "api"
```

**Test auth is working:**
```bash
# Without token — must return 401
curl -s -o /dev/null -w "%{http_code}" \
  https://api.dompetgaruda.com/admin/users
# Expected: 401

# With token — must return 200
curl -sf https://api.dompetgaruda.com/admin/users \
  -H "Authorization: Bearer $ADMIN_API_TOKEN"
# Expected: JSON array
```

**Check Flyway ran correctly:**
```bash
docker compose -f docker-compose.prod.yml logs api | grep -i flyway
# Should show: "Successfully applied N migrations"
# Should NOT show: "FlywayException" or "Found non-empty schema"
```

---

## 6. Caddy / TLS issues

### Cert not obtained
```bash
# Check what certs Caddy has
docker compose -f docker-compose.prod.yml exec caddy \
  ls /data/caddy/certificates/acme-v02.api.letsencrypt.org-directory/

# Check Caddy logs for errors
docker compose -f docker-compose.prod.yml logs caddy | grep -E "error|obtain|certificate"
```

**If cert missing after Caddyfile change:**
The Caddyfile may not have been updated inside the container. Force reload:
```bash
docker compose -f docker-compose.prod.yml exec caddy \
  caddy reload --config /etc/caddy/Caddyfile
```
Wait 30 seconds then check certs again.

**Let's Encrypt rate limit hit:**
Max 5 failed cert issuance attempts per domain per hour.
If you see `rateLimited` in Caddy logs, wait 1 hour before retrying.
Always confirm `nslookup <domain>` returns the correct IP before starting Caddy.

**⚠️ Never run `docker compose down -v`** — this deletes `caddy_data` which contains
your TLS certificates. Caddy would need to re-request them and you'd burn rate limit attempts.

### Caddyfile formatting issues
Caddy prefers tabs (not spaces) for indentation. Use plain ASCII characters — no
typographic quotes or em dashes. If you see:
```
WARN Caddyfile input is not formatted
```
Fix with:
```bash
docker compose -f docker-compose.prod.yml exec caddy \
  caddy fmt --overwrite /etc/caddy/Caddyfile
```

---

## 7. Worker issues

### Worker keeps restarting (exit code 0)

**Before PR7:** Expected. The worker has no long-running task and exits cleanly.
Docker restarts it every 30 seconds. This resolves when the inbox poller is deployed.

**After PR7:** The inbox poller should keep the JVM alive. If the worker still restarts:
```bash
docker compose -f docker-compose.prod.yml logs worker --tail=50
```
Look for the crash reason — most common is a missing `@Profile("api")` annotation on a
bean that injects admin-only config (like `ADMIN_API_TOKEN` or `SERVER_SIGNING_KEY`).

### ShedLock check — verify jobs are running
```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet \
  -c "SELECT name, lock_until, locked_at FROM shedlock ORDER BY locked_at DESC;"
```
Expected rows: `sync-inbox-poller` and `reconciliation-job`.
`locked_at` should be recent (within the last 5 seconds for the poller, within the
last hour for reconciliation).

### Check settlement is working
```bash
# Check sync_inbox for stuck PENDING batches
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet \
  -c "SELECT status, COUNT(*) FROM sync_inbox GROUP BY status;"

# Check for flagged transactions
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet \
  -c "SELECT reason, COUNT(*) FROM flagged_transactions 
      WHERE resolved = false GROUP BY reason;"
```

---

## 8. Database issues

### Connect to the database directly
```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet
```

### Useful diagnostic queries
```bash
# Check all table row counts
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet -c "
    SELECT schemaname, tablename, n_live_tup AS rows
    FROM pg_stat_user_tables
    ORDER BY n_live_tup DESC;"

# Check ledger balance for a user
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet -c "
    SELECT a.type, 
      SUM(CASE WHEN le.direction='CREDIT' THEN le.amount ELSE -le.amount END) AS balance
    FROM ledger_entries le
    JOIN accounts a ON le.account_id = a.account_id
    GROUP BY a.type;"

# Check active certificates
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet -c "
    SELECT certificate_id, device_id, issued_amount, status, expires_at
    FROM offline_certificates
    WHERE status = 'ACTIVE';"

# Check unresolved flags
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet -c "
    SELECT reason, detail, created_at
    FROM flagged_transactions
    WHERE resolved = false
    ORDER BY created_at DESC
    LIMIT 10;"
```

### Connect from DBeaver via SSH tunnel
```bash
# Keep this running in a terminal while using DBeaver
ssh -N -L 5432:127.0.0.1:5432 deploy@72.60.74.117

# DBeaver settings:
# Host: localhost
# Port: 5432
# Database: dompet
# User: dompet
# Password: (from ~/dompet/.env POSTGRES_PASSWORD)
```

---

## 9. Disk space

```bash
# Check overall disk usage
df -h /

# Find what's using space
du -sh ~/dompet/*
docker system df

# Clean up unused Docker images (safe — only removes untagged images)
docker image prune -f

# Check log sizes
du -sh ~/dompet/mosquitto/log/
docker compose -f docker-compose.prod.yml exec caddy \
  du -sh /var/log/caddy/
```

---

## 10. Emergency procedures

### Restart all containers without touching data
```bash
cd ~/dompet
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml up -d
```

### Full system restart (when SSH is blocked or system is unresponsive)
From the Hostinger VNC console:
```bash
sudo reboot
```
All containers with `restart: unless-stopped` come back automatically.
Volumes are never affected by a reboot.

### Restore from backup
```bash
# Stop the API to prevent writes during restore
docker compose -f docker-compose.prod.yml stop api worker

# Restore from restic backup (see backup documentation)
restic -r <repo> restore latest --target /tmp/restore
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet < /tmp/restore/dompet.sql

# Restart
docker compose -f docker-compose.prod.yml start api worker
```

---

## 11. Mosquitto-specific issues

### Container crash-looping
**Stop it immediately** to prevent SSH lockout from resource exhaustion:
```bash
docker compose -f docker-compose.prod.yml stop mosquitto
docker compose -f docker-compose.prod.yml rm -f mosquitto
```

**Common causes:**
- TLS cert files not readable by the `mosquitto` user (most common)
- Wrong cert path in `mosquitto.conf`
- `caddy_data` volume not mounted or cert not yet obtained by Caddy

**Debug without starting the container:**
```bash
docker run --rm \
  -v ~/dompet/mosquitto/config/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro \
  -v caddy_data:/caddy_data:ro \
  eclipse-mosquitto:2 \
  mosquitto -c /mosquitto/config/mosquitto.conf --verbose 2>&1
```
This shows the full startup output including any permission or path errors.

### Test MQTT connection
```bash
# Subscribe (terminal 1)
mosquitto_sub -h mqtt.dompetgaruda.com -p 8883 \
  --cafile /tmp/isrg-root-x1.pem \
  -u dompet-worker \
  -P "$MQTT_WORKER_PASSWORD" \
  -t "wallet/test/#" -v

# Publish (terminal 2)
mosquitto_pub -h mqtt.dompetgaruda.com -p 8883 \
  --cafile /tmp/isrg-root-x1.pem \
  -u dompet-worker \
  -P "$MQTT_WORKER_PASSWORD" \
  -t "wallet/test/ping" \
  -m "pong"
```

Download the Let's Encrypt CA cert if needed:
```bash
curl -o /tmp/isrg-root-x1.pem https://letsencrypt.org/certs/isrgrootx1.pem
```

---

## 12. Checking the deployment pipeline

### After a GitHub Actions deploy, verify in order:

```bash
cd ~/dompet

# 1. All containers healthy?
docker compose -f docker-compose.prod.yml ps

# 2. API started with correct Flyway output?
docker compose -f docker-compose.prod.yml logs api | grep -E "flyway|Started|ERROR"

# 3. No security password warning?
docker compose -f docker-compose.prod.yml logs api | grep -i "security password"
# Must return nothing

# 4. Worker stable (not restarting)?
docker compose -f docker-compose.prod.yml ps | grep worker

# 5. ShedLock jobs firing?
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U dompet -d dompet \
  -c "SELECT name, locked_at FROM shedlock ORDER BY locked_at DESC;"

# 6. HTTPS working?
curl -sf https://api.dompetgaruda.com/actuator/health
# Expected: {"status":"UP"}

# 7. Admin auth working?
curl -s -o /dev/null -w "%{http_code}" \
  https://api.dompetgaruda.com/admin/users
# Expected: 401
```

---

## 13. .env reference — all required variables

| Variable | Used by | Example |
|---|---|---|
| `POSTGRES_PASSWORD` | postgres container, api, worker | `openssl rand -base64 24` |
| `ADMIN_API_TOKEN` | api only (`@Profile("api")`) | `openssl rand -hex 32` |
| `SERVER_SIGNING_KEY` | api only (`@Profile("api")`) | 44-char base64 Ed25519 seed |
| `POUCH_MAX_AMOUNT_IDR` | api only | `3000000` |
| `MQTT_WORKER_PASSWORD` | worker (PR10) | strong random password |
| `MQTT_BROKER_URL` | worker (PR10) | `ssl://mqtt.dompetgaruda.com:8883` |
| `MQTT_CLIENT_ID` | worker (PR10) | `dompet-worker-01` |

Check all are set:
```bash
cat ~/dompet/.env | grep -v "^#" | grep -v "^$"
```

No variable should be empty or missing. A missing required variable causes
`PlaceholderResolutionException` on startup.