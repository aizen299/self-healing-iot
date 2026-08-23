package io.fleet.gateway.store;

import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.DeviceHealth;
import io.fleet.common.DeviceStatus;
import io.fleet.common.StoreException;
import io.fleet.common.Telemetry;
import io.fleet.common.TelemetryStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Telemetry history in an embedded H2 database.
 *
 * <p>Embedded because Docker does not arrive until Phase 7 and persistence
 * must not wait for it, and H2 specifically because it is pure Java: 2.8 MB
 * with no native libraries, which keeps the gateway small in a stack that has
 * to fit in 8 GB and portable to the arm64 containers of Phase 7. See ADR-007.
 *
 * <p>Readings are buffered and inserted in batches. At fleet rate the
 * per-statement round trip dominates, and committing every reading
 * individually would make the store rather than the pipeline the bottleneck
 * in Phase 8's throughput measurements. Health transitions bypass the buffer:
 * they are rare, individually meaningful, and the input to MTTR.
 *
 * <p>One connection, guarded by this object's monitor. The gateway's writers
 * are the MQTT callback thread and the monitor thread, and its readers are
 * HTTP handlers; a pool would add a dependency and a tuning surface for a
 * workload that is one batched writer plus occasional queries.
 */
public final class H2TelemetryStore implements TelemetryStore {

    private static final String INSERT_TELEMETRY = """
            INSERT INTO telemetry
                (device_id, ts, received_at, temperature, vibration, battery,
                 latitude, longitude, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String INSERT_EVENT = """
            INSERT INTO device_event
                (device_id, event, from_health, to_health, at_ts,
                 missed_heartbeats, recovery_duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    private final Connection connection;
    private final int batchSize;
    private final PreparedStatement insertTelemetry;
    private int buffered;

    public H2TelemetryStore(StoreConfig config) throws StoreException {
        this.batchSize = config.batchSize();
        try {
            this.connection = DriverManager.getConnection(config.jdbcUrl());
            this.connection.setAutoCommit(false);
            applySchema();
            this.insertTelemetry = connection.prepareStatement(INSERT_TELEMETRY);
        } catch (SQLException e) {
            throw new StoreException("could not open the telemetry store at "
                    + config.jdbcUrl(), e);
        }
    }

    private void applySchema() throws StoreException {
        try (InputStream in = H2TelemetryStore.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new StoreException("schema.sql is missing from the gateway jar");
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        } catch (IOException | SQLException e) {
            throw new StoreException("could not apply the telemetry schema", e);
        }
    }

    @Override
    public synchronized void record(Telemetry telemetry, long receivedAtMillis)
            throws StoreException {
        try {
            insertTelemetry.setString(1, telemetry.deviceId());
            insertTelemetry.setLong(2, telemetry.timestamp());
            insertTelemetry.setLong(3, receivedAtMillis);
            insertTelemetry.setDouble(4, telemetry.temperature());
            insertTelemetry.setDouble(5, telemetry.vibration());
            insertTelemetry.setDouble(6, telemetry.batteryLevel());
            insertTelemetry.setDouble(7, telemetry.latitude());
            insertTelemetry.setDouble(8, telemetry.longitude());
            insertTelemetry.setString(9, telemetry.status().name());
            insertTelemetry.addBatch();
            buffered++;
            if (buffered >= batchSize) {
                flushLocked();
            }
        } catch (SQLException e) {
            throw new StoreException("could not buffer a reading from "
                    + telemetry.deviceId(), e);
        }
    }

    @Override
    public synchronized void recordEvent(DeviceEventRecord event) throws StoreException {
        // Flushed first so the history reads in order: an event must not land
        // before readings that happened before it.
        flushLocked();
        try (PreparedStatement statement = connection.prepareStatement(INSERT_EVENT)) {
            statement.setString(1, event.deviceId());
            statement.setString(2, event.event().name());
            statement.setString(3, event.fromHealth().name());
            statement.setString(4, event.toHealth().name());
            statement.setLong(5, event.atMillis());
            statement.setInt(6, event.missedHeartbeats());
            statement.setLong(7, event.recoveryDurationMillis());
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            throw new StoreException("could not record a " + event.event()
                    + " for " + event.deviceId(), e);
        }
    }

    @Override
    public synchronized void flush() throws StoreException {
        flushLocked();
    }

    private void flushLocked() throws StoreException {
        if (buffered == 0) {
            return;
        }
        try {
            insertTelemetry.executeBatch();
            connection.commit();
            buffered = 0;
        } catch (SQLException e) {
            // The counter is cleared regardless: leaving it set would retry the
            // same doomed batch on every subsequent write and turn one failure
            // into a permanently stuck store.
            buffered = 0;
            throw new StoreException("could not flush buffered telemetry", e);
        }
    }

    @Override
    public synchronized List<Telemetry> history(String deviceId, long fromMillis, long toMillis)
            throws StoreException {
        flushLocked();
        String sql = """
                SELECT device_id, ts, temperature, vibration, battery,
                       latitude, longitude, status
                  FROM telemetry
                 WHERE device_id = ? AND ts BETWEEN ? AND ?
                 ORDER BY ts""";
        List<Telemetry> readings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, deviceId);
            statement.setLong(2, fromMillis);
            statement.setLong(3, toMillis);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    readings.add(new Telemetry(
                            rows.getString(1), rows.getLong(2), rows.getDouble(3),
                            rows.getDouble(4), rows.getDouble(5), rows.getDouble(6),
                            rows.getDouble(7), DeviceStatus.valueOf(rows.getString(8))));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("could not read history for " + deviceId, e);
        }
        return readings;
    }

    @Override
    public synchronized OptionalDouble fleetAverageTemperature(long fromMillis, long toMillis)
            throws StoreException {
        return singleAverage(
                "SELECT AVG(temperature) FROM telemetry WHERE ts BETWEEN ? AND ?",
                fromMillis, toMillis, "fleet average temperature");
    }

    @Override
    public synchronized double telemetryRate(long fromMillis, long toMillis)
            throws StoreException {
        flushLocked();
        long windowMillis = toMillis - fromMillis;
        if (windowMillis <= 0L) {
            return 0.0d;
        }
        String sql = "SELECT COUNT(*) FROM telemetry WHERE ts BETWEEN ? AND ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, fromMillis);
            statement.setLong(2, toMillis);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return (rows.getLong(1) * 1000.0d) / windowMillis;
            }
        } catch (SQLException e) {
            throw new StoreException("could not compute the telemetry rate", e);
        }
    }

    /**
     * Devices whose most recent transition left them failed.
     *
     * <p>Derived from the event history rather than from a status column, so
     * the answer stays correct after a gateway restart: the live registry is
     * rebuilt from scratch, but the record of what happened is not.
     */
    @Override
    public synchronized List<String> currentlyFailedDevices() throws StoreException {
        String sql = """
                SELECT e.device_id
                  FROM device_event e
                  JOIN (SELECT device_id, MAX(at_ts) AS latest
                          FROM device_event GROUP BY device_id) newest
                    ON e.device_id = newest.device_id AND e.at_ts = newest.latest
                 WHERE e.to_health = ?
                 ORDER BY e.at_ts DESC""";
        List<String> failed = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DeviceHealth.OFFLINE.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    failed.add(rows.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("could not list failed devices", e);
        }
        return failed;
    }

    @Override
    public synchronized List<DeviceEventRecord> recoveries(long fromMillis, long toMillis)
            throws StoreException {
        String sql = """
                SELECT device_id, event, from_health, to_health, at_ts,
                       missed_heartbeats, recovery_duration_ms
                  FROM device_event
                 WHERE event = ? AND at_ts BETWEEN ? AND ?
                 ORDER BY at_ts""";
        List<DeviceEventRecord> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DeviceEventType.DEVICE_RECOVERED.name());
            statement.setLong(2, fromMillis);
            statement.setLong(3, toMillis);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(new DeviceEventRecord(
                            rows.getString(1),
                            DeviceEventType.valueOf(rows.getString(2)),
                            DeviceHealth.valueOf(rows.getString(3)),
                            DeviceHealth.valueOf(rows.getString(4)),
                            rows.getLong(5), rows.getInt(6), rows.getLong(7)));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("could not list recoveries", e);
        }
        return events;
    }

    @Override
    public synchronized OptionalDouble meanRecoveryMillis(long fromMillis, long toMillis)
            throws StoreException {
        String sql = """
                SELECT AVG(recovery_duration_ms) FROM device_event
                 WHERE event = '""" + DeviceEventType.DEVICE_RECOVERED.name() + """
                '  AND recovery_duration_ms > 0 AND at_ts BETWEEN ? AND ?""";
        return singleAverage(sql, fromMillis, toMillis, "mean recovery time");
    }

    @Override
    public synchronized long readingCount(String deviceId) throws StoreException {
        flushLocked();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM telemetry WHERE device_id = ?")) {
            statement.setString(1, deviceId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new StoreException("could not count readings for " + deviceId, e);
        }
    }

    @Override
    public synchronized int pruneTelemetryBefore(long cutoffMillis) throws StoreException {
        flushLocked();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM telemetry WHERE ts < ?")) {
            statement.setLong(1, cutoffMillis);
            int removed = statement.executeUpdate();
            connection.commit();
            return removed;
        } catch (SQLException e) {
            throw new StoreException("could not prune telemetry", e);
        }
    }

    private OptionalDouble singleAverage(String sql, long from, long to, String what)
            throws StoreException {
        flushLocked();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, from);
            statement.setLong(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                double value = rows.getDouble(1);
                // AVG over no rows is SQL NULL, which getDouble reports as 0.0
                // — indistinguishable from a genuine zero without wasNull().
                return rows.wasNull() ? OptionalDouble.empty() : OptionalDouble.of(value);
            }
        } catch (SQLException e) {
            throw new StoreException("could not compute " + what, e);
        }
    }

    @Override
    public synchronized void close() throws StoreException {
        StoreException failure = null;
        try {
            flushLocked();
        } catch (StoreException e) {
            failure = e;
        }
        try {
            insertTelemetry.close();
            connection.close();
        } catch (SQLException e) {
            StoreException closeFailure =
                    new StoreException("could not close the telemetry store", e);
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
