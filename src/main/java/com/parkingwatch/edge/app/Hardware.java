package com.parkingwatch.edge.app;

import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.detection.HotSwappableDetector;
import com.parkingwatch.edge.frame.FrameSource;
import com.parkingwatch.edge.ocr.PlateReader;
import java.util.function.Consumer;

/**
 * Patrón Abstract Factory: crea una familia coherente de adaptadores de captura, detección y OCR.
 * {@link RaspberryHardware} usa la cámara y los modelos ONNX; {@link SimulatedHardware} simula la
 * maqueta completa sin hardware.
 */
public interface Hardware {

  FrameSource frameSource();

  Detector detector();

  PlateReader plateReader();

  /** Observador que instala nuevas versiones del modelo cuando cambia la configuración. */
  Consumer<EdgeConfiguration> modelManager(HotSwappableDetector detector);

  /** Versión del agente leída del manifiesto del jar. */
  default String agentVersion() {
    String version = Hardware.class.getPackage().getImplementationVersion();
    return version == null ? "0.1.0-dev" : version;
  }
}
