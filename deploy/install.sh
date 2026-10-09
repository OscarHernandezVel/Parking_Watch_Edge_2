#!/usr/bin/env bash
# Instalación inicial en Raspberry Pi OS de 64 bits (una sola vez):
#   sudo ./install.sh v1.0.0
set -euo pipefail
VERSION="${1:?Uso: install.sh vX.Y.Z}"
apt-get install -y openjdk-21-jre-headless rpicam-apps
id cupo >/dev/null 2>&1 || useradd --system --groups video --home /opt/cupo-edge cupo
install -d -o cupo -g cupo /opt/cupo-edge /var/lib/cupo-edge/data /var/lib/cupo-edge/models
install -d -m 700 /etc/cupo-edge
if [ ! -f /etc/cupo-edge/edge.env ]; then
  install -m 600 "$(dirname "$0")/edge.env.example" /etc/cupo-edge/edge.env
  echo "Edite /etc/cupo-edge/edge.env (URL del backend, token y cámara)"
fi
install -m 644 "$(dirname "$0")/cupo-edge.service" /etc/systemd/system/cupo-edge.service
systemctl daemon-reload
systemctl enable cupo-edge
"$(dirname "$0")/update.sh" "$VERSION"
