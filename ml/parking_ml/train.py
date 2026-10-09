"""Entrenamiento, exportación y registro del detector (RF-10.1, RF-13.1 a RF-13.3).

Uso:
    python -m parking_ml.train --dataset datasets/maqueta --version det-1.1 \
        --backend https://api.parkingwatch.example --token "$ADMIN_TOKEN"

Pasos: transfer learning de YOLOv8n con aumento de datos (brillo, contraste, desenfoque y
rotación leve), evaluación en el conjunto de prueba, exportación a ONNX cuantizado INT8,
registro en MLflow y en el backend. El modelo solo se puede activar si cumple las metas.
"""

import argparse
import hashlib
from pathlib import Path

from parking_ml.quality_gate import ModelMetrics, failures

AUGMENTATION = {"hsv_v": 0.4, "degrees": 3.0, "blur": 0.01, "translate": 0.05, "scale": 0.2}


def sha256(path: Path) -> str:
    """SHA-256 del artefacto; el agente lo verifica antes de instalarlo."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def train(data_yaml: Path, epochs: int) -> tuple[Path, ModelMetrics]:
    """Entrena y evalúa; la exactitud de placas se mide aparte (ver evaluate_plates)."""
    from ultralytics import YOLO

    model = YOLO("yolov8n.pt")
    model.train(data=str(data_yaml), epochs=epochs, imgsz=640, **AUGMENTATION)
    results = model.val(data=str(data_yaml), split="test")
    metrics = ModelMetrics(
        map50=float(results.box.map50),
        precision=float(results.box.mp),
        recall=float(results.box.mr),
        plate_accuracy=0.0,
    )
    exported = Path(model.export(format="onnx", imgsz=640, int8=True, dynamic=False, simplify=True))
    return exported, metrics


def register(version: str, model: Path, metrics: ModelMetrics, backend: str, token: str) -> None:
    """Registra la versión en MLflow y en el backend (POST /api/v1/models)."""
    import mlflow
    import requests

    with mlflow.start_run(run_name=version):
        mlflow.log_metrics(metrics.__dict__)
        mlflow.log_artifact(str(model))
    payload = {
        "version": version,
        "trainedAt": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
        "map50": metrics.map50,
        "precision": metrics.precision,
        "recall": metrics.recall,
        "plateAccuracy": metrics.plate_accuracy,
        "detectorObjectKey": f"models/{version}/detector.onnx",
        "detectorSha256": sha256(model),
    }
    response = requests.post(
        f"{backend}/api/v1/models", json=payload, headers={"Authorization": f"Bearer {token}"}, timeout=30
    )
    response.raise_for_status()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--plate-accuracy", type=float, required=True, help="Exactitud medida del OCR")
    parser.add_argument("--backend")
    parser.add_argument("--token")
    args = parser.parse_args()
    model, metrics = train(args.dataset / "data.yaml", args.epochs)
    metrics = ModelMetrics(metrics.map50, metrics.precision, metrics.recall, args.plate_accuracy)
    problems = failures(metrics)
    print("Métricas:", metrics, "| incumple:" if problems else "| cumple las metas", problems or "")
    if args.backend and args.token:
        register(args.version, model, metrics, args.backend, args.token)
        print("Suba el ONNX a s3://<bucket>/models/" + args.version + "/detector.onnx y actívelo desde la web.")


if __name__ == "__main__":
    main()
