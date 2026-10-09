package com.parkingwatch.edge.pipeline;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cola de tamaño limitado entre etapas (RF-9.1) que descarta el elemento más antiguo cuando está
 * llena (RF-9.2): si el modelo va más lento que la cámara, el sistema siempre trabaja sobre la
 * imagen más reciente y la latencia no se acumula.
 *
 * @param <T> tipo de los elementos
 */
public final class BoundedQueue<T> {

  private final BlockingQueue<T> queue;
  private final AtomicLong dropped = new AtomicLong();

  public BoundedQueue(int capacity) {
    this.queue = new ArrayBlockingQueue<>(capacity);
  }

  /** Encola sin bloquear, descartando los elementos más antiguos si hace falta. */
  public void offer(T item) {
    while (!queue.offer(item)) {
      if (queue.poll() != null) {
        dropped.incrementAndGet();
      }
    }
  }

  /** Toma el siguiente elemento, esperando si la cola está vacía. */
  public T take() throws InterruptedException {
    return queue.take();
  }

  public int size() {
    return queue.size();
  }

  /** Elementos descartados por contrapresión. */
  public long dropped() {
    return dropped.get();
  }
}
