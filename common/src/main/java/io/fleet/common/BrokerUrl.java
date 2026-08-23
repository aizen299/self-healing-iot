package io.fleet.common;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Validation for MQTT broker URLs.
 *
 * <p>Shared because both the device and the gateway read the same
 * {@code MQTT_BROKER_URL}. Two independent copies would eventually disagree
 * about what a valid broker URL is — a new scheme added to one and forgotten
 * in the other — and the two components would then accept different
 * configurations for the same variable.
 */
public final class BrokerUrl {

    /**
     * @param key the environment variable being validated, so the error names
     *            what the operator actually has to change
     * @throws ConfigurationException if the URL is unusable
     */
    public static void validate(String key, String url) {
        if (url == null || url.isBlank()) {
            throw new ConfigurationException(key + " must not be blank");
        }
        if (!url.startsWith("tcp://") && !url.startsWith("ssl://")) {
            throw new ConfigurationException(
                    key + " must start with tcp:// or ssl://, got '" + url + "'");
        }
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw new ConfigurationException(key + " is not a valid URI: '" + url + "'", e);
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new ConfigurationException(key + " has no host: '" + url + "'");
        }
        // Checked here rather than left to the client library: a missing port
        // surfaces from Paho as a generic connect failure, which points the
        // operator at the broker instead of at the variable they mistyped.
        if (parsed.getPort() < 1) {
            throw new ConfigurationException(
                    key + " must include a port, e.g. tcp://host:1883, got '" + url + "'");
        }
    }

    private BrokerUrl() {
    }
}
