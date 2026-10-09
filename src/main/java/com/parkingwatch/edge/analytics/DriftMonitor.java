package com.parkingwatch.edge.analytics;

import com.parkingwatch.common.contract.DriftAlertMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Monitoreo de deriva de datos en el borde (RF-14.2): compara un promedio móvil rápido con una
 * línea base lenta (EWMA) de la confianza del modelo y del brillo de la imagen. Si se alejan más
 * que el umbral relativo, emite una alerta (con un tiempo de espera para no repetirla).
 */
public final class DriftMonitor {

  private static final double BASELINE_ALPHA = 0.002;
  private static final double RECENT_ALPHA = 0.05;

  /** Parámetros del monitor. */
  public record Settings(
      int warmupSamples,
      double confidenceThreshold,
      double brightnessThreshold,
      Duration cooldown) {

    public static Settings defaults() {
      return new Settings(600, 0.15, 0.30, Duration.ofMinutes(10));
    }
  }

  private static final class Signal {
    private double baseline;
    private double recent;
    private long samples;

    void add(double value) {
      if (samples++ == 0) {
        baseline = value;
        recent = value;
        return;
      }
      baseline += BASELINE_ALPHA * (value - baseline);
      recent += RECENT_ALPHA * (value - recent);
    }

    double relativeDeviation() {
      return baseline == 0 ? 0 : Math.abs(recent - baseline) / baseline;
    }
  }

  private final Settings settings;
  private final Map<DriftAlertMessage.Type, Signal> signals =
      new EnumMap<>(DriftAlertMessage.Type.class);
  private final Map<DriftAlertMessage.Type, Instant> lastAlert =
      new EnumMap<>(DriftAlertMessage.Type.class);

  public DriftMonitor(Settings settings) {
    this.settings = settings;
    for (DriftAlertMessage.Type type : DriftAlertMessage.Type.values()) {
      signals.put(type, new Signal());
    }
  }

  /** Registra la confianza promedio de las detecciones (si hubo) y el brillo del fotograma. */
  public synchronized Optional<DriftAlertMessage> record(
      Double meanConfidence, double brightness, Instant at) {
    if (meanConfidence != null) {
      signals.get(DriftAlertMessage.Type.CONFIDENCE_DRIFT).add(meanConfidence);
    }
    signals.get(DriftAlertMessage.Type.BRIGHTNESS_DRIFT).add(brightness);
    return check(DriftAlertMessage.Type.CONFIDENCE_DRIFT, settings.confidenceThreshold(), at)
        .or(
            () ->
                check(DriftAlertMessage.Type.BRIGHTNESS_DRIFT, settings.brightnessThreshold(), at));
  }

  private Optional<DriftAlertMessage> check(
      DriftAlertMessage.Type type, double threshold, Instant at) {
    Signal signal = signals.get(type);
    Instant previous = lastAlert.get(type);
    boolean coolingDown = previous != null && previous.plus(settings.cooldown()).isAfter(at);
    if (signal.samples < settings.warmupSamples()
        || coolingDown
        || signal.relativeDeviation() <= threshold) {
      return Optional.empty();
    }
    lastAlert.put(type, at);
    String message =
        String.format(
            Locale.ROOT,
            "%s se alejó %.0f %% de su línea base",
            type == DriftAlertMessage.Type.CONFIDENCE_DRIFT
                ? "La confianza promedio"
                : "El brillo promedio",
            signal.relativeDeviation() * 100);
    return Optional.of(
        new DriftAlertMessage(type, round(signal.recent), round(signal.baseline), at, message));
  }

  private static double round(double value) {
    return Math.round(value * 1000) / 1000.0;
  }
}
