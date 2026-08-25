package io.fleet.common;

/**
 * Writes the Prometheus text exposition format, and nothing else.
 *
 * <p>Hand-rolled rather than taken from Micrometer or simpleclient, for the
 * reason recorded in ADR-012: the format is a handful of lines, everything
 * this project exposes is already counted by {@code GatewayMetrics} and the
 * operator's own fields, and a client library would arrive with its own
 * registry, its own naming, and a second place where a metric can be
 * declared. The same argument ADR-011 made about the Kubernetes client.
 *
 * <p>Every name this project exposes carries a {@code fleet_} prefix,
 * deliberately including the JVM ones. Calling them {@code jvm_memory_*}
 * would claim compatibility with the Micrometer schema a dashboard might
 * expect, and these are not those metrics — they are four numbers read off
 * the MXBeans.
 *
 * <p>Declaration and sampling are separate calls so one {@code # HELP} can
 * head several label series, which is what the format requires: repeating the
 * help line per series makes some scrapers drop the duplicates.
 *
 * <p>Not a registry. It holds no metrics between scrapes and remembers
 * nothing — the counters live where they are incremented, and this renders
 * whatever it is handed. A rendering object that also stored state would be
 * the second place a metric could be defined.
 */
public final class PrometheusText {

    private final StringBuilder out = new StringBuilder(4_096);

    /** Declares a monotonically increasing series. Name should end in _total. */
    public PrometheusText counter(String name, String help) {
        return declare(name, "counter", help);
    }

    /** Declares a series that can go up or down. */
    public PrometheusText gauge(String name, String help) {
        return declare(name, "gauge", help);
    }

    /**
     * Declares a histogram.
     *
     * <p>The caller writes the {@code _bucket}, {@code _sum} and
     * {@code _count} series itself with {@link #sample}: the format wants them
     * under this one declaration, and cumulative bucket counts are the
     * caller's arithmetic, not this class's.
     */
    public PrometheusText histogram(String name, String help) {
        return declare(name, "histogram", help);
    }

    /**
     * Declares a summary: a {@code _sum} and a {@code _count}, no buckets.
     *
     * <p>What a duration deserves when the shape of its distribution is not
     * the question. The operator's detection-to-replacement time is a
     * component of MTTR rather than MTTR itself, so its average is worth
     * having and its quantiles are not worth eight more series.
     */
    public PrometheusText summary(String name, String help) {
        return declare(name, "summary", help);
    }

    private PrometheusText declare(String name, String type, String help) {
        out.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        return this;
    }

    /** An unlabelled sample. */
    public PrometheusText sample(String name, long value) {
        out.append(name).append(' ').append(value).append('\n');
        return this;
    }

    /**
     * A sample carrying one label.
     *
     * <p>One, not a map of them: every series this project exposes is keyed by
     * a single dimension — a health state, a device id, a rejection reason, a
     * collector name, a histogram bound. A general label map would be more
     * machinery than any caller has a use for.
     */
    public PrometheusText sample(String name, String labelName, String labelValue, long value) {
        out.append(name).append('{').append(labelName).append("=\"")
                .append(escapeLabelValue(labelValue)).append("\"} ")
                .append(value).append('\n');
        return this;
    }

    /**
     * Escapes a label value.
     *
     * <p>Label values carry device ids, which arrive over MQTT from outside
     * this process. A quote or a backslash in one would otherwise end the
     * label early and produce a scrape the parser rejects — taking every other
     * metric in the response with it, so one malformed device id would blind
     * the whole dashboard.
     */
    private static String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Help text is unquoted, so only the backslash and the newline matter. */
    private static String escapeHelp(String help) {
        return help.replace("\\", "\\\\").replace("\n", " ");
    }

    /** The exposition body, ready to write to a response. */
    public String render() {
        return out.toString();
    }

    /** What to send it as. Prometheus checks the version parameter. */
    public static String contentType() {
        return "text/plain; version=0.0.4; charset=utf-8";
    }
}
