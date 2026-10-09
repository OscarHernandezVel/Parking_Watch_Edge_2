package com.parkingwatch.edge.transport;

/**
 * Error de comunicación con el backend. Si es reintentable (red caída, 5xx, 429) el agente guarda
 * los eventos en el buzón local y los reenvía después (RNF-5.2); si no, el backend rechazó el
 * mensaje y reintentarlo no sirve.
 */
public class BackendException extends Exception {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final boolean retryable;

  public BackendException(String message, int status, boolean retryable, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.retryable = retryable;
  }

  /** Error de red sin respuesta HTTP. */
  public static BackendException unreachable(Throwable cause) {
    return new BackendException("Backend no disponible: " + cause.getMessage(), 0, true, cause);
  }

  /** Error por código de estado HTTP. */
  public static BackendException fromStatus(int status, String body) {
    boolean retryable = status >= 500 || status == 429 || status == 408;
    return new BackendException("HTTP " + status + ": " + body, status, retryable, null);
  }

  public int status() {
    return status;
  }

  public boolean isRetryable() {
    return retryable;
  }
}
