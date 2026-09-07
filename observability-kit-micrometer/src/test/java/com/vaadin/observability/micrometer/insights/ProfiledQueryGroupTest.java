/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies which queries of an interaction count as the same query, since that
 * is what the profiler's duplicate figure is built on.
 */
class ProfiledQueryGroupTest {

    private static ProfiledQuery jdbc(String statement, long durationMs) {
        return new ProfiledQuery(ProfiledQuery.KIND_JDBC, statement, 1,
                durationMs, 0);
    }

    private static List<String> statements(List<ProfiledQueryGroup> groups) {
        return groups.stream().map(ProfiledQueryGroup::statement).toList();
    }

    @Test
    void theSameQueryWithDifferentValuesIsOneStatement() {
        List<ProfiledQueryGroup> groups = ProfiledQueryGroup
                .group(List.of(jdbc("select * from item where id = 7", 3),
                        jdbc("select * from item where id = 8", 4),
                        jdbc("select * from item where name = 'chair'", 5)));

        Assertions.assertEquals(2, groups.size());
        ProfiledQueryGroup byId = groups.get(0);
        Assertions.assertEquals("select * from item where id = ?",
                byId.statement());
        Assertions.assertEquals(2, byId.count());
        Assertions.assertEquals(7, byId.durationMs(),
                "the group carries what fixing the duplication could win back");
        Assertions.assertEquals("select * from item where name = ?",
                groups.get(1).statement());
    }

    @Test
    void theTwoPagesOfAScrollingComponentAreOneQuery() {
        // A data provider query has no statement of its own; the range it
        // asked for is written the way a statement's values are, so that
        // parameterising it groups the pages of one component together.
        List<ProfiledQueryGroup> groups = ProfiledQueryGroup.group(List.of(
                new ProfiledQuery(CapturedQuery.KIND_FETCH,
                        "com.example.OrdersGrid [0, 50]", 50, 3, 0),
                new ProfiledQuery(CapturedQuery.KIND_FETCH,
                        "com.example.OrdersGrid [50, 50]", 50, 3, 4)));

        Assertions.assertEquals(List.of("com.example.OrdersGrid [?, ?]"),
                statements(groups));
        Assertions.assertEquals(2, groups.get(0).count());
    }

    @Test
    void aCountAndAFetchOfOneComponentAreNotTheSameQuery() {
        List<ProfiledQueryGroup> groups = ProfiledQueryGroup.group(List.of(
                new ProfiledQuery(CapturedQuery.KIND_COUNT,
                        "com.example.OrdersGrid", -1, 3, 0),
                new ProfiledQuery(CapturedQuery.KIND_FETCH,
                        "com.example.OrdersGrid", 50, 3, 4)));

        Assertions.assertEquals(2, groups.size(),
                "one component, two questions asked of it");
        Assertions.assertEquals(
                List.of(CapturedQuery.KIND_COUNT, CapturedQuery.KIND_FETCH),
                groups.stream().map(ProfiledQueryGroup::kind).toList());
    }

    @Test
    void formattingIsNotWhatMakesTwoQueriesDifferent() {
        List<ProfiledQueryGroup> groups = ProfiledQueryGroup
                .group(List.of(jdbc("select *\n  from item\n  where id = ?", 3),
                        jdbc("select * from item where id = ?", 3)));

        Assertions.assertEquals(List.of("select * from item where id = ?"),
                statements(groups));
    }

    @Test
    void anIdentifierThatEndsInADigitIsNotAValue() {
        List<ProfiledQueryGroup> groups = ProfiledQueryGroup
                .group(List.of(jdbc("select * from order_line2", 3),
                        jdbc("select * from order_line3", 3)));

        Assertions.assertEquals(2, groups.size(),
                "two tables, not one table queried twice: "
                        + statements(groups));
    }

    @Test
    void theMostRepeatedStatementComesFirst() {
        List<ProfiledQueryGroup> groups = ProfiledQueryGroup.group(List.of(
                jdbc("select 1 from once", 1), jdbc("select ? from twice", 1),
                jdbc("select ? from twice", 1), jdbc("select ? from thrice", 1),
                jdbc("select ? from thrice", 1),
                jdbc("select ? from thrice", 1)));

        Assertions.assertEquals(List.of("select ? from thrice",
                "select ? from twice", "select ? from once"),
                statements(groups));
    }

    @Test
    void anInteractionThatRanNothingHasNoGroups() {
        Assertions.assertEquals(List.of(), ProfiledQueryGroup.group(null));
        Assertions.assertEquals(List.of(), ProfiledQueryGroup.group(List.of()));
    }

    @Test
    void aQueryWhoseStatementIsUnknownStillGroups() {
        // The driver does not always tell us the SQL; a query with no
        // statement is still one of the interaction's queries.
        List<ProfiledQueryGroup> groups = ProfiledQueryGroup
                .group(List.of(jdbc(null, 3), jdbc(null, 4)));

        Assertions.assertEquals(1, groups.size());
        Assertions.assertNull(groups.get(0).statement());
        Assertions.assertEquals(2, groups.get(0).count());
    }
}
