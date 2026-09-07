/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.data.DataCountEndedEvent;
import com.vaadin.flow.server.data.DataCountStartedEvent;
import com.vaadin.flow.server.data.DataFetchEndedEvent;
import com.vaadin.flow.server.data.DataFetchFailedEvent;
import com.vaadin.flow.server.data.DataFetchStartedEvent;
import com.vaadin.observability.micrometer.ObservabilitySettings;

class DataQueryCollectorTest {

    /** Budget of 0 makes any query qualify as slow. */
    private static final long CAPTURE_ALL = 0;
    /** Budget beyond any elapsed time, so nothing qualifies as slow. */
    private static final long CAPTURE_NONE = Long.MAX_VALUE;

    @Tag("test-combo")
    private static class TestComponent extends Component {
    }

    private final RecentQueries buffer = new RecentQueries(100);
    private final UI ui = new UI();
    private final Component component = new TestComponent();

    private DataQueryCollector collector(long budget) {
        return collector(budget, null);
    }

    private DataQueryCollector collector(long budget, ProfileStore profiles) {
        return new DataQueryCollector(buffer, profiles, ObservabilitySettings
                .builder().errors(true).requests(true).build(), budget);
    }

    @Test
    void capturesSlowFetchWithTheRangeAndRowCount() {
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 100, 50, true));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 100, 50, true, 30));

        List<CapturedQuery> snapshot = buffer.snapshot();
        Assertions.assertEquals(1, snapshot.size());
        CapturedQuery query = snapshot.get(0);
        Assertions.assertEquals(CapturedQuery.KIND_FETCH, query.kind());
        Assertions.assertEquals(CapturedQuery.OUTCOME_SUCCESS, query.outcome());
        Assertions.assertEquals(component.getClass().getName(),
                query.component());
        Assertions.assertEquals(100, query.offset());
        Assertions.assertEquals(50, query.limit());
        Assertions.assertEquals(30, query.rows(),
                "a short page must stay distinguishable from the limit");
        Assertions.assertTrue(query.filtered());
    }

    @Test
    void capturesFailedFetchWithTheExceptionType() {
        collector(CAPTURE_NONE)
                .fetchFailed(new DataFetchFailedEvent(ui, component, 0, 50,
                        false, new IllegalStateException("backend down")));

        CapturedQuery query = buffer.snapshot().get(0);
        Assertions.assertEquals(CapturedQuery.OUTCOME_ERROR, query.outcome());
        Assertions.assertEquals("java.lang.IllegalStateException",
                query.exceptionType());
        Assertions.assertEquals(-1, query.rows(),
                "a failed fetch has no row count");
    }

    @Test
    void reportsTheRootCauseRatherThanTheWrapper() {
        collector(CAPTURE_NONE).fetchFailed(new DataFetchFailedEvent(ui,
                component, 0, 10, false, new RuntimeException("wrapped",
                        new IllegalArgumentException("the real problem"))));

        Assertions.assertEquals("java.lang.IllegalArgumentException",
                buffer.snapshot().get(0).exceptionType(),
                "the root cause is what identifies the problem");
    }

    /**
     * The store as the dev-mode profiler has it, and one interaction already
     * open on the tab — which is what the collector attributes queries to.
     */
    private ProfileStore profilesWithAnOpenInteraction() {
        ProfileStore profiles = new ProfileStore(
                ObservabilitySettings.builder().insightsDetails(true).build());
        profiles.begin(ui);
        return profiles;
    }

    /** Completes the open interaction, so the profile reports it. */
    private void endInteraction(ProfileStore profiles) {
        profiles.add(new CapturedInteraction(Instant.now(), "orders",
                "orders/17", component.getClass().getName(), "click", "event",
                CapturedInteraction.OUTCOME_SUCCESS, 20, 0, true, null, null,
                null, null, null, ui.getUIId()));
    }

    @Test
    void everyQueryIsAChildOfTheInteractionEvenWhenNothingWasSlow() {
        // What the profiler answers is "what did my click cost", and a click
        // that ran a fast count and a fast fetch has to show both. The budget
        // still governs the insights buffer, which wants neither.
        ProfileStore profiles = profilesWithAnOpenInteraction();
        DataQueryCollector collector = collector(CAPTURE_NONE, profiles);

        collector.countStarted(new DataCountStartedEvent(ui, component, false));
        collector
                .countEnded(new DataCountEndedEvent(ui, component, false, 900));
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, true));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, true, 50));
        endInteraction(profiles);

        List<ProfiledQuery> queries = profiles.profile(ui).get(0).queries();
        Assertions.assertEquals(
                List.of(CapturedQuery.KIND_COUNT, CapturedQuery.KIND_FETCH),
                queries.stream().map(ProfiledQuery::kind).toList());
        Assertions.assertEquals(component.getClass().getName(),
                queries.get(0).statement(),
                "a count asked the component for its total and nothing else");
        Assertions.assertEquals(900, queries.get(0).rows(),
                "what a count returns is the total it reported");
        Assertions.assertEquals(
                component.getClass().getName() + " [0, 50] (filtered)",
                queries.get(1).statement(),
                "a fetch says who asked, for which range, and whether it was "
                        + "filtered");
        Assertions.assertEquals(50, queries.get(1).rows());
        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "and none of this is an insight: nothing was slow");
    }

    @Test
    void aFetchThatThrewIsStillSomethingTheInteractionDid() {
        ProfileStore profiles = profilesWithAnOpenInteraction();
        DataQueryCollector collector = collector(CAPTURE_NONE, profiles);

        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        collector.fetchFailed(new DataFetchFailedEvent(ui, component, 0, 50,
                false, new IllegalStateException("backend down")));
        // Flow ends the fetch after failing it, with -1 for the rows it never
        // returned.
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, -1));
        endInteraction(profiles);

        List<ProfiledQuery> queries = profiles.profile(ui).get(0).queries();
        Assertions.assertEquals(1, queries.size(),
                "the failure and the end of one fetch are one query");
        Assertions.assertEquals(-1, queries.get(0).rows(),
                "a fetch that threw returned nothing, and says so");
    }

    @Test
    void aQueryOfATabThatIsHandlingNoInteractionIsNotProfiled() {
        // A data provider queried outside a user interaction — a background
        // refresh, a query on a UI the profiler never opened an interaction
        // for — belongs to nothing, and may not attach itself to whatever
        // came before.
        ProfileStore profiles = new ProfileStore(
                ObservabilitySettings.builder().insightsDetails(true).build());
        DataQueryCollector collector = collector(CAPTURE_NONE, profiles);

        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 50));
        endInteraction(profiles);

        Assertions.assertTrue(profiles.profile(ui).get(0).queries().isEmpty());
    }

    @Test
    void theQueriesOfOneInteractionGroupByWhatWasAsked() {
        // The combo box that counts once and then fetches page after page:
        // one count, one fetch, whatever the pages.
        ProfileStore profiles = profilesWithAnOpenInteraction();
        DataQueryCollector collector = collector(CAPTURE_NONE, profiles);

        collector.countStarted(new DataCountStartedEvent(ui, component, false));
        collector
                .countEnded(new DataCountEndedEvent(ui, component, false, 900));
        for (int page = 0; page < 3; page++) {
            collector.fetchStarted(new DataFetchStartedEvent(ui, component,
                    page * 50, 50, false));
            collector.fetchEnded(new DataFetchEndedEvent(ui, component,
                    page * 50, 50, false, 50));
        }
        endInteraction(profiles);

        List<ProfiledQueryGroup> groups = profiles.profile(ui).get(0)
                .queryGroups();
        Assertions.assertEquals(2, groups.size());
        Assertions.assertEquals(3, groups.get(0).count(),
                "three pages of the same fetch");
        Assertions.assertEquals(CapturedQuery.KIND_FETCH, groups.get(0).kind());
        Assertions.assertEquals(1, groups.get(1).count());
    }

    @Test
    void doesNotCaptureFastQueries() {
        DataQueryCollector collector = collector(CAPTURE_NONE);
        collector.countStarted(new DataCountStartedEvent(ui, component, false));
        collector
                .countEnded(new DataCountEndedEvent(ui, component, false, 250));

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "a query within the budget is not retained");
    }

    @Test
    void doesNotCaptureACountThatThrewAsSlow() {
        // -1 marks a failure, which countFailed already captured; it must not
        // also surface as a slow successful count.
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.countStarted(new DataCountStartedEvent(ui, component, false));
        collector.countEnded(new DataCountEndedEvent(ui, component, false, -1));

        Assertions.assertTrue(buffer.snapshot().isEmpty(),
                "a failed count is not a slow successful one");
    }

    @Test
    void slowQueriesBecomeAGroupedInsight() {
        DataQueryCollector collector = collector(CAPTURE_ALL);
        for (int i = 0; i < 3; i++) {
            collector.fetchStarted(
                    new DataFetchStartedEvent(ui, component, 0, 50, false));
            collector.fetchEnded(
                    new DataFetchEndedEvent(ui, component, 0, 50, false, 50));
        }

        Map<String, Object> payload = new InsightsService(null, buffer)
                .payload();
        Assertions.assertEquals("active", payload.get("instrumentation"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) payload
                .get("insights");
        Assertions.assertEquals(1, insights.size(),
                "three occurrences of one problem are one insight");
        Map<String, Object> insight = insights.get(0);
        Assertions.assertEquals("slow-data-query", insight.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insight
                .get("evidence");
        Assertions.assertEquals(3, evidence.get("occurrences"));
        Assertions.assertEquals(CapturedQuery.KIND_FETCH,
                evidence.get("queryKind"));
        Assertions.assertEquals(50, evidence.get("requested"));
        Assertions.assertEquals(50, evidence.get("returned"));
    }

    @Test
    void interactionAndQueryInsightsCoexistInOnePayload() {
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 50));

        Map<String, Object> payload = new InsightsService(
                new RecentInteractions(10), buffer).payload();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) payload
                .get("insights");
        Assertions.assertEquals(1, insights.size(),
                "an empty interaction buffer contributes nothing, but the "
                        + "query insight still appears");
    }

    @Test
    void aCyclicCauseChainDoesNotSpinForever() {
        // A caused by B caused by A. This runs while the server is already
        // handling a failure, so looping here would hang the request.
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        collector(CAPTURE_NONE).fetchFailed(
                new DataFetchFailedEvent(ui, component, 0, 10, false, a));

        Assertions.assertEquals("java.lang.RuntimeException",
                buffer.snapshot().get(0).exceptionType(),
                "the walk stops at the loop rather than never returning");
    }

    @Test
    void aSlowCountInsightReportsWhatItCounted() {
        // The duration alone does not explain a slow count. The size of the
        // result does, and the collector already captures it.
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.countStarted(new DataCountStartedEvent(ui, component, false));
        collector.countEnded(
                new DataCountEndedEvent(ui, component, false, 2_000_000));

        Map<String, Object> payload = new InsightsService(null, buffer)
                .payload();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) payload
                .get("insights");
        Map<String, Object> insight = insights.get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insight
                .get("evidence");
        Assertions.assertEquals(2_000_000, evidence.get("counted"),
                "a slow count has to say how much it counted");
        Assertions.assertTrue(
                insight.get("summary").toString().contains("2,000,000"),
                "and say it where a reader looks first");
    }

    @Test
    void theSummaryReadsTheSameUnderEveryDefaultLocale() {
        // The payload is a contract read by machines, so the numbers in it
        // cannot come from the server's locale. They did: `%,d` takes its
        // grouping separator from the default format locale (2.000.000 under
        // de-DE, 2 000 000 under fi-FI) and plain `%d` takes its digits from
        // it, so ar-EG rendered "takes ١٢ ms".
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try {
            String reference = null;
            for (String tag : new String[] { "en-US", "fi-FI", "de-DE", "ar-EG",
                    "hi-IN-u-nu-deva" }) {
                Locale.setDefault(Locale.Category.FORMAT,
                        Locale.forLanguageTag(tag));

                RecentQueries queries = new RecentQueries(10);
                DataQueryCollector collector = new DataQueryCollector(
                        queries, null, ObservabilitySettings.builder()
                                .errors(true).requests(true).build(),
                        CAPTURE_ALL);
                collector.countStarted(
                        new DataCountStartedEvent(ui, component, false));
                collector.countEnded(new DataCountEndedEvent(ui, component,
                        false, 2_000_000));

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                        null, queries).payload().get("insights");
                String summary = insights.get(0).get("summary").toString();

                Assertions.assertTrue(summary.contains("2,000,000"),
                        () -> "grouping separator followed the locale under "
                                + tag + ": " + summary);
                if (reference == null) {
                    reference = summary;
                } else {
                    Assertions.assertEquals(reference, summary,
                            () -> "the summary differed under " + tag);
                }
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    @Test
    void aSlowFetchInsightStillReportsRequestedAgainstReturned() {
        DataQueryCollector collector = collector(CAPTURE_ALL);
        collector.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, false));
        collector.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, false, 30));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> insights = (List<Map<String, Object>>) new InsightsService(
                null, buffer).payload().get("insights");
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) insights.get(0)
                .get("evidence");

        Assertions.assertEquals(50, evidence.get("requested"));
        Assertions.assertEquals(30, evidence.get("returned"));
        Assertions.assertNull(evidence.get("counted"),
                "a fetch reports a range, not a count");
    }
}
