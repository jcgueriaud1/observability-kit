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
}
