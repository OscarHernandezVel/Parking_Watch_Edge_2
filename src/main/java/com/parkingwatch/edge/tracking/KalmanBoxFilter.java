package com.parkingwatch.edge.tracking;

import com.parkingwatch.edge.domain.BoundingBox;

/**
 * Filtro de Kalman de velocidad constante sobre el espacio (cx, cy, a, h), igual al de SORT y
 * ByteTrack: centro, relación de aspecto, alto y sus velocidades (estado de 8 dimensiones).
 */
public final class KalmanBoxFilter {

  private static final int DIM = 4;
  private static final double WEIGHT_POSITION = 1.0 / 20;
  private static final double WEIGHT_VELOCITY = 1.0 / 160;

  private final double[][] motion;
  private final double[][] update;

  public KalmanBoxFilter() {
    motion = Matrix.identity(2 * DIM);
    for (int i = 0; i < DIM; i++) {
      motion[i][DIM + i] = 1;
    }
    update = new double[DIM][2 * DIM];
    for (int i = 0; i < DIM; i++) {
      update[i][i] = 1;
    }
  }

  /** Estado del filtro: media y covarianza. */
  public record State(double[] mean, double[][] covariance) {

    /** Recuadro estimado a partir de la media (cx, cy, a, h). */
    public BoundingBox box() {
      double height = Math.max(1e-6, mean[3]);
      double width = Math.max(1e-6, mean[2] * height);
      return BoundingBox.fromCenter(mean[0], mean[1], width, height);
    }
  }

  /** Crea el estado de una pista nueva a partir de su primera detección. */
  public State initiate(BoundingBox box) {
    double[] measurement = toMeasurement(box);
    double[] mean = new double[2 * DIM];
    System.arraycopy(measurement, 0, mean, 0, DIM);
    double h = measurement[3];
    double[] std = {
      2 * WEIGHT_POSITION * h, 2 * WEIGHT_POSITION * h, 1e-2, 2 * WEIGHT_POSITION * h,
      10 * WEIGHT_VELOCITY * h, 10 * WEIGHT_VELOCITY * h, 1e-5, 10 * WEIGHT_VELOCITY * h
    };
    return new State(mean, Matrix.diagonal(squared(std)));
  }

  /** Paso de predicción: avanza el estado un fotograma. */
  public State predict(State state) {
    double h = state.mean()[3];
    double[] std = {
      WEIGHT_POSITION * h, WEIGHT_POSITION * h, 1e-2, WEIGHT_POSITION * h,
      WEIGHT_VELOCITY * h, WEIGHT_VELOCITY * h, 1e-5, WEIGHT_VELOCITY * h
    };
    double[] mean = Matrix.multiply(motion, state.mean());
    double[][] covariance =
        Matrix.add(
            Matrix.multiply(Matrix.multiply(motion, state.covariance()), Matrix.transpose(motion)),
            Matrix.diagonal(squared(std)));
    return new State(mean, covariance);
  }

  /** Paso de corrección con una nueva detección. */
  public State correct(State state, BoundingBox box) {
    double h = state.mean()[3];
    double[] std = {WEIGHT_POSITION * h, WEIGHT_POSITION * h, 1e-1, WEIGHT_POSITION * h};
    double[] projectedMean = Matrix.multiply(update, state.mean());
    double[][] projectedCov =
        Matrix.add(
            Matrix.multiply(Matrix.multiply(update, state.covariance()), Matrix.transpose(update)),
            Matrix.diagonal(squared(std)));
    double[][] gain =
        Matrix.multiply(
            Matrix.multiply(state.covariance(), Matrix.transpose(update)),
            Matrix.inverse(projectedCov));
    double[] measurement = toMeasurement(box);
    double[] innovation = new double[DIM];
    for (int i = 0; i < DIM; i++) {
      innovation[i] = measurement[i] - projectedMean[i];
    }
    double[] correction = Matrix.multiply(gain, innovation);
    double[] mean = state.mean().clone();
    for (int i = 0; i < mean.length; i++) {
      mean[i] += correction[i];
    }
    double[][] covariance =
        Matrix.subtract(
            state.covariance(),
            Matrix.multiply(Matrix.multiply(gain, projectedCov), Matrix.transpose(gain)));
    return new State(mean, covariance);
  }

  private static double[] toMeasurement(BoundingBox box) {
    double height = Math.max(1e-6, box.height());
    return new double[] {box.center().x(), box.center().y(), box.width() / height, height};
  }

  private static double[] squared(double[] values) {
    double[] result = new double[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = values[i] * values[i];
    }
    return result;
  }
}
