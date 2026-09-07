/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.component.UI;

/**
 * Loads the in-browser Vaadin Copilot observability panels. They register
 * themselves with Copilot's plugin API and pull from the server over the
 * dev-tools websocket (see {@code ObservabilityDevToolsHandler}): the metrics
 * panel the live {@code vaadin.*} meters, the view profiler this tab's own
 * interactions.
 * <p>
 * Injected once per UI and only in development mode; in production Copilot and
 * the dev-tools connection do not exist, so this is never called.
 */
final class ObservabilityDevToolsClient {

    private static final String INIT_KEY = "vaadinObservabilityDevToolsInitialized";
    private static final String CLIENT_RESOURCE = "META-INF/frontend/VaadinObservabilityDevTools.js";

    /**
     * Tells the profiler panel which tab it is looking at. The profile command
     * is asked for one UI id, and the browser cannot learn its own: the id is a
     * server-side identity. Handing it over is also what keeps the panel from
     * guessing, since the guess that succeeds is another tab of the same
     * session.
     * <p>
     * Written on every injection rather than read once by the panel, so that a
     * UI replaced under the same page — a resync — moves the panel onto the new
     * id. The panel reads the global at each poll for that reason.
     * <p>
     * The name is one half of a contract in two languages: the panel reads
     * {@link #UI_ID_GLOBAL} in {@code VaadinObservabilityDevTools.js}, and a
     * one-sided rename would leave the profiler with no tab to ask about and no
     * error to show for it. {@code ObservabilityDevToolsClientTest} is what
     * fails instead.
     */
    static final String UI_ID_GLOBAL = "window.__vaadinObservabilityUiId";

    private static final String UI_ID_PRELUDE = UI_ID_GLOBAL + "=%d;";

    private ObservabilityDevToolsClient() {
    }

    static void inject(UI ui) {
        if (ui == null) {
            return;
        }
        ClientResourceLoader.loadOnce(ui, INIT_KEY, CLIENT_RESOURCE,
                ObservabilityDevToolsClient.class,
                UI_ID_PRELUDE.formatted(ui.getUIId()));
    }
}
