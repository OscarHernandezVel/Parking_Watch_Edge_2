package com.parkingwatch.edge.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.detection.HotSwappableDetector;
import com.parkingwatch.edge.support.InMemoryBackendGateway;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Instalación de modelos (RF-15.3): descarga autenticada, verificación SHA-256 e intercambio en
 * caliente.
 */
class ModelManagerTest {

  private static final byte[] MODEL = "onnx-model-bytes".getBytes(StandardCharsets.UTF_8);

  private static final String PATH = "/api/v1/edge/cameras/CAM-MAQ-01/models/det-2/detector";

  @TempDir Path models;
  private final List<String> downloads = new CopyOnWriteArrayList<>();
  private HotSwappableDetector detector;

  @BeforeEach
  void setUp() {
    Detector current = mock(Detector.class);
    when(current.modelVersion()).thenReturn("det-1");
    detector = new HotSwappableDetector(current);
  }

  /** Doble del backend: registra la ruta pedida y entrega el modelo. */
  private void download(String path, Path target) throws IOException {
    downloads.add(path);
    Files.write(target, MODEL);
  }

  private EdgeConfiguration configuration(String version, String sha256) {
    String url = PATH;
    EdgeConfiguration base = InMemoryBackendGateway.maquetaConfiguration();
    return new EdgeConfiguration(
        base.cameraId(),
        2,
        base.frame(),
        List.of(),
        new EdgeConfiguration.ActiveModel(
            version, new EdgeConfiguration.ModelArtifact(url, sha256), null),
        base.settings());
  }

  @Test
  void downloadsVerifiesAndSwapsTheNewModel() throws Exception {
    Detector loaded = mock(Detector.class);
    when(loaded.modelVersion()).thenReturn("det-2");
    ModelManager manager =
        new ModelManager(
            detector,
            models,
            this::download,
            (file, version) -> {
              assertThat(Files.readAllBytes(file)).isEqualTo(MODEL);
              return loaded;
            });
    manager.accept(configuration("det-2", sha256(MODEL)));
    assertThat(detector.modelVersion()).isEqualTo("det-2");
    assertThat(models.resolve("det-2").resolve("detector.onnx")).exists();
    assertThat(downloads).containsExactly(PATH);
    manager.accept(configuration("det-2", sha256(MODEL)));
    assertThat(detector.modelVersion()).isEqualTo("det-2");
  }

  @Test
  void keepsTheCurrentModelWhenTheChecksumDoesNotMatch() {
    ModelManager manager =
        new ModelManager(
            detector,
            models,
            this::download,
            (file, version) -> {
              throw new AssertionError("no debe cargarse");
            });
    manager.accept(configuration("det-2", "0".repeat(64)));
    assertThat(detector.modelVersion()).isEqualTo("det-1");
    assertThat(models.resolve("det-2").resolve("detector.onnx")).doesNotExist();
  }

  @Test
  void ignoresVersionsWithoutPublishedArtifacts() {
    ModelManager manager =
        new ModelManager(detector, models, this::download, (file, version) -> null);
    manager.accept(InMemoryBackendGateway.maquetaConfiguration());
    assertThat(detector.modelVersion()).isEqualTo("det-1");
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }
}
