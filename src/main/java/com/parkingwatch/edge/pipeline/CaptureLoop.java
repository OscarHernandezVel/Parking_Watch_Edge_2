package com.parkingwatch.edge.pipeline;

import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.frame.FrameSource;
import com.parkingwatch.edge.transport.RetryPolicy;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Etapa de captura: lee la fuente de video y entrega cada fotograma a la cola de entrada. Si la
 * cámara se desconecta, reintenta con espera exponencial sin detener el resto del agente.
 */
public final class CaptureLoop implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(CaptureLoop.class);

  private final FrameSource source;
  private final BoundedQueue<Frame> output;
  private final RetryPolicy retry;
  private final CountDownLatch finished = new CountDownLatch(1);

  public CaptureLoop(FrameSource source, BoundedQueue<Frame> output, RetryPolicy retry) {
    this.source = source;
    this.output = output;
    this.retry = retry;
  }

  @Override
  public void run() {
    int attempt = 0;
    try {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          source.open();
          attempt = 0;
          if (!pump()) {
            return;
          }
        } catch (IOException e) {
          attempt++;
          LOG.warn("Fuente de video no disponible (intento {}): {}", attempt, e.getMessage());
          Thread.sleep(retry.delayFor(attempt).toMillis());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      source.close();
      finished.countDown();
    }
  }

  /** Lee fotogramas hasta que la fuente termina (false) o falla (IOException). */
  private boolean pump() throws IOException {
    while (!Thread.currentThread().isInterrupted()) {
      Optional<Frame> frame = source.next();
      if (frame.isEmpty()) {
        LOG.info("La fuente de video terminó");
        return false;
      }
      output.offer(frame.get());
    }
    return false;
  }

  /** Espera a que la captura termine (fuente finita, por ejemplo en simulación). */
  public void awaitFinished() throws InterruptedException {
    finished.await();
  }
}
