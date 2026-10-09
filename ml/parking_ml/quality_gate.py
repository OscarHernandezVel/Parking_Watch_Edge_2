"""Metas mínimas de un modelo (RF-13.2); son las mismas que valida el backend al activarlo."""

from dataclasses import dataclass

THRESHOLDS = {
    "map50": 0.90,
    "precision": 0.95,
    "recall": 0.90,
    "plate_accuracy": 0.90,
}


@dataclass(frozen=True)
class ModelMetrics:
    """Métricas del modelo sobre el conjunto de prueba."""

    map50: float
    precision: float
    recall: float
    plate_accuracy: float

    @property
    def f1(self) -> float:
        total = self.precision + self.recall
        return 0.0 if total == 0 else 2 * self.precision * self.recall / total


def failures(metrics: ModelMetrics) -> list[str]:
    """Lista de metas incumplidas; vacía si el modelo se puede desplegar."""
    return [
        f"{name} = {getattr(metrics, name):.3f} < {minimum:.2f}"
        for name, minimum in THRESHOLDS.items()
        if getattr(metrics, name) < minimum
    ]


def outperforms(candidate: ModelMetrics, current: ModelMetrics) -> bool:
    """El candidato reemplaza al vigente solo si lo supera (RF-14.3): mAP no menor y F1 mayor."""
    return candidate.map50 >= current.map50 and candidate.f1 > current.f1
