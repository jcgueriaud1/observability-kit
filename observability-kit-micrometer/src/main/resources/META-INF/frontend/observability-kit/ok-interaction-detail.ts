// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import { LitElement, css, html, nothing } from 'lit';
import type { TemplateResult } from 'lit';

import './ok-query-table';
import './ok-waterfall';
import { clockTime, count, duration, simpleName } from './format';
import type { Hint, Interaction } from './model';
import { RPC_NAVIGATION, hints } from './model';
import { tokens } from './styles';

/**
 * One interaction in full: what it was, when its queries ran, what they were,
 * and -- when a rule fires -- what to do about it.
 *
 * The hint box is the part that is not a number. Everything above it says
 * what happened; it says what is wrong with what happened, and only when
 * something is: an interaction inside the budget that ran one query breaks no
 * rule and gets no box, so a box appearing means something.
 */
export class OkInteractionDetail extends LitElement {
  static properties = {
    interaction: { attribute: false }
  };

  declare interaction: Interaction | null;

  constructor() {
    super();
    this.interaction = null;
  }

  static styles = [tokens, css`
    :host {
      display: block;
      overflow-y: auto;
      padding: calc(var(--ok-space) * 3);
    }

    .title {
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .subtitle {
      color: var(--ok-muted);
      font-size: var(--ok-font-size-xs);
      overflow-wrap: anywhere;
    }

    h4 {
      color: var(--ok-muted);
      font-size: var(--ok-font-size-xs);
      font-weight: 400;
      letter-spacing: 0.04em;
      margin: calc(var(--ok-space) * 3) 0 var(--ok-space);
      text-transform: uppercase;
    }

    .hint {
      border: 1px solid var(--ok-warn);
      border-radius: var(--ok-radius);
      color: var(--ok-warn);
      margin-top: calc(var(--ok-space) * 3);
      padding: calc(var(--ok-space) * 2) calc(var(--ok-space) * 3);
    }

    .hint[data-level='error'] {
      border-color: var(--ok-error);
      color: var(--ok-error);
    }

    .hint .frame {
      display: block;
      margin-top: var(--ok-space);
      opacity: 0.85;
    }

    .placeholder {
      color: var(--ok-muted);
    }
  `];

  render(): unknown {
    const interaction = this.interaction;
    if (interaction === null) {
      return html`<div class="placeholder">
        Pick an interaction to see what it did.
      </div>`;
    }
    const component = simpleName(interaction.component);
    const where = interaction.rpcType === RPC_NAVIGATION
      ? interaction.location ?? interaction.route
      : interaction.route;
    const found = hints(interaction);
    return html`
      <div class="title">
        ${interaction.event ?? interaction.rpcType ?? 'interaction'}
        ${component && component !== '_unknown'
          ? html` · ${component}`
          : nothing}
      </div>
      <div class="subtitle">
        ${where ?? 'unknown route'} · ${duration(interaction.durationMs)}
        ${interaction.timestamp !== null
          ? html` · ${clockTime(interaction.timestamp)}`
          : nothing}
        ${interaction.applicationFrame
          ? html` · ${interaction.applicationFrame}`
          : nothing}
      </div>

      <h4>Timeline</h4>
      <ok-waterfall .interaction=${interaction}></ok-waterfall>

      <h4>
        Queries${interaction.queries.length > 0
          ? html` · ${count(interaction.queries.length, 'query', 'queries')}`
          : nothing}
      </h4>
      ${interaction.queries.length === 0
        ? html`<div class="placeholder">
            This interaction ran no queries.
          </div>`
        : html`<ok-query-table .interaction=${interaction}></ok-query-table>`}

      ${found.map((hint) => this.hint(hint))}
    `;
  }

  private hint(hint: Hint): TemplateResult {
    return html`
      <div class="hint" data-level=${hint.level}>
        ${hint.text}
        ${hint.frame
          ? html`<span class="frame mono">${hint.frame}</span>`
          : nothing}
      </div>
    `;
  }
}

customElements.define('ok-interaction-detail', OkInteractionDetail);
