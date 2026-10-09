"""Construcción del dataset propio de la maqueta (RF-13.1): división 70/15/15 reproducible."""

import random
import shutil
from pathlib import Path

SPLITS = {"train": 0.70, "val": 0.15, "test": 0.15}
CLASSES = ["car", "motorcycle", "bus", "truck"]


def split_images(images: list[Path], seed: int = 42) -> dict[str, list[Path]]:
    """Divide las imágenes en entrenamiento, validación y prueba de forma determinista."""
    shuffled = sorted(images)
    random.Random(seed).shuffle(shuffled)
    train_end = int(len(shuffled) * SPLITS["train"])
    val_end = train_end + int(len(shuffled) * SPLITS["val"])
    return {
        "train": shuffled[:train_end],
        "val": shuffled[train_end:val_end],
        "test": shuffled[val_end:],
    }


def build_yolo_dataset(source: Path, target: Path, seed: int = 42) -> Path:
    """Copia imágenes y etiquetas YOLO (exportadas de Label Studio) y escribe data.yaml."""
    images = [p for p in source.glob("images/*") if p.suffix.lower() in {".jpg", ".jpeg", ".png"}]
    for split, files in split_images(images, seed).items():
        for image in files:
            label = source / "labels" / f"{image.stem}.txt"
            for kind, file in (("images", image), ("labels", label)):
                destination = target / kind / split
                destination.mkdir(parents=True, exist_ok=True)
                if file.exists():
                    shutil.copy2(file, destination / file.name)
    data_yaml = target / "data.yaml"
    names = "\n".join(f"  {index}: {name}" for index, name in enumerate(CLASSES))
    data_yaml.write_text(
        f"path: {target.resolve()}\ntrain: images/train\nval: images/val\ntest: images/test\nnames:\n{names}\n",
        encoding="utf-8",
    )
    return data_yaml
