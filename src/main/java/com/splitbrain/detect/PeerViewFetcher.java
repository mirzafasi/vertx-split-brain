package com.splitbrain.detect;

import com.splitbrain.model.ClusterView;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches cluster views from peer nodes via HTTP — *not* via the cluster manager.
 * This is the out-of-band channel that survives a Hazelcast-only partition
 * (the most common real-world split-brain trigger: someone tightens a NetworkPolicy
 * and accidentally blocks port 5701 while leaving 8080 open).
 *
 * Peers are typically discovered via Kubernetes headless service DNS or static config.
 */
public final class PeerViewFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(PeerViewFetcher.class);

    private final WebClient client;
    private final ConcurrentHashMap<String, ClusterView> cache = new ConcurrentHashMap<>();

    public PeerViewFetcher(Vertx vertx, int timeoutMillis) {
        this.client = WebClient.create(vertx, new WebClientOptions()
                .setConnectTimeout(timeoutMillis)
                .setIdleTimeout(timeoutMillis)
                .setKeepAlive(false));
    }

    /**
     * Fetch views from all known peer endpoints (e.g. "node-1.cluster.svc:8080").
     * Failures are tolerated — a peer we can't reach is itself a signal (recorded separately).
     */
    public Future<List<ClusterView>> fetchAll(List<String> peerEndpoints) {
        List<Future<Optional<ClusterView>>> futures = peerEndpoints.stream()
                .map(this::fetchOne)
                .toList();

        return Future.join(futures)
                .map(cf -> futures.stream()
                        .map(Future::result)
                        .flatMap(Optional::stream)
                        .toList());
    }

    private Future<Optional<ClusterView>> fetchOne(String endpoint) {
        String[] parts = endpoint.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 8080;

        return client.get(port, host, "/sb/cluster/view")
                .send()
                .map(resp -> {
                    if (resp.statusCode() == 200) {
                        ClusterView v = ClusterView.fromJson(resp.bodyAsJsonObject());
                        cache.put(endpoint, v);
                        return Optional.of(v);
                    }
                    LOG.warn("peer {} returned {}", endpoint, resp.statusCode());
                    return Optional.<ClusterView>empty();
                })
                .otherwise(err -> {
                    LOG.debug("peer {} unreachable: {}", endpoint, err.getMessage());
                    return Optional.empty();
                });
    }

    public ConcurrentHashMap<String, ClusterView> lastKnown() {
        return cache;
    }
}
