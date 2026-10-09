package com.parkingwatch.edge.transport;

import com.parkingwatch.common.contract.EventBatchResult;
import com.parkingwatch.common.contract.EventResult;
import com.parkingwatch.common.contract.EvidenceKeys;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.common.domain.EvidenceKind;
import com.parkingwatch.common.domain.ParkingEventType;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publicación confiable con almacenamiento y reenvío (store-and-forward, RF-15.2). Cada evento se
 * escribe primero en el buzón local y luego se envía; si el backend no está disponible, los eventos
 * se conservan y se reenvían al reconectarse, sin perder reportes. El backend es idempotente por
 * {@code eventId}, así que un reenvío nunca duplica reportes.
 */
public final class ReliableEventPublisher implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ReliableEventPublisher.class);
  private static final int BATCH_SIZE = 20;

  private final OutboxStore outbox;
  private final BackendGateway gateway;
  private final RetryPolicy retry;
  private int failures;
  private long retryAtNanos;

  public ReliableEventPublisher(OutboxStore outbox, BackendGateway gateway, RetryPolicy retry) {
    this.outbox = outbox;
    this.gateway = gateway;
    this.retry = retry;
  }

  /** Encola un evento (y su evidencia, si la hay) de forma durable. */
  public void publish(ParkingEventMessage message, EvidenceBundle evidence) throws IOException {
    outbox.save(message, evidence);
  }

  /**
   * Envía los eventos pendientes. Retorna la cantidad confirmada por el backend; detiene el envío
   * ante un error reintentable para respetar el orden.
   */
  public synchronized int flush() throws IOException {
    if (failures > 0 && System.nanoTime() < retryAtNanos) {
      return 0;
    }
    int delivered = 0;
    List<ParkingEventMessage> pending = outbox.pending(BATCH_SIZE);
    try {
      List<ParkingEventMessage> ready = new ArrayList<>();
      for (ParkingEventMessage message : pending) {
        ready.add(withUploadedEvidence(message));
      }
      if (!ready.isEmpty()) {
        delivered = settle(gateway.sendEvents(ready));
      }
      failures = 0;
    } catch (BackendException e) {
      handleFailure(e, pending);
    }
    return delivered;
  }

  /** Cierra el búfer local. */
  @Override
  public synchronized void close() throws IOException {
    outbox.close();
  }

  public int backlog() throws IOException {
    return outbox.size();
  }

  /** Sube las fotos de un evento de tolerancia superada y guarda sus rutas en el búfer. */
  private ParkingEventMessage withUploadedEvidence(ParkingEventMessage message)
      throws IOException, BackendException {
    if (message.eventType() != ParkingEventType.TOLERANCE_EXCEEDED || message.evidence() != null) {
      return message;
    }
    EvidenceBundle photos =
        new EvidenceBundle(
            photo(message, EvidenceKind.ENTRY),
            photo(message, EvidenceKind.REPORT),
            photo(message, EvidenceKind.PLATE));
    ParkingEventMessage uploaded = withEvidence(message, gateway.uploadEvidence(photos));
    outbox.update(uploaded);
    return uploaded;
  }

  private byte[] photo(ParkingEventMessage message, EvidenceKind kind) throws IOException {
    return outbox
        .evidence(message.eventId(), kind)
        .orElseThrow(
            () -> new IOException("Falta la foto " + kind + " del evento " + message.eventId()));
  }

  private int settle(EventBatchResult result) throws IOException {
    int delivered = 0;
    for (EventResult event : result.results()) {
      if (event.outcome() == EventResult.Outcome.REJECTED) {
        LOG.warn("El backend rechazó el evento {}: {}", event.eventId(), event.reason());
        outbox.remove(event.eventId());
      } else {
        outbox.remove(event.eventId());
        delivered++;
      }
    }
    return delivered;
  }

  private void handleFailure(BackendException error, List<ParkingEventMessage> pending)
      throws IOException {
    if (error.isRetryable()) {
      failures++;
      retryAtNanos = System.nanoTime() + retry.delayFor(failures).toNanos();
      LOG.warn("Backend no disponible; {} eventos quedan en el búfer local", pending.size());
      return;
    }
    LOG.error(
        "El backend rechazó el lote ({}); se apartan {} eventos",
        error.getMessage(),
        pending.size());
    for (ParkingEventMessage message : pending) {
      outbox.quarantine(message.eventId());
    }
  }

  private static ParkingEventMessage withEvidence(ParkingEventMessage m, EvidenceKeys evidence) {
    return new ParkingEventMessage(
        m.eventId(),
        m.eventType(),
        m.zoneId(),
        m.trackId(),
        m.vehicleType(),
        m.detectionConfidence(),
        m.modelVersion(),
        m.enteredAt(),
        m.occurredAt(),
        m.dwellSeconds(),
        m.plate(),
        evidence);
  }
}
