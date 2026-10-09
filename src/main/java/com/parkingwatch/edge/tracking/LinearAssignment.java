package com.parkingwatch.edge.tracking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Asignación lineal óptima entre pistas y detecciones con el algoritmo húngaro (versión con
 * potenciales, O(n²m)); se descartan los pares cuyo costo supera el umbral.
 */
public final class LinearAssignment {

  private LinearAssignment() {}

  /** Par asignado: índice de fila (pista) y de columna (detección). */
  public record Match(int row, int column) {}

  /** Resuelve la asignación de mínimo costo y filtra por umbral. */
  public static List<Match> solve(double[][] cost, double threshold) {
    if (cost.length == 0 || cost[0].length == 0) {
      return List.of();
    }
    boolean transposed = cost.length > cost[0].length;
    double[][] matrix = transposed ? transpose(cost) : cost;
    int[] assignment = new Solver(matrix).solve();
    List<Match> matches = new ArrayList<>();
    for (int row = 0; row < assignment.length; row++) {
      int column = assignment[row];
      if (column >= 0 && matrix[row][column] <= threshold) {
        matches.add(transposed ? new Match(column, row) : new Match(row, column));
      }
    }
    return matches;
  }

  private static double[][] transpose(double[][] cost) {
    double[][] result = new double[cost[0].length][cost.length];
    for (int i = 0; i < cost.length; i++) {
      for (int j = 0; j < cost[0].length; j++) {
        result[j][i] = cost[i][j];
      }
    }
    return result;
  }

  /** Estado del algoritmo húngaro para una matriz con filas <= columnas (índices desde 1). */
  private static final class Solver {
    private final double[][] cost;
    private final int columns;
    private final double[] rowPotential;
    private final double[] columnPotential;
    private final int[] owner;
    private final int[] way;
    private double[] minimum;
    private boolean[] used;

    Solver(double[][] cost) {
      this.cost = cost;
      this.columns = cost[0].length;
      this.rowPotential = new double[cost.length + 1];
      this.columnPotential = new double[columns + 1];
      this.owner = new int[columns + 1];
      this.way = new int[columns + 1];
    }

    /** Columna asignada a cada fila (o -1). */
    int[] solve() {
      for (int row = 1; row <= cost.length; row++) {
        augment(row);
      }
      int[] result = new int[cost.length];
      Arrays.fill(result, -1);
      for (int column = 1; column <= columns; column++) {
        if (owner[column] != 0) {
          result[owner[column] - 1] = column - 1;
        }
      }
      return result;
    }

    /** Busca un camino de aumento para la fila y actualiza la asignación. */
    private void augment(int row) {
      owner[0] = row;
      minimum = new double[columns + 1];
      Arrays.fill(minimum, Double.POSITIVE_INFINITY);
      used = new boolean[columns + 1];
      int column = 0;
      do {
        used[column] = true;
        int next = relax(owner[column], column);
        shiftPotentials(minimum[next]);
        column = next;
      } while (owner[column] != 0);
      unwind(column);
    }

    /** Actualiza los costos reducidos desde la fila y retorna la columna libre más barata. */
    private int relax(int row, int fromColumn) {
      int best = 0;
      double bestValue = Double.POSITIVE_INFINITY;
      for (int column = 1; column <= columns; column++) {
        if (used[column]) {
          continue;
        }
        double reduced = cost[row - 1][column - 1] - rowPotential[row] - columnPotential[column];
        if (reduced < minimum[column]) {
          minimum[column] = reduced;
          way[column] = fromColumn;
        }
        if (minimum[column] < bestValue) {
          bestValue = minimum[column];
          best = column;
        }
      }
      return best;
    }

    private void shiftPotentials(double delta) {
      for (int column = 0; column <= columns; column++) {
        if (used[column]) {
          rowPotential[owner[column]] += delta;
          columnPotential[column] -= delta;
        } else {
          minimum[column] -= delta;
        }
      }
    }

    /** Recorre el camino de aumento hacia atrás reasignando las columnas. */
    private void unwind(int lastColumn) {
      int column = lastColumn;
      do {
        int previous = way[column];
        owner[column] = owner[previous];
        column = previous;
      } while (column != 0);
    }
  }
}
