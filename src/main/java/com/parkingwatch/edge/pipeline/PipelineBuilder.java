package com.parkingwatch.edge.pipeline;

import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.detection.LetterboxPreprocessor;
import com.parkingwatch.edge.detection.ModelInput;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.frame.FrameSource;
import com.parkingwatch.edge.monitoring.EdgeMetrics;
import com.parkingwatch.edge.ocr.PlateReader;
import com.parkingwatch.edge.ocr.PlateVoter;
import com.parkingwatch.edge.tracking.ByteTracker;
import com.parkingwatch.edge.transport.RetryPolicy;
import com.parkingwatch.edge.zone.ZoneEvaluator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Patrón Builder: arma el pipeline con sus colas acotadas y valida que no falte ninguna pieza. */
public final class PipelineBuilder {

  /**
   * Solo las colas de imágenes descartan (RF-9.2); las de datos tienen holgura para no perder
   * eventos.
   */
  private static final int FRAME_CAPACITY = 2;

  private static final int DATA_CAPACITY = 256;

  private FrameSource source;
  private LetterboxPreprocessor preprocessor;
  private Detector detector;
  private ByteTracker tracker;
  private ZoneEvaluator zones;
  private PlateReader plateReader;
  private PlateVoter voter;
  private int plateEveryFrames = 3;
  private Consumer<FrameData.Analysis> sink;
  private EdgeMetrics metrics;

  public PipelineBuilder source(FrameSource value) {
    this.source = value;
    return this;
  }

  public PipelineBuilder preprocessor(LetterboxPreprocessor value) {
    this.preprocessor = value;
    return this;
  }

  public PipelineBuilder detector(Detector value) {
    this.detector = value;
    return this;
  }

  public PipelineBuilder tracker(ByteTracker value) {
    this.tracker = value;
    return this;
  }

  public PipelineBuilder zones(ZoneEvaluator value) {
    this.zones = value;
    return this;
  }

  /** Lector de placas, votación y cada cuántos fotogramas se lee. */
  public PipelineBuilder plates(PlateReader reader, PlateVoter plateVoter, int everyFrames) {
    this.plateReader = reader;
    this.voter = plateVoter;
    this.plateEveryFrames = everyFrames;
    return this;
  }

  public PipelineBuilder sink(Consumer<FrameData.Analysis> value) {
    this.sink = value;
    return this;
  }

  public PipelineBuilder metrics(EdgeMetrics value) {
    this.metrics = value;
    return this;
  }

  /** Construye el pipeline listo para iniciar. */
  public Pipeline build() {
    BoundedQueue<Frame> frames = new BoundedQueue<>(FRAME_CAPACITY);
    BoundedQueue<ModelInput> inputs = new BoundedQueue<>(FRAME_CAPACITY);
    BoundedQueue<FrameData.Detected> detected = new BoundedQueue<>(FRAME_CAPACITY);
    BoundedQueue<FrameData.Tracked> tracked = new BoundedQueue<>(DATA_CAPACITY);
    BoundedQueue<FrameData.Analysis> analyzed = new BoundedQueue<>(DATA_CAPACITY);
    BoundedQueue<FrameData.Analysis> published = new BoundedQueue<>(DATA_CAPACITY);
    List<PipelineStage<?, ?>> stages =
        List.of(
            new Stages.Preprocess(require(preprocessor, "preprocessor"), frames, inputs),
            new Stages.Inference(
                require(detector, "detector"), require(metrics, "metrics"), inputs, detected),
            new Stages.Tracking(require(tracker, "tracker"), detected, tracked),
            new Stages.Zones(require(zones, "zones"), tracked, analyzed),
            new Stages.Plates(
                require(plateReader, "plateReader"),
                require(voter, "voter"),
                plateEveryFrames,
                analyzed,
                published),
            new Stages.Publish(require(sink, "sink"), published));
    CaptureLoop capture =
        new CaptureLoop(require(source, "source"), frames, RetryPolicy.defaults());
    return new Pipeline(
        capture, stages, List.of(frames, inputs, detected, tracked, analyzed, published));
  }

  private static <T> T require(T value, String name) {
    return Objects.requireNonNull(value, "Falta configurar " + name);
  }
}
