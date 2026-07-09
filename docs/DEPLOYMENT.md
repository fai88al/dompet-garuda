# Deployment Guide

## Part A — VPS setup (one-time)

### A1. Provision the VPS

Minimum spec: 1 vCPU, 1 GB RAM, Ubuntu 24.04 LTS. Open ports in UFW:

```bash
ufw allow 22/tcp    # SSH
ufw allow 80/tcp    # Caddy HTTP (ACME challenge)
ufw allow 443/tcp   # Caddy HTTPS
ufw allow 443/udp   # Caddy HTTP/3
ufw enable
```

### A1.5. Verify DNS is propagated before first deploy with Caddy

Caddy requests a TLS certificate from Let's Encrypt on first startup. Let's Encrypt
must be able to reach the server via the domain. Verify DNS before deploying:

```bash
nslookup api.dompetgaruda.com
```

The response must resolve to the VPS public IP. If DNS is not yet propagated, wait
and re-check before triggering the deploy workflow.

### A2. Install Docker

```bash
curl -fsSL https://get.docker.com | sh
usermod -aG docker $USER   # log out and back in to apply
```

### A3. Create the deploy directory and `.env`

```bash
mkdir -p ~/dompet
cd ~/dompet
cat > .env <<'EOF'
POSTGRES_PASSWORD=<strong-random-password>
ADMIN_API_TOKEN=<strong-random-token>
SERVER_SIGNING_KEY=<base64-encoded-32-byte-ed25519-seed>
POUCH_MAX_AMOUNT_IDR=500000
EOF
chmod 600 .env
```

### A4. Provision the GitHub Actions deploy key

```bash
ssh-keygen -t ed25519 -f ~/.ssh/github-actions-deploy -N ""
cat ~/.ssh/github-actions-deploy.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

Add the private key (`~/.ssh/github-actions-deploy`) as the `VPS_SSH_KEY` secret in
GitHub → Settings → Secrets. Also add `VPS_HOST` (server IP) and `VPS_USER` (deploy
username).

---

## Part B — GitHub secrets

| Secret | Description |
|--------|-------------|
| `VPS_HOST` | VPS public IP address |
| `VPS_USER` | SSH login username |
| `VPS_SSH_KEY` | Private Ed25519 deploy key (from A4) |

The workflow uses `GITHUB_TOKEN` (auto-provided) to push images to GHCR — no
additional secret is needed for image publishing.

---

## Part C — Production services

The stack is defined in `docker-compose.prod.yml` and managed by the CI/CD deploy
job (`.github/workflows/deploy.yml`). Never run `docker compose` manually on the VPS
to start or stop services — the workflow handles it.

### Services

| Service | Container | Purpose |
|---------|-----------|---------|
| `postgres` | `dompet-postgres` | PostgreSQL 16 database |
| `api` | `dompet-api` | Spring Boot REST API (Flyway, port 8080 internal only) |
| `worker` | `dompet-worker` | Spring Boot background jobs (inbox poller, reconciliation) |
| `caddy` | `dompet-caddy` | Reverse proxy with automatic HTTPS via Let's Encrypt |

### Caddy

Caddy sits in front of the `api` container and terminates TLS. The Caddyfile is at
`caddy/Caddyfile` in the repo. It is copied to the VPS on every deploy alongside
`docker-compose.prod.yml`.

Caddy obtains and renews certificates automatically from Let's Encrypt. No manual TLS
configuration is needed.

> **Important:** the `caddy_data` Docker volume stores the TLS certificate and private key.
> **Never delete this volume.** Deleting it forces Caddy to re-issue the certificate from
> Let's Encrypt. Let's Encrypt enforces a rate limit of 5 failed certificate issuances per
> registered domain per hour. Repeated volume deletions can exhaust this limit and lock
> the domain out of certificate issuance for up to a week.

### First deploy

On the first deploy after Caddy is added to the stack:
1. DNS must already point `api.dompetgaruda.com` to the VPS (see A1.5).
2. The workflow runs `docker compose up -d` which starts all services including Caddy.
3. Caddy performs the ACME HTTP-01 challenge on port 80 and obtains the certificate.
4. All subsequent HTTPS traffic on port 443 is proxied to `api:8080` internally.

### Useful commands

```bash
# Check all service statuses
docker compose -f docker-compose.prod.yml ps

# Tail Caddy logs (TLS issuance, access log)
docker compose -f docker-compose.prod.yml logs caddy --tail=50 -f

# Tail API logs
docker compose -f docker-compose.prod.yml logs api --tail=50

# Check API health through Caddy
curl https://api.dompetgaruda.com/actuator/health
```

---

## Part D — Rollback

To roll back to the previous image tag:

```bash
# SSH into VPS
cd ~/dompet
# Edit docker-compose.prod.yml to pin the image tag, then:
docker compose -f docker-compose.prod.yml up -d api worker
```

Or re-run the GitHub Actions workflow from the previous commit using
**Actions → CI/Deploy → Re-run workflow** (selecting the prior commit).
