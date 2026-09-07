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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.VaadinTelemetryContext;
import com.vaadin.observability.micrometer.insights.ProfileStore.UiKey;

/**
 * Verifies that the dev-mode store keeps one profile per browser tab: every
 * interaction of that tab, bounded, and gone when the tab is.
 */
class ProfileStoreTest {

    private static final int CAPACITY = 3;
    private static final int MAX_UIS = 2;

    private final ProfileStore store = new ProfileStore(CAPACITY, true,
            MAX_UIS);

    /** A successful, well within budget interaction of the given tab. */
    private static CapturedInteraction interaction(String sessionId, int uiId,
            String event) {
        return new CapturedInteraction(Instant.now(), "orders", "orders/17",
                "com.example.OrdersGrid", event, "event",
                CapturedInteraction.OUTCOME_SUCCESS, 300, 0, true, null, null,
                null, null, sessionId, uiId);
    }

    private static List<String> events(List<ProfiledInteraction> profile) {
        return profile.stream().map(ProfiledInteraction::interaction)
                .map(CapturedInteraction::event).toList();
    }

    /**
     * A tab whose session and UI id are the ones an interaction of it carries.
     */
    private static UI tab(String sessionId, int uiId) {
        UI ui = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ui.getUIId()).thenReturn(uiId);
        Mockito.when(ui.getSession().getSession().getId())
                .thenReturn(sessionId);
        Mockito.when(ui.getInternals().getActiveRouterTargetsChain())
                .thenReturn(List.of());
        Mockito.when(ui.getInternals().getActiveViewLocation())
                .thenReturn(new Location("orders/17"));
        return ui;
    }

    /** Fires the detach the closing of a tab would fire. */
    @SuppressWarnings("unchecked")
    private static void close(UI ui) {
        ArgumentCaptor<ComponentEventListener<DetachEvent>> captor = ArgumentCaptor
                .forClass(ComponentEventListener.class);
        Mockito.verify(ui).addDetachListener(captor.capture());
        captor.getValue().onComponentEvent(Mockito.mock(DetachEvent.class));
    }

    @Test
    void twoTabsOfOneSessionAreProfiledApart() {
        // The developer has the same application open twice; each tab has to
        // report what happened in it, not what happened in the other one.
        store.add(interaction("session-a", 0, "click"));
        store.add(interaction("session-a", 1, "scroll"));
        store.add(interaction("session-a", 1, "keydown"));

        Assertions.assertEquals(List.of("click"),
                events(store.profile(new UiKey("session-a", 0))));
        Assertions.assertEquals(List.of("keydown", "scroll"),
                events(store.profile(new UiKey("session-a", 1))),
                "the second tab's own interactions, newest first");
    }

    @Test
    void twoSessionsDoNotShareAProfile() {
        // The uiId is only unique within a session, so it cannot be the key on
        // its own: two users' first tabs are both UI 0.
        store.add(interaction("session-a", 0, "click"));
        store.add(interaction("session-b", 0, "scroll"));

        Assertions.assertEquals(List.of("click"),
                events(store.profile(new UiKey("session-a", 0))));
        Assertions.assertEquals(List.of("scroll"),
                events(store.profile(new UiKey("session-b", 0))));
    }

    @Test
    void everyInteractionIsKeptNotOnlyTheFailedAndSlowOnes() {
        // The point of the store: a healthy click is exactly what a profiler
        // has to be able to show.
        store.add(interaction("session-a", 0, "click"));

        ProfiledInteraction profiled = store.profile(new UiKey("session-a", 0))
                .get(0);
        Assertions.assertEquals(CapturedInteraction.OUTCOME_SUCCESS,
                profiled.interaction().outcome());
        Assertions.assertEquals(300, profiled.interaction().durationMs(),
                "an interaction well within the UX budget is retained as it is");
    }

    @Test
    void idsIdentifyInteractionsInCaptureOrder() {
        // Children of an interaction (its queries) refer to it by id, so the
        // id has to be unique across the store and say which came later.
        store.add(interaction("session-a", 0, "click"));
        store.add(interaction("session-b", 1, "scroll"));

        long first = store.profile(new UiKey("session-a", 0)).get(0).id();
        long second = store.profile(new UiKey("session-b", 1)).get(0).id();
        Assertions.assertTrue(first > 0, "ids start above zero, got " + first);
        Assertions.assertTrue(second > first,
                "a later interaction gets a higher id, got " + first + " then "
                        + second);
    }

    @Test
    void aTabsProfileIsCappedAndDropsItsOldestInteraction() {
        for (int i = 0; i < CAPACITY + 2; i++) {
            store.add(interaction("session-a", 0, "click-" + i));
        }

        List<String> profile = events(store.profile(new UiKey("session-a", 0)));
        Assertions.assertEquals(CAPACITY, profile.size(),
                "the profile of one tab is capped at the capacity");
        Assertions.assertEquals(List.of("click-4", "click-3", "click-2"),
                profile,
                "the newest interactions survive, the oldest are evicted");
    }

    @Test
    void closingATabDropsItsProfile() {
        UI ui = tab("session-a", 0);
        store.track(ui);
        store.add(interaction("session-a", 0, "click"));
        Assertions.assertEquals(1, store.trackedUis());

        close(ui);

        Assertions.assertEquals(0, store.trackedUis());
        Assertions.assertTrue(store.profile(ui).isEmpty(),
                "a closed tab keeps nothing");
    }

    @Test
    void closingATabLeavesTheOtherTabsProfileAlone() {
        UI closing = tab("session-a", 0);
        UI staying = tab("session-a", 1);
        store.track(closing);
        store.track(staying);
        store.add(interaction("session-a", 0, "click"));
        store.add(interaction("session-a", 1, "scroll"));

        close(closing);

        Assertions.assertEquals(List.of("scroll"),
                events(store.profile(staying)));
    }

    @Test
    void theNumberOfProfiledTabsIsBounded() {
        // A tab whose detach never runs — a session restored from
        // serialization, a session that expired — must not keep its records
        // for the life of the server.
        for (int uiId = 0; uiId < MAX_UIS + 2; uiId++) {
            store.add(interaction("session-a", uiId, "click"));
        }

        Assertions.assertEquals(MAX_UIS, store.trackedUis(),
                "no more than the UI limit of profiles are held");
        Assertions.assertTrue(
                store.profile(new UiKey("session-a", 0)).isEmpty(),
                "the least recently used profile is the one dropped");
        Assertions.assertFalse(
                store.profile(new UiKey("session-a", MAX_UIS + 1)).isEmpty(),
                "the profile of the tab that just interacted is kept");
    }

    @Test
    void anUnknownTabHasAnEmptyProfile() {
        Assertions.assertTrue(store.profile(tab("session-a", 0)).isEmpty());
        Assertions.assertTrue(store.profile((UI) null).isEmpty(),
                "no UI to profile is not a failure");
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void profilesTheTabTheCollectorLabelledTheInteractionWith(boolean details) {
        // The store keys by the session id the record carries, which is the raw
        // one or a hash of it depending on insights-details. Whichever it is,
        // looking a UI up has to arrive at the same key — so the collector and
        // the store are wired together here rather than asserted apart.
        ProfileStore store = new ProfileStore(CAPACITY, details, MAX_UIS);
        ObservabilitySettings settings = ObservabilitySettings.builder()
                .errors(true).requests(true).insightsDetails(details).build();
        InteractionCollector collector = InteractionCollector
                .retainingEverything(store, settings);
        UI ui = tab("session-a", 3);

        collector.invocationStarted(started(ui));
        collector.invocationEnded(ended(ui));

        List<ProfiledInteraction> profile = store.profile(ui);
        Assertions.assertEquals(1, profile.size(),
                "the interaction should be found under the UI it came from");
        Assertions.assertEquals("click", profile.get(0).interaction().event());
    }

    private static RpcInvocationStartedEvent started(UI ui) {
        RpcInvocationStartedEvent event = Mockito
                .mock(RpcInvocationStartedEvent.class);
        Mockito.when(event.getType()).thenReturn("event");
        Mockito.when(event.getName()).thenReturn("click");
        Mockito.when(event.getUI()).thenReturn(ui);
        // No target node: this test is about the key, not the component.
        Mockito.when(event.getNodeId()).thenReturn(-1);
        return event;
    }

    private static RpcInvocationEndedEvent ended(UI ui) {
        RpcInvocationEndedEvent event = Mockito
                .mock(RpcInvocationEndedEvent.class);
        Mockito.when(event.getType()).thenReturn("event");
        Mockito.when(event.getName()).thenReturn("click");
        Mockito.when(event.getUI()).thenReturn(ui);
        Mockito.when(event.getNodeId()).thenReturn(-1);
        return event;
    }

    /**
     * Opens an interaction for the tab the way the collector does, and hands
     * back the id its queries are recorded against — which is the id the store
     * left on the UI, since that is all a query has to go on.
     */
    private long begin(UI ui) {
        store.begin(ui);
        long interactionId = VaadinTelemetryContext.interactionId(ui);
        Assertions.assertNotEquals(VaadinTelemetryContext.NO_INTERACTION,
                interactionId,
                "beginning an interaction should mark it on the UI, so a "
                        + "query running on that thread can find it");
        return interactionId;
    }

    private void addQuery(long interactionId, String statement, long rows) {
        store.addQuery(interactionId, ProfiledQuery.KIND_JDBC, statement, rows,
                1, System.nanoTime());
    }

    @Test
    void theQueriesAnInteractionRanAreItsChildren() {
        UI ui = tab("session-a", 0);
        long interactionId = begin(ui);
        addQuery(interactionId, "select * from item limit 50", 50);
        addQuery(interactionId, "select count(*) from item", 1);
        store.add(interaction("session-a", 0, "click"));

        ProfiledInteraction profiled = store.profile(ui).get(0);
        Assertions.assertEquals(interactionId, profiled.id(),
                "the queries were recorded against the id the store published "
                        + "on the UI, so it has to be this interaction's id");
        Assertions.assertEquals(
                List.of("select * from item limit 50",
                        "select count(*) from item"),
                profiled.queries().stream().map(ProfiledQuery::statement)
                        .toList(),
                "the children read as a timeline: in the order they ran");
        Assertions.assertEquals(50, profiled.queries().get(0).rows());
    }

    @Test
    void theQueryRunOncePerRowGroupsIntoOneStatement() {
        // The headline finding of a profiler: a grid that looks up a supplier
        // per row ran one query fifty times, not fifty queries.
        UI ui = tab("session-a", 0);
        long interactionId = begin(ui);
        addQuery(interactionId, "select * from item limit 50", 50);
        for (int row = 0; row < 50; row++) {
            addQuery(interactionId,
                    "select name from supplier where id = " + row, 1);
        }
        store.add(interaction("session-a", 0, "scroll"));

        ProfiledInteraction profiled = store.profile(ui).get(0);
        Assertions.assertEquals(51, profiled.queries().size(),
                "the page load plus one lookup per row");
        List<ProfiledQueryGroup> groups = profiled.queryGroups();
        Assertions.assertEquals(2, groups.size(),
                "fifty-one queries, two statements");
        Assertions.assertEquals(50, groups.get(0).count(),
                "the repeated statement comes first, with its count");
        Assertions.assertEquals("select name from supplier where id = ?",
                groups.get(0).statement());
        Assertions.assertEquals(1, groups.get(1).count());
    }

    @Test
    void aQueryThatRanAfterTheInvocationEndedStillBelongsToIt() {
        // A lazy-loading component queries its data provider while the
        // response is being built, after RPC handling has ended. Those are
        // exactly the queries a profiler is asked about, so the interaction
        // has to keep accepting children after it has been captured.
        UI ui = tab("session-a", 0);
        long interactionId = begin(ui);
        store.add(interaction("session-a", 0, "click"));

        addQuery(interactionId, "select * from item limit 50", 50);

        Assertions.assertEquals(1, store.profile(ui).get(0).queries().size());
    }

    @Test
    void anInteractionStillBeingHandledIsNotReportedYet() {
        UI ui = tab("session-a", 0);
        long interactionId = begin(ui);
        addQuery(interactionId, "select * from item", 50);

        Assertions.assertTrue(store.profile(ui).isEmpty(),
                "nothing was captured of it yet, so there is nothing to "
                        + "report about it");

        store.add(interaction("session-a", 0, "click"));

        Assertions.assertEquals(1, store.profile(ui).size(),
                "and once it is captured it arrives with the queries it ran");
        Assertions.assertEquals(1, store.profile(ui).get(0).queries().size());
    }

    @Test
    void aQueryOfNoInteractionIsDropped() {
        // A query on a background thread reports no interaction, and a query
        // whose interaction the store never saw (or has already evicted) is
        // just as unattachable. Neither may throw: this runs inside the
        // application's own JDBC call.
        UI ui = tab("session-a", 0);
        long interactionId = begin(ui);
        store.add(interaction("session-a", 0, "click"));

        addQuery(VaadinTelemetryContext.NO_INTERACTION, "select 1", 1);
        addQuery(interactionId + 1000, "select 2", 1);

        Assertions.assertTrue(store.profile(ui).get(0).queries().isEmpty());
    }

    @Test
    void theQueriesOfAnEvictedInteractionGoWithIt() {
        UI ui = tab("session-a", 0);
        long evicted = begin(ui);
        store.add(interaction("session-a", 0, "click-0"));
        for (int i = 1; i <= CAPACITY; i++) {
            begin(ui);
            store.add(interaction("session-a", 0, "click-" + i));
        }

        addQuery(evicted, "select * from item", 50);

        Assertions.assertEquals(CAPACITY, store.profile(ui).size());
        Assertions.assertTrue(
                store.profile(ui).stream()
                        .allMatch(profiled -> profiled.queries().isEmpty()),
                "a query recorded against an interaction that has been "
                        + "evicted must not attach itself to another one");
    }

    @Test
    void theQueriesOfAClosedTabAreDroppedWithIt() {
        UI ui = tab("session-a", 0);
        long interactionId = begin(ui);
        store.track(ui);
        store.add(interaction("session-a", 0, "click"));

        close(ui);
        addQuery(interactionId, "select * from item", 50);
        store.add(interaction("session-a", 0, "click"));

        Assertions.assertTrue(store.profile(ui).get(0).queries().isEmpty(),
                "the closed tab's interactions are gone, and so is the index "
                        + "a late query would have found them by");
    }

    @Test
    void aQueryIsPlacedInTheInteractionsTimeline() {
        UI ui = tab("session-a", 0);
        long beforeBegin = System.nanoTime();
        long interactionId = begin(ui);
        store.add(interaction("session-a", 0, "click"));

        store.addQuery(interactionId, ProfiledQuery.KIND_JDBC, "select 1", 1, 5,
                beforeBegin + TimeUnit.SECONDS.toNanos(1));
        // A statement left open across invocations can look as if it started
        // before the interaction that is stopping it.
        store.addQuery(interactionId, ProfiledQuery.KIND_JDBC, "select 2", 1, 5,
                beforeBegin - TimeUnit.SECONDS.toNanos(1));

        List<ProfiledQuery> queries = store.profile(ui).get(0).queries();
        long offset = queries.get(0).startOffsetMs();
        Assertions.assertTrue(offset > 900 && offset <= 1000,
                "a query a second into the interaction should say so, got "
                        + offset);
        Assertions.assertEquals(0, queries.get(1).startOffsetMs(),
                "and one that predates the interaction reads as starting "
                        + "with it rather than before it");
    }

    @Test
    void aRunawayInteractionStopsRecordingQueriesAtTheCap() {
        // Development mode or not, an interaction that ran a hundred thousand
        // queries must not be able to fill the developer's heap. By then the
        // finding has been delivered several hundred times over.
        UI ui = tab("session-a", 0);
        long interactionId = begin(ui);
        store.add(interaction("session-a", 0, "click"));
        for (int i = 0; i < ProfileStore.MAX_QUERIES_PER_INTERACTION
                + 10; i++) {
            addQuery(interactionId, "select * from item where id = " + i, 1);
        }

        Assertions.assertEquals(ProfileStore.MAX_QUERIES_PER_INTERACTION,
                store.profile(ui).get(0).queries().size());
    }

    @Test
    void beginningAnInteractionOfNoTabIsNotAFailure() {
        // The RPC events carry a UI, but nothing guarantees one.
        store.begin(null);

        Assertions.assertEquals(0, store.trackedUis());
    }

    @Test
    void anInteractionCapturedWithoutBeingBegunIsStillKept() {
        // The insights collector never calls begin(); a store fed by one has
        // to keep every interaction all the same, just without children.
        store.add(interaction("session-a", 0, "click"));

        ProfiledInteraction profiled = store.profile(new UiKey("session-a", 0))
                .get(0);
        Assertions.assertEquals("click", profiled.interaction().event());
        Assertions.assertTrue(profiled.queries().isEmpty());
    }

    @Test
    void rejectsBoundsNothingCouldBeKeptUnder() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ProfileStore(0, true, MAX_UIS));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ProfileStore(CAPACITY, true, 0));
    }

    @Test
    void takesItsCapacityFromTheSettings() {
        ProfileStore store = new ProfileStore(ObservabilitySettings.builder()
                .insightsCapacity(1).insightsDetails(true).build());

        store.add(interaction("session-a", 0, "click"));
        store.add(interaction("session-a", 0, "scroll"));

        Assertions.assertEquals(List.of("scroll"),
                events(store.profile(new UiKey("session-a", 0))),
                "insights-capacity should bound one tab's profile");
    }
}
