// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import { LitElement, css, html, nothing } from 'lit';
import type { TemplateResult } from 'lit';

import { duration } from './format';
import type { Bar, Interaction } from './model';
import { waterfall } from './model';
import { tokens } from './styles';

/**
 * One interaction as bars on its own timeline: the request, then each query
 * it ran, placed where in the interaction it happened.
 *
 * Pure rendering. It holds no state and asks nothing of the server -- give it
 * an interaction and it draws that interaction -- so what it shows can only
 * be wrong if `waterfall()` is, and that is a function with a test.
 *
 * The scale is the interaction, not the longest bar: a query that took two
 * milliseconds of a 900 ms click has to look like two milliseconds of it,
 * because the gap between the bars is the finding. Where the same statement
 * ran repeatedly in a row, the run is one hatched bar labelled with its
 * count, so thirty lookups read as one block of lookups.
 */
export class OkWaterfall extends LitElement {
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
    }

    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: calc(var(--ok-space) * 3);
      color: var(--ok-muted);
      font-size: var(--ok-font-size-xs);
      margin-bottom: var(--ok-space);
    }

    .legend span {
      align-items: center;
      display: inline-flex;
      gap: var(--ok-space);
    }

    .swatch {
      block-size: 8px;
      border-radius: 2px;
      inline-size: 8px;
    }

    .row {
      align-items: center;
      display: grid;
      gap: calc(var(--ok-space) * 2);
      grid-template-columns: minmax(0, 9rem) minmax(0, 1fr) 4.5rem;
      padding-block: 1px;
    }

    .label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .row[data-child] .label {
      color: var(--ok-muted);
      padding-inline-start: calc(var(--ok-space) * 3);
    }

    .track {
      background: var(--ok-raised);
      block-size: 12px;
      border-radius: 4px;
      position: relative;
    }

    .bar {
      block-size: 12px;
      border-radius: 4px;
      min-inline-size: 2px;
      position: absolute;
      inset-block-start: 0;
    }

    /* A run of the same statement: hatched, so the count is visible as
       texture before it is read as a number. */
    .bar[data-repeated] {
      background-image: repeating-linear-gradient(
        135deg,
        rgba(255, 255, 255, 0.55) 0 3px,
        transparent 3px 6px
      );
    }

    /* The count of a collapsed run, next to the bar it belongs to when
       there is room after it, and inside its end when there is not. */
    .count {
      color: var(--ok-muted);
      font-size: var(--ok-font-size-xs);
      line-height: 12px;
      position: absolute;
      white-space: nowrap;
    }

    .count[data-inside] {
      color: light-dark(#fff, #06121f);
      font-weight: 600;
      transform: translateX(-100%);
    }

    .time {
      color: var(--ok-muted);
      text-align: end;
    }

    .axis {
      color: var(--ok-muted);
      display: grid;
      font-size: var(--ok-font-size-xs);
      /* Aligned with the tracks above, so a label sits under its own tick. */
      grid-template-columns: minmax(0, 9rem) minmax(0, 1fr) 4.5rem;
      gap: calc(var(--ok-space) * 2);
      margin-top: var(--ok-space);
    }

    .ticks {
      display: flex;
      justify-content: space-between;
    }
  `];

  render(): unknown {
    const interaction = this.interaction;
    if (interaction === null) {
      return nothing;
    }
    const bars = waterfall(interaction);
    // The interaction's own duration is the scale. A zero-millisecond
    // interaction still has to divide, and everything in it is then at the
    // start of a bar of no length.
    const total = Math.max(interaction.durationMs, 1);
    return html`
      <div class="legend">
        ${this.swatch('server', 'server')}
        ${this.swatch('data', 'data provider')}
        ${this.swatch('jdbc', 'JDBC')}
        ${this.swatch('error', 'error')}
      </div>
      ${bars.map((bar) => this.row(bar, total))}
      <div class="axis">
        <span></span>
        <span class="ticks">
          <span>0</span>
          <span>${duration(total / 2)}</span>
          <span>${duration(total)}</span>
        </span>
        <span></span>
      </div>
    `;
  }

  private swatch(category: string, label: string): TemplateResult {
    return html`<span>
      <span class="swatch" style="background: var(--s-${category})"></span>${label}
    </span>`;
  }

  /**
   * The run's count, placed after the bar while the bar leaves room for it
   * and inside its trailing edge once it does not, so a run that fills the
   * whole interaction still says how many executions it was.
   */
  private countLabel(count: number, end: number): TemplateResult {
    const inside = end > 82;
    return html`<span
      class="count"
      ?data-inside=${inside}
      style="inset-inline-start: calc(${end}% ${inside ? '- 4px' : '+ 4px'})"
      >×${count}</span
    >`;
  }

  private row(bar: Bar, total: number): TemplateResult {
    const left = Math.min(100, (bar.startOffsetMs / total) * 100);
    const width = Math.min(100 - left, (bar.durationMs / total) * 100);
    const title = bar.count > 1
      ? `${bar.label} — ${bar.count}× in ${duration(bar.durationMs)}`
      : `${bar.label} — ${duration(bar.durationMs)}`;
    return html`
      <div class="row" ?data-child=${!bar.root}>
        <span class="label" title=${bar.label}>${bar.label}</span>
        <span class="track">
          <span
            class="bar"
            ?data-repeated=${bar.count > 1}
            style="inset-inline-start: ${left}%; inline-size: ${width}%;
                   background-color: var(--s-${bar.category})"
            title=${title}></span>
          ${bar.count > 1 ? this.countLabel(bar.count, left + width) : nothing}
        </span>
        <span class="time num">${duration(bar.durationMs)}</span>
      </div>
    `;
  }
}

customElements.define('ok-waterfall', OkWaterfall);
