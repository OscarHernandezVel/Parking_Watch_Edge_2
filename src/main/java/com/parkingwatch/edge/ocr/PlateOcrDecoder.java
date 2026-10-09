package com.parkingwatch.edge.ocr;

import java.util.Optional;

/**
 * Decodifica la salida de los modelos de fast-plate-ocr: una distribución de probabilidad por
 * posición (ranura) sobre el alfabeto; el carácter de relleno indica ranuras vacías.
 */
public final class PlateOcrDecoder {

  /** Alfabeto de los modelos globales de fast-plate-ocr. */
  public static final String DEFAULT_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_";

  private final String alphabet;
  private final char padding;
  private final int slots;

  public PlateOcrDecoder(String alphabet, char padding, int slots) {
    this.alphabet = alphabet;
    this.padding = padding;
    this.slots = slots;
  }

  /** Decodifica probabilidades aplanadas [ranuras x alfabeto]. */
  public Optional<OcrResult> decode(float[] probabilities) {
    if (probabilities.length != slots * alphabet.length()) {
      throw new IllegalArgumentException(
          "Salida de OCR con tamaño inesperado: " + probabilities.length);
    }
    StringBuilder text = new StringBuilder();
    double confidenceSum = 0;
    for (int slot = 0; slot < slots; slot++) {
      int best = argmax(probabilities, slot * alphabet.length(), alphabet.length());
      char character = alphabet.charAt(best);
      if (character != padding) {
        text.append(character);
        confidenceSum += probabilities[slot * alphabet.length() + best];
      }
    }
    if (text.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new OcrResult(text.toString(), confidenceSum / text.length()));
  }

  private static int argmax(float[] values, int offset, int length) {
    int best = 0;
    for (int i = 1; i < length; i++) {
      if (values[offset + i] > values[offset + best]) {
        best = i;
      }
    }
    return best;
  }
}
