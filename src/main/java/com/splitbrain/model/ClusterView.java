package com.splitbrain.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Snapshot of what one node sees as the cluster at a point in time.
 * The detector compares these across nodes; disagreement = split-brain.
 */
public record ClusterView(
        String nodeId,             // who is reporting
        String nodeAddress,        // host:port
        Set<String> visibleMembers,// UUIDs this node currently sees
        long clusterStateVersion,  // Hazelcast cluster state version (monotonic)
        Instant capturedAt
) {
    public JsonObject toJson() {
        return new JsonObject()
                .put("nodeId", nodeId)
                .put("nodeAddress", nodeAddress)
                .put("visibleMembers", new JsonArray(List.copyOf(visibleMembers)))
                .put("clusterStateVersion", clusterStateVersion)
                .put("capturedAt", capturedAt.toString());
    }

    public static ClusterView fromJson(JsonObject json) {
        Set<String> members = json.getJsonArray("visibleMembers").stream()
                .map(Object::toString).collect(Collectors.toSet());
        return new ClusterView(
                json.getString("nodeId"),
                json.getString("nodeAddress"),
                members,
                json.getLong("clusterStateVersion", 0L),
                Instant.parse(json.getString("capturedAt"))
        );
    }
}
