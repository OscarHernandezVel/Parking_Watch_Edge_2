package com.parkingwatch.edge.frame;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Cámara de la maqueta (RF-10.2): Raspberry Pi Camera Module 3 Wide capturada con rpicam-vid a 1280
 * x 720 px y 15 fps, con exposición y balance de blancos fijos para que la luz sea igual en el
 * entrenamiento y en la demostración. rpicam-vid entrega MJPEG por su salida estándar y JavaCV lo
 * decodifica. Se valida en la Raspberry Pi (excluida de la cobertura).
 */
public final class RpicamFrameSource implements FrameSource {

  private final Settings settings;
  private final Clock clock;
  private final Java2DFrameConverter converter = new Java2DFrameConverter();
  private Process process;
  private FFmpegFrameGrabber grabber;
  private long sequence;

  /**
   * Parámetros de captura.
   *
   * @param width ancho en píxeles (1280)
   * @param height alto en píxeles (720)
   * @param fps cuadros por segundo (15)
   * @param shutterMicros exposición fija en microsegundos
   * @param gain ganancia analógica fija
   * @param awbGains ganancias fijas de rojo y azul del balance de blancos, p. ej. "1.8,1.5"
   */
  public record Settings(
      int width, int height, int fps, int shutterMicros, double gain, String awbGains) {

    /** Comando rpicam-vid sin vista previa, con salida MJPEG por stdout. */
    List<String> command() {
      List<String> command = new ArrayList<>();
      command.addAll(List.of("rpicam-vid", "-t", "0", "-n", "--codec", "mjpeg"));
      command.addAll(List.of("--width", String.valueOf(width), "--height", String.valueOf(height)));
      command.addAll(List.of("--framerate", String.valueOf(fps)));
      command.addAll(
          List.of("--shutter", String.valueOf(shutterMicros), "--gain", String.valueOf(gain)));
      command.addAll(List.of("--awbgains", awbGains, "-o", "-"));
      return command;
    }
  }

  public RpicamFrameSource(Settings settings, Clock clock) {
    this.settings = settings;
    this.clock = clock;
  }

  @Override
  public void open() throws IOException {
    close();
    process =
        new ProcessBuilder(settings.command())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    FFmpegFrameGrabber candidate = new FFmpegFrameGrabber(process.getInputStream());
    candidate.setFormat("mjpeg");
    try {
      candidate.start();
    } catch (FrameGrabber.Exception e) {
      close();
      throw new IOException("No se pudo iniciar rpicam-vid", e);
    }
    grabber = candidate;
  }

  @Override
  public Optional<Frame> next() throws IOException {
    try {
      org.bytedeco.javacv.Frame grabbed = grabber.grabImage();
      if (grabbed == null) {
        throw new IOException("rpicam-vid dejó de entregar video");
      }
      return Optional.of(Frame.fromImage(converter.convert(grabbed), clock.instant(), sequence++));
    } catch (FrameGrabber.Exception e) {
      throw new IOException("Error leyendo la cámara", e);
    }
  }

  @Override
  public void close() {
    if (grabber != null) {
      try {
        grabber.close();
      } catch (FrameGrabber.Exception e) {
        // El cierre es de mejor esfuerzo: la reconexión crea un grabber nuevo.
      }
      grabber = null;
    }
    if (process != null) {
      process.destroy();
      process = null;
    }
  }
}
