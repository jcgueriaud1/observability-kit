// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import { LitElement, css, html, nothing } from 'lit';
import type { TemplateResult } from 'lit';

import { duration, simpleName } from './format';
import type { Interaction } from './model';
import { RPC_NAVIGATION, SLOW_MS, failed } from './model';
import { tokens } from './styles';

/**
 * What this tab did, newest first.
 *
 * A hundred rows is the most it ever holds, so it renders all of them: a list
 * that cannot grow does not need to be virtualised, and a plain list keeps
 * the keyboard behaviour that a windowed one has to reimplement.
 *
 * A pushed interaction arrives at the top without moving the selection,
 * because the developer reading a row is reading it while the application
 * keeps running -- a click on a Copilot control is itself an interaction, and
 * a list that followed the newest row would take the one being read away.
 * The exception is the first one, which nothing was reading.
 *
 * The empty state is the onboarding. It is the first thing anyone opening
 * this panel sees, and what it has to say is not "no data" but what to do to
 * get some, and that doing it costs nobody but the developer.
 */
export class OkInteractionList extends LitElement {
  static properties = {
    interactions: { attribute: false },
    selectedId: { attribute: false }
  };

  declare interactions: Interaction[];
  declare selectedId: number | null;

  constructor() {
    super();
    this.interactions = [];
    this.selectedId = null;
  }

  static styles = [tokens, css`
    :host {
      display: block;
      overflow-y: auto;
    }

    .list {
      display: flex;
      flex-direction: column;
    }

    .list:focus-visible {
      outline: 2px solid var(--s-server);
      outline-offset: -2px;
    }

    .row {
      align-items: start;
      border: 0;
      border-bottom: 1px solid var(--ok-line);
      border-radius: 0;
      display: grid;
      gap: calc(var(--ok-space) * 2);
      grid-template-columns: 8px minmax(0, 1fr) auto;
      padding: calc(var(--ok-space) * 2) calc(var(--ok-space) * 3);
      text-align: start;
      inline-size: 100%;
    }

    .row[aria-selected='true'] {
      background: var(--ok-selected);
    }

    .dot {
      background: var(--s-server);
      block-size: 8px;
      border-radius: 50%;
      inline-size: 8px;
      margin-block-start: 0.35em;
    }

    .row[data-slow] .dot {
      background: var(--ok-warn);
    }

    .row[data-failed] .dot {
      background: var(--ok-error);
    }

    .what {
      min-inline-size: 0;
    }

    .what > div {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .where {
      color: var(--ok-muted);
      font-size: var(--ok-font-size-xs);
    }

    .took {
      color: var(--ok-muted);
      white-space: nowrap;
    }

    .row[data-failed] .took {
      color: var(--ok-error);
    }

    .empty {
      color: var(--ok-muted);
      padding: calc(var(--ok-space) * 4);
    }

    .empty strong {
      color: var(--ok-text);
      display: block;
      margin-bottom: var(--ok-space);
    }

    .empty ol {
      margin: calc(var(--ok-space) * 3) 0;
      padding-inline-start: calc(var(--ok-space) * 5);
    }

    .empty li {
      margin-bottom: var(--ok-space);
    }

    .empty .scope {
      border-top: 1px solid var(--ok-line);
      display: block;
      margin-top: calc(var(--ok-space) * 3);
      padding-top: calc(var(--ok-space) * 3);
    }
  `];

  render(): unknown {
    if (this.interactions.length === 0) {
      return this.empty();
    }
    return html`
      <div
        class="list"
        role="listbox"
        tabindex="0"
        aria-label="Interactions"
        aria-activedescendant=${this.selectedId === null
          ? nothing
          : `ok-interaction-${this.selectedId}`}
        @keydown=${this.onKeyDown}
      >
        ${this.interactions.map((interaction) => this.row(interaction))}
      </div>
    `;
  }

  private empty(): TemplateResult {
    return html`
      <div class="empty">
        <strong>Nothing recorded yet for this tab.</strong>
        Click something in the application. Each click, navigation and grid
        scroll appears here with what it cost on the server.
        <ol>
          <li>Click a button, or open another view.</li>
          <li>Pick the row that appears at the top of this list.</li>
          <li>
            Read what it did: the server time, then every query the click ran.
          </li>
        </ol>
        <span class="scope">
          Only your own tab is profiled. Other users and production are never
          affected.
        </span>
      </div>
    `;
  }

  private row(interaction: Interaction): TemplateResult {
    const error = failed(interaction);
    const component = simpleName(interaction.component);
    const what = component && component !== '_unknown'
      ? `${interaction.event ?? interaction.rpcType} · ${component}`
      : `${interaction.event ?? interaction.rpcType ?? 'interaction'}`;
    // A navigation is about where it went, which the template does not say.
    const where = interaction.rpcType === RPC_NAVIGATION
      ? interaction.location ?? interaction.route
      : interaction.route;
    return html`
      <button
        class="row"
        role="option"
        tabindex="-1"
        id="ok-interaction-${interaction.id}"
        aria-selected=${this.selectedId === interaction.id ? 'true' : 'false'}
        ?data-failed=${error}
        ?data-slow=${!error && interaction.durationMs >= SLOW_MS}
        @click=${() => this.select(interaction.id)}
      >
        <span class="dot"></span>
        <span class="what">
          <div>${what}</div>
          <div class="where">${where ?? ''}</div>
        </span>
        <span class="took num">${duration(interaction.durationMs)}</span>
      </button>
    `;
  }

  private onKeyDown(event: KeyboardEvent): void {
    const at = this.interactions
      .findIndex((interaction) => interaction.id === this.selectedId);
    let next = at;
    if (event.key === 'ArrowDown') {
      next = Math.min(this.interactions.length - 1, at + 1);
    } else if (event.key === 'ArrowUp') {
      next = at <= 0 ? 0 : at - 1;
    } else if (event.key === 'Home') {
      next = 0;
    } else if (event.key === 'End') {
      next = this.interactions.length - 1;
    } else if (event.key === 'Enter' || event.key === ' ') {
      next = at === -1 ? 0 : at;
    } else {
      return;
    }
    event.preventDefault();
    const interaction = this.interactions[next];
    if (interaction) {
      this.select(interaction.id);
    }
  }

  private select(id: number): void {
    this.dispatchEvent(new CustomEvent('interaction-selected', {
      detail: { id },
      bubbles: true,
      composed: true
    }));
  }
}

customElements.define('ok-interaction-list', OkInteractionList);
