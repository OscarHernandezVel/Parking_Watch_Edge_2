package com.parkingwatch.edge.transport;

import com.parkingwatch.common.contract.ContractJson;
import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.common.contract.EdgeStomp;
import com.parkingwatch.common.contract.EventBatchResult;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Canal en tiempo real con el Backend (RF-8.1, RF-15.1): WebSocket STOMP sobre WSS iniciado por el
 * Edge, autenticado con el token del dispositivo. Si la conexión se cae, un hilo virtual reconecta
 * con espera creciente; al reconectar se suscribe de nuevo y avisa para refrescar la configuración.
 */
public final class StompChannel implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(StompChannel.class);
  private static final long HEARTBEAT_MS = 10_000;
  private static final long CONNECT_TIMEOUT_S = 15;
  private static final long CHECK_INTERVAL_MS = 1000;
  private static final MimeType VIDEO = MimeTypeUtils.parseMimeType(EdgeStomp.VIDEO_CONTENT_TYPE);

  private final String url;
  private final String deviceToken;
  private final RetryPolicy retry;
  private final WebSocketStompClient client;
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
  private volatile StompSession session;
  private volatile boolean closed;
  private Consumer<EdgeConfiguration> onConfiguration = configuration -> {};
  private Consumer<EventBatchResult> onEventResults = result -> {};
  private Runnable onConnected = () -> {};
  private Thread loop;

  /**
   * Crea el canal.
   *
   * @param backendUrl URL base del backend (https://... en producción)
   */
  public StompChannel(String backendUrl, String deviceToken, RetryPolicy retry) {
    this.url = webSocketUrl(backendUrl);
    this.deviceToken = deviceToken;
    this.retry = retry;
    MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
    json.setObjectMapper(ContractJson.newMapper());
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("stomp-heartbeat-");
    scheduler.initialize();
    client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(
        new CompositeMessageConverter(List.of(new ByteArrayMessageConverter(), json)));
    client.setTaskScheduler(scheduler);
    client.setDefaultHeartbeat(new long[] {HEARTBEAT_MS, HEARTBEAT_MS});
    client.setInboundMessageSizeLimit(1024 * 1024);
  }

  /** URL del endpoint del Edge a partir de la URL HTTP(S) del backend. */
  static String webSocketUrl(String backendUrl) {
    String base =
        backendUrl.endsWith("/") ? backendUrl.substring(0, backendUrl.length() - 1) : backendUrl;
    return base.replaceFirst("^http", "ws") + EdgeStomp.ENDPOINT;
  }

  public void onConfiguration(Consumer<EdgeConfiguration> listener) {
    this.onConfiguration = listener;
  }

  public void onEventResults(Consumer<EventBatchResult> listener) {
    this.onEventResults = listener;
  }

  public void onConnected(Runnable listener) {
    this.onConnected = listener;
  }

  /** Inicia el ciclo de conexión y reconexión en un hilo virtual. */
  public synchronized void start() {
    loop = Thread.ofVirtual().name("stomp-connection").start(this::keepConnected);
  }

  public boolean isConnected() {
    StompSession current = session;
    return current != null && current.isConnected();
  }

  /** Envía un objeto como JSON; falla de forma reintentable si no hay conexión. */
  public void sendJson(String destination, Object payload) throws BackendException {
    send(destination, MimeTypeUtils.APPLICATION_JSON, payload);
  }

  /** Envía un cuerpo binario (fotograma JPEG). */
  public void sendBinary(String destination, byte[] payload) throws BackendException {
    send(destination, VIDEO, payload);
  }

  // La API de Spring exige la clase concreta StompHeaders.
  @SuppressWarnings("PMD.LooseCoupling")
  private void send(String destination, MimeType type, Object payload) throws BackendException {
    StompSession current = session;
    if (current == null || !current.isConnected()) {
      throw BackendException.unreachable(new IllegalStateException("WebSocket desconectado"));
    }
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    headers.setContentType(type);
    try {
      current.send(headers, payload);
    } catch (RuntimeException e) {
      throw BackendException.unreachable(e);
    }
  }

  private void keepConnected() {
    int attempt = 0;
    while (!closed) {
      try {
        if (isConnected()) {
          TimeUnit.MILLISECONDS.sleep(CHECK_INTERVAL_MS);
          continue;
        }
        connect();
        attempt = 0;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        attempt++;
        LOG.warn("WebSocket no disponible (intento {}): {}", attempt, e.getMessage());
        sleep(retry.delayFor(attempt).toMillis());
      }
    }
  }

  @SuppressWarnings("PMD.LooseCoupling")
  private void connect() throws Exception {
    StompHeaders connect = new StompHeaders();
    connect.add(EdgeStomp.AUTHORIZATION_HEADER, "Bearer " + deviceToken);
    StompSession connected =
        client
            .connectAsync(
                url, new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() {})
            .get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS);
    connected.subscribe(
        EdgeStomp.CONFIG_QUEUE, handler(EdgeConfiguration.class, this::configuration));
    connected.subscribe(
        EdgeStomp.EVENT_RESULTS_QUEUE, handler(EventBatchResult.class, this::results));
    session = connected;
    LOG.info("WebSocket conectado a {}", url);
    onConnected.run();
  }

  private void configuration(Object payload) {
    onConfiguration.accept((EdgeConfiguration) payload);
  }

  private void results(Object payload) {
    onEventResults.accept((EventBatchResult) payload);
  }

  private static StompFrameHandler handler(Class<?> type, Consumer<Object> consumer) {
    return new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return type;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        consumer.accept(payload);
      }
    };
  }

  private static void sleep(long millis) {
    try {
      TimeUnit.MILLISECONDS.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (loop != null) {
      loop.interrupt();
    }
    StompSession current = session;
    if (current != null && current.isConnected()) {
      current.disconnect();
    }
    scheduler.shutdown();
  }
}
