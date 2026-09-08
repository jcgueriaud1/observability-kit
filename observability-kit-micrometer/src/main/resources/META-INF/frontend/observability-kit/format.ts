// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import type { Meter } from './model';

/**
 * How the panel writes numbers and names. Small enough to read in one go, and
 * gathered here because the same figure appears in a row, a chip and a bar
 * tooltip, and has to look the same in all three.
 */

/**
 * A duration as the developer reads it: milliseconds while a click still
 * feels like a response, seconds once it does not.
 */
export function duration(ms: number): string {
  if (typeof ms !== 'number' || !isFinite(ms)) {
    return '–';
  }
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(2)} s`;
}

/**
 * A total across several measurements. Each is whole milliseconds, so a run of
 * sub-millisecond queries sums to zero -- which is true of the arithmetic and
 * false of what happened. Anything that did work and rounded away is reported
 * as under a millisecond rather than as none.
 */
export function sumDuration(ms: number, samples: number): string {
  return ms === 0 && samples > 0 ? '< 1 ms' : duration(ms);
}

/** How stale a figure is, in the same two ranges. */
export function ago(ms: number): string {
  if (typeof ms !== 'number' || !isFinite(ms) || ms < 0) {
    return '';
  }
  return ms < 1000 ? `${Math.round(ms)} ms ago` : `${(ms / 1000).toFixed(1)} s ago`;
}

/** A count with a noun that agrees with it, since these read as prose. */
export function count(n: number, singular: string, plural?: string): string {
  return `${n} ${n === 1 ? singular : plural ?? `${singular}s`}`;
}

/** A rounded figure, or the empty dash when there is nothing to round. */
export function num(value: unknown, decimals = 1): string {
  if (typeof value !== 'number' || !isFinite(value)) {
    return String(value);
  }
  return Number.isInteger(value) ? String(value) : value.toFixed(decimals);
}

/** Rows, with the unknown row count of a query that threw left unknown. */
export function rows(value: number): string {
  return typeof value !== 'number' || value < 0 ? '–' : String(value);
}

export function clockTime(epochMs: number | null): string {
  return typeof epochMs === 'number'
    ? new Date(epochMs).toLocaleTimeString()
    : '';
}

/** The last segment of a class name, which is what a row has room for. */
export function simpleName(className: string | null | undefined): string {
  if (!className) {
    return '';
  }
  const dot = className.lastIndexOf('.');
  return dot === -1 ? className : className.substring(dot + 1);
}

/**
 * The file an interaction ran through, from the frame it stopped at:
 * `OrdersView.java:88` names `OrdersView.java`. Preferred over the component
 * for the header, because it is the file the developer would open.
 */
export function frameFile(frame: string | null | undefined): string {
  if (!frame) {
    return '';
  }
  const colon = frame.lastIndexOf(':');
  return colon === -1 ? frame : frame.substring(0, colon);
}

/**
 * The keywords a statement is broken before. Longest first, so that
 * `ORDER BY` is not read as `OR` followed by `DER BY`.
 */
const KEYWORDS = [
  'GROUP BY', 'ORDER BY', 'SELECT', 'DELETE', 'INSERT', 'UPDATE', 'VALUES',
  'HAVING', 'OFFSET', 'UNION', 'INNER', 'OUTER', 'RIGHT', 'WHERE', 'LIMIT',
  'FROM', 'JOIN', 'LEFT', 'SET', 'AND', 'ON', 'OR'
];

function isWordChar(ch: string | undefined): boolean {
  return ch !== undefined && /[A-Za-z0-9_$]/.test(ch);
}

/** The keyword starting at this position as a whole word, if any. */
function keywordAt(sql: string, at: number): string | null {
  if (isWordChar(sql[at - 1])) {
    return null;
  }
  const rest = sql.substring(at).toUpperCase();
  for (const keyword of KEYWORDS) {
    if (rest.startsWith(keyword) && !isWordChar(sql[at + keyword.length])) {
      return keyword;
    }
  }
  return null;
}

/**
 * A statement laid out to be read: one clause per line, indented under the
 * first. Nothing inside a quoted string is touched -- a literal containing
 * the word `and` is data, not a clause -- and the text itself is never
 * rewritten, only broken, so what is shown is still the statement that ran.
 */
export function prettySql(sql: string): string {
  const lines: string[] = [];
  let line = '';
  let quote: string | null = null;
  let i = 0;
  while (i < sql.length) {
    const ch = sql[i];
    if (quote !== null) {
      line += ch;
      if (ch === quote) {
        quote = null;
      }
      i += 1;
      continue;
    }
    if (ch === "'" || ch === '"' || ch === '`') {
      quote = ch;
      line += ch;
      i += 1;
      continue;
    }
    const keyword = keywordAt(sql, i);
    if (keyword !== null) {
      if (line.trim().length > 0) {
        lines.push(line.trim());
      }
      line = sql.substring(i, i + keyword.length);
      i += keyword.length;
      continue;
    }
    line += ch;
    i += 1;
  }
  if (line.trim().length > 0) {
    lines.push(line.trim());
  }
  if (lines.length === 0) {
    return sql;
  }
  return lines.map((text, index) => (index === 0 ? text : `  ${text}`))
    .join('\n');
}

/** Tag values of a meter as one line, the way the meters table shows them. */
export function formatTags(tags: Record<string, string> | undefined): string {
  const keys = Object.keys(tags ?? {});
  if (keys.length === 0) {
    return '';
  }
  return keys.map((key) => `${key}=${tags![key]}`).join(', ');
}

/** A stable identity for a meter across polls, so its trend keeps its history. */
export function meterKey(meter: Meter): string {
  return `${meter.name}|${formatTags(meter.tags)}`;
}

/** The single scalar plotted in a meter's sparkline. */
export function trendValue(meter: Meter): number | null {
  if (typeof meter.mean === 'number') {
    return meter.mean;
  }
  if (typeof meter.value === 'number') {
    return meter.value;
  }
  if (typeof meter.count === 'number') {
    return meter.count;
  }
  if (meter.measurements && meter.measurements.length > 0) {
    return meter.measurements[0].value;
  }
  return null;
}

/**
 * A meter's value cell, from the type-aware fields the server sends. For
 * timers the cumulative mean is the stable figure, the count gives it weight,
 * and the max is shown only when it is not zero -- it decays to zero between
 * polls in a `SimpleMeterRegistry`.
 */
export function formatMeterValue(meter: Meter): string {
  const unit = meter.unit ? ` ${meter.unit}` : '';
  if (typeof meter.mean === 'number') {
    const parts = [`mean ${num(meter.mean)}${unit}`];
    if (typeof meter.max === 'number' && meter.max > 0) {
      parts.push(`max ${num(meter.max)}${unit}`);
    }
    if (typeof meter.count === 'number') {
      parts.push(`n=${meter.count}`);
    }
    return parts.join(' · ');
  }
  if (typeof meter.value === 'number') {
    return num(meter.value, 3);
  }
  if (typeof meter.count === 'number') {
    return String(meter.count);
  }
  return (meter.measurements ?? [])
    .map((m) => `${m.statistic}: ${num(m.value, 3)}`).join(', ');
}
