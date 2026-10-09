package com.parkingwatch.edge.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EdgeMetricsTest {

  @Test
  void computedGaugesSurviveGarbageCollection() {
    EdgeMetrics metrics = new EdgeMetrics();
    AtomicInteger backlog = new AtomicInteger(7);
    metrics.gauge("edge.outbox.size", backlog::get);

    // Regresión: el proveedor no debe quedar con referencia débil (antes reportaba NaN).
    for (int i = 0; i < 3; i++) {
      System.gc();
    }

    assertThat(metrics.scrape()).contains("edge_outbox_size 7.0");
    backlog.set(2);
    assertThat(metrics.scrape()).contains("edge_outbox_size 2.0");
  }

  @Test
  void countersAndTimersAreExposedInPrometheusFormat() {
    EdgeMetrics metrics = new EdgeMetrics();
    metrics.frameProcessed(1_000L);
    metrics.framesDropped(2);
    metrics.eventPublished();
    metrics.recordInference(Duration.ofMillis(5));

    assertThat(metrics.lastFrameEpochMillis()).isEqualTo(1_000L);
    assertThat(metrics.scrape())
        .contains("edge_frames_processed_total 1.0")
        .contains("edge_frames_dropped_total 2.0")
        .contains("edge_events_published_total 1.0")
        .contains("edge_inference_latency_seconds_count 1");
  }
}
