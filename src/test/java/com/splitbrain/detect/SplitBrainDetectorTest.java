package com.splitbrain.detect;

import com.splitbrain.model.ClusterView;
import com.splitbrain.model.SplitBrainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the core detection algorithm.
 *
 * Each test constructs a synthetic set of ClusterViews — one per node, each
 * declaring what it sees — and asserts what the detector concludes. These cover
 * the cases that matter in practice: healthy clusters, transient flux during
 * deploys, asymmetric partitions, and full splits.
 */
class SplitBrainDetectorTest {

    private static final Duration GRACE = Duration.ofSeconds(30);
    private final SplitBrainDetector detector = new SplitBrainDetector(GRACE);

    private static ClusterView view(String nodeId, String... visible) {
        return new ClusterView(
                nodeId,
                nodeId + ":8080",
                Set.of(visible),
                1L,
                Instant.now());
    }

    @Test
    @DisplayName("single view → no event, no disagreement")
    void singleView() {
        var result = detector.evaluate(
                List.of(view("a", "a")),
                SplitBrainDetector.Disagreement.NONE,
                Instant.now());

        assertTrue(result.event().isEmpty());
        assertFalse(result.currentDisagreement().present());
    }

    @Test
    @DisplayName("all nodes agree → healthy, no event")
    void healthyCluster() {
        var views = List.of(
                view("a", "a", "b", "c"),
                view("b", "a", "b", "c"),
                view("c", "a", "b", "c"));

        var result = detector.evaluate(views, SplitBrainDetector.Disagreement.NONE, Instant.now());

        assertTrue(result.event().isEmpty(), "all-agree should not emit");
        assertFalse(result.currentDisagreement().present());
    }

    @Test
    @DisplayName("disjoint partitions → CRITICAL")
    void confirmedSplitBrain() {
        // {a,b} cannot see {c,d} and vice versa. Both halves still reachable over HTTP.
        var views = List.of(
                view("a", "a", "b"),
                view("b", "a", "b"),
                view("c", "c", "d"),
                view("d", "c", "d"));

        var result = detector.evaluate(views, SplitBrainDetector.Disagreement.NONE, Instant.now());

        assertTrue(result.event().isPresent());
        var event = result.event().get();
        assertEquals(SplitBrainEvent.Severity.CRITICAL, event.severity());
        assertEquals(2, event.partitions().size(), "should identify two disjoint partitions");
        assertTrue(result.currentDisagreement().present());
    }

    @Test
    @DisplayName("transient disagreement within grace window → INFO on first detection")
    void transientDisagreementIsInfo() {
        // 'c' lags slightly — sees only a,b for a moment.
        var views = List.of(
                view("a", "a", "b", "c"),
                view("b", "a", "b", "c"),
                view("c", "a", "b"));

        var result = detector.evaluate(views, SplitBrainDetector.Disagreement.NONE, Instant.now());

        assertTrue(result.event().isPresent());
        assertEquals(SplitBrainEvent.Severity.INFO, result.event().get().severity());
        assertTrue(result.currentDisagreement().present());
    }

    @Test
    @DisplayName("transient disagreement does NOT re-emit INFO on subsequent ticks")
    void transientDoesNotSpam() {
        var views = List.of(
                view("a", "a", "b", "c"),
                view("b", "a", "b", "c"),
                view("c", "a", "b"));

        Instant t0 = Instant.now();
        var first = detector.evaluate(views, SplitBrainDetector.Disagreement.NONE, t0);
        assertTrue(first.event().isPresent());

        // Still within grace window, condition unchanged.
        var second = detector.evaluate(
                views, first.currentDisagreement(), t0.plusSeconds(5));

        assertTrue(second.event().isEmpty(),
                "second tick within grace should not produce another INFO");
        assertTrue(second.currentDisagreement().present());
    }

    @Test
    @DisplayName("disagreement persisting past grace window → WARN")
    void prolongedDisagreementEscalatesToWarn() {
        // Asymmetric: a sees everyone, b sees only itself + a.
        // No clean disjoint partition, but persistent — possible stuck merge.
        var views = List.of(
                view("a", "a", "b", "c"),
                view("b", "a", "b"),
                view("c", "a", "b", "c"));

        Instant t0 = Instant.now();
        var first = detector.evaluate(views, SplitBrainDetector.Disagreement.NONE, t0);
        var pastGrace = detector.evaluate(
                views, first.currentDisagreement(), t0.plus(GRACE).plusSeconds(1));

        assertTrue(pastGrace.event().isPresent());
        assertEquals(SplitBrainEvent.Severity.WARN, pastGrace.event().get().severity());
    }

    @Test
    @DisplayName("recovery: agreement after disagreement → state resets")
    void healing() {
        var split = List.of(
                view("a", "a", "b"),
                view("b", "a", "b"),
                view("c", "c", "d"),
                view("d", "c", "d"));
        var healed = List.of(
                view("a", "a", "b", "c", "d"),
                view("b", "a", "b", "c", "d"),
                view("c", "a", "b", "c", "d"),
                view("d", "a", "b", "c", "d"));

        var afterSplit = detector.evaluate(split, SplitBrainDetector.Disagreement.NONE, Instant.now());
        assertTrue(afterSplit.currentDisagreement().present());

        var afterHeal = detector.evaluate(
                healed, afterSplit.currentDisagreement(), Instant.now());

        assertFalse(afterHeal.currentDisagreement().present());
        assertTrue(afterHeal.event().isEmpty());
    }

    @Test
    @DisplayName("3-way split → CRITICAL with 3 partitions")
    void threeWaySplit() {
        var views = List.of(
                view("a", "a"),
                view("b", "b"),
                view("c", "c"));

        var result = detector.evaluate(views, SplitBrainDetector.Disagreement.NONE, Instant.now());

        assertTrue(result.event().isPresent());
        assertEquals(SplitBrainEvent.Severity.CRITICAL, result.event().get().severity());
        assertEquals(3, result.event().get().partitions().size());
    }

    @Test
    @DisplayName("duration in event reflects how long disagreement has persisted")
    void durationReported() {
        var views = List.of(
                view("a", "a", "b"),
                view("b", "a", "b"),
                view("c", "c", "d"),
                view("d", "c", "d"));

        Instant t0 = Instant.now();
        var first = detector.evaluate(views, SplitBrainDetector.Disagreement.NONE, t0);
        var later = detector.evaluate(views, first.currentDisagreement(), t0.plusSeconds(45));

        assertTrue(later.event().isPresent());
        assertTrue(later.event().get().durationMillis() >= 45_000,
                "duration should accumulate across ticks");
    }
}
