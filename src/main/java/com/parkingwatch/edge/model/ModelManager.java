package com.parkingwatch.edge.model;

import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.detection.HotSwappableDetector;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instala la versión del modelo que activa el administrador (RF-15.3, HU-5): descarga el ONNX desde
 * el backend, verifica su SHA-256 y lo intercambia en caliente sin detener el pipeline. Si algo
 * falla se conserva el modelo actual.
 */
public final class ModelManager implements Consumer<EdgeConfiguration> {

  private static final Logger LOG = LoggerFactory.getLogger(ModelManager.class);

  /** Descarga un archivo del backend (ruta relativa autenticada con el token del dispositivo). */
  @FunctionalInterface
  public interface Downloader {
    void download(String path, Path target) throws Exception;
  }

  /** Crea un detector a partir de un archivo ONNX verificado. */
  @FunctionalInterface
  public interface DetectorLoader {
    Detector load(Path model, String version) throws Exception;
  }

  private final HotSwappableDetector detector;
  private final Path modelsDirectory;
  private final Downloader downloader;
  private final DetectorLoader loader;

  public ModelManager(
      HotSwappableDetector detector,
      Path modelsDirectory,
      Downloader downloader,
      DetectorLoader loader) {
    this.detector = detector;
    this.modelsDirectory = modelsDirectory;
    this.downloader = downloader;
    this.loader = loader;
  }

  @Override
  public void accept(EdgeConfiguration configuration) {
    EdgeConfiguration.ActiveModel model = configuration.model();
    if (model == null || model.version().equals(detector.modelVersion())) {
      return;
    }
    if (model.detector() == null) {
      LOG.info(
          "La versión {} no tiene detector publicado; se conserva {}",
          model.version(),
          detector.modelVersion());
      return;
    }
    try {
      Path file = ensureDownloaded(model.version(), model.detector());
      detector.swap(loader.load(file, model.version()));
      LOG.info("Modelo {} instalado", model.version());
    } catch (Exception e) {
      LOG.error(
          "No se pudo instalar el modelo {}; se conserva {}",
          model.version(),
          detector.modelVersion(),
          e);
    }
  }

  private Path ensureDownloaded(String version, EdgeConfiguration.ModelArtifact artifact)
      throws Exception {
    Path target = modelsDirectory.resolve(version).resolve("detector.onnx");
    if (Files.exists(target) && artifact.sha256().equals(sha256(target))) {
      return target;
    }
    Path directory = target.getParent();
    if (directory == null) {
      throw new IOException("Ruta de modelo sin directorio: " + target);
    }
    Files.createDirectories(directory);
    Path temp = target.resolveSibling("detector.onnx.download");
    downloader.download(artifact.url(), temp);
    String actual = sha256(temp);
    if (!artifact.sha256().equals(actual)) {
      Files.deleteIfExists(temp);
      throw new IOException("SHA-256 del modelo no coincide: " + actual);
    }
    return Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /** SHA-256 de un archivo en hexadecimal. */
  static String sha256(Path file) throws IOException {
    try (InputStream input =
        new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
      input.transferTo(OutputStream.nullOutputStream());
      return HexFormat.of().formatHex(((DigestInputStream) input).getMessageDigest().digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }
}
