/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.starter;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.Route;

/**
 * View that issues real JDBC queries through the (proxied) {@code DataSource}
 * so the {@code vaadin.db.fetch.rows} summary is recorded under route
 * {@code db}. A small fetch returns {@value #SMALL} rows and a large fetch
 * returns every seeded row. Part of the {@code db-demo} profile (see
 * {@link DbDemoConfig}); it is not instantiated outside that profile because
 * its {@link JdbcTemplate} dependency only exists there.
 * <p>
 * The third button is an N+1, on purpose: {@value #LOOKUPS} executions of one
 * statement in a single click, which is the shape the Copilot view profiler
 * exists to make visible. The count is above the profiler's own warning
 * threshold, so the panel's database figure has to be called out as well as
 * counted.
 */
@Route("db")
@Profile("db-demo")
public class DatabaseView extends Div {

    static final int SMALL = 3;

    /**
     * Lookups the per-row button issues. Deliberately above the profiler's
     * warning threshold of twenty, so that a click on it is a finding rather
     * than a number.
     */
    static final int LOOKUPS = 25;

    private final transient JdbcTemplate jdbc;
    private final Span result = new Span();

    public DatabaseView(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        result.setId("fetch-result");

        NativeButton small = new NativeButton("Small fetch", e -> fetch(
                "SELECT id FROM numbers ORDER BY id LIMIT " + SMALL));
        small.setId("small-fetch");

        NativeButton large = new NativeButton("Large fetch",
                e -> fetch("SELECT id FROM numbers"));
        large.setId("large-fetch");

        NativeButton perRow = new NativeButton("Lookup per row",
                e -> lookupPerRow());
        perRow.setId("per-row-fetch");

        add(small, large, perRow, result);
    }

    /**
     * One query per row, the way a view accidentally does it: a list, then a
     * lookup for each of its entries. The statement is the same every time,
     * bound with a different id, so the profiler sees {@value #LOOKUPS}
     * executions of one statement rather than {@value #LOOKUPS} statements.
     */
    private void lookupPerRow() {
        int found = 0;
        for (int id = 1; id <= LOOKUPS; id++) {
            Integer row = jdbc.queryForObject(
                    "SELECT id FROM numbers WHERE id = ?", Integer.class, id);
            if (row != null) {
                found++;
            }
        }
        result.setText("rows: " + found);
    }

    private void fetch(String sql) {
        int rows = jdbc.queryForList(sql, Integer.class).size();
        result.setText("rows: " + rows);
    }
}
