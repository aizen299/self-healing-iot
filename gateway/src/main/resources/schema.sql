-- Telemetry history.
--
-- An identity primary key rather than (device_id, ts): MESSAGE_FLOOD emits
-- several readings inside one millisecond, and a natural key on the timestamp
-- would reject exactly the traffic a flood experiment exists to record.
CREATE TABLE IF NOT EXISTS telemetry (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id    VARCHAR(80)  NOT NULL,
    ts           BIGINT       NOT NULL,
    received_at  BIGINT       NOT NULL,
    temperature  DOUBLE       NOT NULL,
    vibration    DOUBLE       NOT NULL,
    battery      DOUBLE       NOT NULL,
    latitude     DOUBLE       NOT NULL,
    longitude    DOUBLE       NOT NULL,
    status       VARCHAR(16)  NOT NULL
);

-- Every telemetry query is either "this device over a window" or "the fleet
-- over a window", so those are the two indexes and there are no others.
-- Each one is paid for on every insert, and inserts are the hot path.
CREATE INDEX IF NOT EXISTS idx_telemetry_device_ts ON telemetry (device_id, ts);
CREATE INDEX IF NOT EXISTS idx_telemetry_ts        ON telemetry (ts);

-- Health transitions. Far rarer than telemetry and individually meaningful,
-- so these are written through rather than buffered.
CREATE TABLE IF NOT EXISTS device_event (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id            VARCHAR(80) NOT NULL,
    event                VARCHAR(32) NOT NULL,
    from_health          VARCHAR(16) NOT NULL,
    to_health            VARCHAR(16) NOT NULL,
    at_ts                BIGINT      NOT NULL,
    missed_heartbeats    INT         NOT NULL,
    recovery_duration_ms BIGINT      NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_event_device_ts ON device_event (device_id, at_ts);
CREATE INDEX IF NOT EXISTS idx_event_type_ts   ON device_event (event, at_ts);

-- Losses the store detected, so a query can tell a complete window from a
-- gapped one. Written on a best-effort basis: the failure that loses readings
-- may also prevent this row landing, which is why the store keeps an
-- in-memory count as well.
CREATE TABLE IF NOT EXISTS store_integrity (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    at_ts         BIGINT      NOT NULL,
    dropped_count INT         NOT NULL,
    reason        VARCHAR(500) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_integrity_ts ON store_integrity (at_ts);
