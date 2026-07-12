#!/bin/sh
set -e

CERT_SRC="/caddy_data/caddy/certificates/acme-v02.api.letsencrypt.org-directory/mqtt.dompetgaruda.com"
CERT_DST="/mosquitto/certs"

echo "Copying TLS certs from Caddy..."
mkdir -p "$CERT_DST"
cp "$CERT_SRC/mqtt.dompetgaruda.com.crt" "$CERT_DST/server.crt"
cp "$CERT_SRC/mqtt.dompetgaruda.com.key" "$CERT_DST/server.key"
chmod 644 "$CERT_DST/server.crt"
chmod 644 "$CERT_DST/server.key"
echo "Certs copied. Starting Mosquitto..."

exec mosquitto -c /mosquitto/config/mosquitto.conf