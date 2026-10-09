package com.parkingwatch.edge.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.common.domain.ParkingEventType;
import com.parkingwatch.common.domain.PlateStatus;
import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.simulation.SyntheticFrameSource;
import com.parkingwatch.edge.support.InMemoryBackendGateway;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prueba de extremo a extremo del agente con el guion de la demostración (sin hardware): video
 * sintético, detector y OCR simulados, ByteTrack, zonas, evidencias y buzón local.
 */
class MaquetaSimulationTest {

  private static final Instant START = Instant.parse("2026-10-01T15:00:00Z");

  @TempDir Path data;

  @Test
  void theDemoScriptProducesOneReportForTheYellowZoneOnly() throws Exception {
    InMemoryBackendGateway backend = new InMemoryBackendGateway();
    AgentConfig config =
        AgentConfig.fromEnvironment(
            Map.of(
                "BACKEND_URL", "http://backend.test",
                "DEVICE_TOKEN", "pwd_test",
                "AGENT_MODE", "SIMULATION",
                "HEALTH_PORT", "0",
                "DATA_DIR", data.toString()));
    SimulatedHardware hardware =
        new SimulatedHardware(START, 10, paced(), 450, config.modelVersion());
    try (EdgeAgent agent =
        EdgeAgent.create(config, hardware, backend, Clock.fixed(START, ZoneOffset.UTC))) {
      agent.start();
      agent.awaitCaptureFinished();
      await().atMost(Duration.ofSeconds(60)).until(() -> agent.pipelineBacklog() == 0);
      Thread.sleep(500);
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                agent.flushOutbox();
                return agent.outboxBacklog() == 0;
              });
    }

    List<ParkingEventMessage> exceeded = ofType(backend, ParkingEventType.TOLERANCE_EXCEEDED);
    assertThat(exceeded)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.zoneId()).isEqualTo(1);
              assertThat(event.vehicleType()).isEqualTo(VehicleType.CAR);
              assertThat(event.plate().value()).isEqualTo("ABC123");
              assertThat(event.plate().status()).isEqualTo(PlateStatus.READ);
              assertThat(event.dwellSeconds()).isBetween(10.0, 11.0);
              assertThat(event.evidence()).isNotNull();
            });
    assertThat(backend.uploads).hasSize(3);
    Optional<ParkingEventMessage> garageExit =
        ofType(backend, ParkingEventType.ZONE_EXITED).stream()
            .filter(e -> e.zoneId() == 2)
            .findFirst();
    assertThat(garageExit).hasValueSatisfying(e -> assertThat(e.dwellSeconds()).isLessThan(10));
    assertThat(
            ofType(backend, ParkingEventType.ZONE_EXITED).stream().anyMatch(e -> e.zoneId() == 1))
        .isTrue();
  }

  /** Emite un fotograma cada 40 ms para que el pipeline procese casi todos sin descartarlos. */
  private static SyntheticFrameSource.Pacer paced() {
    return instant -> Thread.sleep(40);
  }

  private static List<ParkingEventMessage> ofType(
      InMemoryBackendGateway backend, ParkingEventType type) {
    return backend.events.stream().filter(event -> event.eventType() == type).toList();
  }
}
