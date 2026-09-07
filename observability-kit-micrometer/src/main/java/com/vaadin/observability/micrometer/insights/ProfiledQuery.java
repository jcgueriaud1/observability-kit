/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

/**
 * One query an interaction ran, retained by the dev-mode {@link ProfileStore}
 * as a child of the interaction it ran under.
 * <p>
 * Deliberately thinner than {@link CapturedQuery}. That record describes a
 * query that is worth an insight on its own — one that failed or missed the UX
 * budget — and carries what a reader of the insights endpoint needs to act on
 * it. This one is a line in a list: a profiler shows every query of one
 * interaction, and its headline figure is "N queries in M ms", so what each
 * child has to carry is what it did, how long it took, and where in the
 * interaction it happened.
 * <p>
 * Both JDBC statements and data provider queries are kept in this one shape,
 * because the developer asks about them together: a slow interaction is slow
 * whether the time went into the driver or into the data provider around it,
 * and a list that interleaves them in {@link #startOffsetMs} order is what
 * shows an N+1 pattern for what it is.
 *
 * @param kind
 *            {@link #KIND_JDBC} for a statement executed through the wrapped
 *            {@code DataSource}, or {@link CapturedQuery#KIND_COUNT} /
 *            {@link CapturedQuery#KIND_FETCH} for a data provider query
 * @param statement
 *            for a JDBC query the SQL text as the driver received it; for a
 *            data provider query the component and range it asked for, since
 *            that is the closest thing it has to a statement. {@code null} when
 *            the SQL could not be determined
 * @param rows
 *            rows the query produced — read from the result set, returned by a
 *            fetch, or reported by a count — and {@code -1} when unknown,
 *            either because the query threw or because its result set was never
 *            closed
 * @param durationMs
 *            how long the query took, in milliseconds
 * @param startOffsetMs
 *            when the query started, in milliseconds since the start of the
 *            interaction it belongs to, so a list of children reads as a
 *            timeline
 */
public record ProfiledQuery(String kind, String statement, long rows,
        long durationMs, long startOffsetMs) {

    /**
     * A statement executed against the database. The data provider kinds are
     * {@link CapturedQuery#KIND_COUNT} and {@link CapturedQuery#KIND_FETCH},
     * reused rather than redeclared so one vocabulary describes a query
     * wherever it is reported.
     */
    public static final String KIND_JDBC = "jdbc";
}
