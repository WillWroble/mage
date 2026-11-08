package mage.player.cabt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.Flushable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * Writes {@link CabtDatasetRecord} transitions to a stable JSONL format: one
 * JSON object per line, every line stamped with {@code schemaVersion} and the
 * run's {@link CabtDatasetMetadata}.
 * <p>
 * Key order and null handling are fixed (nulls are serialized explicitly) so
 * the on-disk schema stays stable for downstream readers. In test mode
 * (flushAfterEachRecord) each record is flushed as soon as it is written.
 */
public final class CabtDatasetWriter {

    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private final Writer out;
    private final CabtDatasetMetadata metadata;
    private final boolean flushAfterEachRecord;

    public CabtDatasetWriter(Writer out, CabtDatasetMetadata metadata, boolean flushAfterEachRecord) {
        if (out == null || metadata == null) {
            throw new IllegalArgumentException("out and metadata must not be null");
        }
        this.out = out;
        this.metadata = metadata;
        this.flushAfterEachRecord = flushAfterEachRecord;
    }

    public void write(CabtDatasetRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        JsonObject line = new JsonObject();
        line.addProperty("schemaVersion", SCHEMA_VERSION);
        line.addProperty("gameId", record.getGameId());
        line.addProperty("sequenceNumber", record.getSequenceNumber());
        line.addProperty("decisionMethod", record.getDecisionMethod());
        line.add("observation", GSON.toJsonTree(record.getObservation()));
        line.add("select", GSON.toJsonTree(record.getSelect()));
        line.add("selectedIndices", GSON.toJsonTree(record.getSelectedIndices()));
        line.add("nextObservation", record.getNextObservation() == null
                ? JsonNull.INSTANCE
                : GSON.toJsonTree(record.getNextObservation()));
        line.addProperty("terminal", record.isTerminal());
        line.add("reward", record.getReward() == null
                ? JsonNull.INSTANCE
                : GSON.toJsonTree(record.getReward()));
        JsonObject metadataJson = new JsonObject();
        metadataJson.addProperty("xmageVersion", metadata.getXmageVersion());
        metadataJson.addProperty("deck0Id", metadata.getDeck0Id());
        metadataJson.addProperty("deck1Id", metadata.getDeck1Id());
        line.add("metadata", metadataJson);
        try {
            out.write(GSON.toJson(line));
            out.write('\n');
            if (flushAfterEachRecord) {
                ((Flushable) out).flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write dataset record", e);
        }
    }
}
