package com.splitbrain.detect;

import com.splitbrain.model.ClusterView;
import com.splitbrain.model.SplitBrainEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Pure-function detector. Given a collection of views from different nodes,
 * decide whether a split-brain is happening, and at what severity.
 *
 * Algorithm (in plain English):
 *   1. Group views by what they see. Each unique "visibleMembers" set is a candidate partition.
 *   2. The CRITICAL signal: two reporters who are both ALIVE (we just fetched their view)
 *      but who DON'T see each other in their member lists. That's a partition.
 *   3. The WARN signal: reporters disagree about cluster membership but still see each other —
 *      this is usually transient (deploys, scaling). Alert only if it persists past graceWindow.
 *   4. The INFO signal: a single view differs (one node lagging) — log only.
 *
 * State (firstSeenAt for current condition) is held externally so the function stays pure.
 */
public final class SplitBrainDetector {

    public record DetectionResult(
            Optional<SplitBrainEvent> event,
            Disagreement currentDisagreement
    ) {}

    public record Disagreement(boolean present, Instant since, int partitionCount) {
        public static final Disagreement NONE = new Disagreement(false, Instant.EPOCH, 1);
    }

    private final Duration graceWindow;

    public SplitBrainDetector(Duration graceWindow) {
        this.graceWindow = graceWindow;
    }

    public DetectionResult evaluate(Collection<ClusterView> views,
                                    Disagreement previous,
                                    Instant now) {
        if (views.size() < 2) {
            // Nothing to compare with. Either we're alone or we couldn't fetch peers.
            return new DetectionResult(Optional.empty(), Disagreement.NONE);
        }

        // Step 1: equivalence-class the views by their visibleMembers set.
        Map<Set<String>, List<ClusterView>> groups = new HashMap<>();
        for (ClusterView v : views) {
            groups.computeIfAbsent(v.visibleMembers(), k -> new ArrayList<>()).add(v);
        }

        if (groups.size() == 1) {
            // All nodes agree. Healthy.
            return new DetectionResult(Optional.empty(), Disagreement.NONE);
        }

        // Step 2: check for the strong signal — reporters that don't see each other.
        List<Set<String>> partitions = findDisjointPartitions(views);

        // Track how long this disagreement has persisted.
        Instant since = previous.present() ? previous.since() : now;
        long durationMs = Duration.between(since, now).toMillis();
        Disagreement current = new Disagreement(true, since, Math.max(groups.size(), partitions.size()));

        SplitBrainEvent.Severity severity;
        String reason;

        if (partitions.size() >= 2) {
            // Confirmed split-brain: nodes are alive but mutually invisible.
            severity = SplitBrainEvent.Severity.CRITICAL;
            reason = "Detected " + partitions.size() + " disjoint partitions: nodes are reachable via HTTP "
                    + "but cannot see each other through the cluster manager.";
        } else if (durationMs >= graceWindow.toMillis()) {
            severity = SplitBrainEvent.Severity.WARN;
            reason = "Cluster members disagree about membership for >" + graceWindow.toSeconds()
                    + "s. Possible asymmetric partition or stuck merge.";
        } else {
            severity = SplitBrainEvent.Severity.INFO;
            reason = "Transient membership disagreement (within grace window).";
            // Don't emit INFO repeatedly; only on transition.
            if (previous.present()) {
                return new DetectionResult(Optional.empty(), current);
            }
        }

        SplitBrainEvent event = new SplitBrainEvent(
                severity,
                reason,
                partitions.isEmpty() ? List.copyOf(groups.keySet()) : partitions,
                now,
                durationMs
        );
        return new DetectionResult(Optional.of(event), current);
    }

    /**
     * Find groups of nodes that mutually fail to see each other.
     * A "disjoint partition" is a maximal set of reporters whose visibleMembers
     * sets are mutually exclusive.
     */
    private List<Set<String>> findDisjointPartitions(Collection<ClusterView> views) {
        // Build: reporterUuid -> what it sees
        Map<String, Set<String>> seen = new HashMap<>();
        for (ClusterView v : views) {
            seen.put(v.nodeId(), v.visibleMembers());
        }

        // Union-find: two reporters are in the same partition iff each appears in the other's view.
        Map<String, String> parent = new HashMap<>();
        seen.keySet().forEach(id -> parent.put(id, id));

        for (var e1 : seen.entrySet()) {
            for (var e2 : seen.entrySet()) {
                if (e1.getKey().equals(e2.getKey())) continue;
                boolean mutuallyVisible =
                        e1.getValue().contains(e2.getKey()) && e2.getValue().contains(e1.getKey());
                if (mutuallyVisible) union(parent, e1.getKey(), e2.getKey());
            }
        }

        Map<String, Set<String>> groups = new HashMap<>();
        for (String id : seen.keySet()) {
            groups.computeIfAbsent(find(parent, id), k -> new HashSet<>()).add(id);
        }

        return groups.values().stream()
                .filter(g -> g.size() >= 1)
                .toList();
    }

    private static String find(Map<String, String> p, String x) {
        while (!p.get(x).equals(x)) {
            p.put(x, p.get(p.get(x)));
            x = p.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> p, String a, String b) {
        String ra = find(p, a), rb = find(p, b);
        if (!ra.equals(rb)) p.put(ra, rb);
    }
}
