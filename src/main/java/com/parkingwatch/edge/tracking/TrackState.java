package com.parkingwatch.edge.tracking;

/** Ciclo de vida de una pista en ByteTrack. */
public enum TrackState {
  /** Detectada una vez; se confirma si vuelve a aparecer en el siguiente fotograma. */
  NEW,
  /** Seguida en el fotograma actual. */
  TRACKED,
  /** El detector la perdió; se conserva un tiempo para recuperarla con el mismo id. */
  LOST,
  /** Descartada definitivamente. */
  REMOVED
}
