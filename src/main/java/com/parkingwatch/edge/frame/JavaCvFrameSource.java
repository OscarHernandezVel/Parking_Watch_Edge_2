package com.parkingwatch.edge.frame;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Lee un video grabado de la maqueta o un flujo de red con JavaCV (pruebas con videos grabados,
 * RNF-10.1). Se usa el reloj del sistema como marca de captura.
 */
public final class JavaCvFrameSource implements FrameSource {

  private final String url;
  private final Clock clock;
  private final Java2DFrameConverter converter = new Java2DFrameConverter();
  private FFmpegFrameGrabber grabber;
  private long sequence;

  public JavaCvFrameSource(String url, Clock clock) {
    this.url = url;
    this.clock = clock;
  }

  @Override
  public void open() throws IOException {
    close();
    FFmpegFrameGrabber candidate = new FFmpegFrameGrabber(url);
    candidate.setOption("rtsp_transport", "tcp");
    candidate.setOption("fflags", "nobuffer");
    candidate.setOption("stimeout", "5000000");
    try {
      candidate.start();
    } catch (FrameGrabber.Exception e) {
      throw new IOException("No se pudo abrir el video " + url, e);
    }
    grabber = candidate;
  }

  @Override
  public Optional<Frame> next() throws IOException {
    try {
      org.bytedeco.javacv.Frame grabbed = grabber.grabImage();
      if (grabbed == null) {
        return Optional.empty();
      }
      return Optional.of(Frame.fromImage(converter.convert(grabbed), clock.instant(), sequence++));
    } catch (FrameGrabber.Exception e) {
      throw new IOException("Error leyendo el video", e);
    }
  }

  @Override
  public void close() {
    if (grabber == null) {
      return;
    }
    try {
      grabber.close();
    } catch (FrameGrabber.Exception e) {
      // El cierre es de mejor esfuerzo: la reconexión crea un grabber nuevo.
    } finally {
      grabber = null;
    }
  }
}
