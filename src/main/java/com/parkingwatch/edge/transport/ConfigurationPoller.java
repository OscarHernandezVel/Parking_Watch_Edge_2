package com.parkingwatch.edge.transport;

import com.parkingwatch.common.contract.EdgeConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuración de zonas y modelo (RF-6.2, RF-15.3): la consulta por HTTPS con ETag al iniciar, al
 * reconectarse y como respaldo periódico, y aplica la que el backend empuja por WebSocket. Notifica
 * a los interesados (patrón Observer): registro de zonas, gestor del modelo y parámetros.
 */
public final class ConfigurationPoller {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigurationPoller.class);

  private final BackendGateway gateway;
  private final List<Consumer<EdgeConfiguration>> listeners = new CopyOnWriteArrayList<>();
  private volatile String etag;
  private volatile EdgeConfiguration current;

  public ConfigurationPoller(BackendGateway gateway) {
    this.gateway = gateway;
  }

  /** Registra un observador de cambios de configuración. */
  public void onChange(Consumer<EdgeConfiguration> listener) {
    listeners.add(listener);
  }

  /** Consulta una vez; retorna true si la configuración cambió. */
  public boolean poll() {
    try {
      Optional<BackendGateway.VersionedConfiguration> updated = gateway.fetchConfiguration(etag);
      if (updated.isEmpty()) {
        return false;
      }
      etag = updated.get().etag();
      current = updated.get().configuration();
      LOG.info(
          "Configuración {} recibida: {} zonas", current.configVersion(), current.zones().size());
      listeners.forEach(listener -> listener.accept(current));
      return true;
    } catch (BackendException e) {
      LOG.warn("No se pudo consultar la configuración: {}", e.getMessage());
      return false;
    }
  }

  /** Aplica la configuración que el backend empujó por WebSocket (RF-6.2), sin reiniciar. */
  public void apply(EdgeConfiguration configuration) {
    current = configuration;
    LOG.info("Configuración {} recibida por WebSocket", configuration.configVersion());
    listeners.forEach(listener -> listener.accept(configuration));
  }

  public Optional<EdgeConfiguration> current() {
    return Optional.ofNullable(current);
  }
}
