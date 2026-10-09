package com.parkingwatch.edge.evidence;

import com.parkingwatch.edge.frame.Frame;

/**
 * Filtro de privacidad aplicado a las fotos antes de que salgan del equipo (RNF-2.2): por ejemplo,
 * difuminar rostros. En la maqueta no hay personas, por eso el filtro por defecto no modifica la
 * imagen; en la vía real se reemplaza por un detector de rostros con pixelado.
 */
@FunctionalInterface
public interface PrivacyFilter {

  Frame apply(Frame frame);

  static PrivacyFilter none() {
    return frame -> frame;
  }
}
