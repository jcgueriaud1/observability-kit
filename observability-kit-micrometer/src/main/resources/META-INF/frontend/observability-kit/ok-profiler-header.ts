// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import { LitElement, css, html, nothing } from 'lit';
import type { TemplateResult } from 'lit';

import { count, duration, frameFile, simpleName, sumDuration } from './format';
import type { Interaction, UiState } from './model';
import {
  QUERY_WARN_COUNT, databaseMs, failed
} from './model';
import { tokens } from './styles';

/**
 * Where the developer is and what the interaction they are looking at cost.
 *
 * The route and class name the *selected* interaction rather than the tab's
 * current view, so that scrolling back through the list moves the header with
 * it: a row from before a navigation belongs to the view it happened in, and
 * a header that kept saying where the tab is now would attribute it to the
 * wrong one.
 *
 * Four figures, because four is what a headline holds: what the server spent,
 * what the database did, what the view is holding, and -- only when there is
 * one -- what went wrong.
 */
export class OkProfilerHeader extends LitElement {
  static properties = {
    interaction: { attribute: false },
    uiState: { attribute: false },
    routeFilter: { type: Boolean },
    hiddenCount: { type: Number }
  };

  declare interaction: Interaction | null;
  declare uiState: UiState | null;
  declare routeFilter: boolean;
  /** Rows the route filter is holding back, which is what makes it visible. */
  declare hiddenCount: number;

  constructor() {
    super();
    this.interaction = null;
    this.uiState = null;
    this.routeFilter = false;
    this.hiddenCount = 0;
  }

  static styles = [tokens, css`
    :host {
      border-bottom: 1px solid var(--ok-line);
      display: block;
      padding: calc(var(--ok-space) * 3);
    }

    .top {
      align-items: baseline;
      display: flex;
      gap: calc(var(--ok-space) * 2);
    }

    .where {
      flex: 1;
      min-inline-size: 0;
    }

    .route {
      font-weight: 600;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .class {
      color: var(--ok-muted);
      font-size: var(--ok-font-size-xs);
    }

    .actions {
      display: flex;
      flex: none;
      gap: var(--ok-space);
    }

    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: calc(var(--ok-space) * 2);
      margin-top: calc(var(--ok-space) * 3);
    }

    .chip {
      border: 1px solid var(--ok-line);
      border-radius: var(--ok-radius);
      line-height: 1.3;
      padding: var(--ok-space) calc(var(--ok-space) * 2);
    }

    .chip[data-level='warn'] {
      border-color: var(--ok-warn);
      color: var(--ok-warn);
    }

    .chip[data-level='error'] {
      border-color: var(--ok-error);
      color: var(--ok-error);
    }

    .chip .label {
      color: var(--ok-muted);
      display: block;
      font-size: var(--ok-font-size-xs);
    }

    .chip[data-level] .label {
      color: inherit;
    }

    .filter {
      align-items: center;
      display: flex;
      gap: var(--ok-space);
      margin-inline-start: auto;
    }

    .filter[aria-pressed='true'] {
      border-color: var(--s-server);
      color: var(--s-server);
    }
  `];

  render(): unknown {
    const interaction = this.interaction;
    return html`
      <div class="top">
        <span class="where">
          <div class="route">
            ${interaction?.route ?? html`<span class="muted">no route yet</span>`}
          </div>
          <div class="class">${this.viewClass(interaction)}</div>
        </span>
        <span class="actions">
          <button title="Load this tab's interactions again"
            @click=${() => this.fire('profile-refresh')}>Refresh</button>
          <button title="Forget every interaction of this tab"
            @click=${() => this.fire('profile-clear')}>Clear</button>
        </span>
      </div>
      <div class="chips">
        ${this.chips(interaction)}
        <button
          class="tag filter"
          aria-pressed=${this.routeFilter ? 'true' : 'false'}
          title="Show only the interactions of this route"
          @click=${() => this.fire('route-filter-toggled')}
        >
          This route only${this.routeFilter && this.hiddenCount > 0
            ? html` · ${this.hiddenCount} hidden`
            : nothing}
        </button>
      </div>
    `;
  }

  /**
   * The file the interaction ran through, which is the one the developer
   * would open. The frame says it exactly; the component is the fallback,
   * being the class that was targeted rather than the one that handled it.
   */
  private viewClass(interaction: Interaction | null): string {
    if (interaction === null) {
      return '';
    }
    const fromFrame = frameFile(interaction.applicationFrame);
    if (fromFrame) {
      return fromFrame;
    }
    const component = simpleName(interaction.component);
    return component === '_unknown' ? '' : component;
  }

  private chips(interaction: Interaction | null): TemplateResult[] {
    if (interaction === null) {
      return [];
    }
    const queries = interaction.queries.length;
    const out = [
      this.chip('Server time', duration(interaction.durationMs)),
      this.chip('Database',
        queries === 0
          ? 'no queries'
          : `${count(queries, 'query', 'queries')} · `
            + `${sumDuration(databaseMs(interaction), queries)}`,
        queries >= QUERY_WARN_COUNT ? 'warn' : undefined)
    ];
    if (this.uiState !== null) {
      out.push(this.chip('Retained state',
        `${this.uiState.nodes} nodes`));
    }
    if (failed(interaction)) {
      out.push(this.chip('Outcome',
        interaction.exceptionType
          ? simpleName(interaction.exceptionType)
          : 'error',
        'error'));
    }
    return out;
  }

  private chip(label: string, value: string,
      level?: 'warn' | 'error'): TemplateResult {
    return html`<span class="chip" data-level=${level ?? nothing}>
      <span class="label">${label}</span><span class="num">${value}</span>
    </span>`;
  }

  private fire(type: string): void {
    this.dispatchEvent(new CustomEvent(type, {
      bubbles: true,
      composed: true
    }));
  }
}

customElements.define('ok-profiler-header', OkProfilerHeader);
