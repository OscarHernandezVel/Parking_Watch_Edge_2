package com.parkingwatch.edge.ocr;

import com.parkingwatch.common.contract.PlateReading;
import com.parkingwatch.common.domain.PlateNumber;
import com.parkingwatch.common.domain.PlateStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Votación temporal (RF-11.2): combina las lecturas de varios fotogramas del mismo vehículo y se
 * queda con la placa válida más frecuente y de mayor confianza. Con poca evidencia o confianza baja
 * la placa queda "por confirmar".
 */
public final class PlateVoter {

  private final double minConfidence;
  private final int minVotes;
  private final Map<Long, List<OcrResult>> readings = new HashMap<>();

  public PlateVoter(double minConfidence, int minVotes) {
    this.minConfidence = minConfidence;
    this.minVotes = minVotes;
  }

  /** Registra una lectura de un vehículo; descarta textos sin formato colombiano. */
  public synchronized void add(long trackId, OcrResult result) {
    PlateNumber.normalize(result.text())
        .ifPresent(
            plate ->
                readings
                    .computeIfAbsent(trackId, id -> new ArrayList<>())
                    .add(new OcrResult(plate, result.confidence())));
  }

  /** Resultado de la votación para el vehículo. */
  public synchronized PlateReading vote(long trackId) {
    List<OcrResult> votes = readings.getOrDefault(trackId, List.of());
    if (votes.isEmpty()) {
      return PlateReading.notRead();
    }
    Map<String, List<OcrResult>> byPlate =
        votes.stream().collect(Collectors.groupingBy(OcrResult::text));
    Map.Entry<String, List<OcrResult>> winner =
        byPlate.entrySet().stream()
            .max(
                Comparator.<Map.Entry<String, List<OcrResult>>>comparingInt(
                        e -> e.getValue().size())
                    .thenComparingDouble(e -> mean(e.getValue())))
            .orElseThrow();
    double confidence = mean(winner.getValue());
    boolean reliable = winner.getValue().size() >= minVotes && confidence >= minConfidence;
    return new PlateReading(
        winner.getKey(),
        Math.round(confidence * 100) / 100.0,
        reliable ? PlateStatus.READ : PlateStatus.PENDING_CONFIRMATION);
  }

  /** Olvida las lecturas de un vehículo que salió. */
  public synchronized void forget(long trackId) {
    readings.remove(trackId);
  }

  private static double mean(List<OcrResult> results) {
    return results.stream().mapToDouble(OcrResult::confidence).average().orElse(0);
  }
}
