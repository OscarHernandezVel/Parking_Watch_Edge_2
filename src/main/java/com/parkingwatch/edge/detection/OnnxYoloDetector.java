package com.parkingwatch.edge.detection;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.parkingwatch.edge.domain.Detection;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Detector YOLOv8n exportado a ONNX (cuantizado INT8) y ejecutado con ONNX Runtime para Java en la
 * CPU de la Raspberry Pi 5 (RF-10.1).
 */
public final class OnnxYoloDetector implements Detector {

  private final OrtEnvironment environment;
  private final OrtSession session;
  private final String inputName;
  private final String version;
  private final int inputSize;
  private final YoloOutputDecoder decoder;
  private final NonMaxSuppression nms;

  private OnnxYoloDetector(
      OrtSession session, String version, ClassMapping mapping, DetectorSettings settings)
      throws OrtException {
    this.environment = OrtEnvironment.getEnvironment();
    this.session = session;
    this.inputName = session.getInputNames().iterator().next();
    this.version = version;
    this.inputSize = settings.inputSize();
    this.decoder = new YoloOutputDecoder(mapping, settings.confidenceThreshold());
    this.nms = new NonMaxSuppression(settings.iouThreshold());
  }

  /** Carga el modelo desde un archivo ONNX. */
  public static OnnxYoloDetector load(
      Path model, String version, ClassMapping mapping, DetectorSettings settings)
      throws OrtException {
    OrtEnvironment environment = OrtEnvironment.getEnvironment();
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
      options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
      OrtSession session = environment.createSession(model.toString(), options);
      return new OnnxYoloDetector(session, version, mapping, settings);
    }
  }

  @Override
  public List<Detection> detect(ModelInput input) {
    long[] shape = {1, 3, inputSize, inputSize};
    try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input.tensor(), shape);
        OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
      float[][][] output = (float[][][]) result.get(0).getValue();
      return nms.apply(decoder.decode(output[0])).stream()
          .map(
              detection ->
                  new Detection(
                      input
                          .letterbox()
                          .toOriginal(
                              detection.box(), input.frame().width(), input.frame().height()),
                      detection.type(),
                      detection.confidence()))
          .toList();
    } catch (OrtException e) {
      throw new IllegalStateException("Falló la inferencia de YOLO", e);
    }
  }

  @Override
  public String modelVersion() {
    return version;
  }

  @Override
  public void close() {
    try {
      session.close();
    } catch (OrtException e) {
      throw new IllegalStateException("No se pudo cerrar el modelo de detección", e);
    }
  }
}
