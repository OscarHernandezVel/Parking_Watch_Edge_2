package com.parkingwatch.edge.evidence;

/** Las tres fotos de evidencia de un reporte, en JPEG (RF-3.2). */
public record EvidenceBundle(byte[] entry, byte[] report, byte[] plate) {

  /** Copias defensivas. */
  public EvidenceBundle {
    entry = entry.clone();
    report = report.clone();
    plate = plate.clone();
  }

  @Override
  public byte[] entry() {
    return entry.clone();
  }

  @Override
  public byte[] report() {
    return report.clone();
  }

  @Override
  public byte[] plate() {
    return plate.clone();
  }
}
