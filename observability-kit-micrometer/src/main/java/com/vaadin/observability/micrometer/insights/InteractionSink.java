/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import com.vaadin.flow.component.UI;

/**
 * Where an {@link InteractionCollector} puts what it captured.
 * <p>
 * Two destinations exist, fed by two collectors with different thresholds: the
 * service-wide {@link RecentInteractions} buffer behind the insights endpoint,
 * which keeps only failed and over-budget interactions, and the dev-mode
 * {@link ProfileStore}, which keeps every interaction of every UI so a
 * developer can see what their own click cost. The collector is indifferent to
 * which one it writes to, hence this interface rather than the concrete buffer.
 */
@FunctionalInterface
public interface InteractionSink {

    /**
     * Retains one captured interaction.
     *
     * @param interaction
     *            the interaction to retain, never {@code null}
     */
    void add(CapturedInteraction interaction);

    /**
     * Signals that an interaction on the given UI is about to be handled,
     * before anything it does has run.
     * <p>
     * A sink that only keeps records has nothing to do here. The
     * {@link ProfileStore} does: an interaction it retains has children — the
     * queries the handler runs — and those need something to belong to while
     * the interaction itself is still in flight.
     *
     * @param ui
     *            the UI handling the interaction, may be {@code null}
     */
    default void begin(UI ui) {
        // Nothing to prepare for a sink that keeps only what it is handed.
    }

    /**
     * Signals that the round trip the interactions of this UI were handled in
     * is over, so that whatever the sink does with a finished interaction can
     * be done now rather than when the interaction itself was captured.
     * <p>
     * The two are not the same moment. A Grid or a ComboBox does not load its
     * data while the invocation runs: the invocation registers a flush, and
     * Flow runs it as the response is written. Everything such an interaction
     * cost is therefore recorded <em>after</em> {@link #add} was called for it.
     * <p>
     * A sink that only keeps records has nothing to do here — a reader of it
     * sees whatever has arrived by the time it reads. The {@link ProfileStore}
     * does: it announces each interaction to the panels watching that tab, and
     * an announcement made at capture time would carry none of those queries.
     *
     * @param ui
     *            the UI whose round trip ended, may be {@code null}
     */
    default void roundTripEnded(UI ui) {
        // Nothing to announce for a sink that only keeps records.
    }

    /**
     * Whether anything is waiting to be told what this UI records, so that a
     * caller can skip arranging the {@link #roundTripEnded(UI)} that would tell
     * it.
     * <p>
     * Asked per UI rather than answered once, because it is the tab with a
     * profiler panel open on it that has to pay for the arrangement, and no
     * other tab of the same server.
     *
     * @param ui
     *            the UI about to be handled, may be {@code null}
     * @return {@code true} when {@link #roundTripEnded(UI)} would do something
     */
    default boolean isWatched(UI ui) {
        return false;
    }
}
