package io.fleet.gateway.store;

import io.fleet.common.ConfigurationException;
import io.fleet.common.Env;

import java.util.Map;

/**
 * Storage configuration.
 *
 * <p>Separate from {@code GatewayConfig} because storage is optional: the
 * gateway runs and detects failures with it switched off, and every test that
 * is not about persistence should not have to think about it.
 *
 * @param enabled           whether telemetry is persisted at all
 * @param path              database file path, or {@code mem} for in-memory
 * @param batchSize         readings buffered before a batch insert
 * @param flushIntervalMs   longest a buffered reading may wait
 * @param retentionHours    age at which readings are pruned; 0 disables pruning
 * @param pruneIntervalMins how often pruning runs
 */
public record StoreConfig(
        boolean enabled,
        String path,
        int batchSize,
        long flushIntervalMs,
        long retentionHours,
        long pruneIntervalMins) {

    /** Requests an in-memory database rather than a file. */
    public static final String IN_MEMORY = "mem";

    public StoreConfig {
        if (path == null || path.isBlank()) {
            throw new ConfigurationException("GATEWAY_STORE_PATH must not be blank");
        }
        if (batchSize < 1) {
            throw new ConfigurationException(
                    "GATEWAY_STORE_BATCH_SIZE must be >= 1, got " + batchSize);
        }
        if (flushIntervalMs < 1) {
            throw new ConfigurationException(
                    "GATEWAY_STORE_FLUSH_INTERVAL_MS must be >= 1, got " + flushIntervalMs);
        }
        if (retentionHours < 0) {
            throw new ConfigurationException(
                    "GATEWAY_STORE_RETENTION_HOURS must be >= 0, got " + retentionHours);
        }
        if (pruneIntervalMins < 1) {
            throw new ConfigurationException(
                    "GATEWAY_STORE_PRUNE_INTERVAL_MINS must be >= 1, got " + pruneIntervalMins);
        }
    }

    public static StoreConfig fromEnv() {
        return from(System.getenv());
    }

    public static StoreConfig from(Map<String, String> env) {
        return new StoreConfig(
                Env.booleanValue(env, "GATEWAY_STORE_ENABLED", true),
                Env.string(env, "GATEWAY_STORE_PATH", "./data/fleet"),
                Env.intValue(env, "GATEWAY_STORE_BATCH_SIZE", 200),
                Env.longValue(env, "GATEWAY_STORE_FLUSH_INTERVAL_MS", 1000L),
                // Off by default. The reproducibility contract says raw
                // experiment data is committed and kept, so silently deleting
                // history would be the wrong default for this project even
                // though it is the right one for a production fleet.
                Env.longValue(env, "GATEWAY_STORE_RETENTION_HOURS", 0L),
                Env.longValue(env, "GATEWAY_STORE_PRUNE_INTERVAL_MINS", 60L));
    }

    public boolean inMemory() {
        return IN_MEMORY.equals(path);
    }

    public boolean pruningEnabled() {
        return retentionHours > 0L;
    }

    /**
     * JDBC URL for this configuration.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} on the in-memory form keeps the database
     * alive while the JVM is, rather than dropping it the moment the first
     * connection closes.
     */
    public String jdbcUrl() {
        return inMemory()
                ? "jdbc:h2:mem:fleet;DB_CLOSE_DELAY=-1"
                : "jdbc:h2:file:" + path + ";AUTO_SERVER=TRUE";
    }
}
