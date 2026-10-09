package com.parkingwatch.edge.ocr;

/** Lectura de un fotograma: texto y confianza promedio de los caracteres. */
public record OcrResult(String text, double confidence) {}
