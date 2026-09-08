// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import { css } from 'lit';

/**
 * The panel's shared look, defined once and adopted by every element in it.
 *
 * Everything comes from a Copilot token with a Lumo or platform fallback, so
 * the panel is typeset by the window it sits in rather than by itself, and
 * the neutrals go through `light-dark()`: Copilot sets `color-scheme` on its
 * root when the developer switches its theme, and that inherits through the
 * shadow boundary, so the panel follows without being told.
 *
 * The four category colours are the panel's own -- they name what a bar is,
 * which is a meaning no host token has -- and are the same four in the
 * waterfall, its legend and the query table.
 */
export const tokens = css`
  :host {
    --ok-font: var(--copilot-font-sans, var(--lumo-font-family, system-ui, sans-serif));
    --ok-font-mono: var(--copilot-font-mono, ui-monospace, monospace);
    --ok-font-size: var(--copilot-font-size-sm, 0.8125rem);
    --ok-font-size-xs: var(--copilot-font-size-xs, 0.75rem);
    --ok-space: var(--copilot-spacing, 0.25rem);
    --ok-radius: var(--vaadin-radius-m, 0.5rem);
    --ok-radius-s: var(--vaadin-radius-s, 0.25rem);

    --ok-text: var(--vaadin-text-color, currentColor);
    --ok-muted: var(--vaadin-text-color-secondary, light-dark(#5c6370, #9aa0aa));
    --ok-line: light-dark(rgba(0, 0, 0, 0.12), rgba(255, 255, 255, 0.16));
    --ok-raised: light-dark(rgba(0, 0, 0, 0.04), rgba(255, 255, 255, 0.06));
    --ok-selected: light-dark(rgba(22, 118, 243, 0.12), rgba(96, 165, 250, 0.18));

    --ok-error: light-dark(#d92d20, #f87171);
    --ok-warn: light-dark(#b54708, #fbbf24);

    /* Waterfall categories, in the order the legend lists them. */
    --s-server: light-dark(#1676f3, #60a5fa);
    --s-data: light-dark(#7b2bff, #b8a5ff);
    --s-jdbc: light-dark(#0d9b8a, #0eb39e);
    --s-error: light-dark(#d92d20, #f87171);

    color: var(--ok-text);
    font-family: var(--ok-font);
    font-size: var(--ok-font-size);
    line-height: 1.45;
  }

  * {
    box-sizing: border-box;
  }

  .muted {
    color: var(--ok-muted);
  }

  .num {
    font-variant-numeric: tabular-nums;
  }

  code,
  .mono {
    font-family: var(--ok-font-mono);
    font-size: var(--ok-font-size-xs);
  }

  button {
    appearance: none;
    background: transparent;
    border: 1px solid var(--ok-line);
    border-radius: var(--ok-radius-s);
    color: inherit;
    cursor: pointer;
    font: inherit;
    padding: calc(var(--ok-space) * 0.5) calc(var(--ok-space) * 2);
  }

  button:hover {
    background: var(--ok-raised);
  }

  button:focus-visible {
    outline: 2px solid var(--s-server);
    outline-offset: 1px;
  }

  /* A short, quiet label on a value: "20 queries", "3 identical". */
  .tag {
    border: 1px solid var(--ok-line);
    border-radius: var(--ok-radius-s);
    font-size: var(--ok-font-size-xs);
    padding: 0 calc(var(--ok-space) * 1.5);
    white-space: nowrap;
  }

  .tag[data-level='warn'] {
    border-color: var(--ok-warn);
    color: var(--ok-warn);
  }

  .tag[data-level='error'] {
    border-color: var(--ok-error);
    color: var(--ok-error);
  }
`;
