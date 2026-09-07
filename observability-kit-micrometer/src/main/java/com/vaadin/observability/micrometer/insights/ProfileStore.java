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
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.ObservabilitySettings;

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
     * opened first. Guarded by {@code this}, like the buffers it holds.
     */
    private final Map<UiKey, Deque<ProfiledInteraction>> profiles;

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
                    Map.Entry<UiKey, Deque<ProfiledInteraction>> eldest) {
                return size() > maxUis;
            }
        };
    }

    /**
     * Records an interaction against the UI it came from, giving it the id the
     * rest of that UI's profile refers to it by.
     */
    @Override
    public synchronized void add(CapturedInteraction interaction) {
        Deque<ProfiledInteraction> buffer = profiles.computeIfAbsent(
                new UiKey(interaction.sessionId(), interaction.uiId()),
                key -> new ArrayDeque<>());
        if (buffer.size() == capacity) {
            buffer.removeFirst();
        }
        // Ids start at 1, so 0 can mean "no interaction" to anything that
        // refers to one.
        buffer.addLast(new ProfiledInteraction(++lastId, interaction));
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
        Deque<ProfiledInteraction> buffer = profiles.get(key);
        return buffer == null ? List.of() : buffer.reversed().stream().toList();
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
        profiles.remove(key);
    }

    /** How many UI buffers are currently held. */
    synchronized int trackedUis() {
        return profiles.size();
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
