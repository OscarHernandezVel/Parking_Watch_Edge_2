package com.parkingwatch.edge.transport;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Espera exponencial con variación aleatoria entre reintentos (evita ráfagas sincronizadas). */
public record RetryPolicy(Duration initialDelay, Duration maxDelay, double multiplier) {

  /** Política por defecto: 1 s, 2 s, 4 s... hasta 60 s. */
  public static RetryPolicy defaults() {
    return new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60), 2.0);
  }

  /** Espera antes del intento número {@code attempt} (empezando en 1). */
  public Duration delayFor(int attempt) {
    double base = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, attempt - 1));
    double capped = Math.min(base, maxDelay.toMillis());
    double jitter = capped * 0.2 * ThreadLocalRandom.current().nextDouble();
    return Duration.ofMillis((long) (capped * 0.9 + jitter));
  }
}
