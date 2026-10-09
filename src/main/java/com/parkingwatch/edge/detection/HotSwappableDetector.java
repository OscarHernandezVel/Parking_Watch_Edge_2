package com.parkingwatch.edge.detection;

import com.parkingwatch.edge.domain.Detection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Patrón Proxy: el pipeline siempre usa esta instancia y el modelo real se reemplaza en caliente
 * cuando el administrador activa otra versión (RF-13.3), sin detener el procesamiento.
 */
public final class HotSwappableDetector implements Detector {

  private final AtomicReference<Detector> delegate;

  public HotSwappableDetector(Detector initial) {
    this.delegate = new AtomicReference<>(initial);
  }

  /** Instala un nuevo detector y libera el anterior. */
  public void swap(Detector replacement) {
    Detector previous = delegate.getAndSet(replacement);
    if (!previous.equals(replacement)) {
      previous.close();
    }
  }

  @Override
  public List<Detection> detect(ModelInput input) {
    return delegate.get().detect(input);
  }

  @Override
  public String modelVersion() {
    return delegate.get().modelVersion();
  }

  @Override
  public void close() {
    delegate.get().close();
  }
}
