package com.splitbrain.detect;

import com.hazelcast.cluster.MembershipEvent;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hooks into Hazelcast's own lifecycle/membership events as a second source of truth.
 * Hazelcast fires MERGING/MERGED when it recovers from a split-brain — that's an
 * after-the-fact confirmation, but invaluable for forensics ("yes it happened, between T1 and T2").
 *
 * Events are republished on the local event bus so the rest of the tool can react.
 */
public final class HazelcastLifecycleWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(HazelcastLifecycleWatcher.class);
    public static final String EB_ADDR = "split-brain.hz.lifecycle";

    public static void install(HazelcastInstance hz, EventBus eb) {
        hz.getLifecycleService().addLifecycleListener(evt -> {
            LOG.info("hazelcast lifecycle: {}", evt.getState());
            JsonObject msg = new JsonObject()
                    .put("type", "lifecycle")
                    .put("state", evt.getState().name())
                    .put("timestamp", System.currentTimeMillis());
            eb.publish(EB_ADDR, msg);

            if (evt.getState() == LifecycleEvent.LifecycleState.MERGING
                    || evt.getState() == LifecycleEvent.LifecycleState.MERGED) {
                LOG.warn("⚠ Hazelcast reports {} — this is forensic confirmation of a prior split-brain.",
                        evt.getState());
            }
        });

        hz.getCluster().addMembershipListener(new MembershipListener() {
            @Override public void memberAdded(MembershipEvent e) { publish(eb, "memberAdded", e); }
            @Override public void memberRemoved(MembershipEvent e) { publish(eb, "memberRemoved", e); }
        });
    }

    private static void publish(EventBus eb, String type, MembershipEvent e) {
        eb.publish(EB_ADDR, new JsonObject()
                .put("type", type)
                .put("member", e.getMember().getUuid().toString())
                .put("address", e.getMember().getAddress().toString())
                .put("clusterSize", e.getMembers().size())
                .put("timestamp", System.currentTimeMillis()));
    }
}
