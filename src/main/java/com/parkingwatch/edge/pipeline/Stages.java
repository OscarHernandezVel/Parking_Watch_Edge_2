package com.parkingwatch.edge.pipeline;

import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.detection.LetterboxPreprocessor;
import com.parkingwatch.edge.detection.ModelInput;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.monitoring.EdgeMetrics;
import com.parkingwatch.edge.ocr.PlateReader;
import com.parkingwatch.edge.ocr.PlateVoter;
import com.parkingwatch.edge.tracking.ByteTracker;
import com.parkingwatch.edge.zone.VehicleZoneStatus;
import com.parkingwatch.edge.zone.ZoneEvaluator;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Etapas concretas del pipeline (RF-9.1): preprocesamiento, inferencia, seguimiento, evaluación de
 * zonas, lectura de placas y publicación. Cada una corre en su propio hilo virtual.
 */
public final class Stages {

  private Stages() {}

  /** Redimensiona a 640 px y normaliza. */
  public static final class Preprocess extends PipelineStage<Frame, ModelInput> {
    private final LetterboxPreprocessor preprocessor;

    public Preprocess(
        LetterboxPreprocessor preprocessor, BoundedQueue<Frame> in, BoundedQueue<ModelInput> out) {
      super("preprocess", in, out);
      this.preprocessor = preprocessor;
    }

    @Override
    protected ModelInput process(Frame frame) {
      return preprocessor.prepare(frame);
    }
  }

  /** Detecta vehículos con el modelo vigente y mide la latencia. */
  public static final class Inference extends PipelineStage<ModelInput, FrameData.Detected> {
    private final Detector detector;
    private final EdgeMetrics metrics;

    public Inference(
        Detector detector,
        EdgeMetrics metrics,
        BoundedQueue<ModelInput> in,
        BoundedQueue<FrameData.Detected> out) {
      super("inference", in, out);
      this.detector = detector;
      this.metrics = metrics;
    }

    @Override
    protected FrameData.Detected process(ModelInput input) {
      long start = System.nanoTime();
      var detections = detector.detect(input);
      Duration latency = Duration.ofNanos(System.nanoTime() - start);
      metrics.recordInference(latency);
      return new FrameData.Detected(input.frame(), detections, latency, detector.modelVersion());
    }
  }

  /** Asigna identificadores estables con ByteTrack. */
  public static final class Tracking extends PipelineStage<FrameData.Detected, FrameData.Tracked> {
    private final ByteTracker tracker;

    public Tracking(
        ByteTracker tracker,
        BoundedQueue<FrameData.Detected> in,
        BoundedQueue<FrameData.Tracked> out) {
      super("tracking", in, out);
      this.tracker = tracker;
    }

    @Override
    protected FrameData.Tracked process(FrameData.Detected detected) {
      var tracks = tracker.update(detected.detections());
      return new FrameData.Tracked(detected, tracks, tracker.aliveTrackIds());
    }
  }

  /** Evalúa zonas, inmovilidad e histéresis. */
  public static final class Zones extends PipelineStage<FrameData.Tracked, FrameData.Analysis> {
    private final ZoneEvaluator evaluator;

    public Zones(
        ZoneEvaluator evaluator,
        BoundedQueue<FrameData.Tracked> in,
        BoundedQueue<FrameData.Analysis> out) {
      super("zones", in, out);
      this.evaluator = evaluator;
    }

    @Override
    protected FrameData.Analysis process(FrameData.Tracked tracked) {
      ZoneEvaluator.Evaluation evaluation =
          evaluator.evaluate(
              tracked.tracks(), tracked.aliveTrackIds(), tracked.detected().frame().capturedAt());
      return new FrameData.Analysis(tracked.detected(), evaluation.vehicles(), evaluation.events());
    }
  }

  /**
   * Lee placas de los vehículos detenidos en una zona cada N fotogramas y acumula votos (RF-11.2);
   * así el OCR no consume CPU con vehículos en movimiento.
   */
  public static final class Plates extends PipelineStage<FrameData.Analysis, FrameData.Analysis> {
    private final PlateReader reader;
    private final PlateVoter voter;
    private final int everyFrames;

    public Plates(
        PlateReader reader,
        PlateVoter voter,
        int everyFrames,
        BoundedQueue<FrameData.Analysis> in,
        BoundedQueue<FrameData.Analysis> out) {
      super("plates", in, out);
      this.reader = reader;
      this.voter = voter;
      this.everyFrames = Math.max(1, everyFrames);
    }

    @Override
    protected FrameData.Analysis process(FrameData.Analysis analysis) {
      if (analysis.frame().sequence() % everyFrames == 0) {
        for (VehicleZoneStatus vehicle : analysis.vehicles()) {
          if (vehicle.zoneId() != null && vehicle.stationarySeconds() > 0) {
            reader
                .read(analysis.frame(), vehicle.track().box())
                .ifPresent(result -> voter.add(vehicle.track().trackId(), result));
          }
        }
      }
      return analysis;
    }
  }

  /** Etapa final: entrega el análisis al procesador de eventos. */
  public static final class Publish extends PipelineStage<FrameData.Analysis, Void> {
    private final Consumer<FrameData.Analysis> sink;

    public Publish(Consumer<FrameData.Analysis> sink, BoundedQueue<FrameData.Analysis> in) {
      super("publish", in, null);
      this.sink = sink;
    }

    @Override
    protected Void process(FrameData.Analysis analysis) {
      sink.accept(analysis);
      return null;
    }
  }
}
