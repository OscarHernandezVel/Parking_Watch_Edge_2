package com.parkingwatch.edge.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkingwatch.common.contract.EdgeStomp;
import com.parkingwatch.common.contract.EventBatchResult;
import com.parkingwatch.common.contract.EventResult;
import com.parkingwatch.common.contract.HeartbeatMessage;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.common.domain.ParkingEventType;
import com.parkingwatch.common.domain.VehicleType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Eventos por WSS con confirmación y respaldo por HTTPS (RF-11.4, RF-8.3). */
class CloudBackendGatewayTest {

  private final HttpBackendGateway https = mock(HttpBackendGateway.class);
  private final StompChannel stomp = mock(StompChannel.class);
  private final CloudBackendGateway gateway =
      new CloudBackendGateway(https, stomp, Duration.ofMillis(100));
  private final ParkingEventMessage event =
      new ParkingEventMessage(
          UUID.randomUUID(),
          ParkingEventType.ZONE_ENTERED,
          1,
          7,
          VehicleType.CAR,
          0.9,
          "det-1",
          Instant.EPOCH,
          Instant.EPOCH,
          0,
          null,
          null);
  private final EventBatchResult accepted =
      new EventBatchResult(List.of(EventResult.accepted(event.eventId(), null)));

  @Test
  void eventsGoByWebSocketWhenTheResultArrives() throws Exception {
    when(stomp.isConnected()).thenReturn(true);
    doAnswer(
            invocation -> {
              gateway.complete(accepted);
              return null;
            })
        .when(stomp)
        .sendJson(eq(EdgeStomp.EVENTS), any());
    assertThat(gateway.sendEvents(List.of(event))).isEqualTo(accepted);
    verify(https, never()).sendEvents(anyList());
  }

  @Test
  void eventsFallBackToHttpsWithoutConnectionOrResult() throws Exception {
    when(https.sendEvents(anyList())).thenReturn(accepted);
    assertThat(gateway.sendEvents(List.of(event))).isEqualTo(accepted);
    when(stomp.isConnected()).thenReturn(true);
    assertThat(gateway.sendEvents(List.of(event))).as("sin resultado a tiempo").isEqualTo(accepted);
    verify(https, org.mockito.Mockito.times(2)).sendEvents(anyList());
  }

  @Test
  void telemetryAndVideoTravelByWebSocket() throws Exception {
    HeartbeatMessage heartbeat = new HeartbeatMessage(Instant.EPOCH, 12, "det-1", 5, "1.0.0");
    gateway.sendHeartbeat(heartbeat);
    gateway.sendVideoFrame(new byte[] {1});
    verify(stomp).sendJson(EdgeStomp.HEARTBEAT, heartbeat);
    verify(stomp).sendBinary(EdgeStomp.VIDEO, new byte[] {1});
    assertThat(StompChannel.webSocketUrl("https://api.cupo.co/"))
        .isEqualTo("wss://api.cupo.co/ws/edge");
    assertThat(StompChannel.webSocketUrl("http://backend:8080"))
        .isEqualTo("ws://backend:8080/ws/edge");
  }
}
