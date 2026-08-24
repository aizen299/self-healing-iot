package io.fleet.stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;

import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/** Runs the fleet topology until interrupted. */
public final class Main {

    public static void main(String[] args) throws InterruptedException {
        StreamConfig config = StreamConfig.fromEnv();
        FleetTopology topology = new FleetTopology(config.window(), config.grace());

        System.out.printf(Locale.ROOT, """
                === stream-processor (Phase 6) ===
                bootstrap servers : %s
                application id    : %s
                window            : %d s (grace %d s)
                jvm               : %s %s
                %n""",
                config.bootstrapServers(), config.applicationId(),
                config.windowSeconds(), config.graceSeconds(),
                System.getProperty("java.vm.name"), System.getProperty("java.version"));

        KafkaStreams streams = new KafkaStreams(topology.build(), properties(config));
        CountDownLatch stopped = new CountDownLatch(1);

        // An uncaught exception on a stream thread kills that thread silently
        // and the application quietly processes less than it should. Replacing
        // the thread keeps the topology running; a repeated failure will at
        // least be visible in the log rather than as an unexplained slowdown.
        streams.setUncaughtExceptionHandler(throwable -> {
            System.err.println("stream thread failed: " + throwable);
            return StreamsUncaughtExceptionHandlerResponse.REPLACE_THREAD;
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            streams.close();
            stopped.countDown();
        }, "stream-shutdown"));

        streams.start();
        System.out.println("topology running; reading telemetry.raw");
        stopped.await();

        System.out.println("malformed records dropped: " + topology.malformedCount());
    }

    private static Properties properties(StreamConfig config) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, config.applicationId());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                Serdes.ByteArray().getClass().getName());
        // One broker, so anything higher simply fails to create the internal
        // topics. Wrong for production, correct here.
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1);
        // Start from the beginning on a fresh application id, so a run picks
        // up telemetry already produced rather than appearing to do nothing.
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG),
                "earliest");
        return props;
    }

    /** Local alias so the handler lambda above stays readable. */
    private interface StreamsUncaughtExceptionHandlerResponse {
        org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
                .StreamThreadExceptionResponse REPLACE_THREAD =
                org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
                        .StreamThreadExceptionResponse.REPLACE_THREAD;
    }

    private Main() {
    }
}
