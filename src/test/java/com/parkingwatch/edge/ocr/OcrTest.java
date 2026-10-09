package com.parkingwatch.edge.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkingwatch.common.contract.PlateReading;
import com.parkingwatch.common.domain.PlateStatus;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.support.Frames;
import org.junit.jupiter.api.Test;

class OcrTest {

  private static final String ALPHABET = "ABC123_";

  @Test
  void decoderTakesTheMostLikelyCharacterPerSlotAndSkipsPadding() {
    PlateOcrDecoder decoder = new PlateOcrDecoder(ALPHABET, '_', 3);
    float[] probabilities = {
      0.9f, 0, 0, 0.1f, 0, 0, 0, 0, 0, 0, 0, 0, 0.8f, 0.2f, 0, 0, 0, 0, 0, 0, 1f
    };
    assertThat(decoder.decode(probabilities))
        .hasValueSatisfying(
            r -> {
              assertThat(r.text()).isEqualTo("A3");
              assertThat(r.confidence())
                  .isEqualTo(0.85, org.assertj.core.api.Assertions.within(1e-6));
            });
    assertThat(
            decoder.decode(
                new float[] {0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1}))
        .isEmpty();
    assertThatThrownBy(() -> decoder.decode(new float[2]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void voterChoosesTheMostFrequentValidPlate() {
    PlateVoter voter = new PlateVoter(0.8, 2);
    voter.add(1, new OcrResult("ABC123", 0.9));
    voter.add(1, new OcrResult("abc-123", 0.85));
    voter.add(1, new OcrResult("ABC128", 0.95));
    voter.add(1, new OcrResult("??", 0.99));
    PlateReading reading = voter.vote(1);
    assertThat(reading.value()).isEqualTo("ABC123");
    assertThat(reading.status()).isEqualTo(PlateStatus.READ);
    assertThat(reading.confidence()).isEqualTo(0.88);
  }

  @Test
  void voterMarksWeakReadingsAsPendingAndForgetsVehicles() {
    PlateVoter voter = new PlateVoter(0.8, 2);
    voter.add(2, new OcrResult("XYZ12A", 0.95));
    assertThat(voter.vote(2).status()).isEqualTo(PlateStatus.PENDING_CONFIRMATION);
    voter.forget(2);
    assertThat(voter.vote(2)).isEqualTo(PlateReading.notRead());
  }

  @Test
  void lowerBandLocatorAndNullReaderBehaveAsDocumented() {
    BoundingBox plate = PlateRegionLocator.lowerBand().locate(new BoundingBox(0, 0, 100, 100));
    assertThat(plate).isEqualTo(new BoundingBox(15, 55, 85, 100));
    try (PlateReader reader = new NoOpPlateReader()) {
      assertThat(reader.read(Frames.black(10, 10), plate)).isEmpty();
    }
  }
}
