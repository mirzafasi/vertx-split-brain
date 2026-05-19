package com.splitbrain.api;

import com.splitbrain.detect.LocalViewCollector;
import com.splitbrain.detect.SplitBrainDetector;
import com.splitbrain.model.SplitBrainEvent;
import com.splitbrain.simulate.PartitionSimulator;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Endpoints:
 *   GET  /sb/cluster/view      — what THIS node sees (consumed by peers + watchers)
 *   GET  /sb/status            — current detector state + recent events
 *   GET  /sb/events            — last N events
 *   POST /sb/simulate/block    — soft-block a peer  {"peer": "host:port"}
 *   POST /sb/simulate/unblock  — soft-unblock a peer
 *   POST /sb/simulate/clear    — clear all sim state
 *   GET  /sb/health            — basic liveness (always 200 if process is up)
 */
public final class ControlApi {

    private static final int EVENT_BUFFER = 200;

    private final LocalViewCollector collector;
    private final PartitionSimulator simulator;
    private final Deque<SplitBrainEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private volatile SplitBrainDetector.Disagreement currentState = SplitBrainDetector.Disagreement.NONE;

    public ControlApi(LocalViewCollector collector, PartitionSimulator simulator) {
        this.collector = collector;
        this.simulator = simulator;
    }

    public void recordEvent(SplitBrainEvent e) {
        recentEvents.addFirst(e);
        while (recentEvents.size() > EVENT_BUFFER) recentEvents.pollLast();
    }

    public void updateState(SplitBrainDetector.Disagreement s) {
        this.currentState = s;
    }

    public Router mount(Vertx vertx) {
        Router r = Router.router(vertx);
        r.route().handler(BodyHandler.create());

        r.get("/sb/cluster/view").handler(ctx ->
                ctx.json(collector.capture().toJson()));

        r.get("/sb/status").handler(ctx ->
                ctx.json(new JsonObject()
                        .put("nodeId", collector.selfNodeId())
                        .put("disagreementPresent", currentState.present())
                        .put("partitionCount", currentState.partitionCount())
                        .put("since", currentState.since().toString())
                        .put("simulationEnabled", simulator.enabled())
                        .put("recentEventCount", recentEvents.size())));

        r.get("/sb/events").handler(ctx -> {
            io.vertx.core.json.JsonArray arr = new io.vertx.core.json.JsonArray();
            recentEvents.forEach(e -> arr.add(e.toJson()));
            ctx.json(new JsonObject().put("events", arr));
        });

        r.post("/sb/simulate/block").handler(ctx -> {
            try {
                String peer = ctx.body().asJsonObject().getString("peer");
                simulator.softBlock(peer);
                ctx.json(new JsonObject().put("ok", true).put("blocked", peer));
            } catch (Exception e) { ctx.fail(400, e); }
        });

        r.post("/sb/simulate/unblock").handler(ctx -> {
            try {
                String peer = ctx.body().asJsonObject().getString("peer");
                simulator.softUnblock(peer);
                ctx.json(new JsonObject().put("ok", true).put("unblocked", peer));
            } catch (Exception e) { ctx.fail(400, e); }
        });

        r.post("/sb/simulate/clear").handler(ctx -> {
            simulator.clearAll();
            ctx.json(new JsonObject().put("ok", true));
        });

        r.get("/sb/health").handler(ctx ->
                ctx.json(new JsonObject().put("status", "UP")));

        return r;
    }
}
