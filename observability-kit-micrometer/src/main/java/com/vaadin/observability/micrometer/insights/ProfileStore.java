/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.UiStateSample;
import com.vaadin.observability.micrometer.VaadinTelemetryContext;

/**
 * What each browser tab did, kept in development mode so a developer can ask
 * what their own click just cost.
 * <p>
 * The insights buffer behind {@code /actuator/vaadin/observability} answers a
 * different question — "what is failing or slow for my users" — and therefore
 * keeps only failed and over-budget interactions, service-wide, grouped into
 * insights. A profiler needs the opposite: every interaction, including the
 * healthy 300 ms click that ran thirty queries, and separated per UI, because
 * the developer wants their own tab and not the fleet. So this store is fed by
 * a second {@link InteractionCollector} whose threshold is zero (see
 * {@link InteractionCollector#retainingEverything}), and keys what it keeps by
 * ({@code sessionId}, {@code uiId}) — the pair every
 * {@link CapturedInteraction} already carries.
 * <p>
 * <strong>Interactions have children.</strong> An interaction is opened by
 * {@link #begin(UI)} before its handler runs and completed by
 * {@link #add(CapturedInteraction)} when the handler is done, so that the
 * queries it runs in between — {@link #addQuery JDBC statements and data
 * provider queries} — have something to be recorded against. What connects them
 * is the interaction id, which {@link #begin(UI)} leaves on the UI through
 * {@link VaadinTelemetryContext#setCurrentInteraction}: a query running on the
 * request thread can read it there without the code that runs it knowing
 * anything about Vaadin.
 * <p>
 * <strong>Interactions are not all a tab has.</strong> How much state the tab
 * itself holds — nodes, components, retained views — is the Vaadin-specific
 * figure a profiler is asked for, and it belongs to the tab rather than to any
 * one of its interactions, so it is kept beside them as the latest
 * {@link #uiStateSampled measurement} of that UI. The store does not measure
 * anything itself: the walk is the one the UI-state instrumentation already
 * does under the session lock, handed over rather than repeated.
 * <p>
 * <strong>Only in development mode.</strong> The store is created by
 * {@code MetricsServiceInitListener} next to the dev-tools client injection and
 * only when the deployment is not in production mode, so a production
 * deployment holds no per-UI records and its insights payload is unchanged.
 * <p>
 * <strong>Bounded twice.</strong> Per UI at {@code insights-capacity} records,
 * oldest evicted first, exactly like {@link RecentInteractions}; and across
 * UIs, at {@link #DEFAULT_MAX_UIS} buffers, least-recently-used evicted first.
 * A UI's buffer is normally dropped by the UI's own detach listener (see
 * {@link #track(UI)}), but a UI whose detach never runs — one restored from a
 * serialized session, one belonging to a session that expired — would otherwise
 * keep its records for the life of the service, which in development mode is
 * the life of the developer's server.
 */
public class ProfileStore implements InteractionSink {

    /**
     * How many UI buffers are kept at once. A developer profiles the tabs they
     * have open; this is generous for that and still a hard bound on what a
     * server that reloads sessions all day can accumulate.
     */
    public static final int DEFAULT_MAX_UIS = 20;

    /**
     * How many queries one interaction keeps. An interaction that ran this many
     * is already the finding the profiler exists to deliver, and the ones past
     * the cap would only add memory to a development-mode server; the count
     * therefore stops rather than growing without bound.
     */
    public static final int MAX_QUERIES_PER_INTERACTION = 1000;

    /**
     * One browser tab, identified the way a captured interaction identifies its
     * origin. The session id is the raw one or a hash of it depending on
     * {@code insights-details}, so it is only ever compared against another
     * value derived under the same setting, never read as an identifier.
     * <p>
     * A UI with no session at all (not attached yet, or already gone) keys as a
     * {@code null} session id and does not collide with any real tab.
     */
    record UiKey(String sessionId, int uiId) {
    }

    private final int capacity;
    private final boolean details;

    /**
     * Access-ordered, so the eldest entry the cap evicts is the tab that has
     * gone longest without recording or being read, rather than the one that
     * opened first. Guarded by {@code this}, like the profiles it holds.
     */
    private final Map<UiKey, Profile> profiles;

    /**
     * Every held entry by its id, so a query can find the interaction it ran
     * under without knowing which tab that was. Kept in step with
     * {@link #profiles}: an entry leaves both at once, or it would outlive the
     * profile that was evicted.
     */
    private final Map<Long, Entry> entriesById = new HashMap<>();

    private long lastId;

    /**
     * Creates a store bounded by the {@code insights-capacity} setting.
     *
     * @param settings
     *            instrumentation settings, not {@code null}
     */
    public ProfileStore(ObservabilitySettings settings) {
        this(settings.getInsightsCapacity(), settings.isInsightsDetails(),
                DEFAULT_MAX_UIS);
    }

    /**
     * Test seam allowing both bounds to be set small enough to be exercised.
     */
    ProfileStore(int capacity, boolean details, int maxUis) {
        if (capacity < 1 || maxUis < 1) {
            throw new IllegalArgumentException(
                    "Capacity and the UI limit need to be 1 or more");
        }
        this.capacity = capacity;
        this.details = details;
        this.profiles = new LinkedHashMap<>(16, 0.75f, true) {
            @Serial
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<UiKey, Profile> eldest) {
                if (size() <= maxUis) {
                    return false;
                }
                forget(eldest.getValue().interactions);
                return true;
            }
        };
    }

    /**
     * Opens an interaction for the given UI and marks it as the one that UI is
     * handling, so the queries it runs can be recorded as its children before
     * anything is known about the interaction itself.
     * <p>
     * Called at the start of an RPC invocation, where all that exists yet is
     * the UI; the record arrives at {@link #add(CapturedInteraction)} when the
     * invocation ends. An interaction whose {@code add} never comes — a request
     * that died mid-handling — is never reported, since a profile reports what
     * was captured and nothing was; the entry it left is claimed by the next
     * interaction of that tab, or evicted with the rest.
     */
    @Override
    public synchronized void begin(UI ui) {
        if (ui == null) {
            return;
        }
        // Ids start at 1, so 0 can mean "no interaction" to anything that
        // refers to one.
        Entry entry = new Entry(++lastId, System.nanoTime());
        retain(key(ui), entry);
        VaadinTelemetryContext.setCurrentInteraction(ui, entry.id);
    }

    /**
     * Records what was captured of an interaction against the UI it came from,
     * completing the entry {@link #begin(UI)} opened for it so that the queries
     * already recorded against it are its children.
     * <p>
     * The entry is found as the newest incomplete one of that UI rather than
     * through the interaction id on the UI, so that a store fed by a collector
     * that never called {@code begin} still keeps every interaction — just
     * without children.
     */
    @Override
    public synchronized void add(CapturedInteraction interaction) {
        UiKey key = new UiKey(interaction.sessionId(), interaction.uiId());
        Entry pending = pending(key);
        if (pending == null) {
            pending = new Entry(++lastId, System.nanoTime());
            retain(key, pending);
        }
        pending.interaction = interaction;
    }

    /**
     * Records one query as a child of the interaction that ran it.
     * <p>
     * A query whose interaction is unknown to the store is dropped, which is
     * what makes this safe to call from anywhere: a background thread reports
     * {@link VaadinTelemetryContext#NO_INTERACTION} and lands here as a no-op,
     * and so does a query so slow that the interaction it belongs to was
     * evicted while it ran.
     *
     * @param interactionId
     *            the interaction the query ran under, as read from
     *            {@link VaadinTelemetryContext#currentInteractionId()}
     * @param kind
     *            {@link ProfiledQuery#KIND_JDBC} or one of the data provider
     *            kinds
     * @param statement
     *            the SQL, or what the data provider was asked for, may be
     *            {@code null}
     * @param rows
     *            rows the query produced, {@code -1} when unknown
     * @param durationMs
     *            how long the query took, in milliseconds
     * @param startedNanos
     *            {@link System#nanoTime()} as the query started, from which its
     *            offset into the interaction is derived
     */
    public synchronized void addQuery(long interactionId, String kind,
            String statement, long rows, long durationMs, long startedNanos) {
        Entry entry = entriesById.get(interactionId);
        if (entry == null) {
            return;
        }
        // A query that started before its interaction did (the clock of a
        // statement left open across invocations) reads as starting with it.
        long startOffsetMs = Math.max(0, TimeUnit.NANOSECONDS
                .toMillis(startedNanos - entry.startedNanos));
        entry.addQuery(new ProfiledQuery(kind, statement, rows, durationMs,
                startOffsetMs));
    }

    /**
     * The newest entry of a UI that is still waiting for its interaction, or
     * {@code null} when every entry it has is complete.
     */
    private Entry pending(UiKey key) {
        Profile profile = profiles.get(key);
        if (profile == null) {
            return null;
        }
        for (Entry entry : profile.interactions.reversed()) {
            if (entry.interaction == null) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Adds an entry to a UI's buffer, evicting that UI's oldest when it is
     * full.
     */
    private void retain(UiKey key, Entry entry) {
        Deque<Entry> buffer = tab(key).interactions;
        if (buffer.size() == capacity) {
            entriesById.remove(buffer.removeFirst().id);
        }
        buffer.addLast(entry);
        entriesById.put(entry.id, entry);
    }

    /** Drops the id index of a whole profile that is going away. */
    private void forget(Deque<Entry> buffer) {
        for (Entry entry : buffer) {
            entriesById.remove(entry.id);
        }
    }

    /**
     * The interactions recorded for one browser tab, newest first.
     *
     * @param ui
     *            the UI to profile, may be {@code null}
     * @return the retained interactions, empty when nothing was recorded for
     *         that UI
     */
    public List<ProfiledInteraction> profile(UI ui) {
        if (ui == null) {
            return List.of();
        }
        return profile(key(ui));
    }

    synchronized List<ProfiledInteraction> profile(UiKey key) {
        Profile profile = profiles.get(key);
        if (profile == null) {
            return List.of();
        }
        // An interaction still being handled is left out: what a profile
        // reports is what was captured, and that is only known once the
        // invocation has ended.
        return profile.interactions.reversed().stream()
                .filter(Entry::isComplete).map(Entry::toProfiledInteraction)
                .toList();
    }

    /**
     * Records how much server-side state one browser tab holds, replacing that
     * tab's previous measurement — the question "how much memory is my view
     * holding" is about the tab as it is now, not about each click it took to
     * get there.
     * <p>
     * The measurement is handed in rather than made here: it is the sample the
     * UI-state instrumentation already takes under the UI's own session lock,
     * at UI init, after each navigation and when an interaction ends. Nothing
     * walks a tree on this store's account, so a tab is measured no more often
     * for being profiled. In development mode that instrumentation runs for
     * this store even when the {@code ui-state} gauges are off, so the panel
     * has a figure to show out of the box.
     *
     * @param ui
     *            the tab that was measured, may be {@code null}
     * @param sample
     *            what the measurement found, may be {@code null}
     */
    public synchronized void uiStateSampled(UI ui, UiStateSample sample) {
        if (ui == null || sample == null) {
            return;
        }
        tab(key(ui)).state = sample;
    }

    /**
     * The state one browser tab holds, as of the last time it was measured.
     *
     * @param ui
     *            the tab to report on, may be {@code null}
     * @return the tab's latest measurement, or {@code null} when it has none —
     *         an unknown tab, or one whose UI-state instrumentation never ran
     */
    public UiStateSample uiState(UI ui) {
        if (ui == null) {
            return null;
        }
        return uiState(key(ui));
    }

    synchronized UiStateSample uiState(UiKey key) {
        Profile profile = profiles.get(key);
        return profile == null ? null : profile.state;
    }

    /**
     * The profile of one tab, created on that tab's first record. Also marks
     * the tab as recently used, which is what the UI limit evicts by.
     */
    private Profile tab(UiKey key) {
        return profiles.computeIfAbsent(key, k -> new Profile());
    }

    /**
     * Drops a UI's records when it detaches, so a closed tab stops costing
     * memory. Called from the UI-init listener, where the UI's session is
     * available: at detach time it may already be on its way out, and the key
     * has to be the same one {@link #add} derived from the interactions.
     *
     * @param ui
     *            the UI to follow, not {@code null}
     * @return a handle removing the detach listener registered here
     */
    public Registration track(UI ui) {
        return ui.addDetachListener(new EvictOnDetach(this, key(ui)));
    }

    private UiKey key(UI ui) {
        return new UiKey(InsightDetails.sessionId(ui, details), ui.getUIId());
    }

    private synchronized void evict(UiKey key) {
        Profile profile = profiles.remove(key);
        if (profile != null) {
            forget(profile.interactions);
        }
    }

    /** How many UI buffers are currently held. */
    synchronized int trackedUis() {
        return profiles.size();
    }

    /**
     * What the store holds about one browser tab: the interactions it recorded
     * and the state the tab was last measured to hold.
     * <p>
     * One object rather than two maps, so the two leave the store together —
     * whichever of them the tab was evicted or closed on. Guarded by the
     * store's monitor.
     */
    private static final class Profile {
        private final Deque<Entry> interactions = new ArrayDeque<>();
        /** Null until the UI-state instrumentation has measured the tab. */
        private UiStateSample state;
    }

    /**
     * One interaction while the store still holds it: the identity it was
     * given, when it started, what the collector captured of it, and what it
     * ran.
     * <p>
     * Mutable, and the only mutable thing here, because an interaction is
     * recorded in pieces: the id and the start exist before the handler runs,
     * the queries arrive while it runs, and the capture itself arrives last.
     * What {@link #profile(UiKey)} hands out is the immutable
     * {@link ProfiledInteraction} view of it, so nothing outside the store sees
     * an entry change under it. Guarded by the store's monitor.
     */
    private static final class Entry {
        private final long id;
        private final long startedNanos;
        private CapturedInteraction interaction;
        /** Created on the first query; most interactions run none. */
        private List<ProfiledQuery> queries;

        Entry(long id, long startedNanos) {
            this.id = id;
            this.startedNanos = startedNanos;
        }

        void addQuery(ProfiledQuery query) {
            if (queries == null) {
                queries = new ArrayList<>();
            } else if (queries.size() >= MAX_QUERIES_PER_INTERACTION) {
                return;
            }
            queries.add(query);
        }

        boolean isComplete() {
            return interaction != null;
        }

        ProfiledInteraction toProfiledInteraction() {
            return new ProfiledInteraction(id, interaction, queries);
        }
    }

    /**
     * The per-UI detach hook.
     * <p>
     * The store is held {@code transient} on purpose: this listener lives on
     * the UI, so it is written out with a serialized session, while the store
     * is service-wide development-mode state that has no business there. A
     * restored stub therefore does nothing, and the UI limit is what bounds the
     * buffer it can no longer evict.
     */
    private static final class EvictOnDetach
            implements ComponentEventListener<DetachEvent>, Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient ProfileStore store;
        private final transient UiKey key;

        EvictOnDetach(ProfileStore store, UiKey key) {
            this.store = store;
            this.key = key;
        }

        @Override
        public void onComponentEvent(DetachEvent event) {
            if (store != null) {
                store.evict(key);
            }
        }
    }
}
