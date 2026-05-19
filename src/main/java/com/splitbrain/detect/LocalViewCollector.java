package com.splitbrain.detect;

import com.hazelcast.cluster.Member;
import com.hazelcast.core.HazelcastInstance;
import com.splitbrain.model.ClusterView;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Captures what THIS node currently sees as the cluster.
 * This is intentionally cheap and side-effect free so it can run frequently.
 */
public final class LocalViewCollector {

    private final HazelcastInstance hz;
    private final String selfNodeId;

    public LocalViewCollector(HazelcastInstance hz) {
        this.hz = hz;
        this.selfNodeId = hz.getLocalEndpoint().getUuid().toString();
    }

    public ClusterView capture() {
        Set<String> visible = hz.getCluster().getMembers().stream()
                .map(Member::getUuid)
                .map(java.util.UUID::toString)
                .collect(Collectors.toUnmodifiableSet());

        Member local = hz.getCluster().getLocalMember();
        String addr = local.getAddress().getHost() + ":" + local.getAddress().getPort();

        long version = hz.getCluster().getClusterState().ordinal(); // proxy; HZ exposes more via Mgmt
        return new ClusterView(selfNodeId, addr, visible, version, Instant.now());
    }

    public String selfNodeId() {
        return selfNodeId;
    }
}
