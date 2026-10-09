package com.parkingwatch.edge.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline en tiempo real (RF-9.1): captura, preprocesamiento, inferencia, seguimiento, zonas,
 * placas y publicación, desacopladas por colas y ejecutadas en hilos virtuales de Java 21.
 */
public final class Pipeline implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);

  private final CaptureLoop capture;
  private final List<PipelineStage<?, ?>> stages;
  private final List<BoundedQueue<?>> queues;
  private final List<Thread> threads = new ArrayList<>();

  Pipeline(CaptureLoop capture, List<PipelineStage<?, ?>> stages, List<BoundedQueue<?>> queues) {
    this.capture = capture;
    this.stages = List.copyOf(stages);
    this.queues = List.copyOf(queues);
  }

  /** Inicia cada etapa en su propio hilo virtual. */
  public synchronized void start() {
    for (PipelineStage<?, ?> stage : stages) {
      threads.add(Thread.ofVirtual().name("stage-" + stage.name()).start(stage));
    }
    threads.add(Thread.ofVirtual().name("stage-capture").start(capture));
    LOG.info("Pipeline iniciado con {} etapas", stages.size() + 1);
  }

  /** Espera a que la fuente termine (útil en simulaciones finitas y pruebas). */
  public void awaitCaptureFinished() throws InterruptedException {
    capture.awaitFinished();
  }

  /** Total de fotogramas descartados por contrapresión en todas las colas. */
  public long droppedFrames() {
    return queues.stream().mapToLong(BoundedQueue::dropped).sum();
  }

  /** Elementos pendientes en las colas (cero cuando el pipeline quedó al día). */
  public int backlog() {
    return queues.stream().mapToInt(BoundedQueue::size).sum();
  }

  @Override
  public synchronized void close() {
    threads.forEach(Thread::interrupt);
    for (Thread thread : threads) {
      try {
        thread.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    threads.clear();
  }
}
