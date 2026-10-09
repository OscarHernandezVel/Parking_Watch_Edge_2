package com.parkingwatch.edge.frame;

import com.parkingwatch.edge.domain.BoundingBox;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Objects;

/**
 * Fotograma inmutable en formato BGR de 8 bits (el mismo de OpenCV). Lleva la marca de tiempo de
 * captura, que es la referencia de todos los cálculos de tiempo del pipeline (RF-9.3).
 */
public final class Frame {

  private static final int CHANNELS = 3;
  private static final int BRIGHTNESS_STEP = 4;

  private final int width;
  private final int height;
  private final byte[] bgr;
  private final Instant capturedAt;
  private final long sequence;

  /** Crea un fotograma copiando los píxeles para garantizar la inmutabilidad. */
  public Frame(int width, int height, byte[] bgr, Instant capturedAt, long sequence) {
    if (width <= 0 || height <= 0 || bgr.length != width * height * CHANNELS) {
      throw new IllegalArgumentException("Dimensiones de fotograma inválidas");
    }
    this.width = width;
    this.height = height;
    this.bgr = bgr.clone();
    this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    this.sequence = sequence;
  }

  /** Convierte una imagen de Java2D (cualquier tipo) a un fotograma BGR. */
  public static Frame fromImage(BufferedImage image, Instant capturedAt, long sequence) {
    BufferedImage bgrImage = image;
    if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
      bgrImage =
          new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
      bgrImage.getGraphics().drawImage(image, 0, 0, null);
    }
    byte[] data = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
    return new Frame(bgrImage.getWidth(), bgrImage.getHeight(), data, capturedAt, sequence);
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public Instant capturedAt() {
    return capturedAt;
  }

  public long sequence() {
    return sequence;
  }

  /** Vista de solo lectura de los píxeles BGR (sin copiar). */
  public ByteBuffer pixels() {
    return ByteBuffer.wrap(bgr).asReadOnlyBuffer();
  }

  /** Valor (0-255) de un canal: 0 = azul, 1 = verde, 2 = rojo. */
  public int channel(int x, int y, int channel) {
    return bgr[(y * width + x) * CHANNELS + channel] & 0xFF;
  }

  /** Recorta una región; el recuadro se ajusta a los límites del fotograma. */
  public Frame crop(BoundingBox box) {
    BoundingBox clipped = box.clip(width, height);
    int x0 = (int) Math.floor(clipped.x1());
    int y0 = (int) Math.floor(clipped.y1());
    int cropWidth = Math.max(1, Math.min(width - x0, (int) Math.ceil(clipped.width())));
    int cropHeight = Math.max(1, Math.min(height - y0, (int) Math.ceil(clipped.height())));
    byte[] data = new byte[cropWidth * cropHeight * CHANNELS];
    for (int row = 0; row < cropHeight; row++) {
      int source = ((y0 + row) * width + x0) * CHANNELS;
      System.arraycopy(bgr, source, data, row * cropWidth * CHANNELS, cropWidth * CHANNELS);
    }
    return new Frame(cropWidth, cropHeight, data, capturedAt, sequence);
  }

  /** Brillo promedio (luma aproximada) muestreando píxeles; se usa para la deriva (RF-14.2). */
  public double meanBrightness() {
    long sum = 0;
    long samples = 0;
    for (int y = 0; y < height; y += BRIGHTNESS_STEP) {
      for (int x = 0; x < width; x += BRIGHTNESS_STEP) {
        int index = (y * width + x) * CHANNELS;
        sum +=
            (114L * (bgr[index] & 0xFF)
                    + 587L * (bgr[index + 1] & 0xFF)
                    + 299L * (bgr[index + 2] & 0xFF))
                / 1000;
        samples++;
      }
    }
    return samples == 0 ? 0 : (double) sum / samples;
  }

  /** Copia el fotograma a una imagen de Java2D (para codificar evidencias en JPEG). */
  public BufferedImage toImage() {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    System.arraycopy(bgr, 0, target, 0, bgr.length);
    return image;
  }
}
