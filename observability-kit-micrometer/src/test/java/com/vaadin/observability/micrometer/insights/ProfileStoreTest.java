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
import java.util.ArrayList;
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
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.UiStateSample;
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

    /** A measurement of a tab holding the given number of nodes. */
    private static UiStateSample state(int nodes) {
        return new UiStateSample(nodes, nodes / 10, 1, 0, System.nanoTime());
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

    /** A panel watching one tab, keeping what it was told. */
    private static final class Watcher implements ProfileListener {
        private final List<ProfiledInteraction> interactions = new ArrayList<>();
        private final List<UiStateSample> states = new ArrayList<>();

        @Override
        public void interactionRecorded(ProfiledInteraction interaction) {
            interactions.add(interaction);
        }

        @Override
        public void uiStateSampled(UiStateSample sample) {
            states.add(sample);
        }

        List<String> events() {
            return interactions.stream().map(ProfiledInteraction::interaction)
                    .map(CapturedInteraction::event).toList();
        }
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
    void aTabReportsTheStateItWasLastMeasuredToHold() {
        // "How much memory is my view holding" is the question, and the answer
        // is about the tab as it is now — so a second measurement replaces the
        // first rather than joining it.
        UI ui = tab("session-a", 0);

        store.uiStateSampled(ui, state(120));
        store.uiStateSampled(ui, state(4000));

        Assertions.assertEquals(4000, store.uiState(ui).nodes());
    }

    @Test
    void aTabIsMeasuredEvenBeforeItHasInteracted() {
        // Navigating to a view is measured where the navigation happens, which
        // is before anything has been clicked in it. That state is exactly what
        // the panel opens on.
        UI ui = tab("session-a", 0);

        store.uiStateSampled(ui, state(4000));

        Assertions.assertEquals(4000, store.uiState(ui).nodes());
        Assertions.assertTrue(store.profile(ui).isEmpty(),
                "and it has done nothing yet");
    }

    @Test
    void eachTabHoldsItsOwnState() {
        // The developer looking at their grid tab must not be shown what the
        // login tab next to it holds.
        UI first = tab("session-a", 0);
        UI second = tab("session-a", 1);

        store.uiStateSampled(first, state(120));
        store.uiStateSampled(second, state(4000));

        Assertions.assertEquals(120, store.uiState(first).nodes());
        Assertions.assertEquals(4000, store.uiState(second).nodes());
    }

    @Test
    void twoSessionsDoNotShareAState() {
        // The uiId alone cannot key a measurement either: two users' first
        // tabs are both UI 0.
        UI ui = tab("session-a", 0);
        UI otherUser = tab("session-b", 0);

        store.uiStateSampled(ui, state(4000));
        store.uiStateSampled(otherUser, state(70));

        Assertions.assertEquals(4000, store.uiState(ui).nodes());
        Assertions.assertEquals(70, store.uiState(otherUser).nodes());
    }

    @Test
    void anUnmeasuredTabHasNoState() {
        // The store never measures anything itself, so having no figure is a
        // normal answer: UI-state instrumentation may not have run yet.
        store.add(interaction("session-a", 0, "click"));

        Assertions.assertNull(store.uiState(tab("session-a", 0)),
                "an interaction is not a measurement");
        Assertions.assertNull(store.uiState((UI) null),
                "no UI to report on is not a failure");
        Assertions.assertNull(store.uiState(tab("session-b", 7)),
                "and neither is a tab the store has never seen");
    }

    @Test
    void aMeasurementOfNoTabIsIgnored() {
        Assertions.assertDoesNotThrow(() -> {
            store.uiStateSampled(null, state(120));
            store.uiStateSampled(tab("session-a", 0), null);
        });
        Assertions.assertEquals(0, store.trackedUis());
    }

    @Test
    void closingATabDropsTheStateItHeld() {
        UI ui = tab("session-a", 0);
        store.track(ui);
        store.uiStateSampled(ui, state(4000));

        close(ui);

        Assertions.assertNull(store.uiState(ui),
                "a closed tab holds no state, and the store holds none of it");
    }

    @Test
    void theStateOfAnEvictedProfileGoesWithIt() {
        // A tab whose detach never runs is evicted by the UI limit; its state
        // sample must not be what outlives it.
        UI evicted = tab("session-a", 0);
        store.uiStateSampled(evicted, state(4000));
        for (int uiId = 1; uiId <= MAX_UIS; uiId++) {
            store.uiStateSampled(tab("session-a", uiId), state(120));
        }

        Assertions.assertEquals(MAX_UIS, store.trackedUis(),
                "a tab that is only measured still counts against the limit");
        Assertions.assertNull(store.uiState(evicted));
    }

    @Test
    void aWatcherIsToldAsEachInteractionOfItsTabCompletes() {
        // What makes the panel push-driven: the developer clicks and the row
        // is there, rather than there on the next poll.
        UI ui = tab("session-a", 0);
        Watcher watcher = new Watcher();
        store.listen(ui, watcher);

        store.add(interaction("session-a", 0, "click"));
        store.add(interaction("session-a", 0, "keydown"));

        Assertions.assertEquals(List.of("click", "keydown"), watcher.events(),
                "in the order they happened, unlike a profile");
    }

    @Test
    void aPushedInteractionCarriesTheQueriesItRan() {
        // The panel draws a waterfall from the message it is pushed, so the
        // children have to be on it — an interaction is complete when it is
        // announced, and nothing goes back to the store for the rest.
        UI ui = tab("session-a", 0);
        Watcher watcher = new Watcher();
        store.listen(ui, watcher);

        addQuery(begin(ui), "select * from orders where id=?", 1);
        store.add(interaction("session-a", 0, "click"));

        Assertions.assertEquals(List.of("select * from orders where id=?"),
                watcher.interactions.get(0).queries().stream()
                        .map(ProfiledQuery::statement).toList());
    }

    @Test
    void aWatcherHearsAboutNoOtherTab() {
        // A subscription is to one tab: the panel of one tab must not be told
        // what the tab beside it — or another user's — is doing.
        UI ui = tab("session-a", 0);
        Watcher watcher = new Watcher();
        store.listen(ui, watcher);

        store.add(interaction("session-a", 1, "click"));
        store.add(interaction("session-b", 0, "click"));
        store.uiStateSampled(tab("session-a", 1), state(4000));

        Assertions.assertEquals(List.of(), watcher.events());
        Assertions.assertEquals(List.of(), watcher.states);
    }

    @Test
    void aWatcherIsToldWhenItsTabIsMeasured() {
        UI ui = tab("session-a", 0);
        Watcher watcher = new Watcher();
        store.listen(ui, watcher);

        store.uiStateSampled(ui, state(4000));

        Assertions.assertEquals(List.of(4000),
                watcher.states.stream().map(UiStateSample::nodes).toList());
    }

    @Test
    void removingTheHandleStopsTheMessages() {
        UI ui = tab("session-a", 0);
        Watcher watcher = new Watcher();
        Registration registration = store.listen(ui, watcher);

        registration.remove();
        store.add(interaction("session-a", 0, "click"));
        store.uiStateSampled(ui, state(4000));

        Assertions.assertEquals(List.of(), watcher.events());
        Assertions.assertEquals(List.of(), watcher.states);
    }

    @Test
    void closingATabDropsWhoeverWasWatchingIt() {
        // A panel does not have to unsubscribe from a tab that is gone: the
        // subscription ends with the shorter-lived of the two.
        UI ui = tab("session-a", 0);
        Watcher watcher = new Watcher();
        store.track(ui);
        store.listen(ui, watcher);

        close(ui);
        store.add(interaction("session-a", 0, "click"));

        Assertions.assertEquals(List.of(), watcher.events());
    }

    @Test
    void aWatcherThatFailsCostsNothingButItsOwnMessage() {
        // Told on the request thread of the interaction it is about: a panel
        // whose connection is on its way out must not fail the click.
        UI ui = tab("session-a", 0);
        Watcher second = new Watcher();
        store.listen(ui, new ProfileListener() {
            @Override
            public void interactionRecorded(ProfiledInteraction interaction) {
                throw new IllegalStateException("the connection is closed");
            }

            @Override
            public void uiStateSampled(UiStateSample sample) {
                throw new IllegalStateException("the connection is closed");
            }
        });
        store.listen(ui, second);

        Assertions.assertDoesNotThrow(() -> {
            store.add(interaction("session-a", 0, "click"));
            store.uiStateSampled(ui, state(4000));
        });

        Assertions.assertEquals(List.of("click"), second.events(),
                "the other watcher is told all the same");
        Assertions.assertEquals(List.of("click"), events(store.profile(ui)),
                "and the interaction is kept");
    }

    @Test
    void watchingNothingIsNotAFailure() {
        Assertions.assertDoesNotThrow(() -> {
            store.listen(null, new Watcher()).remove();
            store.listen(tab("session-a", 0), null).remove();
        });
    }

    @Test
    void clearingATabForgetsWhatItDid() {
        // The panel's Clear button: the developer has read what is there and
        // wants the next click to arrive on an empty list — including after a
        // reload, which loads the profile again.
        UI ui = tab("session-a", 0);
        store.add(interaction("session-a", 0, "click"));

        store.clear(ui);

        Assertions.assertTrue(store.profile(ui).isEmpty());
        store.add(interaction("session-a", 0, "keydown"));
        Assertions.assertEquals(List.of("keydown"), events(store.profile(ui)),
                "and the tab goes on being profiled");
    }

    @Test
    void clearingATabKeepsTheStateItHolds() {
        // State is what the tab holds now, not something it did; clearing the
        // list of interactions is not a reason for the panel's footer to go
        // blank until the next measurement.
        UI ui = tab("session-a", 0);
        store.uiStateSampled(ui, state(4000));

        store.clear(ui);

        Assertions.assertEquals(4000, store.uiState(ui).nodes());
    }

    @Test
    void clearingKeepsTheInteractionStillBeingHandled() {
        // Clear arrives on the dev-tools connection, which is not the thread
        // handling the click: one in flight has to survive it, or the queries
        // it has already run are lost and its record never completes.
        UI ui = tab("session-a", 0);
        long inFlight = begin(ui);

        store.clear(ui);
        addQuery(inFlight, "select * from orders", 12);
        store.add(interaction("session-a", 0, "click"));

        List<ProfiledInteraction> profile = store.profile(ui);
        Assertions.assertEquals(List.of("click"), events(profile));
        Assertions.assertEquals(1, profile.get(0).queries().size(),
                "with the queries it had already run");
    }

    @Test
    void clearingATabNobodyHasProfiledIsNotAFailure() {
        Assertions.assertDoesNotThrow(() -> {
            store.clear(tab("session-a", 0));
            store.clear(null);
        });
        Assertions.assertEquals(0, store.trackedUis());
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
