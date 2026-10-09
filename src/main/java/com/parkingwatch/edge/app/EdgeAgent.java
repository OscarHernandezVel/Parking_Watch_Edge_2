package com.parkingwatch.edge.app;

import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.edge.analytics.DriftMonitor;
import com.parkingwatch.edge.analytics.MinuteAggregator;
import com.parkingwatch.edge.analytics.RealtimeAnalytics;
import com.parkingwatch.edge.detection.DetectorSettings;
import com.parkingwatch.edge.detection.HotSwappableDetector;
import com.parkingwatch.edge.detection.LetterboxPreprocessor;
import com.parkingwatch.edge.evidence.EvidenceCollector;
import com.parkingwatch.edge.evidence.JpegEncoder;
import com.parkingwatch.edge.evidence.PrivacyFilter;
import com.parkingwatch.edge.monitoring.EdgeMetrics;
import com.parkingwatch.edge.monitoring.HealthServer;
import com.parkingwatch.edge.ocr.PlateRegionLocator;
import com.parkingwatch.edge.ocr.PlateVoter;
import com.parkingwatch.edge.pipeline.AnalysisProcessor;
import com.parkingwatch.edge.pipeline.Pipeline;
import com.parkingwatch.edge.pipeline.PipelineBuilder;
import com.parkingwatch.edge.tracking.ByteTracker;
import com.parkingwatch.edge.tracking.TrackerSettings;
import com.parkingwatch.edge.transport.BackendGateway;
import com.parkingwatch.edge.transport.ConfigurationPoller;
import com.parkingwatch.edge.transport.ReliableEventPublisher;
import com.parkingwatch.edge.transport.RetryPolicy;
import com.parkingwatch.edge.transport.SqliteOutboxStore;
import com.parkingwatch.edge.zone.MotionAnalyzer;
import com.parkingwatch.edge.zone.ZoneEvaluator;
import com.parkingwatch.edge.zone.ZoneRegistry;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Patrón Facade: arma y controla todas las piezas del agente (pipeline, buzón, telemetría, modelo y
 * servidor de salud) detrás de start/close.
 */
public final class EdgeAgent implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(EdgeAgent.class);

  private final Pipeline pipeline;
  private final TelemetryScheduler telemetry;
  private final ConfigurationPoller poller;
  private final HealthServer health;
  private final ReliableEventPublisher publisher;

  private EdgeAgent(
      Pipeline pipeline,
      TelemetryScheduler telemetry,
      ConfigurationPoller poller,
      HealthServer health,
      ReliableEventPublisher publisher) {
    this.pipeline = pipeline;
    this.telemetry = telemetry;
    this.poller = poller;
    this.health = health;
    this.publisher = publisher;
  }

  /** Crea el agente con sus adaptadores de hardware (o simulación) y el gateway al backend. */
  public static EdgeAgent create(
      AgentConfig config, Hardware hardware, BackendGateway gateway, Clock clock)
      throws IOException {
    EdgeMetrics metrics = new EdgeMetrics();
    ZoneRegistry zones = new ZoneRegistry();
    HotSwappableDetector detector = new HotSwappableDetector(hardware.detector());
    PlateVoter voter = new PlateVoter(0.8, 2);
    RealtimeAnalytics analytics = new RealtimeAnalytics();
    MinuteAggregator minutes = new MinuteAggregator();
    ReliableEventPublisher publisher =
        new ReliableEventPublisher(
            new SqliteOutboxStore(config.dataDirectory()), gateway, RetryPolicy.defaults());
    ConfigurationPoller poller = new ConfigurationPoller(gateway);
    poller.onChange(configuration -> zones.replace(ConfigurationMapper.zones(configuration)));
    poller.onChange(hardware.modelManager(detector));
    TelemetryScheduler[] telemetryRef = new TelemetryScheduler[1];
    AnalysisProcessor processor =
        new AnalysisProcessor(
            new AnalysisProcessor.Collaborators(
                publisher,
                new EvidenceCollector(
                    new JpegEncoder(0.85f), PrivacyFilter.none(), PlateRegionLocator.lowerBand()),
                voter,
                analytics,
                minutes,
                new DriftMonitor(DriftMonitor.Settings.defaults()),
                alert -> telemetryRef[0].enqueueDriftAlert(alert),
                zones,
                metrics));
    Pipeline pipeline =
        buildPipeline(config.tuning(), hardware, detector, zones, voter, processor, metrics);
    telemetryRef[0] =
        new TelemetryScheduler(
            new TelemetryScheduler.Sources(
                gateway,
                poller,
                publisher,
                processor,
                analytics,
                minutes,
                zones,
                detector,
                hardware.agentVersion()),
            clock);
    metrics.gauge("edge.outbox.size", () -> backlogOf(publisher));
    metrics.gauge("edge.pipeline.dropped", pipeline::droppedFrames);
    HealthServer health =
        new HealthServer(config.healthPort(), metrics, clock, () -> config.cameraId());
    return new EdgeAgent(pipeline, telemetryRef[0], poller, health, publisher);
  }

  /** Arma el pipeline en tiempo real con los parámetros calibrables (RF-9.1, RF-10.3). */
  private static Pipeline buildPipeline(
      AgentConfig.Tuning tuning,
      Hardware hardware,
      HotSwappableDetector detector,
      ZoneRegistry zones,
      PlateVoter voter,
      AnalysisProcessor processor,
      EdgeMetrics metrics) {
    return new PipelineBuilder()
        .source(hardware.frameSource())
        .preprocessor(new LetterboxPreprocessor(DetectorSettings.defaults().inputSize()))
        .detector(detector)
        .tracker(new ByteTracker(TrackerSettings.defaults()))
        .zones(
            new ZoneEvaluator(
                zones,
                new MotionAnalyzer(tuning.motionThresholdPx(), tuning.stationaryWindow()),
                tuning.enterFrames(),
                tuning.exitFrames()))
        .plates(hardware.plateReader(), voter, tuning.plateEveryFrames())
        .sink(processor)
        .metrics(metrics)
        .build();
  }

  /** Inicia: configuración inicial, servidor de salud, pipeline y tareas periódicas. */
  public void start() {
    poller.poll();
    health.start();
    pipeline.start();
    telemetry.start(
        poller
            .current()
            .map(EdgeConfiguration::settings)
            .orElse(EdgeConfiguration.Settings.defaults()));
    LOG.info("Agente de borde en marcha (salud en el puerto {})", health.port());
  }

  /** Aplica la configuración empujada por el backend por WebSocket. */
  public void applyConfiguration(EdgeConfiguration configuration) {
    poller.apply(configuration);
  }

  /** Consulta la configuración por HTTPS (al reconectarse el WebSocket). */
  public void refreshConfiguration() {
    poller.poll();
  }

  /** Espera a que termine la fuente de video (simulación finita). */
  public void awaitCaptureFinished() throws InterruptedException {
    pipeline.awaitCaptureFinished();
  }

  /** Envía lo pendiente del buzón (útil antes de apagar). */
  public int flushOutbox() throws IOException {
    return publisher.flush();
  }

  public int outboxBacklog() throws IOException {
    return publisher.backlog();
  }

  public int pipelineBacklog() {
    return pipeline.backlog();
  }

  private static int backlogOf(ReliableEventPublisher publisher) {
    try {
      return publisher.backlog();
    } catch (IOException e) {
      return -1;
    }
  }

  @Override
  public void close() {
    telemetry.close();
    pipeline.close();
    health.close();
    try {
      publisher.close();
    } catch (IOException e) {
      LOG.warn("No se pudo cerrar el búfer local: {}", e.getMessage());
    }
  }
}
