package com.parkingwatch.edge.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineTest {

  @Test
  void boundedQueueDropsTheOldestElementsUnderBackpressure() throws InterruptedException {
    BoundedQueue<Integer> queue = new BoundedQueue<>(2);
    for (int i = 1; i <= 5; i++) {
      queue.offer(i);
    }
    assertThat(queue.dropped()).isEqualTo(3);
    assertThat(queue.size()).isEqualTo(2);
    assertThat(queue.take()).isEqualTo(4);
    assertThat(queue.take()).isEqualTo(5);
  }

  @Test
  void aFailingElementDoesNotStopTheStage() {
    BoundedQueue<Integer> output = new BoundedQueue<>(10);
    PipelineStage<Integer, Integer> stage =
        new PipelineStage<>("doubler", new BoundedQueue<>(1), output) {
          @Override
          protected Integer process(Integer item) {
            if (item < 0) {
              throw new IllegalArgumentException("negativo");
            }
            return item * 2;
          }
        };
    stage.handle(1);
    stage.handle(-1);
    stage.handle(3);
    assertThat(stage.processed()).isEqualTo(2);
    assertThat(output.size()).isEqualTo(2);
    assertThat(stage.name()).isEqualTo("doubler");
  }

  @Test
  void stagesRunOnVirtualThreadsAndStopOnInterrupt() throws Exception {
    BoundedQueue<Integer> input = new BoundedQueue<>(10);
    List<Integer> seen = new ArrayList<>();
    PipelineStage<FrameData.Analysis, Void> sink =
        new Stages.Publish(analysis -> {}, new BoundedQueue<>(1));
    PipelineStage<Integer, Integer> stage =
        new PipelineStage<>("collector", input, null) {
          @Override
          protected Integer process(Integer item) {
            synchronized (seen) {
              seen.add(item);
            }
            return null;
          }
        };
    Thread thread = Thread.ofVirtual().start(stage);
    input.offer(1);
    input.offer(2);
    org.awaitility.Awaitility.await()
        .until(
            () -> {
              synchronized (seen) {
                return seen.size() == 2;
              }
            });
    thread.interrupt();
    thread.join(2000);
    assertThat(thread.isAlive()).isFalse();
    assertThat(sink.name()).isEqualTo("publish");
  }
}
