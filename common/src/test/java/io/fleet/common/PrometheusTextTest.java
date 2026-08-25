package io.fleet.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exposition format, which is a contract with a parser rather than a
 * human.
 *
 * <p>Prometheus discards a scrape it cannot parse in its entirety, so a single
 * malformed line costs every metric in the response. That is what makes the
 * escaping worth a test of its own.
 */
class PrometheusTextTest {

    @Test
    @DisplayName("a declaration writes HELP and TYPE, once, above its samples")
    void writesTheHeaderLines() {
        String text = new PrometheusText()
                .counter("fleet_telemetry_accepted_total", "Readings accepted")
                .sample("fleet_telemetry_accepted_total", 42L)
                .render();

        assertEquals("""
                # HELP fleet_telemetry_accepted_total Readings accepted
                # TYPE fleet_telemetry_accepted_total counter
                fleet_telemetry_accepted_total 42
                """, text);
    }

    @Test
    @DisplayName("one declaration can head several label series")
    void oneDeclarationManySeries() {
        // Why declaring and sampling are separate calls: repeating # HELP per
        // series makes some scrapers drop the duplicates.
        String text = new PrometheusText()
                .gauge("fleet_devices", "Devices by health state")
                .sample("fleet_devices", "state", "ONLINE", 3L)
                .sample("fleet_devices", "state", "OFFLINE", 1L)
                .render();

        assertEquals(1, text.lines().filter(line -> line.startsWith("# HELP")).count());
        assertTrue(text.contains("fleet_devices{state=\"ONLINE\"} 3"), text);
        assertTrue(text.contains("fleet_devices{state=\"OFFLINE\"} 1"), text);
    }

    @Test
    @DisplayName("a quote in a label value is escaped, not left to end the label")
    void escapesLabelValues() {
        // Device ids arrive over MQTT from outside the process. An unescaped
        // quote would end the label early and make the whole scrape
        // unparseable — blinding every panel, not just this one.
        String text = new PrometheusText()
                .gauge("fleet_device_up", "1 when ONLINE")
                .sample("fleet_device_up", "device_id", "dev\"ice\\001", 1L)
                .render();

        assertTrue(text.contains("device_id=\"dev\\\"ice\\\\001\""), text);
        assertEquals(3, text.lines().count(), "the value must not introduce a line break");
    }

    @Test
    @DisplayName("a newline in a label value does not become a new sample")
    void escapesNewlinesInLabels() {
        String text = new PrometheusText()
                .gauge("fleet_device_up", "1 when ONLINE")
                .sample("fleet_device_up", "device_id", "a\nb", 1L)
                .render();

        assertTrue(text.contains("device_id=\"a\\nb\""), text);
        assertEquals(3, text.lines().count(), text);
    }

    @Test
    @DisplayName("a multi-line help string stays on one line")
    void flattensHelpText() {
        // Help is unquoted and terminated by the newline, so a wrapped
        // sentence would leave its tail parsed as a metric name.
        String text = new PrometheusText()
                .gauge("fleet_thing", "first line\nsecond line")
                .sample("fleet_thing", 1L)
                .render();

        assertTrue(text.contains("# HELP fleet_thing first line second line"), text);
        assertEquals(3, text.lines().count(), text);
    }

    @Test
    @DisplayName("histograms declare as histograms")
    void declaresHistograms() {
        String text = new PrometheusText()
                .histogram("fleet_recovery_duration_millis", "MTTR")
                .sample("fleet_recovery_duration_millis_bucket", "le", "+Inf", 2L)
                .render();

        assertTrue(text.contains("# TYPE fleet_recovery_duration_millis histogram"), text);
        assertTrue(text.contains("le=\"+Inf\""), text);
    }

    @Test
    @DisplayName("the content type names the format version Prometheus checks")
    void declaresItsContentType() {
        assertEquals("text/plain; version=0.0.4; charset=utf-8", PrometheusText.contentType());
    }
}
