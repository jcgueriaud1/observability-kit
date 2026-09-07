/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.devtools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;

import com.vaadin.base.devserver.DevToolsInterface;
import com.vaadin.base.devserver.DevToolsMessageHandler;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilityKit;
import com.vaadin.observability.micrometer.UiStateSample;
import com.vaadin.observability.micrometer.insights.CapturedInteraction;
import com.vaadin.observability.micrometer.insights.ProfileStore;
import com.vaadin.observability.micrometer.insights.ProfiledInteraction;
import com.vaadin.observability.micrometer.insights.ProfiledQuery;
import com.vaadin.observability.micrometer.insights.ProfiledQueryGroup;

/**
 * Dev-mode bridge between the live Micrometer {@link MeterRegistry} and the
 * Vaadin Copilot observability panels.
 * <p>
 * Discovered via the Java {@link java.util.ServiceLoader} by Flow's dev-tools
 * server (see {@code META-INF/services}). On request from a panel it reads the
 * live meters and the development-mode {@link ProfileStore} and sends what it
 * finds to the browser over the shared dev-tools websocket. This is a
 * developer-only convenience view; it has no effect in production where the
 * dev-tools connection does not exist.
 *
 * <h2>The protocol</h2>
 * <p>
 * Three commands, each answered with one message. This is what the Copilot
 * panels code against, so treat the field names below as a contract.
 *
 * <h3>{@code observability-kit-refresh} →
 * {@code observability-kit-metrics}</h3>
 * <p>
 * No request fields. Answers with every {@code vaadin.*} meter in the registry
 * — all users and all views — as
 * 
 * <pre>
 * { "timestamp": 1767225600000, "meters": [ &lt;meter&gt;, ... ] }
 * </pre>
 * 
 * A {@code <meter>} carries {@code name}, {@code type}, {@code tags} (an
 * object) and, depending on its type, {@code count}, {@code mean}, {@code max},
 * {@code value}, {@code unit}, or a {@code measurements} array of
 * {@code { statistic, value }} for a type this handler does not know. Also
 * pushed once on connect.
 *
 * <h3>{@code observability-kit-profile} →
 * {@code observability-kit-profile-data}</h3>
 * <p>
 * Request: {@code { "uiId": <int> }}. Answers with what the developer's own tab
 * did — the interactions recorded for the current {@link VaadinSession} and
 * that UI id, newest first — as
 * 
 * <pre>
 * {
 *   "timestamp": 1767225600000,
 *   "uiId": 3,
 *   "uiState": { "nodes": 812, "components": 210, "views": 2,
 *                "staleViews": 0, "sampleAgeMs": 1400 },
 *   "interactions": [ {
 *     "id": 42, "timestamp": 1767225599880,
 *     "route": "orders/:orderId", "location": "orders/17",
 *     "component": "com.example.OrdersGrid", "event": "click",
 *     "rpcType": "event", "outcome": "success", "durationMs": 312,
 *     "exceptionType": null, "exceptionMessage": null,
 *     "applicationFrame": null,
 *     "queries": [ { "kind": "jdbc", "sql": "select * from orders where id=?",
 *                    "rows": 1, "durationMs": 8, "startOffsetMs": 12 } ],
 *     "queryGroups": [ { "statement": "select * from orders where id=?",
 *                        "kind": "jdbc", "count": 100, "durationMs": 640 } ]
 *   } ]
 * }
 * </pre>
 * 
 * Both {@code timestamp} fields are epoch milliseconds, matching the envelope
 * of the metrics message. {@code uiState} is {@code null} until the UI-state
 * instrumentation has measured the tab, and its {@code sampleAgeMs} says how
 * long ago that was — a tab is measured on its own session's thread, so an idle
 * tab's figures are as old as its last interaction. {@code queries} is empty
 * for an interaction that ran none, and its {@code sql} is the statement text
 * for a JDBC query and what the component asked for in the case of a data
 * provider query, which is the closest thing that has to a statement.
 * <p>
 * {@code queryGroups} is the same queries with the ones that are the same query
 * counted together, most repeated first: the toolbar headline is read off it
 * without the panel having to know how a statement is parameterised —
 * {@code queries.length} queries, {@code queryGroups.length} different
 * statements, and any group whose {@code count} is above one is a view running
 * one query N times. Its {@code durationMs} is the group's total, which is what
 * fixing the duplication could win back.
 * <p>
 * <strong>Scope is the developer's own session.</strong> The UI is resolved
 * through {@link VaadinSession#getUIById(int)} of the session the dev-tools
 * connection belongs to, so a UI id from another user's session is simply not
 * found and the profile comes back empty. A dev-tools connection must not be
 * able to read another user's tab by guessing a small integer.
 *
 * <h3>{@code observability-kit-route-summary} →
 * {@code observability-kit-route-summary-data}</h3>
 * <p>
 * Request: {@code { "route": "<template>" }}, a route template such as
 * {@code orders/:orderId} ({@code ""} being the root route). Answers with the
 * meters attributed to that view — everyone's, not just this developer's, since
 * that is what a meter holds — as
 * 
 * <pre>
 * { "timestamp": 1767225600000, "route": "orders/:orderId",
 *   "meters": [ &lt;meter&gt;, ... ] }
 * </pre>
 * 
 * with {@code <meter>} in the same shape as the metrics message. A meter is
 * included when its {@code route} tag names the requested view
 * ({@code vaadin.navigation}, {@code vaadin.rpc.duration} when it is recorded
 * through an observation, {@code vaadin.db.query},
 * {@code vaadin.db.fetch.rows}, {@code vaadin.data.*}, {@code vaadin.errors},
 * {@code vaadin.client.*}), or when its {@code uri} tag does: per-view request
 * latency lives on the framework's own HTTP meter, whose path pattern the kit
 * sets to the active route template, rather than on
 * {@code vaadin.request.duration}, which is deliberately not tagged by route. A
 * view nobody has visited yet has no meters and comes back with an empty list.
 */
public class ObservabilityDevToolsHandler implements DevToolsMessageHandler {

    static final String COMMAND_REFRESH = "observability-kit-refresh";
    static final String COMMAND_METRICS = "observability-kit-metrics";

    static final String COMMAND_PROFILE = "observability-kit-profile";
    static final String COMMAND_PROFILE_DATA = "observability-kit-profile-data";

    static final String COMMAND_ROUTE_SUMMARY = "observability-kit-route-summary";
    static final String COMMAND_ROUTE_SUMMARY_DATA = "observability-kit-route-summary-data";

    /**
     * Only the kit's own meters are exposed to the panel: the whole registry
     * belongs to the application, and a snapshot of it is not this handler's to
     * hand out. The one exception is the framework's HTTP meter in a route
     * summary, which is reached by its {@link #TAG_URI} tag rather than by
     * name, because that is where per-view request latency lives.
     */
    private static final String METER_PREFIX = "vaadin.";

    /**
     * Tag key carrying the route on the framework's HTTP server meter: the kit
     * sets the active route template as the observation's path pattern, which
     * is where per-view request latency ends up. Not a kit meter, so its name
     * is not ours to hardcode — the tag value is, because only the kit puts a
     * Vaadin route template there.
     */
    private static final String TAG_URI = "uri";

    private final Supplier<MeterRegistry> registry;
    private final Supplier<ProfileStore> profiles;
    private final Supplier<VaadinSession> currentSession;

    public ObservabilityDevToolsHandler() {
        this(ObservabilityKit::getActiveMeterRegistry,
                ObservabilityKit::getProfileStore, VaadinSession::getCurrent);
    }

    /**
     * Test seam: the three pieces of ambient state this handler reads, so a
     * test can hand it a registry, a store and a session of its own.
     */
    ObservabilityDevToolsHandler(Supplier<MeterRegistry> registry,
            Supplier<ProfileStore> profiles,
            Supplier<VaadinSession> currentSession) {
        this.registry = registry;
        this.profiles = profiles;
        this.currentSession = currentSession;
    }

    @Override
    public void handleConnect(DevToolsInterface devToolsInterface) {
        // Push an initial snapshot; the panel also pulls on demand.
        sendSnapshot(devToolsInterface);
    }

    @Override
    public boolean handleMessage(String command, JsonNode data,
            DevToolsInterface devToolsInterface) {
        switch (command) {
        case COMMAND_REFRESH:
            sendSnapshot(devToolsInterface);
            return true;
        case COMMAND_PROFILE:
            sendProfile(devToolsInterface, intField(data, "uiId"));
            return true;
        case COMMAND_ROUTE_SUMMARY:
            sendRouteSummary(devToolsInterface, stringField(data, "route"));
            return true;
        default:
            return false;
        }
    }

    private void sendSnapshot(DevToolsInterface devToolsInterface) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("meters",
                snapshot(id -> id.getName().startsWith(METER_PREFIX)));
        devToolsInterface.send(COMMAND_METRICS, payload);
    }

    /**
     * Answers with the interactions of one tab of the session this dev-tools
     * connection belongs to.
     */
    private void sendProfile(DevToolsInterface devToolsInterface, int uiId) {
        UI ui = resolveOwnUi(uiId);
        ProfileStore store = profiles.get();
        List<ProfiledInteraction> interactions = store == null ? List.of()
                : store.profile(ui);
        UiStateSample state = store == null ? null : store.uiState(ui);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", System.currentTimeMillis());
        // Echoed so a panel with more than one tab open can tell which
        // request this answers, including when the answer is empty.
        payload.put("uiId", uiId);
        payload.put("uiState", uiState(state));
        List<Map<String, Object>> list = new ArrayList<>(interactions.size());
        for (ProfiledInteraction interaction : interactions) {
            list.add(interactionJson(interaction));
        }
        payload.put("interactions", list);
        devToolsInterface.send(COMMAND_PROFILE_DATA, payload);
    }

    /**
     * Answers with the meters attributed to one route template.
     */
    private void sendRouteSummary(DevToolsInterface devToolsInterface,
            String route) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("route", route);
        payload.put("meters",
                route == null ? List.of() : snapshot(attributedTo(route)));
        devToolsInterface.send(COMMAND_ROUTE_SUMMARY_DATA, payload);
    }

    /**
     * The UI with the given id <em>within the session this dev-tools connection
     * belongs to</em>, or {@code null} when that session has no such UI — which
     * is what keeps one developer's panel from reading another user's tab: a UI
     * id is only unique within a session, so it is only ever resolved through
     * one.
     * <p>
     * The session's UI map may only be read under its lock, and the dev-tools
     * websocket thread does not hold it, so the lookup goes through
     * {@link VaadinSession#accessSynchronously} unless this thread already has
     * the lock.
     */
    private UI resolveOwnUi(int uiId) {
        VaadinSession session = currentSession.get();
        if (session == null || uiId < 0) {
            return null;
        }
        if (session.hasLock()) {
            return session.getUIById(uiId);
        }
        UI[] found = new UI[1];
        try {
            session.accessSynchronously(
                    () -> found[0] = session.getUIById(uiId));
        } catch (RuntimeException e) {
            // A session on its way out (closing, or already closed) cannot be
            // accessed. There is nothing to profile in it either, so the panel
            // gets an empty profile rather than an error.
            return null;
        }
        return found[0];
    }

    /**
     * Matches the meters that describe one view: the ones the kit tags with the
     * route template, and the framework's HTTP meter, whose path pattern the
     * kit sets to that template with a leading slash.
     */
    private static Predicate<Meter.Id> attributedTo(String route) {
        String pathPattern = route.startsWith("/") ? route : "/" + route;
        return id -> {
            if (id.getName().startsWith(METER_PREFIX)
                    && route.equals(id.getTag(MeterNames.TAG_ROUTE))) {
                return true;
            }
            String uri = id.getTag(TAG_URI);
            return uri != null
                    && (pathPattern.equals(uri) || route.equals(uri));
        };
    }

    private List<Map<String, Object>> snapshot(Predicate<Meter.Id> include) {
        List<Map<String, Object>> meters = new ArrayList<>();
        MeterRegistry meterRegistry = registry.get();
        if (meterRegistry == null) {
            return meters;
        }
        for (Meter meter : meterRegistry.getMeters()) {
            if (!include.test(meter.getId())) {
                continue;
            }
            meters.add(meterJson(meter));
        }
        return meters;
    }

    private static Map<String, Object> meterJson(Meter meter) {
        Meter.Id id = meter.getId();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", id.getName());
        entry.put("type", id.getType().name());

        Map<String, String> tags = new LinkedHashMap<>();
        for (Tag tag : id.getTags()) {
            tags.put(tag.getKey(), tag.getValue());
        }
        entry.put("tags", tags);

        // Emit derived, interpretable values per meter type rather than raw
        // statistics. For timers the cumulative mean is the stable, useful
        // figure (TOTAL_TIME is an ever-growing sum and the SimpleMeter
        // registry's MAX decays to 0 between polls).
        if (meter instanceof Timer timer) {
            entry.put("count", timer.count());
            entry.put("mean", timer.mean(TimeUnit.MILLISECONDS));
            entry.put("max", timer.max(TimeUnit.MILLISECONDS));
            entry.put("unit", "ms");
        } else if (meter instanceof Counter counter) {
            entry.put("count", (long) counter.count());
        } else if (meter instanceof FunctionCounter counter) {
            entry.put("count", (long) counter.count());
        } else if (meter instanceof Gauge gauge) {
            entry.put("value", gauge.value());
        } else if (meter instanceof DistributionSummary summary) {
            entry.put("count", summary.count());
            entry.put("mean", summary.mean());
            entry.put("max", summary.max());
            if (id.getBaseUnit() != null) {
                entry.put("unit", id.getBaseUnit());
            }
        } else {
            // Unknown meter type: fall back to raw measurements.
            List<Map<String, Object>> measurements = new ArrayList<>();
            for (Measurement measurement : meter.measure()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("statistic", measurement.getStatistic().name());
                m.put("value", measurement.getValue());
                measurements.add(m);
            }
            entry.put("measurements", measurements);
        }
        return entry;
    }

    /**
     * One interaction and its query children. Every field of the contract is
     * always present, {@code null} where the interaction has nothing to say, so
     * the panel never has to distinguish a missing key from an absent value.
     */
    private static Map<String, Object> interactionJson(
            ProfiledInteraction profiled) {
        CapturedInteraction interaction = profiled.interaction();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", profiled.id());
        json.put("timestamp", interaction.timestamp() == null ? null
                : interaction.timestamp().toEpochMilli());
        json.put("route", interaction.route());
        json.put("location", interaction.location());
        json.put("component", interaction.component());
        json.put("event", interaction.event());
        json.put("rpcType", interaction.rpcType());
        json.put("outcome", interaction.outcome());
        json.put("durationMs", interaction.durationMs());
        json.put("exceptionType", interaction.exceptionType());
        json.put("exceptionMessage", interaction.exceptionMessage());
        json.put("applicationFrame", interaction.applicationFrame());

        List<Map<String, Object>> queries = new ArrayList<>(
                profiled.queries().size());
        for (ProfiledQuery query : profiled.queries()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("kind", query.kind());
            // Named for what the developer reads it as. A data provider query
            // has no SQL of its own and carries what the component asked for
            // here instead; its "kind" says which it is.
            q.put("sql", query.statement());
            q.put("rows", query.rows());
            q.put("durationMs", query.durationMs());
            q.put("startOffsetMs", query.startOffsetMs());
            queries.add(q);
        }
        json.put("queries", queries);

        List<ProfiledQueryGroup> grouped = profiled.queryGroups();
        List<Map<String, Object>> groups = new ArrayList<>(grouped.size());
        for (ProfiledQueryGroup group : grouped) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("statement", group.statement());
            g.put("kind", group.kind());
            g.put("count", group.count());
            g.put("durationMs", group.durationMs());
            groups.add(g);
        }
        json.put("queryGroups", groups);
        return json;
    }

    /**
     * The tab's latest state measurement, with the monotonic reading it was
     * taken at turned into an age: a nanoTime value means nothing in a browser,
     * and how stale the figures are is the part the panel has to be able to
     * show.
     */
    private static Map<String, Object> uiState(UiStateSample sample) {
        if (sample == null) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("nodes", sample.nodes());
        json.put("components", sample.components());
        json.put("views", sample.views());
        json.put("staleViews", sample.staleViews());
        json.put("sampleAgeMs", Math.max(0, TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - sample.sampledAtNanos())));
        return json;
    }

    private static int intField(JsonNode data, String field) {
        return data == null ? -1 : data.path(field).asInt(-1);
    }

    private static String stringField(JsonNode data, String field) {
        if (data == null || !data.hasNonNull(field)) {
            return null;
        }
        JsonNode node = data.get(field);
        return node.isString() ? node.stringValue() : null;
    }
}
