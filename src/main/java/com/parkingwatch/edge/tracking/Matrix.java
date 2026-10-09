package com.parkingwatch.edge.tracking;

/** Operaciones mínimas de álgebra lineal densa para el filtro de Kalman (matrices pequeñas). */
final class Matrix {

  private Matrix() {}

  static double[][] identity(int size) {
    double[][] result = new double[size][size];
    for (int i = 0; i < size; i++) {
      result[i][i] = 1;
    }
    return result;
  }

  static double[][] diagonal(double[] values) {
    double[][] result = new double[values.length][values.length];
    for (int i = 0; i < values.length; i++) {
      result[i][i] = values[i];
    }
    return result;
  }

  static double[][] multiply(double[][] a, double[][] b) {
    int rows = a.length;
    int inner = b.length;
    int cols = b[0].length;
    double[][] result = new double[rows][cols];
    for (int i = 0; i < rows; i++) {
      for (int k = 0; k < inner; k++) {
        double value = a[i][k];
        for (int j = 0; j < cols; j++) {
          result[i][j] += value * b[k][j];
        }
      }
    }
    return result;
  }

  static double[] multiply(double[][] a, double[] vector) {
    double[] result = new double[a.length];
    for (int i = 0; i < a.length; i++) {
      for (int j = 0; j < vector.length; j++) {
        result[i] += a[i][j] * vector[j];
      }
    }
    return result;
  }

  static double[][] transpose(double[][] a) {
    double[][] result = new double[a[0].length][a.length];
    for (int i = 0; i < a.length; i++) {
      for (int j = 0; j < a[0].length; j++) {
        result[j][i] = a[i][j];
      }
    }
    return result;
  }

  static double[][] add(double[][] a, double[][] b) {
    return combine(a, b, 1);
  }

  static double[][] subtract(double[][] a, double[][] b) {
    return combine(a, b, -1);
  }

  private static double[][] combine(double[][] a, double[][] b, double sign) {
    double[][] result = new double[a.length][a[0].length];
    for (int i = 0; i < a.length; i++) {
      for (int j = 0; j < a[0].length; j++) {
        result[i][j] = a[i][j] + sign * b[i][j];
      }
    }
    return result;
  }

  /** Inversa por eliminación de Gauss-Jordan con pivoteo parcial. */
  static double[][] inverse(double[][] a) {
    int n = a.length;
    double[][] work = new double[n][2 * n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(a[i], 0, work[i], 0, n);
      work[i][n + i] = 1;
    }
    for (int col = 0; col < n; col++) {
      swapRows(work, col, pivotRow(work, col));
      normalizeRow(work[col], work[col][col]);
      eliminate(work, col);
    }
    double[][] result = new double[n][n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(work[i], n, result[i], 0, n);
    }
    return result;
  }

  private static int pivotRow(double[][] work, int col) {
    int pivot = col;
    for (int row = col + 1; row < work.length; row++) {
      if (Math.abs(work[row][col]) > Math.abs(work[pivot][col])) {
        pivot = row;
      }
    }
    if (Math.abs(work[pivot][col]) < 1e-12) {
      throw new ArithmeticException("Matriz singular");
    }
    return pivot;
  }

  private static void swapRows(double[][] work, int a, int b) {
    double[] temp = work[a];
    work[a] = work[b];
    work[b] = temp;
  }

  private static void normalizeRow(double[] row, double pivot) {
    for (int j = 0; j < row.length; j++) {
      row[j] /= pivot;
    }
  }

  private static void eliminate(double[][] work, int col) {
    for (int row = 0; row < work.length; row++) {
      double factor = work[row][col];
      if (row == col || factor == 0) {
        continue;
      }
      for (int j = 0; j < work[row].length; j++) {
        work[row][j] -= factor * work[col][j];
      }
    }
  }
}
