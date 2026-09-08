/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

import com.vaadin.flow.component.dependency.NpmPackage;

/**
 * The JDBC/H2 stack that backs {@link DatabaseView} is deliberately confined to
 * the {@code db-demo} Spring profile (see {@code DbDemoConfig}); Boot's
 * {@link DataSourceAutoConfiguration} is excluded so that, outside that
 * profile, no {@code DataSource} exists at all. This keeps the GraalVM native
 * image lean — it carries none of the DB demo, and the kit's DataSource proxy
 * is never engaged there — while the JVM integration tests activate the profile
 * to exercise {@code vaadin.db.fetch.rows} end to end.
 *
 * <h2>Why this app declares an npm package it never imports</h2>
 * <p>
 * The Vaadin classpath here is deliberately minimal — {@code flow-server} plus
 * {@code flow-html-components}, where a real application gets the platform —
 * which is the point: it proves the kit works without the component set. That
 * left one thing missing. In development mode Flow generates
 * {@code frontend/generated/vaadin.ts} with an import of every component
 * Copilot's own UI is built from, and on this classpath no {@code @NpmPackage}
 * declares them, so they are not installed.
 * <p>
 * It went unnoticed for as long as the app never built a frontend bundle: with
 * no frontend code of its own it used Vaadin's prebuilt default bundle in both
 * modes. The kit's Copilot panel is a {@code @JsModule}, so the application
 * bundle no longer matches the default one and Flow has to build it — and that
 * build failed with two dozen {@code TS2882 Cannot find module '@vaadin/…'},
 * with no dev tools and no panel to show for it.
 * <p>
 * {@code @vaadin/react-components} depends on the whole component set and is
 * what Vaadin's own default development bundle declares, so one entry covers
 * them. Nothing in this app imports it; it is here so that the bundle Flow
 * builds can resolve what Flow generates. The version tracks the platform
 * release line that {@code flow.version} belongs to.
 */
@NpmPackage(value = "@vaadin/react-components", version = "25.3.0-beta1")
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
