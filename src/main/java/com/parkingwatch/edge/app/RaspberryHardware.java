package com.parkingwatch.edge.app;

import ai.onnxruntime.OrtException;
import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.edge.detection.ClassMapping;
import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.detection.DetectorSettings;
import com.parkingwatch.edge.detection.HotSwappableDetector;
import com.parkingwatch.edge.detection.ModelInput;
import com.parkingwatch.edge.detection.OnnxYoloDetector;
import com.parkingwatch.edge.domain.Detection;
import com.parkingwatch.edge.frame.FrameSource;
import com.parkingwatch.edge.frame.JavaCvFrameSource;
import com.parkingwatch.edge.frame.RpicamFrameSource;
import com.parkingwatch.edge.model.ModelManager;
import com.parkingwatch.edge.ocr.NoOpPlateReader;
import com.parkingwatch.edge.ocr.OnnxPlateReader;
import com.parkingwatch.edge.ocr.PlateReader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;

/**
 * Familia real para la Raspberry Pi 5: cámara con rpicam-vid y JavaCV y modelos ONNX con ONNX
 * Runtime. Si aún no hay modelo instalado, el detector no reporta nada hasta que el backend
 * publique uno.
 */
public final class RaspberryHardware implements Hardware {

  private final AgentConfig config;
  private final Clock clock;
  private final ModelManager.Downloader downloader;
  private final ClassMapping classes;

  public RaspberryHardware(AgentConfig config, Clock clock, ModelManager.Downloader downloader) {
    this.config = config;
    this.clock = clock;
    this.downloader = downloader;
    this.classes = config.prototypeClasses() ? ClassMapping.prototype() : ClassMapping.coco();
  }

  @Override
  public FrameSource frameSource() {
    return AgentConfig.RPICAM.equals(config.videoUrl())
        ? new RpicamFrameSource(config.camera(), clock)
        : new JavaCvFrameSource(config.videoUrl(), clock);
  }

  @Override
  public Detector detector() {
    return config
        .detectorModel()
        .map(this::loadDetector)
        .orElseGet(() -> idle(config.modelVersion()));
  }

  @Override
  public PlateReader plateReader() {
    return config
        .plateModel()
        .map(RaspberryHardware::loadPlateReader)
        .orElseGet(NoOpPlateReader::new);
  }

  @Override
  public Consumer<EdgeConfiguration> modelManager(HotSwappableDetector detector) {
    return new ModelManager(
        detector,
        config.modelsDirectory(),
        downloader,
        (file, version) ->
            OnnxYoloDetector.load(file, version, classes, DetectorSettings.defaults()));
  }

  private Detector loadDetector(Path path) {
    try {
      return OnnxYoloDetector.load(
          path, config.modelVersion(), classes, DetectorSettings.defaults());
    } catch (OrtException e) {
      throw new IllegalStateException("No se pudo cargar el modelo " + path, e);
    }
  }

  private static PlateReader loadPlateReader(Path path) {
    try {
      return OnnxPlateReader.load(path, OnnxPlateReader.Settings.defaults());
    } catch (OrtException e) {
      throw new IllegalStateException("No se pudo cargar el OCR " + path, e);
    }
  }

  private static Detector idle(String version) {
    return new Detector() {
      @Override
      public List<Detection> detect(ModelInput input) {
        return List.of();
      }

      @Override
      public String modelVersion() {
        return version;
      }
    };
  }
}
