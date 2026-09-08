/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.devtools;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.observability.micrometer.MetricsServiceInitListener;

/**
 * What holds the Copilot panel and this handler together.
 * <p>
 * The panel is TypeScript in another language in another directory, compiled
 * by the application rather than by the kit, and nothing in a Java build sees
 * it: a command renamed on this side, a module renamed on that one, or a
 * sibling element file moved all leave a panel that loads and then does
 * nothing, in a browser, with no error anyone runs a build to find. So the
 * three joins between the two halves are asserted here, by reading the shipped
 * sources as text.
 * <ul>
 * <li>the {@code @JsModule} names a resource that is in the jar,</li>
 * <li>every command the panel names is one this handler knows, and every
 * command the panel needs is named,</li>
 * <li>every relative import in the panel resolves to a file that ships with
 * it.</li>
 * </ul>
 * The point is not that these are likely mistakes; it is that they are silent
 * ones.
 */
class ObservabilityPanelContractTest {

    private static final String FRONTEND = "META-INF/frontend/";

    /** Where the panel's command names live, and nowhere else. */
    private static final String PROTOCOL_MODULE = "observability-kit/model.ts";

    /** Every command name in the panel's protocol module. */
    private static final Pattern PANEL_COMMAND = Pattern
            .compile("'(observability-kit-[a-z-]+)'");

    /**
     * What the panel has to be able to say to do its job. The route summary
     * pair is deliberately absent: the "all users" aggregate for a view is a
     * follow-up, and until it lands the handler answers a question the panel
     * does not yet ask.
     */
    private static final List<String> REQUIRED = List.of("COMMAND_PROFILE",
            "COMMAND_PROFILE_DATA", "COMMAND_PROFILE_SUBSCRIBE",
            "COMMAND_PROFILE_CLEAR", "COMMAND_INTERACTION", "COMMAND_UI_STATE",
            "COMMAND_REFRESH", "COMMAND_METRICS");

    /** `import ... from './x'` and `import './x'`, which is all the panel uses. */
    private static final Pattern RELATIVE_IMPORT = Pattern
            .compile("from\\s+'(\\./[^']+)'|import\\s+'(\\./[^']+)'");

    @Test
    void theModuleTheListenerRegistersIsInTheJar() {
        JsModule module = MetricsServiceInitListener.class
                .getAnnotation(JsModule.class);
        Assertions.assertNotNull(module,
                "the Copilot panel reaches the browser through a @JsModule on "
                        + MetricsServiceInitListener.class.getSimpleName()
                        + "; without it the kit ships a panel nothing loads");
        Assertions.assertTrue(module.developmentOnly(),
                "the panel talks over the dev-tools websocket, which does not "
                        + "exist in production, so it has no business in a "
                        + "production bundle");

        String path = module.value();
        Assertions.assertTrue(path.startsWith("./"),
                () -> "a jar frontend resource is imported relative to the "
                        + "frontend folder, got " + path);
        assertResourceExists(path.substring(2));
    }

    @Test
    void everyCommandThePanelNamesIsOneTheHandlerKnows() throws IOException {
        Collection<String> known = commandConstants().values();
        Matcher matcher = PANEL_COMMAND.matcher(source(PROTOCOL_MODULE));
        int found = 0;
        while (matcher.find()) {
            String command = matcher.group(1);
            found++;
            Assertions.assertTrue(known.contains(command),
                    () -> "the panel sends or claims " + command
                            + ", which this handler does not know. One side "
                            + "was renamed without the other, and the panel "
                            + "will sit there with nothing to show and nothing "
                            + "to say about it");
        }
        Assertions.assertTrue(found > 0,
                "this test is out of date: the panel's protocol module names "
                        + "no commands");
    }

    @Test
    void thePanelNamesEveryCommandItNeeds() throws IOException {
        String panel = source(PROTOCOL_MODULE);
        Map<String, String> commands = commandConstants();
        for (String constant : REQUIRED) {
            String command = commands.get(constant);
            Assertions.assertNotNull(command,
                    () -> "this test is out of date: the handler no longer "
                            + "declares " + constant);
            Assertions.assertTrue(panel.contains("'" + command + "'"),
                    () -> "the panel does not name " + command
                            + ", so the part of it that " + constant
                            + " serves cannot work");
        }
    }

    @Test
    void everySiblingThePanelImportsShipsWithIt() throws IOException {
        List<String> modules = List.of("observability-kit/view-profiler-panel.ts",
                "observability-kit/ok-profiler-header.ts",
                "observability-kit/ok-interaction-list.ts",
                "observability-kit/ok-interaction-detail.ts",
                "observability-kit/ok-waterfall.ts",
                "observability-kit/ok-query-table.ts",
                "observability-kit/ok-meters-table.ts",
                "observability-kit/model.ts", "observability-kit/format.ts",
                "observability-kit/styles.ts");
        for (String module : modules) {
            String source = source(module);
            Matcher matcher = RELATIVE_IMPORT.matcher(source);
            while (matcher.find()) {
                String target = matcher.group(1) == null ? matcher.group(2)
                        : matcher.group(1);
                // Written without an extension, as a bundler resolves it.
                assertResourceExists("observability-kit/"
                        + target.substring(2) + ".ts");
            }
        }
    }

    /**
     * The command names this handler is built on, by constant, read off the
     * class so that renaming one on this side is enough to fail the test.
     */
    private static Map<String, String> commandConstants() {
        Map<String, String> commands = new LinkedHashMap<>();
        for (Field field : ObservabilityDevToolsHandler.class
                .getDeclaredFields()) {
            if (!field.getName().startsWith("COMMAND_")
                    || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            try {
                commands.put(field.getName(), (String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return commands;
    }

    private static void assertResourceExists(String path) {
        Assertions.assertNotNull(
                ObservabilityPanelContractTest.class.getClassLoader()
                        .getResource(FRONTEND + path),
                () -> FRONTEND + path + " is missing from the module");
    }

    private static String source(String path) throws IOException {
        try (InputStream in = ObservabilityPanelContractTest.class
                .getClassLoader().getResourceAsStream(FRONTEND + path)) {
            Assertions.assertNotNull(in,
                    () -> FRONTEND + path + " is missing from the module");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
