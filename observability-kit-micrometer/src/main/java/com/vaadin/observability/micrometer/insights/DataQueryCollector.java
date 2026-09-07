/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.data.DataCountEndedEvent;
import com.vaadin.flow.server.data.DataCountFailedEvent;
import com.vaadin.flow.server.data.DataCountStartedEvent;
import com.vaadin.flow.server.data.DataFetchEndedEvent;
import com.vaadin.flow.server.data.DataFetchFailedEvent;
import com.vaadin.flow.server.data.DataFetchStartedEvent;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.RouteTagResolver;
import com.vaadin.observability.micrometer.VaadinTelemetryContext;

/**
 * Captures data provider queries worth surfacing: ones that threw, and ones
 * slower than the UX budget.
 * <p>
 * {@link InteractionCollector} cannot see these. It hangs off the RPC events,
 * and the invocation that triggers a load only registers a flush, so a combo
 * box that takes four seconds to fetch a page ends its invocation in
 * microseconds and never qualifies as a slow interaction. This collector
 * watches the queries themselves.
 * <p>
 * The budget is the same {@link InteractionCollector#UX_BUDGET_MS} an
 * interaction is measured against: a query is part of what the user waits for,
 * so it earns attention at the same point.
 * <p>
 * <b>The dev-mode profile store, when there is one, is fed differently.</b>
 * Every count and every fetch is recorded there as a child of the interaction
 * it ran under, fast ones included, because a profiler answers "what did my
 * click just cost" and eleven fast queries is the answer it exists to give. The
 * threshold above governs only what reaches the service-wide insights buffer.
 * <p>
 * <b>Threading.</b> The events of one query arrive on the same thread, so the
 * start time lives in a thread local. Count and fetch keep separate slots
 * because an asynchronous component runs its fetches on its own executor while
 * its counts stay on the request thread.
 */
public class DataQueryCollector {

    private final RecentQueries buffer;
    /** The dev-mode store, or {@code null} in production mode. */
    private final ProfileStore profiles;
    private final boolean captureErrors;
    private final boolean captureSlow;
    private final long uxBudgetMs;
    private final RouteTagResolver routes;

    private final ThreadLocal<Long> countStart = new ThreadLocal<>();
    private final ThreadLocal<Long> fetchStart = new ThreadLocal<>();

    public DataQueryCollector(RecentQueries buffer,
            ObservabilitySettings settings) {
        this(buffer, null, settings, InteractionCollector.UX_BUDGET_MS);
    }

    /**
     * A collector that also records every query it sees into the development
     * mode profile store, as a child of the interaction it ran under.
     *
     * @param buffer
     *            the insights buffer, which keeps only failed and slow queries
     * @param profiles
     *            the dev-mode profile store, or {@code null} for none
     * @param settings
     *            instrumentation settings, not {@code null}
     */
    public DataQueryCollector(RecentQueries buffer, ProfileStore profiles,
            ObservabilitySettings settings) {
        this(buffer, profiles, settings, InteractionCollector.UX_BUDGET_MS);
    }

    /**
     * Test seam allowing the slow-query threshold to be overridden so timing
     * behaviour can be exercised without real delays.
     */
    DataQueryCollector(RecentQueries buffer, ProfileStore profiles,
            ObservabilitySettings settings, long uxBudgetMs) {
        this.buffer = buffer;
        this.profiles = profiles;
        this.captureErrors = settings.isErrors();
        this.captureSlow = settings.isRequests();
        this.uxBudgetMs = uxBudgetMs;
        this.routes = new RouteTagResolver(settings.getRouteCardinalityLimit());
    }

    /**
     * Subscribes to the data query events on the given bus.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing every subscription made here
     */
    public Registration register(VaadinServiceEventBus eventBus) {
        return Registration.combine(
                eventBus.addListener(DataCountStartedEvent.class,
                        this::countStarted),
                eventBus.addListener(DataCountFailedEvent.class,
                        this::countFailed),
                eventBus.addListener(DataCountEndedEvent.class,
                        this::countEnded),
                eventBus.addListener(DataFetchStartedEvent.class,
                        this::fetchStarted),
                eventBus.addListener(DataFetchFailedEvent.class,
                        this::fetchFailed),
                eventBus.addListener(DataFetchEndedEvent.class,
                        this::fetchEnded));
    }

    void countStarted(DataCountStartedEvent event) {
        countStart.set(System.nanoTime());
    }

    void countFailed(DataCountFailedEvent event) {
        if (!captureErrors) {
            return;
        }
        capture(CapturedQuery.KIND_COUNT, event.getUI(),
                event.getComponent().orElse(null), event.isFiltered(), -1, -1,
                -1, elapsedMs(countStart.get()), -1,
                CapturedQuery.OUTCOME_ERROR, event.getError());
    }

    void countEnded(DataCountEndedEvent event) {
        Long startedNanos = countStart.get();
        long durationMs = elapsedMs(startedNanos);
        countStart.remove();
        // Recorded for the profiler whether or not it was slow, and whether or
        // not it threw: a count that failed is still something the click did.
        profile(CapturedQuery.KIND_COUNT, event.getUI(),
                event.getComponent().orElse(null), event.isFiltered(), -1, -1,
                event.getCount(), durationMs, startedNanos);
        // A failed count is already captured with its throwable; -1 here only
        // says the query threw.
        if (event.getCount() < 0 || !captureSlow || durationMs < uxBudgetMs) {
            return;
        }
        capture(CapturedQuery.KIND_COUNT, event.getUI(),
                event.getComponent().orElse(null), event.isFiltered(), -1, -1,
                event.getCount(), durationMs, uxBudgetMs,
                CapturedQuery.OUTCOME_SUCCESS, null);
    }

    void fetchStarted(DataFetchStartedEvent event) {
        fetchStart.set(System.nanoTime());
    }

    void fetchFailed(DataFetchFailedEvent event) {
        if (!captureErrors) {
            return;
        }
        capture(CapturedQuery.KIND_FETCH, event.getUI(),
                event.getComponent().orElse(null), event.isFiltered(),
                event.getOffset(), event.getLimit(), -1,
                elapsedMs(fetchStart.get()), -1, CapturedQuery.OUTCOME_ERROR,
                event.getError());
    }

    void fetchEnded(DataFetchEndedEvent event) {
        Long startedNanos = fetchStart.get();
        long durationMs = elapsedMs(startedNanos);
        fetchStart.remove();
        profile(CapturedQuery.KIND_FETCH, event.getUI(),
                event.getComponent().orElse(null), event.isFiltered(),
                event.getOffset(), event.getLimit(), event.getRowsReturned(),
                durationMs, startedNanos);
        if (event.getRowsReturned() < 0 || !captureSlow
                || durationMs < uxBudgetMs) {
            return;
        }
        capture(CapturedQuery.KIND_FETCH, event.getUI(),
                event.getComponent().orElse(null), event.isFiltered(),
                event.getOffset(), event.getLimit(), event.getRowsReturned(),
                durationMs, uxBudgetMs, CapturedQuery.OUTCOME_SUCCESS, null);
    }

    private void capture(String kind, UI ui, Component component,
            boolean filtered, int offset, int limit, int rows, long durationMs,
            long thresholdMs, String outcome, Throwable error) {
        try {
            buffer.add(
                    new CapturedQuery(Instant.now(), routes.tagForUi(ui, null),
                            component == null ? null
                                    : component.getClass().getName(),
                            kind, filtered, offset, limit, rows, durationMs,
                            thresholdMs, outcome,
                            error == null ? null
                                    : Throwables.rootCause(error).getClass()
                                            .getName()));
        } catch (RuntimeException e) {
            // Collection is best-effort enrichment; never interfere with data
            // loading or the framework's error handling.
        }
    }

    /**
     * Records the query as a child of the interaction the UI is handling.
     * <p>
     * The interaction is read from the UI the event carries rather than from
     * the current thread, because an asynchronous component fetches on its own
     * executor: there is no current UI there, but the query is still part of
     * the click that asked for the data.
     */
    private void profile(String kind, UI ui, Component component,
            boolean filtered, int offset, int limit, int rows, long durationMs,
            Long startedNanos) {
        if (profiles == null || startedNanos == null) {
            return;
        }
        long interactionId = VaadinTelemetryContext.interactionId(ui);
        if (interactionId == VaadinTelemetryContext.NO_INTERACTION) {
            return;
        }
        try {
            profiles.addQuery(interactionId, kind,
                    describe(component, offset, limit, filtered), rows,
                    durationMs, startedNanos);
        } catch (RuntimeException e) {
            // Best-effort, as in capture(): a profiler must never be what
            // breaks data loading.
        }
    }

    /**
     * What a data provider query has instead of a statement: who asked, and for
     * what. Written so that the pages of one scrolling component reduce to one
     * {@link ProfiledQueryGroup} once the range is parameterised away.
     */
    private static String describe(Component component, int offset, int limit,
            boolean filtered) {
        StringBuilder description = new StringBuilder(
                component == null ? "data provider"
                        : component.getClass().getName());
        if (offset >= 0 || limit >= 0) {
            description.append(" [").append(offset).append(", ").append(limit)
                    .append(']');
        }
        if (filtered) {
            description.append(" (filtered)");
        }
        return description.toString();
    }

    private long elapsedMs(Long startedNanos) {
        return startedNanos == null ? -1
                : TimeUnit.NANOSECONDS
                        .toMillis(System.nanoTime() - startedNanos);
    }

}
