/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.vaadin.observability.micrometer.ObservabilityKit;
import com.vaadin.observability.micrometer.VaadinTelemetryContext;
import com.vaadin.observability.micrometer.insights.ProfileStore;
import com.vaadin.observability.micrometer.insights.ProfiledQuery;

/**
 * Records each JDBC query as a child of the interaction that ran it, in the
 * development-mode profile store, so a developer can see the queries behind
 * their own click — how many, which statements, and which of them ran once per
 * row.
 * <p>
 * Everything needed is already at hand where the query span stops: the SQL, the
 * row count and the duration. What was missing was the interaction, and that
 * arrives on the UI bound to the request thread (see
 * {@link VaadinTelemetryContext#currentInteractionId()}). A query on a thread
 * with no current UI — a scheduled job, a background pool — belongs to no
 * interaction and is not recorded.
 * <p>
 * <b>The SQL is always recorded here</b>, unlike on the span, where
 * {@code vaadin.observability.database-statement} governs it. That property
 * exists because a span leaves the machine: it is exported to a tracing backend
 * where the statement is both high cardinality and potentially sensitive. These
 * records go to the dev-tools panel of the developer who wrote the query, on
 * the machine the query ran on, and a query list without the query is of no use
 * to them.
 * <p>
 * <b>Development mode only</b>, and not by a check of its own: the store is
 * bound at service init only when the deployment is not in production mode, so
 * in production there is nothing to record into and every query takes the no-op
 * path.
 */
final class DatabaseQueryProfiler implements QueryObserver {

    private final Supplier<ProfileStore> profiles;

    DatabaseQueryProfiler() {
        this(ObservabilityKit::getProfileStore);
    }

    /**
     * Test seam: the store is otherwise looked up from {@link ObservabilityKit}
     * per query, because a {@code DataSource} is wrapped long before the Vaadin
     * service that binds the store is initialized.
     */
    DatabaseQueryProfiler(Supplier<ProfileStore> profiles) {
        this.profiles = profiles;
    }

    @Override
    public Query start(String sql) {
        ProfileStore store = profiles.get();
        if (store == null) {
            return Query.NONE;
        }
        long interactionId = VaadinTelemetryContext.currentInteractionId();
        if (interactionId == VaadinTelemetryContext.NO_INTERACTION) {
            return Query.NONE;
        }
        // The interaction was read now rather than at stop(): the one that
        // ran the query owns it, even if the result set is closed after that
        // interaction has ended.
        return new Recording(store, interactionId, sql);
    }

    /**
     * One query being timed, recorded when it ends.
     * <p>
     * Idempotent, as the {@link Query} contract requires: the result-set close
     * and the statement-close leak guard both end the query, and the second of
     * them must not add a duplicate — one with {@code -1} rows at that.
     */
    private static final class Recording implements Query {

        private final ProfileStore profiles;
        private final long interactionId;
        private final String sql;
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean stopped = new AtomicBoolean();

        Recording(ProfileStore profiles, long interactionId, String sql) {
            this.profiles = profiles;
            this.interactionId = interactionId;
            this.sql = sql;
        }

        @Override
        public void stop(long rows) {
            if (stopped.compareAndSet(false, true)) {
                profiles.addQuery(interactionId, ProfiledQuery.KIND_JDBC, sql,
                        rows,
                        TimeUnit.NANOSECONDS
                                .toMillis(System.nanoTime() - startedNanos),
                        startedNanos);
            }
        }
    }
}
