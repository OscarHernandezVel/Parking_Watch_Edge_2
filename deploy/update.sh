#!/usr/bin/env bash
# Actualiza el Edge en la Raspberry Pi desde GitHub Releases: descarga el JAR de la versión,
# verifica su huella SHA-256 publicada en la misma versión y reinicia el servicio. El modelo ONNX
# se actualiza aparte, desde el Backend (RF-15.3).
#   sudo ./update.sh v1.2.0
set -euo pipefail
VERSION="${1:?Uso: update.sh vX.Y.Z}"
REPO="${CUPO_EDGE_REPO:-cupo-transito/cupo-edge}"
BASE="https://github.com/${REPO}/releases/download/${VERSION}"
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT

curl -fsSL -o "$WORK/cupo-edge-all.jar" "$BASE/cupo-edge-all.jar"
curl -fsSL -o "$WORK/cupo-edge-all.jar.sha256" "$BASE/cupo-edge-all.jar.sha256"
(cd "$WORK" && sha256sum -c cupo-edge-all.jar.sha256)

install -o root -g root -m 644 "$WORK/cupo-edge-all.jar" /opt/cupo-edge/cupo-edge-all.jar
systemctl restart cupo-edge
echo "Edge actualizado a ${VERSION}"
