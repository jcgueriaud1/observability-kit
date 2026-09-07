/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The queries of one interaction that are the same query, counted.
 * <p>
 * This is the figure a profiler toolbar leads with. A grid that looks up one
 * row at a time does not run a hundred different queries, it runs one query a
 * hundred times, and seeing "1 + 100" next to a statement is what turns a slow
 * view into a fixable bug. Symfony's toolbar reports it as the number of
 * different statements behind the total; Django's debug toolbar calls the
 * members of a group "similar" queries.
 * <p>
 * Grouping is computed when a profile is read rather than as queries arrive,
 * because it is a view of the children and nothing depends on it being
 * maintained incrementally — see {@link ProfiledInteraction#queryGroups()}.
 *
 * @param statement
 *            the {@link #parameterise(String) parameterised} statement shared
 *            by the group's members
 * @param kind
 *            the {@link ProfiledQuery#kind() kind} shared by the group's
 *            members, so a count and a fetch of the same component never merge
 * @param count
 *            how many queries of the interaction are this query; anything above
 *            one is a duplicate worth a look
 * @param durationMs
 *            the total time the group's members took, in milliseconds — what
 *            fixing the duplication could win back
 */
public record ProfiledQueryGroup(String statement, String kind, int count,
        long durationMs) {

    /**
     * A quoted literal, single quotes doubled inside it as SQL escapes them.
     */
    private static final Pattern STRING_LITERAL = Pattern
            .compile("'(?:[^']|'')*'");

    /**
     * A number that stands on its own, so that identifiers which merely end in
     * a digit ({@code order_line2}) are left alone.
     */
    private static final Pattern NUMBER_LITERAL = Pattern
            .compile("\\b\\d+(?:\\.\\d+)?\\b");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Groups the queries of one interaction, most repeated first and, within
     * one count, in the order they first ran.
     *
     * @param queries
     *            the children of one interaction, may be {@code null}
     * @return one group per distinct statement, never {@code null}
     */
    static List<ProfiledQueryGroup> group(List<ProfiledQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        // Insertion-ordered, so the sort below only has to be stable to keep
        // equally repeated statements in the order they first ran.
        Map<Key, Accumulator> groups = new LinkedHashMap<>();
        for (ProfiledQuery query : queries) {
            Key key = new Key(query.kind(), parameterise(query.statement()));
            groups.computeIfAbsent(key,
                    k -> new Accumulator(k.statement(), k.kind())).add(query);
        }
        List<ProfiledQueryGroup> grouped = new ArrayList<>(groups.size());
        for (Accumulator accumulator : groups.values()) {
            grouped.add(accumulator.toGroup());
        }
        grouped.sort(
                Comparator.comparingInt(ProfiledQueryGroup::count).reversed());
        return List.copyOf(grouped);
    }

    /**
     * Reduces a statement to what two runs of the same query have in common:
     * literals become {@code ?} and runs of whitespace collapse, so
     * {@code select * from item where id = 7} and {@code ... id = 8} are one
     * statement, as are the two pages a scrolling grid fetched.
     * <p>
     * A heuristic, and only a heuristic: it works on the text rather than on a
     * parse, so a literal inside an identifier or a dialect that quotes strings
     * differently can group two queries that are not the same one. Telling
     * apart two runs of a <em>prepared</em> statement, whose text is identical
     * and whose parameters are bound separately, is the opposite problem and
     * needs the bound parameters, which this record does not carry.
     *
     * @param statement
     *            the statement to reduce, may be {@code null}
     * @return the parameterised statement, or {@code null} for {@code null}
     */
    static String parameterise(String statement) {
        if (statement == null) {
            return null;
        }
        String parameterised = STRING_LITERAL.matcher(statement)
                .replaceAll("?");
        parameterised = NUMBER_LITERAL.matcher(parameterised).replaceAll("?");
        return WHITESPACE.matcher(parameterised).replaceAll(" ").trim();
    }

    /** What makes two queries the same query. */
    private record Key(String kind, String statement) {
    }

    /** Sums one group as the queries of it are walked. */
    private static final class Accumulator {
        private final String statement;
        private final String kind;
        private int count;
        private long durationMs;

        Accumulator(String statement, String kind) {
            this.statement = statement;
            this.kind = kind;
        }

        void add(ProfiledQuery query) {
            count++;
            durationMs += Math.max(0, query.durationMs());
        }

        ProfiledQueryGroup toGroup() {
            return new ProfiledQueryGroup(statement, kind, count, durationMs);
        }
    }
}
