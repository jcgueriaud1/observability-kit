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

/**
 * One interaction retained by the dev-mode {@link ProfileStore}, with the id
 * the rest of the profile refers to it by.
 * <p>
 * The id is kept beside the interaction rather than inside
 * {@link CapturedInteraction}, because it means nothing outside the store: the
 * interactions the insights endpoint publishes are grouped into insights and
 * never addressed one by one, and that payload is a published contract. Here an
 * identity is needed, so that what an interaction did — its queries (and
 * whatever else a profiler learns to record) — can point at the interaction it
 * belongs to.
 *
 * @param id
 *            store-wide identity of this interaction, unique for the life of
 *            the store and increasing with capture order, so a higher id is a
 *            later interaction
 * @param interaction
 *            what was captured, exactly as the collector captured it
 * @param queries
 *            the queries this interaction ran, in the order they started, never
 *            {@code null}
 */
public record ProfiledInteraction(long id, CapturedInteraction interaction,
        List<ProfiledQuery> queries) {

    public ProfiledInteraction {
        queries = queries == null ? List.of() : List.copyOf(queries);
    }

    /**
     * The interaction's queries grouped by the statement they ran, most
     * repeated first — "one query, run a hundred times" rather than a hundred
     * lines.
     * <p>
     * Derived here rather than stored, so the store keeps one copy of the facts
     * and a reader that only wants the total never pays for the grouping.
     *
     * @return one group per distinct statement, never {@code null}
     */
    public List<ProfiledQueryGroup> queryGroups() {
        return ProfiledQueryGroup.group(queries);
    }
}
