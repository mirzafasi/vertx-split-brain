package com.splitbrain.alert;

import com.splitbrain.model.SplitBrainEvent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public interface Alerter {
    Future<Void> send(SplitBrainEvent event);

    /**
     * Composite alerter that fans out to multiple sinks and de-dupes within a window.
     * Without dedup, a 5-second detection loop on a 10-minute split-brain produces 120 alerts.
     */
    final class Composite implements Alerter {
        private static final Logger LOG = LoggerFactory.getLogger(Composite.class);
        private final List<Alerter> sinks;
        private final Duration dedupWindow;
        private final ConcurrentHashMap<String, Instant> lastSent = new ConcurrentHashMap<>();

        public Composite(List<Alerter> sinks, Duration dedupWindow) {
            this.sinks = sinks;
            this.dedupWindow = dedupWindow;
        }

        @Override
        public Future<Void> send(SplitBrainEvent event) {
            String key = event.severity() + ":" + event.partitions().size();
            Instant last = lastSent.get(key);
            if (last != null && Duration.between(last, Instant.now()).compareTo(dedupWindow) < 0) {
                LOG.debug("dedup: skipping {} (last sent {}s ago)", key, Duration.between(last, Instant.now()).toSeconds());
                return Future.succeededFuture();
            }
            lastSent.put(key, Instant.now());
            return Future.all(sinks.stream().map(s -> s.send(event)).toList()).mapEmpty();
        }
    }

    /** Always-on local logger. Cheap, useful in audit trails. */
    final class Log implements Alerter {
        private static final Logger LOG = LoggerFactory.getLogger("SPLIT_BRAIN_ALERT");
        @Override
        public Future<Void> send(SplitBrainEvent event) {
            switch (event.severity()) {
                case CRITICAL -> LOG.error("🔥 {}", event.toJson().encode());
                case WARN     -> LOG.warn ("⚠  {}", event.toJson().encode());
                case INFO     -> LOG.info ("ℹ  {}", event.toJson().encode());
            }
            return Future.succeededFuture();
        }
    }

    /** Generic webhook (Slack, PagerDuty, OpsGenie, custom). */
    final class Webhook implements Alerter {
        private static final Logger LOG = LoggerFactory.getLogger(Webhook.class);
        private final WebClient client;
        private final String url;

        public Webhook(Vertx vertx, String url) {
            this.client = WebClient.create(vertx);
            this.url = url;
        }

        @Override
        public Future<Void> send(SplitBrainEvent event) {
            return client.postAbs(url)
                    .putHeader("Content-Type", "application/json")
                    .sendJsonObject(event.toJson())
                    .onFailure(e -> LOG.warn("webhook {} failed: {}", url, e.getMessage()))
                    .mapEmpty();
        }
    }

    /**
     * Dynatrace event ingest. Sends a CUSTOM_ALERT event so it shows up in the
     * problem feed and can trigger pre-configured notifications.
     *
     * Endpoint: POST {dt-base}/api/v2/events/ingest
     * Token needs scope: events.ingest
     */
    final class Dynatrace implements Alerter {
        private static final Logger LOG = LoggerFactory.getLogger(Dynatrace.class);
        private final WebClient client;
        private final String baseUrl;
        private final String apiToken;
        private final String entitySelector; // e.g. "type(HOST),tag(env:prod)" or specific entityId

        public Dynatrace(Vertx vertx, String baseUrl, String apiToken, String entitySelector) {
            this.client = WebClient.create(vertx);
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.apiToken = apiToken;
            this.entitySelector = entitySelector;
        }

        @Override
        public Future<Void> send(SplitBrainEvent event) {
            String eventType = switch (event.severity()) {
                case CRITICAL -> "ERROR_EVENT";
                case WARN     -> "AVAILABILITY_EVENT";
                case INFO     -> "CUSTOM_INFO";
            };
            JsonObject payload = new JsonObject()
                    .put("eventType", eventType)
                    .put("title", "Vert.x cluster split-brain (" + event.severity() + ")")
                    .put("entitySelector", entitySelector)
                    .put("properties", new JsonObject()
                            .put("reason", event.reason())
                            .put("partitionCount", event.partitions().size())
                            .put("durationMillis", event.durationMillis())
                            .put("detectedAt", event.detectedAt().toString())
                            .put("source", "vertx-split-brain-detector"));

            return client.postAbs(baseUrl + "/api/v2/events/ingest")
                    .putHeader("Authorization", "Api-Token " + apiToken)
                    .putHeader("Content-Type", "application/json")
                    .sendJsonObject(payload)
                    .onFailure(e -> LOG.warn("dynatrace ingest failed: {}", e.getMessage()))
                    .mapEmpty();
        }
    }
}
