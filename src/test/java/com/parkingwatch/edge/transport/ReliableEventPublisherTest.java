package com.parkingwatch.edge.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkingwatch.common.contract.EventBatchResult;
import com.parkingwatch.common.contract.EventResult;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.common.contract.PlateReading;
import com.parkingwatch.common.domain.EvidenceKind;
import com.parkingwatch.common.domain.ParkingEventType;
import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import com.parkingwatch.edge.support.InMemoryBackendGateway;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Almacenamiento y reenvío con búfer SQLite (RF-15.2): sin conexión no se pierden reportes. */
class ReliableEventPublisherTest {

  private static final RetryPolicy NO_WAIT = new RetryPolicy(Duration.ZERO, Duration.ZERO, 1);
  private final List<AutoCloseable> open = new ArrayList<>();

  private void closeLater(AutoCloseable resource) {
    open.add(resource);
  }

  @AfterEach
  void closeBuffers() throws Exception {
    for (AutoCloseable resource : open) {
      resource.close();
    }
  }

  @TempDir Path directory;

  private static ParkingEventMessage event(ParkingEventType type, int second) {
    Instant at = Instant.parse("2026-10-01T15:00:00Z").plusSeconds(second);
    return new ParkingEventMessage(
        UUID.randomUUID(),
        type,
        1,
        7,
        VehicleType.CAR,
        0.9,
        "det-1",
        Instant.parse("2026-10-01T15:00:00Z"),
        at,
        second,
        type == ParkingEventType.TOLERANCE_EXCEEDED
            ? new PlateReading("ABC123", 0.9, com.parkingwatch.common.domain.PlateStatus.READ)
            : null,
        null);
  }

  private static EvidenceBundle bundle() {
    return new EvidenceBundle(new byte[] {1}, new byte[] {2}, new byte[] {3});
  }

  @Test
  void keepsEventsWhileOfflineAndDeliversThemInOrderWithEvidenceWhenBack() throws IOException {
    InMemoryBackendGateway backend = new InMemoryBackendGateway();
    ReliableEventPublisher publisher =
        new ReliableEventPublisher(new SqliteOutboxStore(directory), backend, NO_WAIT);
    closeLater(publisher);
    backend.setAvailable(false);
    publisher.publish(event(ParkingEventType.TOLERANCE_EXCEEDED, 12), bundle());
    publisher.publish(event(ParkingEventType.ZONE_ENTERED, 0), null);
    assertThat(publisher.flush()).isZero();
    assertThat(publisher.backlog()).isEqualTo(2);

    backend.setAvailable(true);
    assertThat(publisher.flush()).isEqualTo(2);
    assertThat(publisher.backlog()).isZero();
    assertThat(backend.events)
        .extracting(ParkingEventMessage::eventType)
        .containsExactly(ParkingEventType.ZONE_ENTERED, ParkingEventType.TOLERANCE_EXCEEDED);
    assertThat(backend.events.get(1).evidence()).isNotNull();
    assertThat(backend.uploads).hasSize(3);
  }

  @Test
  void dropsEventsTheBackendRejectsAndQuarantinesInvalidBatches() throws Exception {
    BackendGateway gateway = mock(BackendGateway.class);
    SqliteOutboxStore store = new SqliteOutboxStore(directory);
    ReliableEventPublisher publisher = new ReliableEventPublisher(store, gateway, NO_WAIT);
    closeLater(publisher);
    ParkingEventMessage first = event(ParkingEventType.ZONE_ENTERED, 0);
    publisher.publish(first, null);
    when(gateway.sendEvents(anyList()))
        .thenReturn(new EventBatchResult(List.of(EventResult.rejected(first.eventId(), "zona"))));
    assertThat(publisher.flush()).isZero();
    assertThat(publisher.backlog()).isZero();

    ParkingEventMessage second = event(ParkingEventType.ZONE_EXITED, 5);
    publisher.publish(second, null);
    when(gateway.sendEvents(anyList())).thenThrow(BackendException.fromStatus(400, "inválido"));
    publisher.flush();
    assertThat(publisher.backlog()).isZero();
    assertThat(store.pending(10)).isEmpty();
  }

  @Test
  void waitsLongerBetweenRetriesWhileTheBackendIsDown() throws Exception {
    InMemoryBackendGateway backend = new InMemoryBackendGateway();
    ReliableEventPublisher publisher =
        new ReliableEventPublisher(
            new SqliteOutboxStore(directory),
            backend,
            new RetryPolicy(Duration.ofHours(1), Duration.ofHours(1), 2));
    closeLater(publisher);
    publisher.publish(event(ParkingEventType.ZONE_ENTERED, 0), null);
    backend.setAvailable(false);
    assertThat(publisher.flush()).isZero();
    backend.setAvailable(true);
    assertThat(publisher.flush()).as("en espera hasta el siguiente intento").isZero();
    assertThat(backend.events).isEmpty();
  }

  @Test
  void sqliteBufferPersistsMessagesAndPhotosInOrder() throws IOException {
    ParkingEventMessage late = event(ParkingEventType.TOLERANCE_EXCEEDED, 11);
    ParkingEventMessage early = event(ParkingEventType.ZONE_ENTERED, 0);
    try (SqliteOutboxStore store = new SqliteOutboxStore(directory)) {
      store.save(late, bundle());
      store.save(early, null);
    }
    try (SqliteOutboxStore reopened = new SqliteOutboxStore(directory)) {
      assertThat(reopened.pending(10)).containsExactly(early, late);
      assertThat(reopened.evidence(late.eventId(), EvidenceKind.PLATE)).hasValue(new byte[] {3});
      reopened.quarantine(early.eventId());
      reopened.remove(late.eventId());
      assertThat(reopened.size()).isZero();
      assertThat(reopened.evidence(late.eventId(), EvidenceKind.PLATE)).isEmpty();
    }
  }

  @Test
  void configurationPollerNotifiesOnlyOnChanges() {
    InMemoryBackendGateway backend = new InMemoryBackendGateway();
    ConfigurationPoller poller = new ConfigurationPoller(backend);
    int[] notifications = {0};
    poller.onChange(configuration -> notifications[0]++);
    assertThat(poller.poll()).isTrue();
    assertThat(poller.poll()).isFalse();
    backend.setAvailable(false);
    assertThat(poller.poll()).isFalse();
    assertThat(notifications[0]).isEqualTo(1);
    assertThat(poller.current()).isPresent();
  }

  @Test
  void retryPolicyGrowsExponentiallyUpToTheCap() {
    RetryPolicy policy = RetryPolicy.defaults();
    assertThat(policy.delayFor(1)).isBetween(Duration.ofMillis(900), Duration.ofMillis(1100));
    assertThat(policy.delayFor(3)).isBetween(Duration.ofMillis(3600), Duration.ofMillis(4400));
    assertThat(policy.delayFor(20)).isLessThanOrEqualTo(Duration.ofSeconds(66));
  }
}
