/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

/**
 * Something watching the JDBC queries that pass through
 * {@link RowCountingDataSource}.
 * <p>
 * Two things watch them, for different audiences and under different settings:
 * {@link DatabaseQuerySpans} turns each query into a span and a timer for the
 * tracing backend, and only when tracing is on; {@link DatabaseQueryProfiler}
 * records it as a child of the interaction that ran it, and only in development
 * mode. The data source knows about neither — it starts one observation per
 * query and stops it when the result set closes, which is the only point at
 * which the row count is known.
 */
interface QueryObserver {

    /**
     * Starts observing a query, before it is sent to the database so that the
     * observation brackets the round trip.
     *
     * @param sql
     *            the SQL being executed, may be {@code null} when the driver
     *            did not tell us
     * @return the handle for the in-flight query, never {@code null}
     */
    Query start(String sql);

    /**
     * An in-flight query, to be {@link #stop(long) stopped} when its result set
     * closes.
     * <p>
     * {@code stop} has to be idempotent. The data source ends a query from
     * whichever of the three paths comes first — the result set closing, the
     * statement being re-executed, the statement closing with its result set
     * still open — and does not track which of them already did.
     */
    @FunctionalInterface
    interface Query {

        /**
         * A query nothing is recording, so that an observer that has decided
         * there is nothing to record can say so without the caller checking for
         * {@code null} once per query.
         */
        Query NONE = rows -> {
            // Nothing was started, so there is nothing to stop.
        };

        /**
         * Ends the observation. Called more than once for the same query; only
         * the first call counts.
         *
         * @param rows
         *            rows read from the result set, or a negative value when
         *            unknown (the result set was never closed, or the query
         *            threw)
         */
        void stop(long rows);
    }

    /**
     * The two observers as one, so the data source carries a single handle per
     * query rather than a list. Idempotent as long as both observers are, which
     * the {@link Query#stop(long)} contract requires of them.
     *
     * @param first
     *            an observer, may be {@code null}
     * @param second
     *            another observer, may be {@code null}
     * @return the observer notifying both, or {@code null} when neither was
     *         given
     */
    static QueryObserver composite(QueryObserver first, QueryObserver second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return sql -> {
            Query firstQuery = first.start(sql);
            Query secondQuery = second.start(sql);
            return rows -> {
                try {
                    firstQuery.stop(rows);
                } finally {
                    // The second observer is recorded even if the first threw:
                    // one of them failing is no reason to lose the other.
                    secondQuery.stop(rows);
                }
            };
        };
    }
}
