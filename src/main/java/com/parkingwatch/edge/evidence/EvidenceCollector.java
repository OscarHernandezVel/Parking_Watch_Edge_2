package com.parkingwatch.edge.evidence;

import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.ocr.PlateRegionLocator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reúne la evidencia de un reporte: foto al entrar a la zona, foto al cumplirse la tolerancia y
 * recorte de la placa (RF-3.2, RF-7.3). Solo estas fotos salen del equipo, nunca el video (RF-9.3,
 * RNF-2.2).
 */
public final class EvidenceCollector {

  private record StayKey(long trackId, long zoneId) {}

  private final JpegEncoder encoder;
  private final PrivacyFilter privacy;
  private final PlateRegionLocator plateLocator;
  private final Map<StayKey, byte[]> entryPhotos = new ConcurrentHashMap<>();

  public EvidenceCollector(
      JpegEncoder encoder, PrivacyFilter privacy, PlateRegionLocator plateLocator) {
    this.encoder = encoder;
    this.privacy = privacy;
    this.plateLocator = plateLocator;
  }

  /** Guarda la foto de entrada del vehículo a la zona. */
  public void rememberEntry(long trackId, long zoneId, Frame frame) {
    entryPhotos.put(new StayKey(trackId, zoneId), encoder.encode(privacy.apply(frame)));
  }

  /** Arma la evidencia al superar la tolerancia. */
  public EvidenceBundle collect(long trackId, long zoneId, Frame frame, BoundingBox vehicleBox) {
    byte[] report = encoder.encode(privacy.apply(frame));
    byte[] entry = entryPhotos.getOrDefault(new StayKey(trackId, zoneId), report);
    byte[] plate = encoder.encode(frame.crop(plateLocator.locate(vehicleBox)));
    return new EvidenceBundle(entry, report, plate);
  }

  /** Olvida la foto de entrada cuando el vehículo sale. */
  public void forget(long trackId, long zoneId) {
    entryPhotos.remove(new StayKey(trackId, zoneId));
  }
}
