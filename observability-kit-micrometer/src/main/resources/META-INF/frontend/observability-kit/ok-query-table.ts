// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import { LitElement, css, html, nothing } from 'lit';
import type { TemplateResult } from 'lit';

import { duration, prettySql, rows, sumDuration } from './format';
import type { Interaction, Query, QueryGroup } from './model';
import { KIND_JDBC, ROWS_WARN, queryGroups, queryLabel } from './model';
import { tokens } from './styles';

/**
 * The queries of one interaction, with the ones that were the same query
 * counted rather than listed.
 *
 * A hundred rows saying the same thing is not a list of a hundred findings;
 * it is one finding a hundred times, and the row that says `100×` is the one
 * the developer can act on. The individual executions are still there, one
 * click away, because the timing of the first and the last is how you tell a
 * loop from a batch.
 *
 * Statements are shown on one line until asked for: the interesting part of a
 * statement is its shape, and its shape fits on a line. Opening one lays it
 * out clause by clause without rewriting it, so what is read is still what
 * ran, and the copy button hands over the original text rather than the
 * laid-out one.
 */
export class OkQueryTable extends LitElement {
  static properties = {
    interaction: { attribute: false },
    _expanded: { state: true },
    _open: { state: true },
    _copied: { state: true }
  };

  declare interaction: Interaction | null;
  /** Group keys whose individual executions are shown. */
  private declare _expanded: Set<string>;
  /** Group keys whose statement is laid out. */
  private declare _open: Set<string>;
  /** The group whose statement was just copied, for the flash. */
  private declare _copied: string | null;

  constructor() {
    super();
    this.interaction = null;
    this._expanded = new Set();
    this._open = new Set();
    this._copied = null;
  }

  static styles = [tokens, css`
    :host {
      display: block;
    }

    table {
      border-collapse: collapse;
      inline-size: 100%;
      table-layout: fixed;
    }

    th {
      border-bottom: 1px solid var(--ok-line);
      color: var(--ok-muted);
      font-weight: 400;
      padding: var(--ok-space) calc(var(--ok-space) * 2);
      text-align: start;
    }

    th.right,
    td.right {
      text-align: end;
    }

    /* The statement gets what is left, and what is left is not much: the
       panel is 720 px wide with a list down one side, so the three figure
       columns are sized to their content rather than to comfort. */
    col.rows {
      inline-size: 3.2rem;
    }

    col.time {
      inline-size: 4.4rem;
    }

    col.flags {
      inline-size: 6.5rem;
    }

    td {
      border-bottom: 1px solid var(--ok-line);
      padding: calc(var(--ok-space) * 1.5) calc(var(--ok-space) * 2);
      vertical-align: top;
    }

    tr[data-execution] td {
      background: var(--ok-raised);
    }

    .statement {
      align-items: baseline;
      display: flex;
      gap: var(--ok-space);
      min-inline-size: 0;
    }

    .toggle {
      border: 0;
      color: var(--ok-muted);
      flex: none;
      line-height: 1;
      padding: 0 var(--ok-space);
    }

    .toggle[data-empty] {
      cursor: default;
      visibility: hidden;
    }

    /* The statement itself: one line, and a button so that opening it is
       reachable without a pointer. */
    .text {
      border: 0;
      display: block;
      min-inline-size: 0;
      overflow: hidden;
      padding: 0;
      text-align: start;
      text-overflow: ellipsis;
      white-space: nowrap;
      inline-size: 100%;
    }

    .text:hover {
      background: transparent;
      text-decoration: underline;
    }

    pre {
      margin: calc(var(--ok-space) * 2) 0 0;
      overflow-x: auto;
      white-space: pre;
    }

    .kind {
      color: var(--ok-muted);
    }

    .flags {
      display: flex;
      flex-wrap: wrap;
      gap: var(--ok-space);
    }

    .copy {
      float: inline-end;
      font-size: var(--ok-font-size-xs);
      margin-inline-start: calc(var(--ok-space) * 2);
    }
  `];

  render(): unknown {
    const interaction = this.interaction;
    if (interaction === null || interaction.queries.length === 0) {
      return nothing;
    }
    const groups = queryGroups(interaction);
    return html`
      <table>
        <colgroup>
          <col />
          <col class="rows" />
          <col class="time" />
          <col class="flags" />
        </colgroup>
        <thead>
          <tr>
            <th>Statement</th>
            <th class="right">Rows</th>
            <th class="right">Time</th>
            <th>Flags</th>
          </tr>
        </thead>
        <tbody>
          ${groups.map((group) => this.groupRows(group))}
        </tbody>
      </table>
    `;
  }

  private groupRows(group: QueryGroup): TemplateResult[] {
    const repeated = group.queries.length;
    const expandable = repeated > 1;
    const expanded = this._expanded.has(group.key);
    const open = this._open.has(group.key);

    const out: TemplateResult[] = [html`
      <tr>
        <td>
          <div class="statement">
            <button
              class="toggle"
              ?data-empty=${!expandable}
              aria-expanded=${expandable
                ? (expanded ? 'true' : 'false')
                : nothing}
              title=${expandable
                ? `Show the ${repeated} executions`
                : nothing}
              @click=${() => expandable && this.toggle(this._expanded, group.key)}
            >${expanded ? '▾' : '▸'}</button>
            ${this.statement(group, open)}
          </div>
          ${open && group.sql !== null
            ? html`<pre class="mono">${prettySql(group.sql)}</pre>`
            : nothing}
        </td>
        <td class="right num">${rows(group.rows)}</td>
        <td class="right num">
          ${sumDuration(group.durationMs, repeated)}${expandable
            ? html`<div class="muted">×${repeated}</div>`
            : nothing}
        </td>
        <td>
          <div class="flags">${this.flags(group)}</div>
        </td>
      </tr>
    `];

    if (expanded) {
      group.queries.forEach((query, index) => {
        out.push(this.executionRow(query, index));
      });
    }
    return out;
  }

  /**
   * The statement cell. A JDBC statement is a button, because opening it is
   * an action; a data provider query is its own description -- who asked and
   * for what -- and has nothing to lay out.
   */
  private statement(group: QueryGroup, open: boolean): TemplateResult {
    if (group.sql === null) {
      return html`<span class="muted">statement not captured</span>`;
    }
    if (group.kind !== KIND_JDBC) {
      return html`<span class="text mono" title=${group.sql}
        ><span class="kind">${group.kind} · </span>${queryLabel(
          group.queries[0])}</span
      >`;
    }
    return html`
      <button
        class="text mono"
        aria-expanded=${open ? 'true' : 'false'}
        title=${group.sql}
        @click=${() => this.toggle(this._open, group.key)}
      >${group.sql}</button>
      ${open
        ? html`<button
            class="copy"
            @click=${() => this.copy(group)}
          >${this._copied === group.key ? 'Copied' : 'Copy'}</button>`
        : nothing}
    `;
  }

  /** One execution of a group, when the group has been opened. */
  private executionRow(query: Query, index: number): TemplateResult {
    return html`
      <tr data-execution>
        <td>
          <span class="muted num"
            >#${index + 1} at +${duration(query.startOffsetMs)}</span
          >
        </td>
        <td class="right num">${rows(query.rows)}</td>
        <td class="right num">${duration(query.durationMs)}</td>
        <td></td>
      </tr>
    `;
  }

  /**
   * What is worth flagging about this row. The wire carries no per-query
   * outcome -- a query that threw is reported by the interaction that ran it,
   * and its rows come back unknown -- so what is flagged here is repetition
   * and volume, and the exception is the hint box's to tell.
   */
  private flags(group: QueryGroup): TemplateResult[] {
    const found: TemplateResult[] = [];
    const repeated = group.queries.length;
    if (repeated > 1) {
      found.push(html`<span class="tag" data-level="warn"
        >${repeated} identical</span
      >`);
    }
    if (group.rows > ROWS_WARN) {
      found.push(html`<span class="tag" data-level="warn"
        >&gt; ${ROWS_WARN} rows</span
      >`);
    }
    return found;
  }

  private toggle(set: Set<string>, key: string): void {
    if (set.has(key)) {
      set.delete(key);
    } else {
      set.add(key);
    }
    // Mutated in place, so Lit is told rather than shown.
    this.requestUpdate();
  }

  private copy(group: QueryGroup): void {
    if (group.sql === null) {
      return;
    }
    // The raw statement, not the laid-out one: what is pasted has to be
    // something a database will accept back.
    navigator.clipboard?.writeText(group.sql).then(() => {
      this._copied = group.key;
      setTimeout(() => {
        if (this._copied === group.key) {
          this._copied = null;
        }
      }, 1200);
    }, () => {
      // A clipboard the browser will not give us is not worth a message of
      // its own; the statement is on screen and selectable either way.
    });
  }
}

customElements.define('ok-query-table', OkQueryTable);
