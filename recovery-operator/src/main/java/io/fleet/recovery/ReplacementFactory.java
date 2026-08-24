package io.fleet.recovery;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.fleet.recovery.k8s.KubernetesException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds a replacement pod out of a pod that already exists.
 *
 * <p>Cloned rather than templated, and that is the point. A pod spec written
 * into this operator — or into a ConfigMap beside it — would be a second copy
 * of {@code base/40-devices.yaml}, and the two would drift: the fleet would
 * get a heap flag or a resource limit that replacements silently did not.
 * Cloning means a replacement is by construction the same shape as the thing
 * it replaces.
 *
 * <p>Only three things change: the name, the {@code device-id} label, and
 * {@code FLEET_DEVICE_INDEX_OFFSET}. Everything else — image, resources, the
 * wait-for-broker init container, the grace period — comes across untouched.
 *
 * <p>The runtime fields a cloned manifest must not carry back are dropped:
 * {@code status}, {@code resourceVersion}, {@code uid}, {@code
 * creationTimestamp}, and the node the source pod happened to be scheduled
 * on. Sending those back asks the API server to recreate one specific
 * historical object, which it refuses.
 */
public final class ReplacementFactory {

    private final JsonFactory json = new JsonFactory();

    /**
     * @param sourceManifest a pod manifest as the API server returned it
     * @param deviceId       the device the replacement is for
     * @param indexOffset    that device's slice of the fleet
     * @param recoveryId     stamped on so a replacement can be traced to its cause
     * @param replacedPod    the pod being replaced, for the same reason; may be null
     */
    public String build(String sourceManifest, String deviceId, int indexOffset,
            String recoveryId, String replacedPod) throws KubernetesException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try (JsonParser parser = json.createParser(sourceManifest);
             JsonGenerator generator = json.createGenerator(out)) {

            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new KubernetesException("source pod manifest is not a JSON object");
            }
            generator.writeStartObject();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String section = parser.currentName();
                parser.nextToken();
                switch (section) {
                    case "metadata" -> writeMetadata(parser, generator, deviceId,
                            recoveryId, replacedPod);
                    case "spec" -> writeSpec(parser, generator, indexOffset);
                    // Dropped, not copied. status is the source pod's history,
                    // and a replacement has none yet.
                    case "status" -> parser.skipChildren();
                    default -> {
                        generator.writeFieldName(section);
                        generator.copyCurrentStructure(parser);
                    }
                }
            }
            generator.writeEndObject();
        } catch (IOException e) {
            throw new KubernetesException("could not build a replacement manifest for "
                    + deviceId + ": " + e.getMessage(), e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private void writeMetadata(JsonParser parser, JsonGenerator generator, String deviceId,
            String recoveryId, String replacedPod) throws IOException {
        generator.writeObjectFieldStart("metadata");
        generator.writeStringField("name",
                RecoveryId.replacementPodName(deviceId, recoveryId));

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                // The name is written above; every other one of these is the
                // identity or history of the source object rather than part of
                // a pod-shaped thing to create.
                case "name", "uid", "resourceVersion", "creationTimestamp",
                     "generateName", "selfLink", "generation", "managedFields",
                     "ownerReferences", "deletionTimestamp",
                     "deletionGracePeriodSeconds", "finalizers" -> parser.skipChildren();
                case "labels" -> {
                    generator.writeObjectFieldStart("labels");
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String key = parser.currentName();
                        parser.nextToken();
                        // device-id is rewritten because a replacement cloned
                        // from a sibling would otherwise claim to be that
                        // sibling — and that label is what the recovery path
                        // and deploy.sh both select on.
                        if (!"device-id".equals(key) && !"recovery-id".equals(key)
                                && !"replaces".equals(key)) {
                            generator.writeStringField(key, parser.getText());
                        }
                    }
                    generator.writeStringField("device-id", deviceId);
                    generator.writeStringField("recovery-id", recoveryId);
                    if (replacedPod != null) {
                        generator.writeStringField("replaces", replacedPod);
                    }
                    generator.writeEndObject();
                }
                default -> {
                    generator.writeFieldName(field);
                    generator.copyCurrentStructure(parser);
                }
            }
        }
        generator.writeEndObject();
    }

    private void writeSpec(JsonParser parser, JsonGenerator generator, int indexOffset)
            throws IOException {
        generator.writeObjectFieldStart("spec");
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                // Assigned by the scheduler, or filled in by the API server.
                // nodeName in particular bypasses scheduling entirely, pinning
                // the replacement to wherever the source happened to land.
                case "nodeName" -> parser.skipChildren();
                case "containers" -> {
                    generator.writeFieldName("containers");
                    generator.writeStartArray();
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        writeContainer(parser, generator, indexOffset);
                    }
                    generator.writeEndArray();
                }
                default -> {
                    generator.writeFieldName(field);
                    generator.copyCurrentStructure(parser);
                }
            }
        }
        generator.writeEndObject();
    }

    private void writeContainer(JsonParser parser, JsonGenerator generator, int indexOffset)
            throws IOException {
        generator.writeStartObject();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            if ("env".equals(field)) {
                generator.writeFieldName("env");
                generator.writeStartArray();
                boolean wroteOffset = false;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (rewriteOffsetEntry(parser, generator, indexOffset)) {
                        wroteOffset = true;
                    }
                }
                // A pod cloned from a sibling that never set an offset still
                // needs one, or the replacement would run device 1 under the
                // failed device's name.
                if (!wroteOffset) {
                    generator.writeStartObject();
                    generator.writeStringField("name", "FLEET_DEVICE_INDEX_OFFSET");
                    generator.writeStringField("value", Integer.toString(indexOffset));
                    generator.writeEndObject();
                }
                generator.writeEndArray();
            } else {
                generator.writeFieldName(field);
                generator.copyCurrentStructure(parser);
            }
        }
        generator.writeEndObject();
    }

    /** @return true when this entry was the offset, rewritten */
    private boolean rewriteOffsetEntry(JsonParser parser, JsonGenerator generator,
            int indexOffset) throws IOException {
        // Buffered rather than streamed straight through: JSON does not
        // promise field order, so whether this is the entry to rewrite is not
        // known until the whole object has been read.
        ByteArrayOutputStream buffered = new ByteArrayOutputStream(128);
        String name = null;
        try (JsonGenerator scratch = json.createGenerator(buffered)) {
            scratch.writeStartObject();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if ("name".equals(field)) {
                    name = parser.getText();
                    scratch.writeStringField("name", name);
                } else {
                    scratch.writeFieldName(field);
                    scratch.copyCurrentStructure(parser);
                }
            }
            scratch.writeEndObject();
        }

        if ("FLEET_DEVICE_INDEX_OFFSET".equals(name)) {
            generator.writeStartObject();
            generator.writeStringField("name", "FLEET_DEVICE_INDEX_OFFSET");
            generator.writeStringField("value", Integer.toString(indexOffset));
            generator.writeEndObject();
            return true;
        }
        generator.writeRawValue(buffered.toString(StandardCharsets.UTF_8));
        return false;
    }
}
