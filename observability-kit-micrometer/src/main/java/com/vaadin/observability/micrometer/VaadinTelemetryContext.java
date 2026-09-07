/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;

/**
 * Exposes the Vaadin view context of the request currently being handled on
 * this thread, so instrumentation living outside the Vaadin runtime (for
 * example a JDBC {@code DataSource} proxy) can attribute its measurements to
 * the view that triggered them.
 * <p>
 * The route is the template resolved by {@link RouteTagResolver} during
 * navigation and is stored as a UI attribute that survives past the navigation
 * event (unlike the transient timing state in {@link NavigationMetricsBinder}).
 * Because Vaadin binds {@link UI#getCurrent()} to the request-handling thread,
 * code running on that thread can read it back here.
 * <p>
 * The interaction id is carried the same way, for the same reason: a query has
 * to know which user action it is part of, and the only thing it shares with
 * that action is the thread it runs on.
 */
public final class VaadinTelemetryContext {

    static final String CURRENT_ROUTE_KEY = VaadinTelemetryContext.class
            .getName() + ".currentRoute";

    static final String CURRENT_INTERACTION_KEY = VaadinTelemetryContext.class
            .getName() + ".currentInteraction";

    /**
     * The id reported when no interaction is being handled. The dev-mode
     * profile store hands out ids starting at 1, so zero can mean "belongs to
     * no interaction" without an {@code Optional} on a path taken once per
     * query.
     */
    public static final long NO_INTERACTION = 0;

    private VaadinTelemetryContext() {
    }

    /**
     * Records the route template the current UI last navigated to. Called from
     * {@link NavigationMetricsBinder} after navigation completes.
     */
    static void setCurrentRoute(UI ui, String route) {
        if (ui != null) {
            ComponentUtil.setData(ui, CURRENT_ROUTE_KEY, route);
        }
    }

    /**
     * Records the interaction the given UI is handling, so that whatever the
     * handler goes on to do can be attributed to it.
     * <p>
     * Called by the development-mode profile store as it opens an interaction;
     * an application has no reason to call it. Public only because the store
     * lives in another package.
     * <p>
     * The id deliberately outlives the interaction it names. A lazy-loading
     * component queries its data provider while the response is being built,
     * after RPC handling has ended, so clearing the id at the end of the
     * invocation would leave exactly the queries a profiler most wants to show
     * belonging to nothing. What it costs is that a query running in a request
     * that handled no interaction at all is attributed to the previous
     * interaction of the same UI; it is never attributed to another UI.
     *
     * @param ui
     *            the UI handling the interaction, may be {@code null}
     * @param interactionId
     *            the id of the interaction being handled
     */
    public static void setCurrentInteraction(UI ui, long interactionId) {
        if (ui != null) {
            ComponentUtil.setData(ui, CURRENT_INTERACTION_KEY, interactionId);
        }
    }

    /**
     * Returns the id of the interaction the UI bound to the current thread is
     * handling, or {@link #NO_INTERACTION} when there is no current UI or
     * nothing has been recorded for it — a query running on a background
     * thread, or in a deployment where no profile store hands out ids.
     *
     * @return the current interaction id, or {@link #NO_INTERACTION}
     */
    public static long currentInteractionId() {
        return interactionId(UI.getCurrent());
    }

    /**
     * Returns the id of the interaction the given UI is handling, for a caller
     * that knows the UI without being on its request thread — a data provider
     * fetched on a component's own executor, for instance.
     *
     * @param ui
     *            the UI to read, may be {@code null}
     * @return the interaction id of that UI, or {@link #NO_INTERACTION}
     */
    public static long interactionId(UI ui) {
        if (ui == null) {
            return NO_INTERACTION;
        }
        Object id = ComponentUtil.getData(ui, CURRENT_INTERACTION_KEY);
        return id instanceof Long value ? value : NO_INTERACTION;
    }

    /**
     * Returns the route template of the view bound to the current thread, or
     * {@link MeterNames#ROUTE_UNKNOWN} when there is no current UI or no
     * navigation has been recorded yet.
     *
     * @return the current route tag value, never {@code null}
     */
    public static String currentRoute() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return MeterNames.ROUTE_UNKNOWN;
        }
        Object route = ComponentUtil.getData(ui, CURRENT_ROUTE_KEY);
        return route instanceof String r ? r : MeterNames.ROUTE_UNKNOWN;
    }
}
