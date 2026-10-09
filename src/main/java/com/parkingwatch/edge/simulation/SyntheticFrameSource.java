package com.parkingwatch.edge.simulation;

import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.frame.FrameSource;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Fuente de video sintética que dibuja la maqueta y los vehículos del guion. Permite ejecutar el
 * pipeline completo sin cámara y genera fotos de evidencia con la placa legible.
 */
public final class SyntheticFrameSource implements FrameSource {

  /** Controla el ritmo de los fotogramas: en tiempo real o lo más rápido posible (pruebas). */
  @FunctionalInterface
  public interface Pacer {
    void waitUntil(Instant instant) throws InterruptedException;

    /** Espera con el reloj del sistema para emitir a la tasa configurada. */
    static Pacer realTime(Clock clock) {
      return instant -> {
        long millis = Duration.between(clock.instant(), instant).toMillis();
        if (millis > 0) {
          Thread.sleep(millis);
        }
      };
    }

    static Pacer none() {
      return instant -> {};
    }
  }

  private final MaquetaScenario scenario;
  private final Instant start;
  private final Duration interval;
  private final int width;
  private final int height;
  private final Pacer pacer;
  private final long maxFrames;
  private long sequence;

  public SyntheticFrameSource(
      MaquetaScenario scenario,
      Instant start,
      double fps,
      int width,
      int height,
      Pacer pacer,
      long maxFrames) {
    this.scenario = scenario;
    this.start = start;
    this.interval = Duration.ofNanos((long) (1_000_000_000L / fps));
    this.width = width;
    this.height = height;
    this.pacer = pacer;
    this.maxFrames = maxFrames;
  }

  @Override
  public void open() {
    // La fuente sintética no requiere conexión.
  }

  @Override
  public Optional<Frame> next() {
    if (maxFrames > 0 && sequence >= maxFrames) {
      return Optional.empty();
    }
    Instant at = start.plus(interval.multipliedBy(sequence));
    try {
      pacer.waitUntil(at);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
    return Optional.of(Frame.fromImage(render(at), at, sequence++));
  }

  private BufferedImage render(Instant at) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D g = image.createGraphics();
    try {
      drawRoad(g);
      for (MaquetaScenario.Appearance appearance : scenario.at(at)) {
        drawVehicle(g, appearance.box(), appearance.vehicle().plate());
      }
    } finally {
      g.dispose();
    }
    return image;
  }

  private void drawRoad(Graphics2D g) {
    g.setColor(new Color(150, 150, 150));
    g.fillRect(0, 0, width, height);
    g.setColor(new Color(70, 70, 75));
    g.fillRect(0, 200, width, 360);
    g.setColor(new Color(230, 200, 30));
    g.setStroke(new BasicStroke(6));
    g.drawLine(820, 560, 1180, 560);
    g.setColor(Color.WHITE);
    for (int x = 0; x < width; x += 80) {
      g.fillRect(x, 395, 40, 6);
    }
  }

  private static void drawVehicle(Graphics2D g, BoundingBox box, String plate) {
    int x = (int) box.x1();
    int y = (int) box.y1();
    int w = (int) box.width();
    int h = (int) box.height();
    g.setColor(new Color(180, 30, 40));
    g.fillRoundRect(x, y, w, h, 18, 18);
    int plateWidth = (int) (w * 0.6);
    int plateHeight = (int) (h * 0.3);
    int plateX = x + (w - plateWidth) / 2;
    int plateY = y + h - plateHeight - 4;
    g.setColor(new Color(250, 210, 0));
    g.fillRect(plateX, plateY, plateWidth, plateHeight);
    g.setColor(Color.BLACK);
    g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(10, plateHeight - 6)));
    g.drawString(plate, plateX + 4, plateY + plateHeight - 5);
  }

  @Override
  public void close() {
    // Nada que liberar.
  }
}
