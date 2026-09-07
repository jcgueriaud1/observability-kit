/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.devtools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import com.vaadin.base.devserver.DevToolsInterface;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.UiStateSample;
import com.vaadin.observability.micrometer.VaadinTelemetryContext;
import com.vaadin.observability.micrometer.insights.CapturedInteraction;
import com.vaadin.observability.micrometer.insights.ProfileStore;
import com.vaadin.observability.micrometer.insights.ProfiledQuery;

/**
 * Verifies the dev-tools protocol the Copilot panels code against: the whole
 * registry on refresh, the developer's own tab on profile — and nobody else's —
 * and the meters of one route on route-summary.
 */
class ObservabilityDevToolsHandlerTest {

    private static final String SESSION = "session-a";
    private static final String OTHER_SESSION = "session-b";
    private static final int UI_ID = 0;
    private static final int OTHER_UI_ID = 1;
    private static final String ROUTE = "orders/:orderId";

    /**
     * One dev-tools connection, recording what the handler sent it. Messages
     * are kept in order and not only per command, since a subscribed panel is
     * sent the same command over and over.
     */
    private static final class Sent implements DevToolsInterface {
        private final List<Map.Entry<String, Object>> messages = new ArrayList<>();

        @Override
        public void send(String command, Object data) {
            messages.add(Map.entry(command, data));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloads(String command) {
            return messages.stream().filter(m -> m.getKey().equals(command))
                    .map(m -> (Map<String, Object>) m.getValue()).toList();
        }

        Map<String, Object> payload(String command) {
            List<Map<String, Object>> sent = payloads(command);
            Assertions.assertFalse(sent.isEmpty(),
                    "no " + command + " message was sent");
            return sent.get(sent.size() - 1);
        }
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProfileStore profiles = new ProfileStore(
            ObservabilitySettings.builder().insightsDetails(true).build());
    private final VaadinSession session = Mockito.mock(VaadinSession.class);
    private final Sent sent = new Sent();

    private final ObservabilityDevToolsHandler handler = new ObservabilityDevToolsHandler(
            () -> registry, () -> profiles, () -> session);

    /** A tab, as the store keys the interactions it records for it. */
    private static UI tab(String sessionId, int uiId) {
        UI ui = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ui.getUIId()).thenReturn(uiId);
        Mockito.when(ui.getSession().getSession().getId())
                .thenReturn(sessionId);
        Mockito.when(ui.getInternals().getActiveViewLocation())
                .thenReturn(new Location("orders/17"));
        return ui;
    }

    /**
     * A tab of the developer's own session, reachable by its UI id the way the
     * handler resolves it. The lock is reported as held, which is the path a
     * handler called from a thread that already has the session takes.
     */
    private UI ownTab(String sessionId, int uiId) {
        UI ui = tab(sessionId, uiId);
        Mockito.when(session.hasLock()).thenReturn(true);
        Mockito.when(session.getUIById(uiId)).thenReturn(ui);
        return ui;
    }

    private static CapturedInteraction interaction(String sessionId, int uiId,
            String event) {
        return new CapturedInteraction(Instant.now(), ROUTE, "orders/17",
                "com.example.OrdersGrid", event, "event",
                CapturedInteraction.OUTCOME_SUCCESS, 312, -1, true, null, null,
                null, null, sessionId, uiId);
    }

    private static ObjectNode data() {
        return JsonNodeFactory.instance.objectNode();
    }

    private void profile(int uiId) {
        Assertions.assertTrue(handler.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_PROFILE,
                data().put("uiId", uiId), sent));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> interactions() {
        return (List<Map<String, Object>>) sent
                .payload(ObservabilityDevToolsHandler.COMMAND_PROFILE_DATA)
                .get("interactions");
    }

    private List<Object> events() {
        return interactions().stream().map(i -> i.get("event")).toList();
    }

    private void subscribe(Sent connection, int uiId) {
        Assertions.assertTrue(handler.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_PROFILE_SUBSCRIBE,
                data().put("uiId", uiId), connection));
    }

    private void clear(int uiId) {
        Assertions.assertTrue(handler.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_PROFILE_CLEAR,
                data().put("uiId", uiId), sent));
    }

    /** The interactions pushed to a connection, in the order they arrived. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pushed(Sent connection) {
        return connection
                .payloads(ObservabilityDevToolsHandler.COMMAND_INTERACTION)
                .stream().map(message -> (Map<String, Object>) message
                        .get("interaction"))
                .toList();
    }

    private static List<Object> pushedEvents(Sent connection) {
        return pushed(connection).stream().map(i -> i.get("event")).toList();
    }

    private void routeSummary(String route) {
        Assertions.assertTrue(handler.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_ROUTE_SUMMARY,
                data().put("route", route), sent));
    }

    @SuppressWarnings("unchecked")
    private List<String> summarisedMeters() {
        return ((List<Map<String, Object>>) sent
                .payload(
                        ObservabilityDevToolsHandler.COMMAND_ROUTE_SUMMARY_DATA)
                .get("meters")).stream().map(m -> (String) m.get("name"))
                .toList();
    }

    @Test
    void refreshStillSnapshotsEveryVaadinMeter() {
        // The command the existing metrics panel polls with; its payload is
        // not ours to change.
        Counter.builder(MeterNames.ERRORS).tag(MeterNames.TAG_ROUTE, ROUTE)
                .register(registry).increment();
        Counter.builder("app.orders.placed").register(registry).increment();

        Assertions.assertTrue(handler.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_REFRESH, data(), sent));

        Map<String, Object> payload = sent
                .payload(ObservabilityDevToolsHandler.COMMAND_METRICS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> meters = (List<Map<String, Object>>) payload
                .get("meters");
        Assertions.assertEquals(List.of(MeterNames.ERRORS),
                meters.stream().map(m -> m.get("name")).toList(),
                "every vaadin.* meter and nothing else");
        Assertions.assertEquals(Map.of(MeterNames.TAG_ROUTE, ROUTE),
                meters.get(0).get("tags"));
        Assertions.assertEquals(1L, meters.get(0).get("count"));
        Assertions.assertNotNull(payload.get("timestamp"));
    }

    @Test
    void anUnknownCommandIsLeftToTheOtherHandlers() {
        Assertions.assertFalse(
                handler.handleMessage("copilot-something-else", data(), sent));
    }

    @Test
    void theProfileIsTheOwnTabsInteractionsNewestFirst() {
        ownTab(SESSION, UI_ID);
        profiles.add(interaction(SESSION, UI_ID, "click"));
        profiles.add(interaction(SESSION, UI_ID, "keydown"));

        profile(UI_ID);

        Assertions.assertEquals(List.of("keydown", "click"), events());
        Assertions.assertEquals(UI_ID,
                sent.payload(ObservabilityDevToolsHandler.COMMAND_PROFILE_DATA)
                        .get("uiId"),
                "the answer says which tab it is about");
    }

    @Test
    void twoTabsOfTheOwnSessionAreProfiledApart() {
        // The developer has the application open twice; each panel asks for
        // its own UI id and has to get its own tab's interactions back.
        ownTab(SESSION, UI_ID);
        ownTab(SESSION, OTHER_UI_ID);
        profiles.add(interaction(SESSION, UI_ID, "click"));
        profiles.add(interaction(SESSION, OTHER_UI_ID, "scroll"));

        profile(UI_ID);
        Assertions.assertEquals(List.of("click"), events());

        profile(OTHER_UI_ID);
        Assertions.assertEquals(List.of("scroll"), events());
    }

    @Test
    void anInteractionCarriesEveryFieldOfTheContract() {
        ownTab(SESSION, UI_ID);
        Instant when = Instant.now();
        profiles.add(new CapturedInteraction(when, ROUTE, "orders/17",
                "com.example.OrdersGrid", "click", "event",
                CapturedInteraction.OUTCOME_ERROR, 1200, 1000, true,
                "java.lang.IllegalStateException", "no order",
                "com.example.OrdersGrid.onClick(OrdersGrid.java:42)", null,
                SESSION, UI_ID));

        profile(UI_ID);

        Map<String, Object> json = interactions().get(0);
        Assertions.assertEquals(when.toEpochMilli(), json.get("timestamp"),
                "epoch milliseconds, like the envelope");
        Assertions.assertEquals(ROUTE, json.get("route"));
        Assertions.assertEquals("orders/17", json.get("location"));
        Assertions.assertEquals("com.example.OrdersGrid",
                json.get("component"));
        Assertions.assertEquals("click", json.get("event"));
        Assertions.assertEquals("event", json.get("rpcType"));
        Assertions.assertEquals(CapturedInteraction.OUTCOME_ERROR,
                json.get("outcome"));
        Assertions.assertEquals(1200L, json.get("durationMs"));
        Assertions.assertEquals("java.lang.IllegalStateException",
                json.get("exceptionType"));
        Assertions.assertEquals("no order", json.get("exceptionMessage"));
        Assertions.assertEquals(
                "com.example.OrdersGrid.onClick(OrdersGrid.java:42)",
                json.get("applicationFrame"));
        Assertions.assertNotNull(json.get("id"));
        Assertions.assertEquals(List.of(), json.get("queries"),
                "an interaction that ran none says so with an empty list");
    }

    @Test
    void theQueriesOfAnInteractionAreItsChildren() {
        UI ui = ownTab(SESSION, UI_ID);
        profiles.begin(ui);
        long interactionId = VaadinTelemetryContext.interactionId(ui);
        profiles.addQuery(interactionId, ProfiledQuery.KIND_JDBC,
                "select * from orders where id=?", 1, 8, System.nanoTime());
        profiles.add(interaction(SESSION, UI_ID, "click"));

        profile(UI_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queries = (List<Map<String, Object>>) interactions()
                .get(0).get("queries");
        Assertions.assertEquals(1, queries.size());
        Assertions.assertEquals(ProfiledQuery.KIND_JDBC,
                queries.get(0).get("kind"));
        Assertions.assertEquals("select * from orders where id=?",
                queries.get(0).get("sql"));
        Assertions.assertEquals(1L, queries.get(0).get("rows"));
        Assertions.assertEquals(8L, queries.get(0).get("durationMs"));
        Assertions.assertNotNull(queries.get(0).get("startOffsetMs"));
    }

    @Test
    void theQueriesThatAreTheSameQueryReachThePanelCounted() {
        UI ui = ownTab(SESSION, UI_ID);
        profiles.begin(ui);
        long interactionId = VaadinTelemetryContext.interactionId(ui);
        profiles.addQuery(interactionId, ProfiledQuery.KIND_JDBC,
                "select * from orders", 100, 12, System.nanoTime());
        // The N of an N+1: one lookup per row, differing only in the id.
        for (int id = 1; id <= 100; id++) {
            profiles.addQuery(interactionId, ProfiledQuery.KIND_JDBC,
                    "select * from customer where id=" + id, 1, 6,
                    System.nanoTime());
        }
        profiles.add(interaction(SESSION, UI_ID, "click"));

        profile(UI_ID);

        Map<String, Object> json = interactions().get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) json
                .get("queryGroups");
        Assertions.assertEquals(2, groups.size(),
                "101 queries, two different statements");
        Map<String, Object> worst = groups.get(0);
        Assertions.assertEquals("select * from customer where id=?",
                worst.get("statement"),
                "the literal is parameterised away, which is what makes the "
                        + "hundred lookups one statement");
        Assertions.assertEquals(100, worst.get("count"),
                "most repeated first, so the N+1 leads");
        Assertions.assertEquals(ProfiledQuery.KIND_JDBC, worst.get("kind"));
        Assertions.assertEquals(600L, worst.get("durationMs"),
                "the group's total, being what fixing it could win back");
        Assertions.assertEquals(1, groups.get(1).get("count"));
    }

    @Test
    void anInteractionThatRanNoQueriesHasNoGroups() {
        ownTab(SESSION, UI_ID);
        profiles.add(interaction(SESSION, UI_ID, "click"));

        profile(UI_ID);

        Assertions.assertEquals(List.of(),
                interactions().get(0).get("queryGroups"));
    }

    @Test
    void theProfileCarriesWhatTheTabItselfHolds() {
        UI ui = ownTab(SESSION, UI_ID);
        profiles.uiStateSampled(ui, new UiStateSample(812, 210, 2, 0,
                System.nanoTime() - TimeUnit.SECONDS.toNanos(1)));

        profile(UI_ID);

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) sent
                .payload(ObservabilityDevToolsHandler.COMMAND_PROFILE_DATA)
                .get("uiState");
        Assertions.assertEquals(812, state.get("nodes"));
        Assertions.assertEquals(210, state.get("components"));
        Assertions.assertEquals(2, state.get("views"));
        Assertions.assertEquals(0, state.get("staleViews"));
        long age = (long) state.get("sampleAgeMs");
        Assertions.assertTrue(age >= 1000 && age < 60_000,
                "the monotonic reading reaches the panel as an age, got "
                        + age);
    }

    @Test
    void aTabThatHasNotBeenMeasuredYetHasNoState() {
        ownTab(SESSION, UI_ID);
        profiles.add(interaction(SESSION, UI_ID, "click"));

        profile(UI_ID);

        Assertions.assertNull(
                sent.payload(ObservabilityDevToolsHandler.COMMAND_PROFILE_DATA)
                        .get("uiState"));
    }

    @Test
    void aUiOfAnotherSessionIsNotProfiled() {
        // The whole point of resolving the id through the session: UI ids are
        // small integers unique only within a session, so a dev-tools
        // connection must not be able to read another user's tab by asking for
        // one.
        ownTab(SESSION, UI_ID);
        profiles.add(interaction(OTHER_SESSION, OTHER_UI_ID, "click"));

        profile(OTHER_UI_ID);

        Assertions.assertEquals(List.of(), interactions(),
                "the other user's tab is not in this session");
        Assertions.assertEquals(OTHER_UI_ID,
                sent.payload(ObservabilityDevToolsHandler.COMMAND_PROFILE_DATA)
                        .get("uiId"));
    }

    @Test
    void aRequestWithNoUiIdIsAnsweredEmpty() {
        ownTab(SESSION, UI_ID);
        profiles.add(interaction(SESSION, UI_ID, "click"));

        Assertions.assertTrue(handler.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_PROFILE, data(), sent));

        Assertions.assertEquals(List.of(), interactions());
    }

    @Test
    void aConnectionWithNoSessionIsAnsweredEmpty() {
        // Nothing guarantees the dev-tools thread has a session bound.
        ObservabilityDevToolsHandler noSession = new ObservabilityDevToolsHandler(
                () -> registry, () -> profiles, () -> null);
        profiles.add(interaction(SESSION, UI_ID, "click"));

        Assertions.assertTrue(noSession.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_PROFILE,
                data().put("uiId", UI_ID), sent));

        Assertions.assertEquals(List.of(), interactions());
    }

    @Test
    void withNoProfileStoreTheProfileIsEmptyRatherThanAFailure() {
        // Production mode leaves no store behind, and the kit may not be
        // installed at all; the panel gets an empty answer either way.
        ObservabilityDevToolsHandler noStore = new ObservabilityDevToolsHandler(
                () -> registry, () -> null, () -> session);
        ownTab(SESSION, UI_ID);

        Assertions.assertTrue(noStore.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_PROFILE,
                data().put("uiId", UI_ID), sent));

        Map<String, Object> payload = sent
                .payload(ObservabilityDevToolsHandler.COMMAND_PROFILE_DATA);
        Assertions.assertEquals(List.of(), payload.get("interactions"));
        Assertions.assertNull(payload.get("uiState"));
    }

    @Test
    void theRouteSummaryIsTheMetersOfThatViewOnly() {
        Counter.builder(MeterNames.ERRORS).tag(MeterNames.TAG_ROUTE, ROUTE)
                .register(registry).increment();
        Timer.builder(MeterNames.NAVIGATION)
                .tag(MeterNames.TAG_ROUTE, "dashboard").register(registry)
                .record(5, TimeUnit.MILLISECONDS);

        routeSummary(ROUTE);

        Assertions.assertEquals(List.of(MeterNames.ERRORS), summarisedMeters(),
                "another view's meters are not this view's summary");
        Assertions.assertEquals(ROUTE,
                sent.payload(
                        ObservabilityDevToolsHandler.COMMAND_ROUTE_SUMMARY_DATA)
                        .get("route"));
    }

    @Test
    void perViewRequestLatencyIsFoundOnTheHttpObservation() {
        // vaadin.request.duration carries no route tag on purpose; the route
        // reaches the framework's HTTP meter as its path pattern instead.
        Timer.builder("http.server.requests").tag("uri", "/" + ROUTE)
                .register(registry).record(20, TimeUnit.MILLISECONDS);
        Timer.builder("http.server.requests").tag("uri", "/vaadin/uidl")
                .register(registry).record(20, TimeUnit.MILLISECONDS);

        routeSummary(ROUTE);

        Assertions.assertEquals(List.of("http.server.requests"),
                summarisedMeters(),
                "the request meter of this view, matched by its path pattern");
    }

    @Test
    void aViewNobodyHasVisitedHasAnEmptySummary() {
        Counter.builder(MeterNames.ERRORS).tag(MeterNames.TAG_ROUTE, ROUTE)
                .register(registry).increment();

        routeSummary("reports");

        Assertions.assertEquals(List.of(), summarisedMeters());
    }

    @Test
    void aRouteSummaryWithNoRouteIsAnsweredEmpty() {
        Counter.builder(MeterNames.ERRORS).tag(MeterNames.TAG_ROUTE, ROUTE)
                .register(registry).increment();

        Assertions.assertTrue(handler.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_ROUTE_SUMMARY, data(),
                sent));

        Assertions.assertEquals(List.of(), summarisedMeters());
    }

    @Test
    void theRootRouteIsARouteLikeAnyOther() {
        // Blank is the root route, not a missing one.
        Counter.builder(MeterNames.ERRORS).tag(MeterNames.TAG_ROUTE, "")
                .register(registry).increment();
        Timer.builder("http.server.requests").tag("uri", "/").register(registry)
                .record(20, TimeUnit.MILLISECONDS);

        routeSummary("");

        Assertions.assertEquals(
                List.of("http.server.requests", MeterNames.ERRORS),
                summarisedMeters().stream().sorted().toList());
    }

    @Test
    void theInitialSnapshotIsPushedOnConnect() {
        Counter.builder(MeterNames.ERRORS).register(registry).increment();

        handler.handleConnect(sent);

        Assertions.assertNotNull(
                sent.payload(ObservabilityDevToolsHandler.COMMAND_METRICS)
                        .get("meters"));
    }

    @Test
    void theSessionIsLockedForTheLookupWhenThisThreadDoesNotHoldIt() {
        // The session's UI map may only be read under its lock, and the
        // dev-tools websocket thread does not hold it.
        UI ui = Mockito.mock(UI.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ui.getUIId()).thenReturn(UI_ID);
        Mockito.when(ui.getSession().getSession().getId()).thenReturn(SESSION);
        Mockito.when(session.hasLock()).thenReturn(false);
        Mockito.when(session.getUIById(UI_ID)).thenReturn(ui);
        Mockito.doAnswer(invocation -> {
            ((Command) invocation.getArgument(0)).execute();
            return null;
        }).when(session).accessSynchronously(Mockito.any());
        profiles.add(interaction(SESSION, UI_ID, "click"));

        profile(UI_ID);

        Assertions.assertEquals(List.of("click"), events());
        Mockito.verify(session).accessSynchronously(Mockito.any());
    }

    @Test
    void aSessionThatCannotBeAccessedIsAnsweredEmpty() {
        // A session on its way out throws rather than running the lookup.
        Mockito.when(session.hasLock()).thenReturn(false);
        Mockito.doThrow(new IllegalStateException("session closed"))
                .when(session).accessSynchronously(Mockito.any());
        profiles.add(interaction(SESSION, UI_ID, "click"));

        profile(UI_ID);

        Assertions.assertEquals(List.of(), interactions());
    }

    @Test
    void aSubscribedPanelIsSentEachInteractionAsItFinishes() {
        // What makes the panel push-driven: the developer clicks and the row
        // is there, rather than there on the next poll.
        UI ui = ownTab(SESSION, UI_ID);
        subscribe(sent, UI_ID);

        profiles.begin(ui);
        profiles.addQuery(VaadinTelemetryContext.interactionId(ui),
                ProfiledQuery.KIND_JDBC, "select * from orders where id=?", 1,
                8, System.nanoTime());
        profiles.add(interaction(SESSION, UI_ID, "click"));
        profiles.add(interaction(SESSION, UI_ID, "keydown"));

        Assertions.assertEquals(List.of("click", "keydown"),
                pushedEvents(sent));
        Map<String, Object> message = sent
                .payloads(ObservabilityDevToolsHandler.COMMAND_INTERACTION)
                .get(0);
        Assertions.assertEquals(UI_ID, message.get("uiId"),
                "the message says which tab it is about");
        Assertions.assertNotNull(message.get("timestamp"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queries = (List<Map<String, Object>>) pushed(
                sent).get(0).get("queries");
        Assertions.assertEquals("select * from orders where id=?",
                queries.get(0).get("sql"),
                "the panel draws its waterfall from what it was pushed");
    }

    @Test
    void aPushedInteractionIsShapedLikeOneInTheProfile() {
        // The panel renders a row the same way whether it was loaded or
        // pushed, so the two must not drift apart.
        ownTab(SESSION, UI_ID);
        subscribe(sent, UI_ID);
        profiles.add(interaction(SESSION, UI_ID, "click"));

        profile(UI_ID);

        Assertions.assertEquals(interactions().get(0), pushed(sent).get(0));
    }

    @Test
    void aSubscribedPanelIsSentEachStateSample() {
        UI ui = ownTab(SESSION, UI_ID);
        subscribe(sent, UI_ID);

        profiles.uiStateSampled(ui, new UiStateSample(812, 210, 2, 0,
                System.nanoTime() - TimeUnit.SECONDS.toNanos(1)));

        Map<String, Object> message = sent
                .payload(ObservabilityDevToolsHandler.COMMAND_UI_STATE);
        Assertions.assertEquals(UI_ID, message.get("uiId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) message
                .get("uiState");
        Assertions.assertEquals(812, state.get("nodes"));
        Assertions.assertTrue((long) state.get("sampleAgeMs") >= 1000,
                "and reaches the panel as an age, like in a profile");
    }

    @Test
    void aUiOfAnotherSessionCannotBeSubscribedTo() {
        // The scope rule of the profile, on the subscription: a dev-tools
        // connection must not be pushed another user's tab either.
        ownTab(SESSION, UI_ID);

        subscribe(sent, OTHER_UI_ID);
        profiles.add(interaction(OTHER_SESSION, OTHER_UI_ID, "click"));

        Assertions.assertEquals(List.of(), pushed(sent));
    }

    @Test
    void eachPanelIsSentItsOwnTabOnly() {
        // Two tabs of the developer's own session, each with its panel open.
        ownTab(SESSION, UI_ID);
        ownTab(SESSION, OTHER_UI_ID);
        Sent otherPanel = new Sent();
        subscribe(sent, UI_ID);
        subscribe(otherPanel, OTHER_UI_ID);

        profiles.add(interaction(SESSION, UI_ID, "click"));
        profiles.add(interaction(SESSION, OTHER_UI_ID, "scroll"));

        Assertions.assertEquals(List.of("click"), pushedEvents(sent));
        Assertions.assertEquals(List.of("scroll"), pushedEvents(otherPanel));
    }

    @Test
    void subscribingAgainDoesNotDoubleTheMessages() {
        // A panel that reloads, or reconnects, subscribes again; the developer
        // must not then see every click twice.
        ownTab(SESSION, UI_ID);
        subscribe(sent, UI_ID);
        subscribe(sent, UI_ID);

        profiles.add(interaction(SESSION, UI_ID, "click"));

        Assertions.assertEquals(List.of("click"), pushedEvents(sent));
    }

    @Test
    void aClosedPanelIsNotSentAnythingMore() {
        ownTab(SESSION, UI_ID);
        subscribe(sent, UI_ID);

        handler.handleDisconnect(sent);
        profiles.add(interaction(SESSION, UI_ID, "click"));

        Assertions.assertEquals(List.of(), pushed(sent),
                "the subscription goes with the connection");
    }

    @Test
    void closingOnePanelLeavesTheOtherSubscribed() {
        ownTab(SESSION, UI_ID);
        Sent otherPanel = new Sent();
        subscribe(sent, UI_ID);
        subscribe(otherPanel, UI_ID);

        handler.handleDisconnect(sent);
        profiles.add(interaction(SESSION, UI_ID, "click"));

        Assertions.assertEquals(List.of(), pushed(sent));
        Assertions.assertEquals(List.of("click"), pushedEvents(otherPanel));
    }

    @Test
    void clearingEmptiesThatTabsBuffer() {
        // The panel clears its own list; this is what keeps the next load from
        // bringing the cleared interactions back.
        UI ui = ownTab(SESSION, UI_ID);
        profiles.uiStateSampled(ui,
                new UiStateSample(812, 210, 2, 0, System.nanoTime()));
        profiles.add(interaction(SESSION, UI_ID, "click"));

        clear(UI_ID);
        profile(UI_ID);

        Assertions.assertEquals(List.of(), interactions());
        Assertions.assertNotNull(
                sent.payload(ObservabilityDevToolsHandler.COMMAND_PROFILE_DATA)
                        .get("uiState"),
                "what the tab holds now is not something it did");
    }

    @Test
    void clearingCannotReachAnotherSessionsTab() {
        ownTab(SESSION, UI_ID);
        UI otherUsersTab = tab(OTHER_SESSION, OTHER_UI_ID);
        profiles.add(interaction(OTHER_SESSION, OTHER_UI_ID, "click"));

        clear(OTHER_UI_ID);

        Assertions.assertEquals(List.of("click"),
                profiles.profile(otherUsersTab).stream()
                        .map(i -> i.interaction().event()).toList(),
                "the other user's tab is not this connection's to clear");
    }

    @Test
    void withNoProfileStoreSubscribingAndClearingAreNotFailures() {
        ObservabilityDevToolsHandler noStore = new ObservabilityDevToolsHandler(
                () -> registry, () -> null, () -> session);
        ownTab(SESSION, UI_ID);

        Assertions.assertTrue(noStore.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_PROFILE_SUBSCRIBE,
                data().put("uiId", UI_ID), sent));
        Assertions.assertTrue(noStore.handleMessage(
                ObservabilityDevToolsHandler.COMMAND_PROFILE_CLEAR,
                data().put("uiId", UI_ID), sent));
        Assertions.assertDoesNotThrow(() -> noStore.handleDisconnect(sent));
    }
}
