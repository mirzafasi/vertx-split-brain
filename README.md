# vertx-split-brain

[![CI](https://github.com/mirzafasi/vertx-split-brain/actions/workflows/ci.yml/badge.svg)](https://github.com/mirzafasi/vertx-split-brain/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/java-17%2B-orange)](https://adoptium.net/)
[![Vert.x 4.5](https://img.shields.io/badge/vert.x-4.5-purple)](https://vertx.io)

> **Detect, alert on, and safely simulate split-brain in Vert.x clusters that use Hazelcast.**

When a Vert.x cluster split-brains — usually because something disrupts Hazelcast's gossip port while leaving the rest of the application reachable — the event bus silently delivers messages only within each partition. Duplicate processing, lost messages, and inconsistent state follow. Hazelcast handles the *merge* (via `LifecycleEvent.MERGED`) but doesn't actively warn you while the split is happening.

This is a small, focused utility that fills that gap.

---

## What it does

| | Component | Role |
|---|---|---|
| 🔎 | **Detect** | Two independent signals: HTTP-based out-of-band view collection + Hazelcast lifecycle events |
| 🚨 | **Alert** | Log, generic webhook (Slack/PagerDuty), or Dynatrace; with severity tiers and dedup |
| 🧪 | **Simulate** | Safely cause a split-brain in a docker-compose cluster to test your handlers |

## The detection idea

The trick that makes this work: when split-brain happens in practice, the most common cause is the Hazelcast cluster port (5701) being blocked while HTTP between pods still works — a tightened NetworkPolicy, a misapplied firewall rule, a flaky cross-AZ link. So **HTTP is the out-of-band channel**:

- Each node exposes `GET /sb/cluster/view` returning the Hazelcast member UUIDs it currently sees.
- The detection loop fetches views from all peers and groups them by what they see.
- If two nodes are both **reachable over HTTP** but **mutually invisible in the cluster manager** → confirmed split-brain.
- A grace window suppresses noise from rolling deploys and member churn.
- Hazelcast's own `MERGING`/`MERGED` lifecycle events are captured as forensic confirmation.

See [`SplitBrainDetector.evaluate()`](src/main/java/com/splitbrain/detect/SplitBrainDetector.java) — the algorithm is a union-find over the mutually-visible relation, ~50 lines.

## Quick start

```bash
git clone https://github.com/mirzafasi/vertx-split-brain
cd vertx-split-brain
mvn verify                        # build + run tests
docker compose up -d --build      # 4-node demo cluster

# Watch the cluster converge — should see all 4 nodes:
curl -s localhost:8081/sb/cluster/view | jq

# Cause a split-brain (blocks port 5701 between node1,2 and node3,4):
./scripts/partition.sh split

# Within ~10s you'll see CRITICAL alerts:
docker compose logs node1 | grep SPLIT_BRAIN_ALERT

# Inspect divergent views:
./scripts/partition.sh status

# Heal:
./scripts/partition.sh heal
```

## Integration modes

**Embedded** (recommended) — drop the `detect/` and `alert/` packages into your existing Vert.x service. Reuse the `HazelcastInstance` Vert.x already created. Most accurate view because it's the same JVM as the affected service.

```java
var collector = new LocalViewCollector(hazelcastInstance);
var fetcher   = new PeerViewFetcher(vertx, 2000);
var detector  = new SplitBrainDetector(Duration.ofSeconds(30));
var alerter   = new Alerter.Composite(
    List.of(new Alerter.Log(), new Alerter.Webhook(vertx, slackUrl)),
    Duration.ofMinutes(2)
);

vertx.setPeriodic(5_000, t ->
    fetcher.fetchAll(peerEndpoints).onSuccess(views -> {
        var all = new ArrayList<>(views);
        all.add(collector.capture());
        var result = detector.evaluate(all, state.get(), Instant.now());
        state.set(result.currentDisagreement());
        result.event().ifPresent(alerter::send);
    }));
```

**Standalone** — run the included jar/image as a sidecar or dedicated watcher pod. It joins the cluster and polls peers via HTTP.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SB_PEERS` | — | comma-separated `host:port` list of peers to poll |
| `SB_DETECT_INTERVAL_MS` | `5000` | detection loop cadence |
| `SB_GRACE_WINDOW_MS` | `30000` | how long disagreement must persist before WARN |
| `SB_HTTP_PORT` | `8080` | REST port |
| `SB_WEBHOOK_URL` | — | Slack/PagerDuty/OpsGenie endpoint |
| `SB_DT_URL` | — | Dynatrace tenant base URL |
| `SB_DT_TOKEN` | — | Dynatrace API token (`events.ingest` scope) |
| `SB_DT_ENTITY` | `type(HOST)` | Dynatrace entitySelector |
| `SB_SIMULATION_ENABLED` | `false` | enables simulator endpoints + iptables ops |

## REST endpoints

| Method | Path | What |
| --- | --- | --- |
| GET | `/sb/cluster/view` | this node's view of cluster membership |
| GET | `/sb/status` | current detector state |
| GET | `/sb/events` | last 200 detection events |
| GET | `/sb/health` | basic liveness |
| POST | `/sb/simulate/block` | soft-block a peer *(sim only)* |
| POST | `/sb/simulate/unblock` | soft-unblock a peer *(sim only)* |
| POST | `/sb/simulate/clear` | clear all sim state *(sim only)* |

## Severity tiers

| Severity | Meaning | Alerted? |
| --- | --- | --- |
| `INFO` | Single transient disagreement (one node lagging) | Logged only, never re-emitted while condition persists |
| `WARN` | Disagreement persisting past grace window, possibly asymmetric partition or stuck merge | Yes |
| `CRITICAL` | Confirmed: ≥2 disjoint groups of nodes mutually invisible | Yes |

The composite alerter deduplicates by `(severity, partitionCount)` within a 2-minute window — a 10-minute incident produces a handful of alerts, not 120.

## Production notes

- **Run the detector embedded in your existing service** when possible. Same JVM as the affected cluster → most accurate view.
- **Restrict `/sb/simulate/*` endpoints** with a reverse proxy or auth filter in any non-test environment.
- **Never** set `SB_SIMULATION_ENABLED=true` outside dedicated test clusters. The simulator refuses to act without the flag, but defense in depth.
- In Kubernetes: use a headless service + DNS so each pod is independently addressable for HTTP view collection.
- For Dynatrace, create a dedicated token with only `events.ingest` scope.

## What this tool does NOT do

- **Doesn't resolve split-brain.** That's Hazelcast's job — configure `MergePolicy` (`PutIfAbsentMergePolicy`, `LatestUpdateMergePolicy`, or custom) separately.
- **Doesn't prevent message loss during the partition.** Application code must be idempotent or use external dedup.
- **Doesn't detect totally network-isolated partitions** (where HTTP is also dead). In that case Hazelcast's `MERGED` is the only signal — captured forensically by `HazelcastLifecycleWatcher`.

## Other cluster managers

The detector and HTTP plumbing are reusable for Infinispan, Apache Ignite, and Zookeeper-based clustering. Only `LocalViewCollector` and `HazelcastLifecycleWatcher` are Hazelcast-specific. PRs welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## File map

```
src/main/java/com/splitbrain/
├── core/MainVerticle.java              # wires it all together
├── detect/
│   ├── LocalViewCollector.java         # what THIS node sees
│   ├── PeerViewFetcher.java            # HTTP out-of-band collection
│   ├── SplitBrainDetector.java         # the algorithm
│   └── HazelcastLifecycleWatcher.java  # MERGING/MERGED events
├── simulate/PartitionSimulator.java    # soft + iptables-based
├── alert/Alerter.java                  # Log, Webhook, Dynatrace, Composite
├── api/ControlApi.java                 # REST endpoints
└── model/                              # ClusterView, SplitBrainEvent
src/test/java/com/splitbrain/detect/
└── SplitBrainDetectorTest.java         # algorithm tests
scripts/partition.sh                    # docker-compose split/heal/status
docker-compose.yml                      # 4-node demo cluster
```

## Contributing

Bug reports, feature ideas, and PRs welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). For security issues, see [SECURITY.md](SECURITY.md).

## License

Apache 2.0. See [LICENSE](LICENSE).
