package com.parkingwatch.edge.ocr;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.frame.Frame;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Lector de placas con un modelo de fast-plate-ocr en ONNX ejecutado con ONNX Runtime para Java
 * (RF-11.1). Entrada: imagen en escala de grises uint8 [1, alto, ancho, 1].
 */
public final class OnnxPlateReader implements PlateReader {

  private final OrtEnvironment environment;
  private final OrtSession session;
  private final String inputName;
  private final PlateRegionLocator locator;
  private final PlateOcrDecoder decoder;
  private final int width;
  private final int height;

  private OnnxPlateReader(OrtSession session, Settings settings) throws OrtException {
    this.environment = OrtEnvironment.getEnvironment();
    this.session = session;
    this.inputName = session.getInputNames().iterator().next();
    this.locator = settings.locator();
    this.decoder = new PlateOcrDecoder(settings.alphabet(), '_', settings.slots());
    this.width = settings.width();
    this.height = settings.height();
  }

  /** Parámetros del modelo de OCR. */
  public record Settings(
      int width, int height, int slots, String alphabet, PlateRegionLocator locator) {

    /** Modelo global de fast-plate-ocr (140 x 70 px, 9 ranuras). */
    public static Settings defaults() {
      return new Settings(
          140, 70, 9, PlateOcrDecoder.DEFAULT_ALPHABET, PlateRegionLocator.lowerBand());
    }
  }

  /** Carga el modelo desde un archivo ONNX. */
  public static OnnxPlateReader load(Path model, Settings settings) throws OrtException {
    OrtEnvironment environment = OrtEnvironment.getEnvironment();
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(2);
      return new OnnxPlateReader(environment.createSession(model.toString(), options), settings);
    }
  }

  @Override
  public Optional<OcrResult> read(Frame frame, BoundingBox vehicleBox) {
    Frame plate = frame.crop(locator.locate(vehicleBox));
    ByteBuffer pixels = ByteBuffer.allocateDirect(width * height);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        pixels.put((byte) gray(plate, x * plate.width() / width, y * plate.height() / height));
      }
    }
    pixels.rewind();
    try (OnnxTensor tensor =
            OnnxTensor.createTensor(
                environment, pixels, new long[] {1, height, width, 1}, OnnxJavaType.UINT8);
        OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
      float[][] output = (float[][]) result.get(0).getValue();
      return decoder.decode(output[0]);
    } catch (OrtException e) {
      throw new IllegalStateException("Fallo la inferencia del OCR de placas", e);
    }
  }

  private static int gray(Frame frame, int x, int y) {
    return (114 * frame.channel(x, y, 0)
            + 587 * frame.channel(x, y, 1)
            + 299 * frame.channel(x, y, 2))
        / 1000;
  }

  @Override
  public void close() {
    try {
      session.close();
    } catch (OrtException e) {
      throw new IllegalStateException("No se pudo cerrar el modelo de OCR", e);
    }
  }
}
