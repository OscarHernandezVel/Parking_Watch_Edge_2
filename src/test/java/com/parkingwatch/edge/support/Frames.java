package com.parkingwatch.edge.support;

import com.parkingwatch.edge.frame.Frame;
import java.time.Instant;
import java.util.Arrays;

/** Fotogramas de prueba. */
public final class Frames {

  private Frames() {}

  /** Fotograma de color uniforme (BGR). */
  public static Frame solid(int width, int height, int blue, int green, int red) {
    byte[] pixels = new byte[width * height * 3];
    for (int i = 0; i < pixels.length; i += 3) {
      pixels[i] = (byte) blue;
      pixels[i + 1] = (byte) green;
      pixels[i + 2] = (byte) red;
    }
    return new Frame(width, height, pixels, Instant.parse("2026-10-01T15:00:00Z"), 0);
  }

  /** Fotograma negro. */
  public static Frame black(int width, int height) {
    byte[] pixels = new byte[width * height * 3];
    Arrays.fill(pixels, (byte) 0);
    return new Frame(width, height, pixels, Instant.parse("2026-10-01T15:00:00Z"), 0);
  }
}
