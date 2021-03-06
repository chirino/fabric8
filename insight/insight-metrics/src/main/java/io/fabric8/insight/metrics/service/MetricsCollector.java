/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.insight.metrics.service;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.api.*;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.common.util.JMXUtils;
import io.fabric8.groups.Group;
import io.fabric8.groups.GroupListener;
import io.fabric8.groups.NodeState;
import io.fabric8.groups.internal.TrackingZooKeeperGroup;
import io.fabric8.insight.metrics.model.*;
import io.fabric8.insight.metrics.service.support.JmxUtils;
import org.apache.felix.scr.annotations.*;
import org.apache.karaf.jaas.boot.principal.RolePrincipal;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.security.auth.Subject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.*;

import static io.fabric8.common.util.IOHelpers.loadFully;
import static io.fabric8.insight.metrics.model.MetricsJSON.parseJson;

/**
 * Collects all the charting metrics defined against its profiles
 */
@Component(immediate = true, metatype = false)
@Service({MetricsCollectorMBean.class})
public class MetricsCollector implements MetricsCollectorMBean {

    public static final String GRAPH_JSON = "io.fabric8.insight.metrics.json";

    public static final String QUERIES = "queries";
    public static final String NAME = "name";
    public static final String TEMPLATE = "template";
    public static final String METADATA = "metadata";
    public static final String LOCK = "lock";
    public static final String PERIOD = "period";
    public static final String MIN_PERIOD = "minPeriod";
    public static final String REQUESTS = "requests";
    public static final String OBJ = "obj";
    public static final String ATTRS = "attrs";
    public static final String OPER = "oper";
    public static final String ARGS = "args";
    public static final String SIG = "sig";
    public static final String DEFAULT = "default";
    public static final String LOCK_GLOBAL = "global";
    public static final String LOCK_HOST = "host";

    private static final transient Logger LOG = LoggerFactory.getLogger(MetricsCollector.class);

    private ObjectName objectName;

    @Reference
    private FabricService fabricService;

    private ScheduledThreadPoolExecutor executor;
    private Map<Query, QueryState> queries = new ConcurrentHashMap<Query, QueryState>();

    @Reference
    private MBeanServer mbeanServer;

    @Reference(name = "storage", referenceInterface = MetricsStorageService.class)
    private final ValidatingReference<MetricsStorageService> storage = new ValidatingReference<>();

    private int defaultDelay = 60;
    private int threadPoolSize = 5;
    private String type = "sta";
    private BundleContext bundleContext;

    static class QueryState {
        ScheduledFuture<?> future;
        Server server;
        Query query;
        QueryResult lastResult;
        boolean lastResultSent;
        long lastSent;
        Map metadata;
        Group<QueryNodeState> lock;

        public void close() {
            future.cancel(false);
            if (lock != null) {
                try {
                    lock.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    @Activate
    private void activate(BundleContext bundleContext) throws Exception {
        this.bundleContext = bundleContext;
        this.executor = new ScheduledThreadPoolExecutor(threadPoolSize);
        this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        this.executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                process();
            }
        }, 1, defaultDelay, TimeUnit.SECONDS);

        JMXUtils.registerMBean(this, mbeanServer, new ObjectName("io.fabric8.insight:type=MetricsCollector"));
    }

    @Deactivate
    private void deactivate() throws Exception {

        JMXUtils.unregisterMBean(mbeanServer, new ObjectName("io.fabric8.insight:type=MetricsCollector"));

        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Ignore
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Ignore
        }
        for (QueryState q : queries.values()) {
            q.close();
        }
    }

    private void bindStorage(MetricsStorageService storage) {
        this.storage.bind(storage);
    }

    private void unbindStorage(MetricsStorageService storage) {
        this.storage.unbind(storage);
    }

    static class QueryNodeState extends NodeState {
        @JsonProperty
        String[] services;

        QueryNodeState() {
        }

        QueryNodeState(String id, String container, String[] services) {
            super(id, container);
            this.services = services;
        }

    }

    public void setDefaultDelay(int defaultDelay) {
        this.defaultDelay = defaultDelay;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String getMetrics() {
        Map<String, Object> meta = new HashMap<String, Object>();
        for (Map.Entry<Query, QueryState> e : queries.entrySet()) {
            meta.put(e.getKey().getName(), e.getValue().metadata);
        }
        return MetricsJSON.toJson(meta);
    }

    public void process() {
        try {
            Container container = MetricsCollector.this.fabricService.getCurrentContainer();
            if (container != null) {
                Set<Query> newQueries = new HashSet<Query>();
                Profile[] profiles = container.getProfiles();
                if (profiles != null) {
                    for (Profile profile : profiles) {
                        loadProfile(profile, newQueries);
                    }
                }
                for (Query q : queries.keySet()) {
                    if (!newQueries.remove(q)) {
                        queries.remove(q).close();
                    }
                }
                Server server = new Server(container.getId());
                for (Query q : newQueries) {
                    final String queryName = q.getName();
                    final String containerName = container.getId();
                    final QueryState state = new QueryState();
                    state.server = server;
                    state.query = q;
                    if (q.getMetadata() != null) {
                        state.metadata = parseJson(loadFully(new URL(q.getMetadata())));
                    }

                    // Clustered stats ?

                    if (q.getLock() != null) {
                        state.lock = new TrackingZooKeeperGroup<>(bundleContext, getGroupPath(q), QueryNodeState.class);
                        state.lock.add(new GroupListener<QueryNodeState>() {
                            @Override
                            public void groupEvent(Group<QueryNodeState> group, GroupEvent event) {
                                try {
                                    state.lock.update(new QueryNodeState(queryName, containerName,
                                            state.lock.isMaster() ? new String[]{"stat"} : null));
                                } catch (IllegalStateException e) {
                                    // not joined ? ignore
                                }
                            }
                        });
                        state.lock.update(new QueryNodeState(queryName, containerName, null));
                        state.lock.start();
                    }

                    long delay = q.getPeriod() > 0 ? q.getPeriod() : defaultDelay;
                    state.future = this.executor.scheduleAtFixedRate(
                            new Task(state, storage),
                            Math.round(Math.random() * 1000) + 1,
                            delay * 1000,
                            TimeUnit.MILLISECONDS);
                    queries.put(q, state);
                }
            }
        } catch (RejectedExecutionException t) {
            // Ignore, the thread pool has been shut down
        } catch (Throwable t) {
            LOG.warn("Error while starting metrics", t);
        }
    }

    protected synchronized String getGroupPath(Query q) {
        if (LOCK_GLOBAL.equals(q.getLock())) {
            return "/fabric/registry/clusters/insight-metrics/global/" + q.getName();
        } else if (LOCK_HOST.equals(q.getLock())) {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Unable to retrieve host name", e);
            }
            return "/fabric/registry/clusters/insight-metrics/host-" + host + "/" + q.getName();
        } else {
            throw new IllegalArgumentException("Unknown lock type: " + q.getLock());
        }
    }

    protected void loadProfile(Profile profile, Set<Query> queries) {
        Map<String, byte[]> fileConfigurations = profile.getFileConfigurations();
        byte[] bytes = fileConfigurations.get(GRAPH_JSON);
        if (bytes != null && bytes.length > 0) {
            try {
                Map object = new ObjectMapper().readValue(bytes, Map.class);
                for (Map q : (List<Map>) object.get(QUERIES)) {
                    String name = (String) q.get(NAME);
                    String template = (String) q.get(TEMPLATE);
                    String metadata = (String) q.get(METADATA);
                    String lock = (String) q.get(LOCK);
                    int period = DEFAULT.equals(q.get(PERIOD)) ? defaultDelay : q.get(PERIOD) != null ? ((Number) q.get(PERIOD)).intValue() : defaultDelay;
                    int minPeriod = DEFAULT.equals(q.get(MIN_PERIOD)) ? defaultDelay : q.get(MIN_PERIOD) != null ? ((Number) q.get(MIN_PERIOD)).intValue() : period;
                    Set<Request> requests = new HashSet<Request>();
                    for (Map mb : (List<Map>) q.get(REQUESTS)) {
                        if (mb.containsKey(ATTRS)) {
                            String mname = (String) mb.get(NAME);
                            String mobj = (String) mb.get(OBJ);
                            List<String> mattrs = (List<String>) mb.get(ATTRS);
                            requests.add(new MBeanAttrs(mname, mobj, mattrs));
                        } else if (mb.containsKey(OPER)) {
                            String mname = (String) mb.get(NAME);
                            String mobj = (String) mb.get(OBJ);
                            String moper = (String) mb.get(OPER);
                            List<Object> margs = (List<Object>) mb.get(ARGS);
                            List<String> msig = (List<String>) mb.get(SIG);
                            requests.add(new MBeanOpers(mname, mobj, moper, margs, msig));
                        } else {
                            throw new IllegalArgumentException("Unknown request " + MetricsJSON.toJson(mb));
                        }
                    }
                    queries.add(new Query(name, requests, template, metadata, lock, period, minPeriod));
                }
            } catch (Throwable t) {
                LOG.warn("Unable to load queries from profile " + profile.getId(), t);
            }
        }
        ProfileService profileService = MetricsCollector.this.fabricService.adapt(ProfileService.class);
        Version version = profileService.getVersion(profile.getVersion());
        for (String parentId : profile.getParentIds()) {
            loadProfile(version.getRequiredProfile(parentId), queries);
        }
    }

    class Task implements Runnable {

        private final QueryState query;

        public Task(QueryState query, ValidatingReference<MetricsStorageService> storage) {
            this.query = query;
        }

        @Override
        public void run() {
            try {
                final MetricsStorageService svc = storage.get();
                // Abort if required services aren't available
                if (mbeanServer == null || svc == null) {
                    return;
                }
                // If there's a lock, check we are the master
                if (query.lock != null && !query.lock.isMaster()) {
                    return;
                }

                Subject subject = new Subject();
                subject.getPrincipals().add(new RolePrincipal("admin"));

                QueryResult qrs = Subject.doAs(subject, new PrivilegedAction<QueryResult>() {
                    @Override
                    public QueryResult run() {
                        try {
                            return JmxUtils.execute(query.server, query.query, mbeanServer);
                        } catch (Throwable e) {
                            LOG.error("Error retrieving metrics for " + query.query.getMetadata(), e);
                        }
                        return null;
                    }
                });

                if (qrs != null) {
                    boolean forceSend = query.query.getMinPeriod() == query.query.getPeriod() ||
                            qrs.getTimestamp().getTime() - query.lastSent >= TimeUnit.SECONDS.toMillis(query.query.getMinPeriod());
                    if (!forceSend && query.lastResult != null) {
                        if (qrs.getResults().equals(query.lastResult.getResults())) {
                            query.lastResult = qrs;
                            query.lastResultSent = false;
                        }
                        if (!query.lastResultSent) {
                            renderAndSend(svc, query.lastResult);
                        }
                    }
                    query.lastResult = qrs;
                    query.lastResultSent = true;
                    query.lastSent = qrs.getTimestamp().getTime();
                    renderAndSend(svc, qrs);
                }
            } catch (Throwable e) {
                LOG.error("Error sending metrics", e);
            }
        }

        private void renderAndSend(MetricsStorageService svc, QueryResult qrs) throws Exception {
            long timestamp = qrs.getTimestamp().getTime();
            svc.store(type, timestamp, qrs);
        }

    }

}
