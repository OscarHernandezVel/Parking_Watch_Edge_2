"""Pruebas del quality gate y de la división del dataset."""

from pathlib import Path

from parking_ml.dataset import split_images
from parking_ml.quality_gate import ModelMetrics, failures, outperforms


def test_gate_accepts_models_that_meet_every_target():
    assert failures(ModelMetrics(0.92, 0.96, 0.91, 0.93)) == []


def test_gate_lists_each_missed_target():
    problems = failures(ModelMetrics(0.80, 0.96, 0.85, 0.93))
    assert len(problems) == 2
    assert problems[0].startswith("map50")


def test_candidate_must_improve_f1_without_losing_map():
    current = ModelMetrics(0.92, 0.96, 0.91, 0.93)
    assert outperforms(ModelMetrics(0.92, 0.96, 0.95, 0.93), current)
    assert not outperforms(ModelMetrics(0.91, 0.99, 0.99, 0.93), current)


def test_split_is_70_15_15_and_reproducible():
    images = [Path(f"img_{i:03d}.jpg") for i in range(100)]
    first = split_images(images, seed=7)
    assert [len(first[s]) for s in ("train", "val", "test")] == [70, 15, 15]
    assert first == split_images(list(reversed(images)), seed=7)
    assert set(first["train"]).isdisjoint(first["test"])
