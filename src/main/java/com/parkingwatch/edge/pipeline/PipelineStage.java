package com.parkingwatch.edge.pipeline;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Patrón Template Method: el ciclo de una etapa (tomar de la cola de entrada, procesar y entregar a
 * la de salida) es fijo; cada etapa implementa solo {@link #process}. Un error en un fotograma se
 * registra y no detiene el pipeline (RNF-5.1).
 *
 * @param <I> tipo de entrada
 * @param <O> tipo de salida; las etapas finales no tienen cola de salida
 */
public abstract class PipelineStage<I, O> implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(PipelineStage.class);

  private final String name;
  private final BoundedQueue<I> input;
  private final BoundedQueue<O> output;
  private final AtomicLong processed = new AtomicLong();

  protected PipelineStage(String name, BoundedQueue<I> input, BoundedQueue<O> output) {
    this.name = name;
    this.input = input;
    this.output = output;
  }

  @Override
  public final void run() {
    while (!Thread.currentThread().isInterrupted()) {
      I item;
      try {
        item = input.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      handle(item);
    }
  }

  /** Procesa un elemento de forma síncrona (también lo usan las pruebas). */
  public final void handle(I item) {
    try {
      O result = process(item);
      processed.incrementAndGet();
      if (result != null && output != null) {
        output.offer(result);
      }
    } catch (RuntimeException e) {
      LOG.error("Error en la etapa {}; se descarta el elemento", name, e);
    }
  }

  protected abstract O process(I item);

  public String name() {
    return name;
  }

  public long processed() {
    return processed.get();
  }
}
