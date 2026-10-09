package com.parkingwatch.edge.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.ocr.PlateRegionLocator;
import com.parkingwatch.edge.support.Frames;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class EvidenceTest {

  @Test
  void encodesFramesAsJpeg() throws IOException {
    byte[] jpeg = new JpegEncoder(0.8f).encode(Frames.solid(64, 48, 10, 200, 30));
    assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
    assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
    assertThat(ImageIO.read(new ByteArrayInputStream(jpeg)).getWidth()).isEqualTo(64);
  }

  @Test
  void collectsEntryReportAndPlatePhotos() throws IOException {
    EvidenceCollector collector =
        new EvidenceCollector(
            new JpegEncoder(0.8f), PrivacyFilter.none(), PlateRegionLocator.lowerBand());
    Frame entry = Frames.solid(200, 100, 0, 0, 255);
    collector.rememberEntry(5, 1, entry);
    EvidenceBundle bundle =
        collector.collect(5, 1, Frames.black(200, 100), new BoundingBox(20, 20, 120, 80));
    assertThat(ImageIO.read(new ByteArrayInputStream(bundle.entry())).getRGB(10, 10) & 0xFF0000)
        .isGreaterThan(0xA00000);
    assertThat(ImageIO.read(new ByteArrayInputStream(bundle.plate())).getWidth()).isEqualTo(70);
    collector.forget(5, 1);
    EvidenceBundle withoutEntry =
        collector.collect(5, 1, Frames.black(200, 100), new BoundingBox(20, 20, 120, 80));
    assertThat(withoutEntry.entry()).isEqualTo(withoutEntry.report());
  }

  @Test
  void framesCropComputeBrightnessAndConvertToImages() {
    Frame white = Frames.solid(40, 20, 255, 255, 255);
    assertThat(white.meanBrightness()).isEqualTo(255);
    Frame crop = white.crop(new BoundingBox(30, 10, 60, 40));
    assertThat(crop.width()).isEqualTo(10);
    assertThat(crop.height()).isEqualTo(10);
    assertThat(Frame.fromImage(white.toImage(), Instant.EPOCH, 3).channel(1, 1, 2)).isEqualTo(255);
    assertThat(white.pixels().isReadOnly()).isTrue();
  }
}
