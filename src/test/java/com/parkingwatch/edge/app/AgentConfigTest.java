package com.parkingwatch.edge.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

  @Test
  void readsDefaultsAndOverridesFromTheEnvironment() {
    AgentConfig config =
        AgentConfig.fromEnvironment(
            Map.of(
                "BACKEND_URL", "https://api.test",
                "DEVICE_TOKEN", "pwd_secret",
                "CLASS_MAPPING", "COCO",
                "DETECTOR_MODEL", "/opt/models/yolo.onnx",
                "EXIT_FRAMES", "7"));
    assertThat(config.cameraId()).isEqualTo("CAM-MAQ-01");
    assertThat(config.mode()).isEqualTo(AgentConfig.Mode.CAMERA);
    assertThat(config.prototypeClasses()).isFalse();
    assertThat(config.detectorModel()).contains(Path.of("/opt/models/yolo.onnx"));
    assertThat(config.plateModel()).isEmpty();
    assertThat(config.tuning().exitFrames()).isEqualTo(7);
    assertThat(config.toString()).doesNotContain("pwd_secret");
  }

  @Test
  void requiresTheBackendUrlAndTheDeviceToken() {
    assertThatThrownBy(() -> AgentConfig.fromEnvironment(Map.of("BACKEND_URL", "x")))
        .hasMessageContaining("DEVICE_TOKEN");
  }
}
