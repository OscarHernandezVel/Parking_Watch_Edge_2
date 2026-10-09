package com.parkingwatch.edge.app;

import com.parkingwatch.common.contract.DetectionSnapshot;
import com.parkingwatch.common.contract.DriftAlertMessage;
import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.common.contract.HeartbeatMessage;
import com.parkingwatch.common.contract.MinuteStatisticsMessage;
import com.parkingwatch.edge.analytics.MinuteAggregator;
import com.parkingwatch.edge.analytics.RealtimeAnalytics;
import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.domain.MonitoredZone;
import com.parkingwatch.edge.evidence.LiveVideoEncoder;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.pipeline.AnalysisProcessor;
import com.parkingwatch.edge.transport.BackendException;
import com.parkingwatch.edge.transport.BackendGateway;
import com.parkingwatch.edge.transport.ConfigurationPoller;
import com.parkingwatch.edge.transport.ReliableEventPublisher;
import com.parkingwatch.edge.zone.ZoneRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tareas periódicas de comunicación con el backend: señal de vida, configuración, envío del buzón,
 * detecciones en vivo, indicadores, estadísticas por minuto y alertas de deriva. Un fallo de red
 * nunca detiene el pipeline: cada tarea registra el error y lo reintenta en su ciclo.
 */
public final class TelemetryScheduler implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(TelemetryScheduler.class);

  /** Revisión frecuente del búfer: un evento llega al backend en menos de 1 s (RF-11.4). */
  private static final long FLUSH_INTERVAL_MS = 250;

  private static final long STATISTICS_INTERVAL_MS = 10_000;

  /** Colaboradores de las tareas periódicas. */
  public record Sources(
      BackendGateway gateway,
      ConfigurationPoller poller,
      ReliableEventPublisher publisher,
      AnalysisProcessor processor,
      RealtimeAnalytics analytics,
      MinuteAggregator minutes,
      ZoneRegistry zones,
      Detector detector,
      String agentVersion) {}

  private final Sources sources;
  private final Clock clock;
  private final Instant startedAt;
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
  private final LiveVideoEncoder videoEncoder = new LiveVideoEncoder();
  private volatile EdgeConfiguration.LiveVideo video = EdgeConfiguration.LiveVideo.defaults();
  private final Queue<DriftAlertMessage> driftAlerts = new ConcurrentLinkedQueue<>();
  private final Queue<MinuteStatisticsMessage> pendingStatistics = new ConcurrentLinkedQueue<>();

  public TelemetryScheduler(Sources sources, Clock clock) {
    this.sources = sources;
    this.clock = clock;
    this.startedAt = clock.instant();
  }

  /** Encola una alerta de deriva para enviarla en el siguiente ciclo. */
  public void enqueueDriftAlert(DriftAlertMessage alert) {
    driftAlerts.add(alert);
  }

  /** Programa todas las tareas con los intervalos de la configuración. */
  public void start(EdgeConfiguration.Settings settings) {
    if (settings.video() != null) {
      video = settings.video();
    }
    schedule("video", this::sendVideo, 1000L / Math.max(1, video.fps()));
    schedule("config", this::pollConfiguration, settings.configPollIntervalSeconds() * 1000L);
    schedule("heartbeat", this::sendHeartbeat, settings.heartbeatIntervalSeconds() * 1000L);
    schedule("outbox", this::flushOutbox, FLUSH_INTERVAL_MS);
    schedule("detections", this::sendDetections, settings.detectionSampleIntervalMs());
    schedule("analytics", this::sendAnalytics, settings.analyticsIntervalSeconds() * 1000L);
    schedule("statistics", this::sendStatistics, STATISTICS_INTERVAL_MS);
  }

  void pollConfiguration() {
    sources.poller().poll();
  }

  void sendHeartbeat() throws BackendException {
    Instant now = clock.instant();
    sources
        .gateway()
        .sendHeartbeat(
            new HeartbeatMessage(
                now,
                sources.analytics().fps(now),
                sources.detector().modelVersion(),
                Duration.between(startedAt, now).toSeconds(),
                sources.agentVersion()));
  }

  void flushOutbox() throws IOException, BackendException {
    sources.publisher().flush();
    DriftAlertMessage alert;
    while ((alert = driftAlerts.peek()) != null) {
      sources.gateway().sendDriftAlert(alert);
      driftAlerts.poll();
    }
  }

  void sendDetections() throws BackendException {
    DetectionSnapshot snapshot = sources.processor().takeLatestDetections();
    if (snapshot != null) {
      sources.gateway().sendDetections(snapshot);
    }
  }

  /** Envía el fotograma más reciente reducido; si no hay conexión simplemente se descarta. */
  void sendVideo() throws BackendException {
    Frame frame = sources.processor().takeLatestFrame();
    if (frame != null) {
      sources.gateway().sendVideoFrame(videoEncoder.encode(frame, video));
    }
  }

  void sendAnalytics() throws BackendException {
    var zoneIds = sources.zones().current().stream().map(MonitoredZone::id).toList();
    sources.gateway().sendAnalytics(sources.analytics().snapshot(clock.instant(), zoneIds));
  }

  void sendStatistics() throws BackendException {
    pendingStatistics.addAll(sources.minutes().drainCompleted(clock.instant()));
    MinuteStatisticsMessage message;
    while ((message = pendingStatistics.peek()) != null) {
      sources.gateway().sendStatistics(message);
      pendingStatistics.poll();
    }
  }

  private void schedule(String name, Task task, long periodMs) {
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            task.run();
          } catch (BackendException e) {
            // Sin conexión es un estado esperado (el búfer y la reconexión lo resuelven).
            if (e.isRetryable()) {
              LOG.debug("Tarea {} sin conexión: {}", name, e.getMessage());
            } else {
              LOG.warn("Tarea {} rechazada por el backend: {}", name, e.getMessage());
            }
          } catch (IOException | RuntimeException e) {
            LOG.warn("Tarea {} falló: {}", name, e.getMessage());
          }
        },
        0,
        Math.max(100, periodMs),
        TimeUnit.MILLISECONDS);
  }

  /** Tarea que puede fallar por red o disco. */
  @FunctionalInterface
  interface Task {
    void run() throws BackendException, IOException;
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
