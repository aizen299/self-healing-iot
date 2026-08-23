package io.fleet.edge.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Heap and garbage-collection counters at a point in time.
 *
 * <p>Deliberately built on the JDK's own management beans rather than an
 * agent or profiler: Pillar A compares allocation behaviour between the two
 * variants, and any instrumentation heavy enough to perturb allocation would
 * be measuring itself.
 *
 * <p>Taking a snapshot never calls {@code System.gc()}. Forcing a collection
 * would flatten precisely the difference in GC pressure the comparison exists
 * to expose.
 *
 * @param collections          total collections across all collectors
 * @param collectionTimeMillis total time spent collecting
 * @param heapUsedBytes        heap in use at capture time
 * @param heapMaxBytes         configured heap ceiling, or -1 if undefined
 */
public record GcSnapshot(
        long collections,
        long collectionTimeMillis,
        long heapUsedBytes,
        long heapMaxBytes) {

    public static GcSnapshot capture() {
        long collections = 0L;
        long collectionTime = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            // Beans report -1 when a collector does not track a figure.
            if (count > 0) {
                collections += count;
            }
            if (time > 0) {
                collectionTime += time;
            }
        }
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new GcSnapshot(collections, collectionTime, heap.getUsed(), heap.getMax());
    }

    /** Counters accumulated since {@code earlier}. */
    public GcSnapshot since(GcSnapshot earlier) {
        return new GcSnapshot(
                collections - earlier.collections,
                collectionTimeMillis - earlier.collectionTimeMillis,
                heapUsedBytes,
                heapMaxBytes);
    }

    /** Names of the active collectors, for the run record. */
    public static String collectorNames() {
        StringBuilder names = new StringBuilder();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(gc.getName());
        }
        return names.toString();
    }
}
