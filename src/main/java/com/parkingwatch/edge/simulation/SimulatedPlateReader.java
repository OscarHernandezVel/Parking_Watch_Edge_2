package com.parkingwatch.edge.simulation;

import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.ocr.OcrResult;
import com.parkingwatch.edge.ocr.PlateReader;
import java.util.Comparator;
import java.util.Optional;

/**
 * Lector de placas simulado: identifica el vehículo del guion que más se solapa con el recuadro y
 * devuelve su placa; cada cierto número de lecturas introduce un error para ejercitar la votación.
 */
public final class SimulatedPlateReader implements PlateReader {

  private static final int MISREAD_EVERY = 5;

  private final MaquetaScenario scenario;

  public SimulatedPlateReader(MaquetaScenario scenario) {
    this.scenario = scenario;
  }

  @Override
  public Optional<OcrResult> read(Frame frame, BoundingBox vehicleBox) {
    return scenario.at(frame.capturedAt()).stream()
        .filter(appearance -> appearance.box().iou(vehicleBox) > 0.3)
        .max(Comparator.comparingDouble(appearance -> appearance.box().iou(vehicleBox)))
        .map(appearance -> reading(appearance.vehicle().plate(), frame.sequence()));
  }

  private static OcrResult reading(String plate, long sequence) {
    if (sequence % MISREAD_EVERY == 0) {
      return new OcrResult(plate.substring(0, plate.length() - 1) + "8", 0.55);
    }
    return new OcrResult(plate, 0.9);
  }
}
