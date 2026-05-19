package com.splitbrain.core;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.splitbrain.alert.Alerter;
import com.splitbrain.api.ControlApi;
import com.splitbrain.detect.*;
import com.splitbrain.model.ClusterView;
import com.splitbrain.simulate.PartitionSimulator;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point. Standalone mode: launches its own Vert.x with Hazelcast cluster manager
 * and runs the detection loop on a configurable interval.
 *
 * Embed mode: if you'd rather call this from your existing service, just construct
 * SplitBrainDetector + PeerViewFetcher + LocalViewCollector yourself and reuse your
 * HazelcastInstance (the one Vert.x clustering already creates).
 *
 * Config via environment variables:
 *   SB_PEERS                comma-separated peer endpoints (host:port[,host:port])
 *   SB_DETECT_INTERVAL_MS   how often to run the detector       (default 5000)
 *   SB_GRACE_WINDOW_MS      how long to wait before WARNing     (default 30000)
 *   SB_HTTP_PORT            REST/management port                (default 8080)
 *   SB_WEBHOOK_URL          generic webhook (Slack/PagerDuty)
 *   SB_DT_URL               Dynatrace tenant base URL
 *   SB_DT_TOKEN             Dynatrace API token (events.ingest)
 *   SB_DT_ENTITY            Dynatrace entitySelector
 *   SB_SIMULATION_ENABLED   true to enable simulator endpoints  (default false)
 */
public class MainVerticle extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(MainVerticle.class);

    public static void main(String[] args) {
        HazelcastClusterManager clusterManager = new HazelcastClusterManager(buildHazelcastConfig());
        VertxOptions opts = new VertxOptions().setClusterManager(clusterManager);

        Vertx.clusteredVertx(opts).onSuccess(vertx ->
                vertx.deployVerticle(new MainVerticle())
                        .onSuccess(id -> LOG.info("✅ vertx-split-brain started, deployment {}", id))
                        .onFailure(err -> {
                            LOG.error("deploy failed", err);
                            System.exit(1);
                        })
        ).onFailure(err -> {
            LOG.error("cluster startup failed", err);
            System.exit(1);
        });
    }

    private static Config buildHazelcastConfig() {
        // The default vertx-hazelcast XML works fine for tests; in prod you'd point
        // at your existing cluster.xml. Doing it programmatically here keeps the demo
        // self-contained.
        Config c = new Config();
        c.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        c.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true)
                .setMembers(Arrays.asList(getEnv("SB_HZ_MEMBERS", "127.0.0.1").split(",")));
        c.setProperty("hazelcast.logging.type", "slf4j");
        return c;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        HazelcastInstance hz = Hazelcast.getAllHazelcastInstances().iterator().next();

        var collector  = new LocalViewCollector(hz);
        var fetcher    = new PeerViewFetcher(vertx, 2000);
        var detector   = new SplitBrainDetector(Duration.ofMillis(envLong("SB_GRACE_WINDOW_MS", 30_000)));
        var simulator  = new PartitionSimulator(vertx);

        HazelcastLifecycleWatcher.install(hz, vertx.eventBus());

        List<Alerter> sinks = new ArrayList<>();
        sinks.add(new Alerter.Log());
        String webhook = System.getenv("SB_WEBHOOK_URL");
        if (webhook != null && !webhook.isBlank()) sinks.add(new Alerter.Webhook(vertx, webhook));
        String dtUrl = System.getenv("SB_DT_URL");
        if (dtUrl != null && !dtUrl.isBlank()) {
            sinks.add(new Alerter.Dynatrace(vertx, dtUrl,
                    System.getenv("SB_DT_TOKEN"),
                    getEnv("SB_DT_ENTITY", "type(HOST)")));
        }
        Alerter alerter = new Alerter.Composite(sinks, Duration.ofMinutes(2));

        ControlApi api = new ControlApi(collector, simulator);

        int httpPort = (int) envLong("SB_HTTP_PORT", 8080);
        vertx.createHttpServer().requestHandler(api.mount(vertx))
                .listen(httpPort)
                .onSuccess(srv -> LOG.info("HTTP API listening on :{}", srv.actualPort()))
                .onFailure(startPromise::fail);

        List<String> peers = peersFromEnv(collector.selfNodeId(), httpPort);
        LOG.info("self={} peers={}", collector.selfNodeId(), peers);

        AtomicReference<SplitBrainDetector.Disagreement> state =
                new AtomicReference<>(SplitBrainDetector.Disagreement.NONE);

        long intervalMs = envLong("SB_DETECT_INTERVAL_MS", 5_000);
        vertx.setPeriodic(intervalMs, tick ->
                fetcher.fetchAll(peers).onSuccess(peerViews -> {
                    List<ClusterView> all = new ArrayList<>(peerViews);
                    all.add(collector.capture());
                    var result = detector.evaluate(all, state.get(), Instant.now());
                    state.set(result.currentDisagreement());
                    api.updateState(result.currentDisagreement());
                    result.event().ifPresent(ev -> {
                        api.recordEvent(ev);
                        alerter.send(ev);
                    });
                }).onFailure(err -> LOG.warn("detection cycle failed: {}", err.getMessage())));

        startPromise.complete();
    }

    private static List<String> peersFromEnv(String selfId, int defaultPort) {
        String raw = System.getenv("SB_PEERS");
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.contains(":") ? s : s + ":" + defaultPort)
                .toList();
    }

    private static String getEnv(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static long envLong(String k, long def) {
        try { return Long.parseLong(getEnv(k, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }
}
