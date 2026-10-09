# cupo-edge

Edge del sistema (Módulo 3 del documento v6): aplicación Java 21 en la Raspberry Pi 5 de la
maqueta que captura el video con rpicam-vid, lo procesa en tiempo real (YOLOv8n en ONNX,
ByteTrack, zonas con histéresis, OCR de placas, analítica) y se comunica con el Backend en Render
por **WSS (STOMP)** y **HTTPS**, con búfer local en **SQLite** para operar sin conexión.

- Contrato: `com.parkingwatch:cupo-contracts` (versión en `pom.xml`, desde GitHub Packages).
- `ml/`: entrenamiento y exportación a ONNX (Ultralytics, Label Studio, MLflow); no corre en
  producción.
- `deploy/`: servicio systemd, configuración de ejemplo, instalación y actualización con
  verificación SHA-256 desde GitHub Releases.

```bash
./mvnw verify                               # pruebas (maqueta simulada), análisis estático, cobertura
./mvnw -Praspberry package -DskipTests      # target/cupo-edge-all.jar con nativos ARM64
./mvnw package jib:dockerBuild -DskipTests  # imagen cupo/edge:local (simulación en docker compose)
cd ml && python -m pytest                   # pruebas del entrenamiento
```

Para compilar en local sin token de GitHub Packages, instale antes el contrato desde
`../cupo-backend` con `./mvnw install -DskipTests`.

| Variable | Uso |
|---|---|
| `BACKEND_URL`, `DEVICE_TOKEN`, `CAMERA_ID` | Conexión con el Backend (WSS en `/ws/edge` y HTTPS). |
| `AGENT_MODE` | `CAMERA` o `SIMULATION`. |
| `VIDEO_URL` | `rpicam` (cámara) o la ruta de un video grabado. |
| `CAMERA_WIDTH/HEIGHT/FPS`, `CAMERA_SHUTTER_US`, `CAMERA_GAIN`, `CAMERA_AWB_GAINS` | rpicam-vid con exposición y balance de blancos fijos. |
| `DATA_DIR`, `MODELS_DIR` | Búfer SQLite (`outbox.db`) y modelos descargados. |
