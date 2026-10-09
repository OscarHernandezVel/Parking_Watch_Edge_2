package com.parkingwatch.edge.evidence;

import com.parkingwatch.edge.frame.Frame;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Codifica fotogramas en JPEG para las fotos de evidencia. */
public final class JpegEncoder {

  private final float quality;

  public JpegEncoder(float quality) {
    this.quality = quality;
  }

  /** Bytes JPEG del fotograma. */
  public byte[] encode(Frame frame) {
    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
      writer.setOutput(stream);
      ImageWriteParam params = writer.getDefaultWriteParam();
      params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      params.setCompressionQuality(quality);
      writer.write(null, new IIOImage(frame.toImage(), null, null), params);
      stream.flush();
      return output.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("No se pudo codificar la evidencia", e);
    } finally {
      writer.dispose();
    }
  }
}
