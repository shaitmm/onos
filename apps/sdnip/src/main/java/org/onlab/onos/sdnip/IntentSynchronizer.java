/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onlab.onos.sdnip;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.onlab.onos.core.ApplicationId;
import org.onlab.onos.net.flow.criteria.Criteria.IPCriterion;
import org.onlab.onos.net.flow.criteria.Criterion;
import org.onlab.onos.net.intent.Intent;
import org.onlab.onos.net.intent.IntentOperations;
import org.onlab.onos.net.intent.IntentService;
import org.onlab.onos.net.intent.IntentState;
import org.onlab.onos.net.intent.MultiPointToSinglePointIntent;
import org.onlab.onos.net.intent.PointToPointIntent;
import org.onlab.packet.Ip4Prefix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import static com.google.common.base.Preconditions.checkArgument;

public class IntentSynchronizer {
    private static final Logger log =
        LoggerFactory.getLogger(IntentSynchronizer.class);

    private final ApplicationId appId;
    private final IntentService intentService;
    private final Map<IntentKey, PointToPointIntent> peerIntents;
    private final Map<Ip4Prefix, MultiPointToSinglePointIntent> routeIntents;

    //
    // State to deal with SDN-IP Leader election and pushing Intents
    //
    private final ExecutorService bgpIntentsSynchronizerExecutor;
    private final Semaphore intentsSynchronizerSemaphore = new Semaphore(0);
    private volatile boolean isElectedLeader = false;
    private volatile boolean isActivatedLeader = false;

    /**
     * Class constructor.
     *
     * @param appId the Application ID
     * @param intentService the intent service
     */
    IntentSynchronizer(ApplicationId appId, IntentService intentService) {
        this.appId = appId;
        this.intentService = intentService;
        peerIntents = new ConcurrentHashMap<>();
        routeIntents = new ConcurrentHashMap<>();

        bgpIntentsSynchronizerExecutor = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder()
                .setNameFormat("sdnip-intents-synchronizer-%d").build());
    }

    /**
     * Starts the synchronizer.
     */
    public void start() {
        bgpIntentsSynchronizerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                doIntentSynchronizationThread();
            }
        });
    }

    /**
     * Stops the synchronizer.
     */
    public void stop() {
        synchronized (this) {
            // Stop the thread(s)
            bgpIntentsSynchronizerExecutor.shutdownNow();

            //
            // Withdraw all SDN-IP intents
            //
            if (!isElectedLeader) {
                return;         // Nothing to do: not the leader anymore
            }

            //
            // NOTE: We don't withdraw the intents during shutdown, because
            // it creates flux in the data plane during switchover.
            //

            /*
            //
            // Build a batch operation to withdraw all intents from this
            // application.
            //
            log.debug("SDN-IP Intent Synchronizer shutdown: " +
                      "withdrawing all intents...");
            IntentOperations.Builder builder = IntentOperations.builder();
            for (Intent intent : intentService.getIntents()) {
                // Skip the intents from other applications
                if (!intent.appId().equals(appId)) {
                    continue;
                }

                // Skip the intents that are already withdrawn
                IntentState intentState =
                    intentService.getIntentState(intent.id());
                if ((intentState == null) ||
                    intentState.equals(IntentState.WITHDRAWING) ||
                    intentState.equals(IntentState.WITHDRAWN)) {
                    continue;
                }

                log.debug("SDN-IP Intent Synchronizer withdrawing intent: {}",
                          intent);
                builder.addWithdrawOperation(intent.id());
            }
            IntentOperations intentOperations = builder.build();
            intentService.execute(intentOperations);
            leaderChanged(false);

            peerIntents.clear();
            routeIntents.clear();
            log.debug("SDN-IP Intent Synchronizer shutdown completed");
            */
        }
    }

    public void leaderChanged(boolean isLeader) {
        log.debug("SDN-IP Leader changed: {}", isLeader);

        if (!isLeader) {
            this.isElectedLeader = false;
            this.isActivatedLeader = false;
            return;                     // Nothing to do
        }
        this.isActivatedLeader = false;
        this.isElectedLeader = true;

        //
        // Tell the Intents Synchronizer thread to start the synchronization
        //
        intentsSynchronizerSemaphore.release();
    }

    /**
     * Gets the route intents.
     *
     * @return the route intents
     */
    public Collection<MultiPointToSinglePointIntent> getRouteIntents() {
        List<MultiPointToSinglePointIntent> result = new LinkedList<>();

        for (Map.Entry<Ip4Prefix, MultiPointToSinglePointIntent> entry :
            routeIntents.entrySet()) {
            result.add(entry.getValue());
        }
        return result;
    }

    /**
     * Thread for Intent Synchronization.
     */
    private void doIntentSynchronizationThread() {
        boolean interrupted = false;
        try {
            while (!interrupted) {
                try {
                    intentsSynchronizerSemaphore.acquire();
                    //
                    // Drain all permits, because a single synchronization is
                    // sufficient.
                    //
                    intentsSynchronizerSemaphore.drainPermits();
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
                synchronizeIntents();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Submits a collection of point-to-point intents.
     *
     * @param intents the intents to submit
     */
    void submitPeerIntents(Collection<PointToPointIntent> intents) {
        synchronized (this) {
            // Store the intents in memory
            for (PointToPointIntent intent : intents) {
                peerIntents.put(new IntentKey(intent), intent);
            }

            // Push the intents
            if (isElectedLeader && isActivatedLeader) {
                log.debug("SDN-IP Submitting all Peer Intents...");
                IntentOperations.Builder builder = IntentOperations.builder();
                for (Intent intent : intents) {
                    builder.addSubmitOperation(intent);
                }
                IntentOperations intentOperations = builder.build();
                log.debug("SDN-IP Submitting intents: {}",
                          intentOperations.operations());
                intentService.execute(intentOperations);
            }
        }
    }

    /**
     * Submits a multi-point-to-single-point intent.
     *
     * @param prefix the IPv4 matching prefix for the intent to submit
     * @param intent the intent to submit
     */
    void submitRouteIntent(Ip4Prefix prefix,
                           MultiPointToSinglePointIntent intent) {
        synchronized (this) {
            MultiPointToSinglePointIntent oldIntent =
                routeIntents.put(prefix, intent);

            if (isElectedLeader && isActivatedLeader) {
                if (oldIntent != null) {
                    //
                    // TODO: Short-term solution to explicitly withdraw
                    // instead of using "replace" operation.
                    //
                    log.debug("SDN-IP Withdrawing old intent: {}", oldIntent);
                    intentService.withdraw(oldIntent);
                }
                log.debug("SDN-IP Submitting intent: {}", intent);
                intentService.submit(intent);
            }
        }
    }

    /**
     * Withdraws a multi-point-to-single-point intent.
     *
     * @param prefix the IPv4 matching prefix for the intent to withdraw.
     */
    void withdrawRouteIntent(Ip4Prefix prefix) {
        synchronized (this) {
            MultiPointToSinglePointIntent intent =
                routeIntents.remove(prefix);

            if (intent == null) {
                log.debug("SDN-IP no intent in routeIntents to delete for " +
                          "prefix: {}", prefix);
                return;
            }

            if (isElectedLeader && isActivatedLeader) {
                log.debug("SDN-IP Withdrawing intent: {}", intent);
                intentService.withdraw(intent);
            }
        }
    }

    /**
     * Synchronize the in-memory Intents with the Intents in the Intent
     * framework.
     */
    void synchronizeIntents() {
        synchronized (this) {

            Map<IntentKey, Intent> localIntents = new HashMap<>();
            Map<IntentKey, Intent> fetchedIntents = new HashMap<>();
            Collection<Intent> storeInMemoryIntents = new LinkedList<>();
            Collection<Intent> addIntents = new LinkedList<>();
            Collection<Intent> deleteIntents = new LinkedList<>();
            IntentOperations intentOperations;

            if (!isElectedLeader) {
                return;         // Nothing to do: not the leader anymore
            }
            log.debug("SDN-IP synchronizing all intents...");

            // Prepare the local intents
            for (Intent intent : routeIntents.values()) {
                localIntents.put(new IntentKey(intent), intent);
            }
            for (Intent intent : peerIntents.values()) {
                localIntents.put(new IntentKey(intent), intent);
            }

            // Fetch all intents for this application
            for (Intent intent : intentService.getIntents()) {
                if (!intent.appId().equals(appId)) {
                    continue;
                }
                fetchedIntents.put(new IntentKey(intent), intent);
            }
            if (log.isDebugEnabled()) {
                for (Intent intent: fetchedIntents.values()) {
                    log.debug("SDN-IP Intent Synchronizer: fetched intent: {}",
                              intent);
                }
            }

            computeIntentsDelta(localIntents, fetchedIntents,
                                storeInMemoryIntents, addIntents,
                                deleteIntents);

            //
            // Perform the actions:
            // 1. Store in memory fetched intents that are same. Can be done
            //    even if we are not the leader anymore
            // 2. Delete intents: check if the leader before the operation
            // 3. Add intents: check if the leader before the operation
            //
            for (Intent intent : storeInMemoryIntents) {
                // Store the intent in memory based on its type
                if (intent instanceof MultiPointToSinglePointIntent) {
                    MultiPointToSinglePointIntent mp2pIntent =
                        (MultiPointToSinglePointIntent) intent;
                    // Find the IP prefix
                    Criterion c =
                        mp2pIntent.selector().getCriterion(Criterion.Type.IPV4_DST);
                    if (c != null && c instanceof IPCriterion) {
                        IPCriterion ipCriterion = (IPCriterion) c;
                        Ip4Prefix ip4Prefix = ipCriterion.ip().getIp4Prefix();
                        if (ip4Prefix == null) {
                            // TODO: For now we support only IPv4
                            continue;
                        }
                        log.debug("SDN-IP Intent Synchronizer: updating " +
                                  "in-memory Route Intent for prefix {}",
                                  ip4Prefix);
                        routeIntents.put(ip4Prefix, mp2pIntent);
                    } else {
                        log.warn("SDN-IP no IPV4_DST criterion found for Intent {}",
                                 mp2pIntent.id());
                    }
                    continue;
                }
                if (intent instanceof PointToPointIntent) {
                    PointToPointIntent p2pIntent = (PointToPointIntent) intent;
                    log.debug("SDN-IP Intent Synchronizer: updating " +
                              "in-memory Peer Intent {}", p2pIntent);
                    peerIntents.put(new IntentKey(intent), p2pIntent);
                    continue;
                }
            }

            // Withdraw Intents
            IntentOperations.Builder builder = IntentOperations.builder();
            for (Intent intent : deleteIntents) {
                builder.addWithdrawOperation(intent.id());
                log.debug("SDN-IP Intent Synchronizer: withdrawing intent: {}",
                      intent);
            }
            if (!isElectedLeader) {
                log.debug("SDN-IP Intent Synchronizer: cannot withdraw intents: " +
                          "not elected leader anymore");
                isActivatedLeader = false;
                return;
            }
            intentOperations = builder.build();
            intentService.execute(intentOperations);

            // Add Intents
            builder = IntentOperations.builder();
            for (Intent intent : addIntents) {
                builder.addSubmitOperation(intent);
                log.debug("SDN-IP Intent Synchronizer: submitting intent: {}",
                          intent);
            }
            if (!isElectedLeader) {
                log.debug("SDN-IP Intent Synchronizer: cannot submit intents: " +
                          "not elected leader anymore");
                isActivatedLeader = false;
                return;
            }
            intentOperations = builder.build();
            intentService.execute(intentOperations);

            if (isElectedLeader) {
                isActivatedLeader = true;       // Allow push of Intents
            } else {
                isActivatedLeader = false;
            }
            log.debug("SDN-IP intent synchronization completed");
        }
    }

    /**
     * Computes the delta in two sets of Intents: local in-memory Intents,
     * and intents fetched from the Intent framework.
     *
     * @param localIntents the local in-memory Intents
     * @param fetchedIntents the Intents fetched from the Intent framework
     * @param storeInMemoryIntents the Intents that should be stored in memory.
     * Note: This Collection must be allocated by the caller, and it will
     * be populated by this method.
     * @param addIntents the Intents that should be added to the Intent
     * framework. Note: This Collection must be allocated by the caller, and
     * it will be populated by this method.
     * @param deleteIntents the Intents that should be deleted from the Intent
     * framework. Note: This Collection must be allocated by the caller, and
     * it will be populated by this method.
     */
    private void computeIntentsDelta(
                                final Map<IntentKey, Intent> localIntents,
                                final Map<IntentKey, Intent> fetchedIntents,
                                Collection<Intent> storeInMemoryIntents,
                                Collection<Intent> addIntents,
                                Collection<Intent> deleteIntents) {

        //
        // Compute the deltas between the LOCAL in-memory Intents and the
        // FETCHED Intents:
        //  - If an Intent is in both the LOCAL and FETCHED sets:
        //    If the FETCHED Intent is WITHDRAWING or WITHDRAWN, then
        //    the LOCAL Intent should be added/installed; otherwise the
        //    FETCHED intent should be stored in the local memory
        //    (i.e., override the LOCAL Intent) to preserve the original
        //    Intent ID.
        //  - if a LOCAL Intent is not in the FETCHED set, then the LOCAL
        //    Intent should be added/installed.
        //  - If a FETCHED Intent is not in the LOCAL set, then the FETCHED
        //    Intent should be deleted/withdrawn.
        //
        for (Map.Entry<IntentKey, Intent> entry : localIntents.entrySet()) {
            IntentKey intentKey = entry.getKey();
            Intent localIntent = entry.getValue();
            Intent fetchedIntent = fetchedIntents.get(intentKey);

            if (fetchedIntent == null) {
                //
                // No FETCHED Intent found: push the LOCAL Intent.
                //
                addIntents.add(localIntent);
                continue;
            }

            IntentState state =
                intentService.getIntentState(fetchedIntent.id());
            if (state == null ||
                state == IntentState.WITHDRAWING ||
                state == IntentState.WITHDRAWN) {
                // The intent has been withdrawn but according to our route
                // table it should be installed. We'll reinstall it.
                addIntents.add(localIntent);
                continue;
            }
            storeInMemoryIntents.add(fetchedIntent);
        }

        for (Map.Entry<IntentKey, Intent> entry : fetchedIntents.entrySet()) {
            IntentKey intentKey = entry.getKey();
            Intent fetchedIntent = entry.getValue();
            Intent localIntent = localIntents.get(intentKey);

            if (localIntent != null) {
                continue;
            }

            IntentState state =
                intentService.getIntentState(fetchedIntent.id());
            if (state == null ||
                state == IntentState.WITHDRAWING ||
                state == IntentState.WITHDRAWN) {
                // Nothing to do. The intent has been already withdrawn.
                continue;
            }
            //
            // No LOCAL Intent found: delete/withdraw the FETCHED Intent.
            //
            deleteIntents.add(fetchedIntent);
        }
    }

    /**
     * Helper class that can be used to compute the key for an Intent by
     * by excluding the Intent ID.
     */
    static final class IntentKey {
        private final Intent intent;

        /**
         * Constructor.
         *
         * @param intent the intent to use
         */
        IntentKey(Intent intent) {
            checkArgument((intent instanceof MultiPointToSinglePointIntent) ||
                          (intent instanceof PointToPointIntent),
                          "Intent type not recognized", intent);
            this.intent = intent;
        }

        /**
         * Compares two Multi-Point to Single-Point Intents whether they
         * represent same logical intention.
         *
         * @param intent1 the first Intent to compare
         * @param intent2 the second Intent to compare
         * @return true if both Intents represent same logical intention,
         * otherwise false
         */
        static boolean equalIntents(MultiPointToSinglePointIntent intent1,
                                    MultiPointToSinglePointIntent intent2) {
            return Objects.equals(intent1.appId(), intent2.appId()) &&
                Objects.equals(intent1.selector(), intent2.selector()) &&
                Objects.equals(intent1.treatment(), intent2.treatment()) &&
                Objects.equals(intent1.ingressPoints(), intent2.ingressPoints()) &&
                Objects.equals(intent1.egressPoint(), intent2.egressPoint());
        }

        /**
         * Compares two Point-to-Point Intents whether they represent
         * same logical intention.
         *
         * @param intent1 the first Intent to compare
         * @param intent2 the second Intent to compare
         * @return true if both Intents represent same logical intention,
         * otherwise false
         */
        static boolean equalIntents(PointToPointIntent intent1,
                                    PointToPointIntent intent2) {
            return Objects.equals(intent1.appId(), intent2.appId()) &&
                Objects.equals(intent1.selector(), intent2.selector()) &&
                Objects.equals(intent1.treatment(), intent2.treatment()) &&
                Objects.equals(intent1.ingressPoint(), intent2.ingressPoint()) &&
                Objects.equals(intent1.egressPoint(), intent2.egressPoint());
        }

        @Override
        public int hashCode() {
            if (intent instanceof PointToPointIntent) {
                PointToPointIntent p2pIntent = (PointToPointIntent) intent;
                return Objects.hash(p2pIntent.appId(),
                                    p2pIntent.resources(),
                                    p2pIntent.selector(),
                                    p2pIntent.treatment(),
                                    p2pIntent.constraints(),
                                    p2pIntent.ingressPoint(),
                                    p2pIntent.egressPoint());
            }
            if (intent instanceof MultiPointToSinglePointIntent) {
                MultiPointToSinglePointIntent m2pIntent =
                    (MultiPointToSinglePointIntent) intent;
                return Objects.hash(m2pIntent.appId(),
                                    m2pIntent.resources(),
                                    m2pIntent.selector(),
                                    m2pIntent.treatment(),
                                    m2pIntent.constraints(),
                                    m2pIntent.ingressPoints(),
                                    m2pIntent.egressPoint());
            }
            checkArgument(false, "Intent type not recognized", intent);
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if ((obj == null) || (!(obj instanceof IntentKey))) {
                return false;
            }
            IntentKey other = (IntentKey) obj;

            if (this.intent instanceof PointToPointIntent) {
                if (!(other.intent instanceof PointToPointIntent)) {
                    return false;
                }
                return equalIntents((PointToPointIntent) this.intent,
                                    (PointToPointIntent) other.intent);
            }
            if (this.intent instanceof MultiPointToSinglePointIntent) {
                if (!(other.intent instanceof MultiPointToSinglePointIntent)) {
                    return false;
                }
                return equalIntents(
                                (MultiPointToSinglePointIntent) this.intent,
                                (MultiPointToSinglePointIntent) other.intent);
            }
            checkArgument(false, "Intent type not recognized", intent);
            return false;
        }
    }
}
