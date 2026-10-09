package com.parkingwatch.edge.transport;

import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.common.domain.EvidenceKind;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Buzón local persistente de eventos pendientes de envío (RNF-5.2). */
public interface OutboxStore extends AutoCloseable {

  /** Guarda el evento y, si las hay, sus fotos de evidencia aún no subidas. */
  void save(ParkingEventMessage message, EvidenceBundle evidence) throws IOException;

  /** Actualiza el evento (por ejemplo, cuando ya tiene las llaves de las fotos subidas). */
  void update(ParkingEventMessage message) throws IOException;

  /** Eventos pendientes en orden cronológico. */
  List<ParkingEventMessage> pending(int limit) throws IOException;

  /** Foto pendiente de un evento. */
  Optional<byte[]> evidence(UUID eventId, EvidenceKind kind) throws IOException;

  /** Elimina el evento y sus fotos tras ser aceptado por el backend. */
  void remove(UUID eventId) throws IOException;

  /** Aparta un evento que el backend rechazó para revisión manual. */
  void quarantine(UUID eventId) throws IOException;

  int size() throws IOException;

  /** Cierra el almacenamiento (al apagar el Edge). */
  @Override
  void close() throws IOException;
}
