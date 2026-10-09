package com.parkingwatch.edge.frame;

import java.io.IOException;
import java.util.Optional;

/**
 * Puerto de captura de video (patrón Strategy): cámara real por RTSP con JavaCV o fuente sintética
 * para simular la maqueta sin hardware.
 */
public interface FrameSource extends AutoCloseable {

  /** Abre la fuente; se puede llamar de nuevo para reconectar. */
  void open() throws IOException;

  /** Siguiente fotograma, o vacío si la fuente terminó. Bloquea hasta que haya uno. */
  Optional<Frame> next() throws IOException;

  @Override
  void close();
}
