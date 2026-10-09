package com.parkingwatch.edge.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkingwatch.common.contract.ContractJson;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.common.domain.EvidenceKind;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Búfer local en SQLite (RF-15.2, RNF-9.1): guarda los eventos y las fotos mientras no hay
 * conexión, en orden cronológico, y sobrevive a cortes de energía (modo WAL con sincronización
 * completa). Un evento sale del búfer solo cuando el backend lo confirma.
 */
public final class SqliteOutboxStore implements OutboxStore {

  private static final String SCHEMA_EVENTS =
      "CREATE TABLE IF NOT EXISTS outbox_events (event_id TEXT PRIMARY KEY,"
          + " occurred_at INTEGER NOT NULL, message TEXT NOT NULL,"
          + " quarantined INTEGER NOT NULL DEFAULT 0)";
  private static final String SCHEMA_EVIDENCE =
      "CREATE TABLE IF NOT EXISTS outbox_evidence (event_id TEXT NOT NULL, kind TEXT NOT NULL,"
          + " photo BLOB NOT NULL, PRIMARY KEY (event_id, kind))";

  private final Connection connection;
  private final ObjectMapper mapper = ContractJson.newMapper();

  /** Abre (o crea) la base de datos {@code outbox.db} en la carpeta indicada. */
  public SqliteOutboxStore(Path directory) throws IOException {
    try {
      Files.createDirectories(directory);
      connection =
          DriverManager.getConnection(
              "jdbc:sqlite:" + directory.resolve("outbox.db").toAbsolutePath());
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA journal_mode=WAL");
        statement.execute("PRAGMA synchronous=FULL");
        statement.execute(SCHEMA_EVENTS);
        statement.execute(SCHEMA_EVIDENCE);
      }
    } catch (SQLException e) {
      throw new IOException("No se pudo abrir el búfer local", e);
    }
  }

  @Override
  public synchronized void save(ParkingEventMessage message, EvidenceBundle evidence)
      throws IOException {
    try {
      connection.setAutoCommit(false);
      if (evidence != null) {
        savePhoto(message.eventId(), EvidenceKind.ENTRY, evidence.entry());
        savePhoto(message.eventId(), EvidenceKind.REPORT, evidence.report());
        savePhoto(message.eventId(), EvidenceKind.PLATE, evidence.plate());
      }
      upsert(message);
      connection.commit();
    } catch (SQLException e) {
      rollback();
      throw new IOException("No se pudo guardar el evento " + message.eventId(), e);
    } finally {
      autoCommit();
    }
  }

  @Override
  public synchronized void update(ParkingEventMessage message) throws IOException {
    try {
      upsert(message);
    } catch (SQLException e) {
      throw new IOException("No se pudo actualizar el evento " + message.eventId(), e);
    }
  }

  @Override
  public synchronized List<ParkingEventMessage> pending(int limit) throws IOException {
    String sql =
        "SELECT message FROM outbox_events WHERE quarantined = 0 ORDER BY occurred_at, event_id"
            + " LIMIT ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, limit);
      List<ParkingEventMessage> messages = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          messages.add(mapper.readValue(rows.getString(1), ParkingEventMessage.class));
        }
      }
      return messages;
    } catch (SQLException e) {
      throw new IOException("No se pudo leer el búfer local", e);
    }
  }

  @Override
  public synchronized Optional<byte[]> evidence(UUID eventId, EvidenceKind kind)
      throws IOException {
    String sql = "SELECT photo FROM outbox_evidence WHERE event_id = ? AND kind = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, eventId.toString());
      statement.setString(2, kind.name());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(rows.getBytes(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IOException("No se pudo leer la foto " + kind + " de " + eventId, e);
    }
  }

  @Override
  public synchronized void remove(UUID eventId) throws IOException {
    execute("DELETE FROM outbox_events WHERE event_id = ?", eventId);
    execute("DELETE FROM outbox_evidence WHERE event_id = ?", eventId);
  }

  @Override
  public synchronized void quarantine(UUID eventId) throws IOException {
    execute("UPDATE outbox_events SET quarantined = 1 WHERE event_id = ?", eventId);
    execute("DELETE FROM outbox_evidence WHERE event_id = ?", eventId);
  }

  @Override
  public synchronized int size() throws IOException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT count(*) FROM outbox_events WHERE quarantined = 0")) {
      return rows.next() ? rows.getInt(1) : 0;
    } catch (SQLException e) {
      throw new IOException("No se pudo contar el búfer local", e);
    }
  }

  private void upsert(ParkingEventMessage message) throws SQLException, IOException {
    String sql =
        "INSERT INTO outbox_events (event_id, occurred_at, message) VALUES (?, ?, ?)"
            + " ON CONFLICT (event_id) DO UPDATE SET message = excluded.message";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, message.eventId().toString());
      statement.setLong(2, message.occurredAt().toEpochMilli());
      statement.setString(3, mapper.writeValueAsString(message));
      statement.executeUpdate();
    }
  }

  private void savePhoto(UUID eventId, EvidenceKind kind, byte[] photo) throws SQLException {
    String sql = "INSERT OR REPLACE INTO outbox_evidence (event_id, kind, photo) VALUES (?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, eventId.toString());
      statement.setString(2, kind.name());
      statement.setBytes(3, photo);
      statement.executeUpdate();
    }
  }

  private void execute(String sql, UUID eventId) throws IOException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, eventId.toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IOException("No se pudo actualizar el búfer local para " + eventId, e);
    }
  }

  private void rollback() {
    try {
      connection.rollback();
    } catch (SQLException e) {
      // La transacción se descarta al cerrar; el evento no quedó guardado.
    }
  }

  private void autoCommit() {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException e) {
      // Se reintenta en la siguiente operación.
    }
  }

  @Override
  public synchronized void close() throws IOException {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new IOException("No se pudo cerrar el búfer local", e);
    }
  }
}
