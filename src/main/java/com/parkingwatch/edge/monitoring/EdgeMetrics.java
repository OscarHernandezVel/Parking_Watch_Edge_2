package com.parkingwatch.edge.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Métricas de operación del agente con Micrometer (RNF-9.1): latencia de inferencia, fotogramas
 * procesados y descartados, eventos publicados y tamaño del buzón local.
 */
public final class EdgeMetrics {

  private final PrometheusMeterRegistry registry =
      new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  private final Timer inference =
      Timer.builder("edge.inference.latency")
          .description("Latencia de inferencia")
          .register(registry);
  private final Counter framesProcessed =
      Counter.builder("edge.frames.processed").register(registry);
  private final Counter framesDropped = Counter.builder("edge.frames.dropped").register(registry);
  private final Counter eventsPublished =
      Counter.builder("edge.events.published").register(registry);
  private final AtomicLong lastFrameEpochMillis = new AtomicLong();

  /**
   * Registra un indicador calculado (por ejemplo, FPS o tamaño del buzón). Se usa la variante con
   * referencia fuerte: con {@code registry.gauge(...)} Micrometer guarda el proveedor por
   * referencia débil y, al recolectarse la lambda, el indicador pasa a reportar {@code NaN}.
   */
  public void gauge(String name, Supplier<Number> supplier) {
    Gauge.builder(name, supplier).register(registry);
  }

  public void recordInference(Duration latency) {
    inference.record(latency);
  }

  /** Cuenta un fotograma procesado y guarda su hora. */
  public void frameProcessed(long epochMillis) {
    framesProcessed.increment();
    lastFrameEpochMillis.set(epochMillis);
  }

  public void framesDropped(long count) {
    framesDropped.increment(count);
  }

  public void eventPublished() {
    eventsPublished.increment();
  }

  public long lastFrameEpochMillis() {
    return lastFrameEpochMillis.get();
  }

  /** Texto en formato Prometheus para /metrics. */
  public String scrape() {
    return registry.scrape();
  }
}
