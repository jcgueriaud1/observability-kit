/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.boot;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.VaadinTelemetryContext;
import com.vaadin.observability.micrometer.insights.ProfileStore;
import com.vaadin.observability.micrometer.insights.ProfiledQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies that a JDBC query is recorded as a child of the interaction that ran
 * it, which is what lets the dev-tools panel answer "which queries did my click
 * run" rather than only "how long did the database take".
 */
class DatabaseQueryProfilerTest {

    private static final long INTERACTION_ID = 7;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProfileStore profiles = mock(ProfileStore.class);
    /**
     * Held in a field, not a local: Flow's current-instance map references the
     * UI weakly, so a tab nothing else points at can be collected mid-test and
     * the query would find no current UI.
     */
    private final UI ui = new UI();

    @AfterEach
    void clearCurrentUi() {
        UI.setCurrent(null);
    }

    /**
     * Puts the tab in the state Vaadin leaves it in while it handles an
     * interaction: the interaction marked on the UI, and the UI bound to this
     * thread. That pair is all a query has to go on.
     */
    private void handlingAnInteraction() {
        VaadinTelemetryContext.setCurrentInteraction(ui, INTERACTION_ID);
        UI.setCurrent(ui);
    }

    /**
     * Runs one two-row query through a data source watched by the given
     * observer.
     */
    private void query(QueryObserver observer, String sql) throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);

        DataSource ds = new RowCountingDataSource(delegate,
                new DatabaseFetchMetrics(registry), observer);
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // drain
            }
        }
    }

    @Test
    void query_isRecordedAsAChildOfTheCurrentInteraction() throws Exception {
        handlingAnInteraction();

        query(new DatabaseQueryProfiler(() -> profiles), "SELECT * FROM x");

        verify(profiles).addQuery(eq(INTERACTION_ID),
                eq(ProfiledQuery.KIND_JDBC), eq("SELECT * FROM x"), eq(2L),
                anyLong(), anyLong());
    }

    @Test
    void statement_isRecordedEvenThoughSpansWithholdIt() throws Exception {
        // database-statement governs what goes on a span, which leaves the
        // machine. These records go to the dev-tools panel of the developer
        // whose query it is, and a query list without the query is useless.
        handlingAnInteraction();

        query(QueryObserver.composite(
                new DatabaseQuerySpans(ObservationRegistry.create(), false),
                new DatabaseQueryProfiler(() -> profiles)), "SELECT * FROM x");

        verify(profiles).addQuery(anyLong(), anyString(), eq("SELECT * FROM x"),
                anyLong(), anyLong(), anyLong());
    }

    @Test
    void queryOutsideAVaadinRequest_belongsToNoInteraction() throws Exception {
        // A scheduled job, a warm-up query at startup, a background pool: no
        // current UI, so no interaction — and nothing may throw, because this
        // runs inside the application's own JDBC call.
        query(new DatabaseQueryProfiler(() -> profiles), "SELECT * FROM x");

        verifyNoInteractions(profiles);
        assertThat(
                registry.find(MeterNames.DB_FETCH_ROWS).summary().totalAmount())
                .as("the row count is recorded either way").isEqualTo(2.0);
    }

    @Test
    void inProductionMode_thereIsNoStoreAndNothingIsRecorded()
            throws Exception {
        // The store is only bound outside production mode, so the profiler
        // finds none there and every query takes the no-op path.
        handlingAnInteraction();

        query(new DatabaseQueryProfiler(() -> null), "SELECT * FROM x");

        verifyNoInteractions(profiles);
        assertThat(
                registry.find(MeterNames.DB_FETCH_ROWS).summary().totalAmount())
                .isEqualTo(2.0);
    }
}
