// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

/**
 * What the dev-tools handler sends and what the panel makes of it.
 *
 * The interfaces are the wire, field for field, as
 * `ObservabilityDevToolsHandler` writes it -- epoch milliseconds rather than
 * ISO strings, an age rather than a sample instant -- so that a reader of
 * either side can check one against the other. Everything derived from it
 * lives below, in one place, because the list, the waterfall, the table and
 * the hints all have to agree about how many queries were the same query.
 */

/** One query an interaction ran, as a child of it. */
export interface Query {
  /** `jdbc` for a statement, `count` / `fetch` for a data provider query. */
  kind: string;
  /**
   * The statement for a JDBC query; for a data provider query, the component
   * and range it asked for, which is the closest thing it has to one. Null
   * when statement capture is off.
   */
  sql: string | null;
  /** Rows the query produced, or -1 when it is not known. */
  rows: number;
  durationMs: number;
  /** Milliseconds from the start of the interaction, so children read as a timeline. */
  startOffsetMs: number;
}

/** One thing the developer's tab did on the server. */
export interface Interaction {
  /** Stable per JVM and increasing, so it identifies a row across reloads. */
  id: number;
  /** Epoch milliseconds, or null when the interaction carried no timestamp. */
  timestamp: number | null;
  /** Route template, e.g. `orders/:orderId`. */
  route: string | null;
  /** Literal path, e.g. `orders/42`. */
  location: string | null;
  /** Fully qualified class name, or `_unknown` when it could not be resolved. */
  component: string | null;
  /** DOM event or method name, e.g. `click`, `setRequestedRange`. */
  event: string | null;
  /** `event` | `publishedEventHandler` | `mSync` | `channel` | `navigation` | `poll`. */
  rpcType: string | null;
  outcome: string;
  durationMs: number;
  exceptionType: string | null;
  /** Only present when `insights-details` is on. */
  exceptionMessage: string | null;
  /** e.g. `OrdersView.java:88`. */
  applicationFrame: string | null;
  queries: Query[];
}

/** What the tab is holding now, rather than what any one click cost. */
export interface UiState {
  nodes: number;
  components: number;
  views: number;
  staleViews: number;
  /**
   * How long ago the tab was measured. A tab is measured on its own session's
   * thread, so an idle tab's figures are as old as its last interaction, and
   * that is the part the developer has to see before trusting them.
   */
  sampleAgeMs: number;
}

/** One Micrometer meter, as the metrics message carries it. */
export interface Meter {
  name: string;
  type: string;
  tags: Record<string, string>;
  count?: number;
  mean?: number;
  max?: number;
  value?: number;
  unit?: string;
  measurements?: { statistic: string; value: number }[];
}

export const OUTCOME_ERROR = 'error';
export const KIND_JDBC = 'jdbc';
export const RPC_NAVIGATION = 'navigation';

/** Commands the panel sends and the answers it claims. */
export const COMMAND_PROFILE = 'observability-kit-profile';
export const COMMAND_PROFILE_DATA = 'observability-kit-profile-data';
export const COMMAND_PROFILE_SUBSCRIBE = 'observability-kit-profile-subscribe';
export const COMMAND_PROFILE_CLEAR = 'observability-kit-profile-clear';
export const COMMAND_INTERACTION = 'observability-kit-interaction';
export const COMMAND_UI_STATE = 'observability-kit-ui-state';
export const COMMAND_REFRESH = 'observability-kit-refresh';
export const COMMAND_METRICS = 'observability-kit-metrics';

/**
 * Rows kept per tab, mirroring the server's `insights-capacity` default: the
 * panel drops the oldest at the same point the store it reads does, so the
 * list and a reload of it hold the same interactions.
 */
export const MAX_INTERACTIONS = 100;

/**
 * Queries above which the database figure is called out. Twenty statements in
 * one round trip is not a number a view arrives at deliberately; Symfony's
 * profiler turns its own icon yellow on the same reasoning.
 */
export const QUERY_WARN_COUNT = 20;

/** Rows above which one query is called out. */
export const ROWS_WARN = 500;

/**
 * The UX budget an interaction is measured against. A second is where a click
 * stops feeling like a response and starts feeling like a wait.
 */
export const SLOW_MS = 1000;

/** Categories the waterfall colours by. */
export type Category = 'server' | 'data' | 'jdbc' | 'error';

/** One bar of the waterfall. */
export interface Bar {
  label: string;
  category: Category;
  startOffsetMs: number;
  durationMs: number;
  /** Executions behind this bar: above one, it is a run of the same statement. */
  count: number;
  /** The interaction's own bar, which is the scale rather than a child of it. */
  root: boolean;
}

/** One row of the query table: a single query, or a group of identical ones. */
export interface QueryGroup {
  /** Stable within an interaction, so an expanded row stays expanded. */
  key: string;
  kind: string;
  /** The statement, or null when statement capture is off. */
  sql: string | null;
  /** The executions in this group, in the order they ran. */
  queries: Query[];
  /** Total across the group, being what fixing the duplication could win back. */
  durationMs: number;
  /** Total rows, or -1 when any execution did not report its own. */
  rows: number;
  startOffsetMs: number;
}

/** A finding worth a sentence under the table. */
export interface Hint {
  level: 'warn' | 'error';
  text: string;
  /** Where it happened, shown as plain text under the sentence. */
  frame?: string;
}

export function isJdbc(query: Query): boolean {
  return query.kind === KIND_JDBC;
}

/**
 * What a bar or a row calls this query. A statement is its own name; a data
 * provider query is named by the component that asked, and a package name in
 * front of it is width spent on nothing a developer reads.
 */
export function queryLabel(query: Query): string {
  if (query.sql === null) {
    return query.kind;
  }
  if (isJdbc(query)) {
    return query.sql;
  }
  const dot = query.sql.lastIndexOf('.');
  return dot === -1 ? query.sql : query.sql.substring(dot + 1);
}

export function failed(interaction: Interaction): boolean {
  return interaction.outcome === OUTCOME_ERROR;
}

/** Total time in the database, which is the JDBC children and only those. */
export function databaseMs(interaction: Interaction): number {
  return interaction.queries.filter(isJdbc)
    .reduce((total, query) => total + query.durationMs, 0);
}

/**
 * The query table's rows: every JDBC child that ran the same statement counted
 * as one, in the order the first of them ran, and every other child on its
 * own. Grouping is by statement text, which is what the server captured -- a
 * prepared statement reaches it parameterised, so the N of an N+1 is N
 * executions of one string.
 */
export function queryGroups(interaction: Interaction): QueryGroup[] {
  const groups: QueryGroup[] = [];
  const byStatement = new Map<string, QueryGroup>();
  const ordered = [...interaction.queries]
    .sort((a, b) => a.startOffsetMs - b.startOffsetMs);

  ordered.forEach((query, index) => {
    const statementKey = isJdbc(query) && query.sql !== null
      ? `jdbc:${query.sql}`
      : null;
    const existing = statementKey === null
      ? undefined
      : byStatement.get(statementKey);
    if (existing) {
      existing.queries.push(query);
      existing.durationMs += query.durationMs;
      existing.rows = addRows(existing.rows, query.rows);
      return;
    }
    const group: QueryGroup = {
      key: statementKey ?? `q${index}`,
      kind: query.kind,
      sql: query.sql,
      queries: [query],
      durationMs: query.durationMs,
      rows: query.rows,
      startOffsetMs: query.startOffsetMs
    };
    groups.push(group);
    if (statementKey !== null) {
      byStatement.set(statementKey, group);
    }
  });
  return groups;
}

/**
 * Rows unknown stay unknown. A query whose result set was never closed, or
 * that threw, reports -1, and a sum that quietly counted it as zero would
 * read as a fact.
 */
function addRows(total: number, rows: number): number {
  return total < 0 || rows < 0 ? -1 : total + rows;
}

/** The largest group of identical statements, or 0 when nothing repeated. */
export function repeats(interaction: Interaction): number {
  return queryGroups(interaction)
    .filter((group) => group.kind === KIND_JDBC)
    .reduce((most, group) => Math.max(most, group.queries.length), 0);
}

/**
 * The waterfall's bars: the interaction itself, then its children in the order
 * they ran, with a run of the same statement collapsed into one bar spanning
 * the first start to the last end. Thirty lookups in a row are one hatched
 * block rather than thirty slivers, which is the shape that reads as an N+1.
 */
export function waterfall(interaction: Interaction): Bar[] {
  const bars: Bar[] = [{
    label: 'vaadin.request',
    category: failed(interaction) ? 'error' : 'server',
    startOffsetMs: 0,
    durationMs: interaction.durationMs,
    count: 1,
    root: true
  }];

  const ordered = [...interaction.queries]
    .sort((a, b) => a.startOffsetMs - b.startOffsetMs);
  for (const query of ordered) {
    const previous = bars.length > 1 ? bars[bars.length - 1] : null;
    const mergeable = previous !== null && isJdbc(query)
      && query.sql !== null && previous.label === query.sql;
    if (mergeable) {
      const end = Math.max(previous.startOffsetMs + previous.durationMs,
        query.startOffsetMs + query.durationMs);
      previous.durationMs = end - previous.startOffsetMs;
      previous.count += 1;
      continue;
    }
    bars.push({
      label: queryLabel(query),
      category: isJdbc(query) ? 'jdbc' : 'data',
      startOffsetMs: query.startOffsetMs,
      durationMs: query.durationMs,
      count: 1,
      root: false
    });
  }
  return bars;
}

/**
 * What is worth saying about this interaction beyond its numbers. Nothing,
 * usually: a hint appears when a rule fires, and an interaction that did its
 * work in one query and came back inside the budget breaks none of them.
 */
export function hints(interaction: Interaction): Hint[] {
  const found: Hint[] = [];

  if (failed(interaction)) {
    const message = interaction.exceptionMessage
      ? `${interaction.exceptionType}: ${interaction.exceptionMessage}`
      : `${interaction.exceptionType ?? 'The interaction failed'}`;
    found.push({
      level: 'error',
      text: message,
      frame: interaction.applicationFrame ?? undefined
    });
  }

  const most = repeats(interaction);
  if (most > 1) {
    const where = interaction.applicationFrame
      ? `in ${interaction.applicationFrame}`
      : 'in the listener';
    found.push({
      level: 'warn',
      text: `${most} identical statements in one interaction: the same SELECT `
        + `ran once per row. A join or a batched lookup ${where} would make `
        + `it one.`
    });
  }

  if (interaction.durationMs >= SLOW_MS) {
    const slowest = [...interaction.queries]
      .sort((a, b) => b.durationMs - a.durationMs)[0];
    const detail = slowest
      ? ` The longest query took ${slowest.durationMs} ms and returned `
        + `${slowest.rows < 0 ? 'an unknown number of' : slowest.rows} rows.`
      : ' It ran no queries, so the time went into the server itself.';
    found.push({
      level: 'warn',
      text: `Over the ${SLOW_MS / 1000} s UX budget.${detail}`
    });
  }

  return found;
}
