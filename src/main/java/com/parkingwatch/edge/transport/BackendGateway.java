package com.parkingwatch.edge.transport;

import com.parkingwatch.common.contract.AnalyticsSnapshot;
import com.parkingwatch.common.contract.DetectionSnapshot;
import com.parkingwatch.common.contract.DriftAlertMessage;
import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.common.contract.EventBatchResult;
import com.parkingwatch.common.contract.EvidenceKeys;
import com.parkingwatch.common.contract.HeartbeatMessage;
import com.parkingwatch.common.contract.MinuteStatisticsMessage;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de comunicación con el Backend en Render (patrón Gateway). La implementación real usa WSS
 * (STOMP) para el tiempo real y HTTPS para la configuración, las fotos y el búfer sin conexión; las
 * pruebas usan un doble en memoria.
 */
public interface BackendGateway {

  /** Configuración con su ETag; vacío si no cambió desde {@code etag} (HTTP 304). */
  Optional<VersionedConfiguration> fetchConfiguration(String etag) throws BackendException;

  /** Envía eventos de cambio y retorna el resultado de cada uno (idempotente por eventId). */
  EventBatchResult sendEvents(List<ParkingEventMessage> events) throws BackendException;

  /** Sube las tres fotos de un reporte por HTTPS y retorna sus rutas en el backend. */
  EvidenceKeys uploadEvidence(EvidenceBundle evidence) throws BackendException;

  void sendHeartbeat(HeartbeatMessage message) throws BackendException;

  void sendStatistics(MinuteStatisticsMessage message) throws BackendException;

  void sendDetections(DetectionSnapshot snapshot) throws BackendException;

  void sendAnalytics(AnalyticsSnapshot snapshot) throws BackendException;

  void sendDriftAlert(DriftAlertMessage message) throws BackendException;

  /** Fotograma JPEG reducido para la vista en vivo; se descarta si no hay conexión. */
  void sendVideoFrame(byte[] jpeg) throws BackendException;

  /** Configuración y su versión. */
  record VersionedConfiguration(String etag, EdgeConfiguration configuration) {}
}
