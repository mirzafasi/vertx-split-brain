package com.splitbrain.simulate;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Two ways to simulate a split-brain, both gated behind SIMULATION_ENABLED=true
 * because doing this in prod would be career-ending.
 *
 *   1) Soft simulation: configure the detector to *pretend* certain peers are unreachable
 *      and lie about their membership. Tests the detector without touching the network.
 *      Use this in unit/integration tests of the detector itself.
 *
 *   2) Hard simulation: shell out to iptables to actually drop packets on Hazelcast's
 *      cluster port (default 5701) between the local container and a target peer.
 *      Use this in docker-compose / dedicated test clusters to validate the full pipeline.
 */
public final class PartitionSimulator {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionSimulator.class);

    private final Vertx vertx;
    private final boolean enabled;
    private final Set<String> softBlockedPeers = new CopyOnWriteArraySet<>();

    public PartitionSimulator(Vertx vertx) {
        this.vertx = vertx;
        this.enabled = "true".equalsIgnoreCase(System.getenv("SB_SIMULATION_ENABLED"));
        if (enabled) {
            LOG.warn("⚠ Simulation enabled. Do NOT run this configuration in production.");
        }
    }

    public boolean enabled() { return enabled; }

    // ---- Soft simulation -------------------------------------------------

    public void softBlock(String peerEndpoint) {
        require();
        softBlockedPeers.add(peerEndpoint);
        LOG.warn("soft-blocking peer {}", peerEndpoint);
    }

    public void softUnblock(String peerEndpoint) {
        softBlockedPeers.remove(peerEndpoint);
    }

    public boolean isSoftBlocked(String peerEndpoint) {
        return softBlockedPeers.contains(peerEndpoint);
    }

    public void clearAll() {
        softBlockedPeers.clear();
    }

    // ---- Hard simulation (iptables) -------------------------------------

    /**
     * Drop all traffic on Hazelcast cluster port to the given target IP.
     * Requires NET_ADMIN capability on the container.
     */
    public Future<Void> partitionFromPeer(String targetIp, int hazelcastPort) {
        require();
        String cmd = String.format(
                "iptables -A INPUT -p tcp -s %s --dport %d -j DROP && " +
                "iptables -A OUTPUT -p tcp -d %s --dport %d -j DROP",
                targetIp, hazelcastPort, targetIp, hazelcastPort);
        return runShell(cmd, "partition from " + targetIp);
    }

    public Future<Void> healPartitionFromPeer(String targetIp, int hazelcastPort) {
        require();
        // -D instead of -A; matches must be identical to -A invocation.
        String cmd = String.format(
                "iptables -D INPUT -p tcp -s %s --dport %d -j DROP; " +
                "iptables -D OUTPUT -p tcp -d %s --dport %d -j DROP",
                targetIp, hazelcastPort, targetIp, hazelcastPort);
        return runShell(cmd, "heal from " + targetIp);
    }

    public Future<Void> flushAllRules() {
        require();
        return runShell("iptables -F", "flush iptables");
    }

    private Future<Void> runShell(String cmd, String label) {
        return vertx.executeBlocking(() -> {
            LOG.warn("[SIMULATOR] {} -> {}", label, cmd);
            try {
                Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
                int rc = p.waitFor();
                String out = new String(p.getInputStream().readAllBytes());
                if (rc != 0) {
                    throw new RuntimeException("shell exit " + rc + ": " + out);
                }
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void require() {
        if (!enabled) {
            throw new IllegalStateException("Simulation not enabled (set SB_SIMULATION_ENABLED=true)");
        }
    }
}
