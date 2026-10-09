package com.parkingwatch.edge;

import com.parkingwatch.edge.app.AgentConfig;
import com.parkingwatch.edge.app.EdgeAgent;
import com.parkingwatch.edge.app.Hardware;
import com.parkingwatch.edge.app.RaspberryHardware;
import com.parkingwatch.edge.app.SimulatedHardware;
import com.parkingwatch.edge.simulation.SyntheticFrameSource;
import com.parkingwatch.edge.transport.CloudBackendGateway;
import com.parkingwatch.edge.transport.HttpBackendGateway;
import com.parkingwatch.edge.transport.RetryPolicy;
import com.parkingwatch.edge.transport.StompChannel;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/** Punto de entrada del Edge: {@code java -jar cupo-edge-all.jar} (servicio systemd). */
public final class EdgeAgentApplication {

  private EdgeAgentApplication() {}

  /** Arranca el Edge con la configuración del entorno y lo detiene con SIGTERM (systemd). */
  public static void main(String[] args) throws Exception {
    Clock clock = Clock.systemUTC();
    AgentConfig config = AgentConfig.fromEnvironment(System.getenv());
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    HttpBackendGateway https =
        new HttpBackendGateway(http, config.backendUrl(), config.cameraId(), config.deviceToken());
    StompChannel stomp =
        new StompChannel(config.backendUrl(), config.deviceToken(), RetryPolicy.defaults());
    Hardware hardware =
        config.mode() == AgentConfig.Mode.SIMULATION
            ? new SimulatedHardware(
                clock.instant(),
                config.simulationFps(),
                SyntheticFrameSource.Pacer.realTime(clock),
                0,
                config.modelVersion())
            : new RaspberryHardware(config, clock, https::download);
    EdgeAgent agent =
        EdgeAgent.create(
            config, hardware, new CloudBackendGateway(https, stomp, Duration.ofSeconds(5)), clock);
    stomp.onConfiguration(agent::applyConfiguration);
    stomp.onConnected(agent::refreshConfiguration);
    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  stomp.close();
                  agent.close();
                  stopped.countDown();
                }));
    agent.start();
    stomp.start();
    stopped.await();
  }
}
