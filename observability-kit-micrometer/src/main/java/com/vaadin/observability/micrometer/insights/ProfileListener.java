/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import com.vaadin.observability.micrometer.UiStateSample;

/**
 * Told when one browser tab records something, so what the {@link ProfileStore}
 * keeps can be pushed to a dev-tools panel instead of polled for.
 * <p>
 * A profiler is watched while it is used: the developer clicks in the
 * application and looks at the panel, and a poll makes that a round trip late
 * and, between clicks, a round trip for nothing. A listener is registered per
 * UI through {@link ProfileStore#listen}, so a panel is told about the tab it
 * is showing and about no other.
 * <p>
 * <strong>Called on the thread that recorded.</strong> Both methods run on the
 * request thread of the interaction, under that UI's session lock, so an
 * implementation may build a message but must not wait for anything — least of
 * all for the browser it is about to send to.
 */
public interface ProfileListener {

    /**
     * One interaction of the watched tab finished and was retained.
     *
     * @param interaction
     *            the interaction as the store now holds it, with the queries it
     *            ran, never {@code null}
     */
    void interactionRecorded(ProfiledInteraction interaction);

    /**
     * The watched tab was measured again.
     *
     * @param sample
     *            what the measurement found, never {@code null}
     */
    void uiStateSampled(UiStateSample sample);
}
