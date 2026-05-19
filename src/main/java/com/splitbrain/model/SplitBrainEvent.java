package com.splitbrain.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What we emit when a split-brain (or strong suspicion of one) is detected.
 * Severity tiers let downstream handlers throttle - we don't want pager storms
 * during normal rolling deploys.
 */
public record SplitBrainEvent(
        Severity severity,
        String reason,
        List<Set<String>> partitions, // the disjoint groups of node UUIDs
        Instant detectedAt,
        long durationMillis           // how long the condition has persisted
) {
    public enum Severity {
        INFO,      // membership flux during deploy - log only
        WARN,      // disagreement persisting past grace window
        CRITICAL   // confirmed disjoint partitions with own quorum
    }

    public JsonObject toJson() {
        JsonArray parts = new JsonArray();
        partitions.forEach(p -> parts.add(new JsonArray(List.copyOf(p))));
        return new JsonObject()
                .put("severity", severity.name())
                .put("reason", reason)
                .put("partitions", parts)
                .put("partitionCount", partitions.size())
                .put("detectedAt", detectedAt.toString())
                .put("durationMillis", durationMillis);
    }
}
