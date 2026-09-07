/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.vaadin.flow.component.UI;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The half of the panel contract that is written in Java: which tab the
 * profiler is told to ask about, and under what name.
 */
class ObservabilityDevToolsClientTest {

    private static final int UI_ID = 7;

    /**
     * A tab whose element is Flow's own, so that the once-per-UI flag
     * {@link ClientResourceLoader} sets has somewhere real to live — on a mock
     * it would be dropped, and every injection would look like the first.
     */
    private static UI tab() {
        UI real = new UI();
        UI ui = mock(UI.class, RETURNS_DEEP_STUBS);
        when(ui.getElement()).thenReturn(real.getElement());
        when(ui.getUIId()).thenReturn(UI_ID);
        return ui;
    }

    private static String injectedScript(UI ui) {
        ArgumentCaptor<String> js = ArgumentCaptor.forClass(String.class);
        verify(ui.getPage()).executeJs(js.capture());
        return js.getValue();
    }

    private static String panelSource() throws Exception {
        try (InputStream in = ObservabilityDevToolsClient.class.getClassLoader()
                .getResourceAsStream(
                        "META-INF/frontend/VaadinObservabilityDevTools.js")) {
            Assertions.assertNotNull(in, "the panel script is not on the "
                    + "classpath, so nothing would be injected at all");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theProfilerReadsTheGlobalTheServerWrites() throws Exception {
        Assertions.assertTrue(
                panelSource()
                        .contains(ObservabilityDevToolsClient.UI_ID_GLOBAL),
                "the prelude writes " + ObservabilityDevToolsClient.UI_ID_GLOBAL
                        + " and the panel has to be the thing that reads it: "
                        + "renaming one side leaves the profiler with no tab "
                        + "to ask about and nothing to report for it");
    }

    @Test
    void theInjectedPreludeNamesThisTabsUi() {
        UI ui = tab();

        ObservabilityDevToolsClient.inject(ui);

        String js = injectedScript(ui);
        Assertions.assertTrue(
                js.contains(ObservabilityDevToolsClient.UI_ID_GLOBAL + "="
                        + UI_ID + ";"),
                "the panel is told which tab it is looking at, the browser "
                        + "having no way to learn a server-side id");
        Assertions.assertTrue(
                js.indexOf(ObservabilityDevToolsClient.UI_ID_GLOBAL) < js
                        .indexOf("(function"),
                "and told before the script that reads it runs");
    }

    @Test
    void aSecondInjectionIntoTheSameTabIsANoOp() {
        UI ui = tab();
        ObservabilityDevToolsClient.inject(ui);

        ObservabilityDevToolsClient.inject(ui);

        // One call in total: the panel registers itself once per tab, and
        // re-sending the whole script on every UI init would be the cost of
        // getting this wrong.
        injectedScript(ui);
    }

    @Test
    void thereIsNoTabToTellWithoutAUi() {
        Assertions.assertDoesNotThrow(
                () -> ObservabilityDevToolsClient.inject(null),
                "a UI init without a UI is not this client's to fail on");
    }

    @Test
    void aTabWhoseIdIsUnknownIsStillGivenTheScript() {
        UI ui = mock(UI.class, RETURNS_DEEP_STUBS);
        UI real = new UI();
        when(ui.getElement()).thenReturn(real.getElement());
        // Flow's own default before a UI is assigned an id.
        when(ui.getUIId()).thenReturn(-1);

        ObservabilityDevToolsClient.inject(ui);

        Assertions.assertTrue(
                injectedScript(ui).contains(
                        ObservabilityDevToolsClient.UI_ID_GLOBAL + "=-1;"),
                "the metrics panel does not depend on the id, so an unknown "
                        + "one costs the profiler its profile and nothing else");
    }
}
