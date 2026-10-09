package com.parkingwatch.edge.evidence;

import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.edge.frame.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Copia reducida del video para la vista en vivo (RF-11.4): redimensiona el fotograma a 960 x 540
 * px y lo codifica en JPEG. El video no se graba: cada fotograma se procesa en memoria y solo sale
 * del equipo cifrado por WSS (RNF-9.2).
 */
public final class LiveVideoEncoder {

  /** Codifica el fotograma con el tamaño y la calidad de la configuración. */
  public byte[] encode(Frame frame, EdgeConfiguration.LiveVideo video) {
    BufferedImage scaled =
        new BufferedImage(video.width(), video.height(), BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D graphics = scaled.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(frame.toImage(), 0, 0, video.width(), video.height(), null);
    } finally {
      graphics.dispose();
    }
    Frame reduced = Frame.fromImage(scaled, frame.capturedAt(), frame.sequence());
    return new JpegEncoder((float) video.jpegQuality()).encode(reduced);
  }
}
