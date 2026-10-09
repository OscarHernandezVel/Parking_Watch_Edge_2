package com.parkingwatch.edge.app;

import com.parkingwatch.edge.frame.RpicamFrameSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Configuración del Edge leída de variables de entorno (RNF-11.2); en la Raspberry Pi las define el
 * archivo de configuración protegido del servicio systemd (RF-15.1).
 *
 * @param cameraId cámara que atiende el agente
 * @param backendUrl URL base del backend (HTTPS en producción)
 * @param deviceToken token del dispositivo (secreto)
 * @param mode CAMERA (Raspberry Pi) o SIMULATION (maqueta simulada, sin hardware)
 * @param videoUrl "rpicam" para la cámara de la maqueta, o la ruta de un video grabado (pruebas)
 * @param camera parámetros fijos de rpicam-vid (resolución, fps, exposición y balance de blancos)
 * @param modelVersion versión del modelo inicial
 * @param detectorModel ruta del ONNX de YOLO (opcional)
 * @param plateModel ruta del ONNX de OCR (opcional)
 * @param prototypeClasses true si el modelo usa las clases de la maqueta (0..3) y no las de COCO
 * @param dataDirectory buzón local de eventos
 * @param modelsDirectory modelos descargados
 * @param simulationFps fotogramas por segundo de la simulación
 * @param healthPort puerto de /health y /metrics
 * @param tuning parámetros del análisis
 */
public record AgentConfig(
    String cameraId,
    String backendUrl,
    String deviceToken,
    Mode mode,
    String videoUrl,
    RpicamFrameSource.Settings camera,
    String modelVersion,
    Optional<Path> detectorModel,
    Optional<Path> plateModel,
    boolean prototypeClasses,
    Path dataDirectory,
    Path modelsDirectory,
    double simulationFps,
    int healthPort,
    Tuning tuning) {

  /** Valor de VIDEO_URL que selecciona la cámara de la maqueta. */
  public static final String RPICAM = "rpicam";

  /** Modo de operación. */
  public enum Mode {
    CAMERA,
    SIMULATION
  }

  /** Parámetros calibrables del análisis (RF-10.3). */
  public record Tuning(
      double motionThresholdPx,
      Duration stationaryWindow,
      int enterFrames,
      int exitFrames,
      int plateEveryFrames) {

    public static Tuning defaults() {
      return new Tuning(6, Duration.ofSeconds(2), 3, 5, 3);
    }
  }

  /** Lee la configuración de las variables de entorno. */
  public static AgentConfig fromEnvironment(Map<String, String> env) {
    return new AgentConfig(
        env.getOrDefault("CAMERA_ID", "CAM-MAQ-01"),
        required(env, "BACKEND_URL"),
        required(env, "DEVICE_TOKEN"),
        Mode.valueOf(env.getOrDefault("AGENT_MODE", "CAMERA")),
        env.getOrDefault("VIDEO_URL", RPICAM),
        new RpicamFrameSource.Settings(
            Integer.parseInt(env.getOrDefault("CAMERA_WIDTH", "1280")),
            Integer.parseInt(env.getOrDefault("CAMERA_HEIGHT", "720")),
            Integer.parseInt(env.getOrDefault("CAMERA_FPS", "15")),
            Integer.parseInt(env.getOrDefault("CAMERA_SHUTTER_US", "10000")),
            Double.parseDouble(env.getOrDefault("CAMERA_GAIN", "1.0")),
            env.getOrDefault("CAMERA_AWB_GAINS", "1.8,1.5")),
        env.getOrDefault("MODEL_VERSION", "det-1.0-maqueta"),
        optionalPath(env, "DETECTOR_MODEL"),
        optionalPath(env, "PLATE_MODEL"),
        !"COCO".equalsIgnoreCase(env.getOrDefault("CLASS_MAPPING", "PROTOTYPE")),
        Path.of(env.getOrDefault("DATA_DIR", "data")),
        Path.of(env.getOrDefault("MODELS_DIR", "models")),
        Double.parseDouble(env.getOrDefault("SIMULATION_FPS", "10")),
        Integer.parseInt(env.getOrDefault("HEALTH_PORT", "8090")),
        new Tuning(
            Double.parseDouble(env.getOrDefault("MOTION_THRESHOLD_PX", "6")),
            Duration.ofMillis(Long.parseLong(env.getOrDefault("STATIONARY_WINDOW_MS", "2000"))),
            Integer.parseInt(env.getOrDefault("ENTER_FRAMES", "3")),
            Integer.parseInt(env.getOrDefault("EXIT_FRAMES", "5")),
            Integer.parseInt(env.getOrDefault("PLATE_EVERY_FRAMES", "3"))));
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Falta la variable de entorno " + name);
    }
    return value;
  }

  private static Optional<Path> optionalPath(Map<String, String> env, String name) {
    return Optional.ofNullable(env.get(name)).filter(value -> !value.isBlank()).map(Path::of);
  }

  @Override
  public String toString() {
    return "AgentConfig[cameraId="
        + cameraId
        + ", backendUrl="
        + backendUrl
        + ", mode="
        + mode
        + "]";
  }
}
