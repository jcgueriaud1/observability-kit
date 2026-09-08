// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.

import { LitElement, css, html, nothing } from 'lit';

import './ok-interaction-detail';
import './ok-interaction-list';
import './ok-meters-table';
import './ok-profiler-header';
import { recordHistory } from './ok-meters-table';
import { ago, count } from './format';
import type { Interaction, Meter, UiState } from './model';
import {
  COMMAND_INTERACTION, COMMAND_METRICS, COMMAND_PROFILE, COMMAND_PROFILE_CLEAR,
  COMMAND_PROFILE_DATA, COMMAND_PROFILE_SUBSCRIBE, COMMAND_REFRESH,
  COMMAND_UI_STATE, MAX_INTERACTIONS
} from './model';
import { tokens } from './styles';

/**
 * The Observability Kit panel in Vaadin Copilot: what this tab just did, and
 * what the application as a whole has done.
 *
 * Shipped as TypeScript in the kit's `META-INF/frontend` and compiled by the
 * application's own build, which is why the kit needs no frontend build of
 * its own. Registered by a `developmentOnly` `@JsModule` on
 * `MetricsServiceInitListener`, so it is in no production bundle: the panel
 * talks over the dev-tools websocket, which in production does not exist.
 *
 * <h3>Two tabs, two questions</h3>
 *
 * *Interactions* is the developer's own tab, interaction by interaction, and
 * is what this panel is for: "what did my click just cost". *Meters* is the
 * whole registry -- every user, every view, since the application started --
 * which is the panel the kit shipped before and the question a meter can
 * actually answer.
 *
 * <h3>Push, not poll</h3>
 *
 * The interactions arrive as they happen: the panel subscribes to its tab
 * once and the server sends each finished interaction and each state sample.
 * Polling for them would be a round trip late when something happened and a
 * round trip for nothing the rest of the time. The meters are still polled,
 * on the three-second interval they always were, and only while their tab is
 * the one being looked at.
 *
 * <h3>Which tab it is</h3>
 *
 * The UI id comes from the Flow client in the page, the way Copilot reads it
 * (`copilot/shared/flow-utils.ts`, `getUIId()`): the browser can ask its own
 * client, and only the server can turn that id into a profile, since it
 * resolves it through the session the dev-tools connection belongs to. It is
 * re-read rather than captured, so a resync that replaces the UI moves the
 * panel onto the new one, and an answer for a different id is dropped rather
 * than shown as this tab's.
 *
 * <h3>State outside the element</h3>
 *
 * Copilot creates a panel's element when the panel is opened and destroys it
 * when it is closed, so what the developer has collected lives at module
 * scope: closing the panel to click something and opening it again must not
 * be what loses the list.
 */

const PANEL_TAG = 'observability-kit-panel';

/** How often the panel checks that it is still watching the right tab. */
const UI_ID_WATCH_MS = 3000;

/** The meters poll, unchanged from the panel this one grew out of. */
const METERS_INTERVAL_MS = 3000;

type Tab = 'interactions' | 'meters';

interface ServerMessage {
  command: string;
  data: any;
}

interface CopilotInterface {
  send(command: string, data: unknown): void;
  addPanel(panel: Record<string, unknown>): void;
}

/**
 * Everything the panel has collected, at module scope so that it outlives the
 * element Copilot creates and destroys with the panel.
 */
const store = {
  uiId: null as number | null,
  /** Newest first, capped like the server buffer it mirrors. */
  interactions: [] as Interaction[],
  selectedId: null as number | null,
  uiState: null as UiState | null,
  routeFilter: false,
  tab: 'interactions' as Tab,
  meters: [] as Meter[],
  metersAt: null as number | null
};

/** The Copilot interface, captured at plugin init; the panel's way to the server. */
let copilot: CopilotInterface | null = null;

/**
 * This page's UI id, read from the Flow client. Copilot reads it exactly this
 * way; the last client wins there too, an application having one.
 */
function currentUiId(): number | null {
  const flow = (window as any).Vaadin?.Flow;
  if (!flow?.clients) {
    return null;
  }
  let found: number | null = null;
  for (const key of Object.keys(flow.clients)) {
    const client = flow.clients[key];
    if (client && typeof client.getUIId === 'function') {
      const id = Number(client.getUIId());
      if (Number.isInteger(id) && id >= 0) {
        found = id;
      }
    }
  }
  return found;
}

export class ObservabilityKitPanel extends LitElement {
  static properties = {
    _revision: { state: true }
  };

  /** Bumped to re-render off the module-scoped store, which Lit cannot watch. */
  private declare _revision: number;

  private uiIdWatch = 0;
  private metersPoll = 0;

  constructor() {
    super();
    this._revision = 0;
  }

  static styles = [tokens, css`
    :host {
      block-size: 100%;
      display: flex;
      flex-direction: column;
      min-block-size: 0;
      overflow: hidden;
    }

    .tabs {
      border-bottom: 1px solid var(--ok-line);
      display: flex;
      flex: none;
      gap: var(--ok-space);
      padding: var(--ok-space) calc(var(--ok-space) * 2) 0;
    }

    .tabs button {
      border: 0;
      border-bottom: 2px solid transparent;
      border-radius: 0;
      color: var(--ok-muted);
      padding: var(--ok-space) calc(var(--ok-space) * 3);
    }

    .tabs button[aria-selected='true'] {
      border-bottom-color: var(--s-server);
      color: var(--ok-text);
    }

    .split {
      display: grid;
      flex: 1;
      grid-template-columns: minmax(12rem, 17rem) minmax(0, 1fr);
      min-block-size: 0;
    }

    ok-interaction-list {
      border-inline-end: 1px solid var(--ok-line);
      min-block-size: 0;
    }

    ok-interaction-detail,
    ok-meters-table {
      min-block-size: 0;
    }

    .footer {
      border-top: 1px solid var(--ok-line);
      color: var(--ok-muted);
      display: flex;
      flex: none;
      flex-wrap: wrap;
      gap: calc(var(--ok-space) * 2);
      font-size: var(--ok-font-size-xs);
      padding: var(--ok-space) calc(var(--ok-space) * 3);
    }

    .footer strong {
      color: var(--ok-text);
      font-weight: 600;
    }
  `];

  connectedCallback(): void {
    super.connectedCallback();
    // Always on connect, not only when the id changed: closing the panel and
    // opening it again gives Copilot a new element, and what the server
    // pushed while there was none to render it went nowhere.
    this.watchUi(true);
    this.uiIdWatch = window.setInterval(() => this.watchUi(), UI_ID_WATCH_MS);
    this.syncMetersPoll();
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    window.clearInterval(this.uiIdWatch);
    window.clearInterval(this.metersPoll);
    this.uiIdWatch = 0;
    this.metersPoll = 0;
  }

  /**
   * Copilot's panel manager calls this on the panel's content element. The
   * panel is positioned explicitly, so there is nothing to recompute -- this
   * only satisfies the contract its own `BasePanel` would.
   */
  requestLayoutUpdate(): Promise<void> {
    return Promise.resolve();
  }

  /**
   * Called by Copilot for every server message. The panel claims the five it
   * asked for and lets everything else past, which is how the other panels
   * get theirs.
   */
  handleMessage(message: ServerMessage): boolean {
    if (!message) {
      return false;
    }
    switch (message.command) {
    case COMMAND_PROFILE_DATA:
      if (this.isOwn(message.data)) {
        store.interactions = (message.data.interactions ?? [])
          .slice(0, MAX_INTERACTIONS);
        store.uiState = message.data.uiState ?? null;
        this.settleSelection();
        this.changed();
      }
      return true;
    case COMMAND_INTERACTION:
      if (this.isOwn(message.data) && message.data.interaction) {
        this.addPushed(message.data.interaction);
        this.changed();
      }
      return true;
    case COMMAND_UI_STATE:
      if (this.isOwn(message.data)) {
        store.uiState = message.data.uiState ?? null;
        this.changed();
      }
      return true;
    case COMMAND_METRICS:
      store.meters = message.data?.meters ?? [];
      store.metersAt = message.data?.timestamp ?? null;
      recordHistory(store.meters);
      this.changed();
      return true;
    default:
      return false;
    }
  }

  render(): unknown {
    return html`
      <div class="tabs" role="tablist">
        ${this.tab('interactions', 'Interactions')}
        ${this.tab('meters', 'Meters')}
      </div>
      ${store.tab === 'interactions' ? this.interactions() : this.meters()}
    `;
  }

  private tab(id: Tab, label: string): unknown {
    return html`<button
      role="tab"
      aria-selected=${store.tab === id ? 'true' : 'false'}
      @click=${() => this.selectTab(id)}
    >${label}</button>`;
  }

  private interactions(): unknown {
    const visible = this.visibleInteractions();
    const selected = visible
      .find((interaction) => interaction.id === store.selectedId)
      ?? null;
    return html`
      <ok-profiler-header
        .interaction=${selected}
        .uiState=${store.uiState}
        .routeFilter=${store.routeFilter}
        .hiddenCount=${store.interactions.length - visible.length}
        @profile-refresh=${this.refresh}
        @profile-clear=${this.clear}
        @route-filter-toggled=${this.toggleRouteFilter}
      ></ok-profiler-header>
      <div class="split">
        <ok-interaction-list
          .interactions=${visible}
          .selectedId=${store.selectedId}
          @interaction-selected=${this.onSelected}
        ></ok-interaction-list>
        <ok-interaction-detail .interaction=${selected}></ok-interaction-detail>
      </div>
      <div class="footer">${this.footer()}</div>
    `;
  }

  private meters(): unknown {
    return html`<ok-meters-table
      .meters=${store.meters}
      .timestamp=${store.metersAt}
    ></ok-meters-table>`;
  }

  private footer(): unknown {
    if (store.uiState === null) {
      return html`<span
        >This tab has not been measured yet. UI state size is opt-in
        (<code>vaadin.observability.ui-state</code>).</span
      >`;
    }
    const state = store.uiState;
    return html`
      <span>
        UI state <strong>${count(state.nodes, 'node')}</strong> ·
        <strong>${count(state.components, 'component')}</strong> ·
        <strong>${count(state.views, 'view')}</strong>
        ${state.staleViews > 0
          ? html` (${state.staleViews} stale)`
          : nothing}
      </span>
      <span>sampled ${ago(state.sampleAgeMs)}</span>
    `;
  }

  /**
   * The rows the list shows: all of them, or -- with the route chip on --
   * only the ones of the route the tab is on now.
   */
  private visibleInteractions(): Interaction[] {
    const route = this.currentRoute();
    if (!store.routeFilter || route === null) {
      return store.interactions;
    }
    return store.interactions
      .filter((interaction) => interaction.route === route);
  }

  /**
   * The route the tab is on, being the route of the newest interaction. The
   * browser path is not a template, and nothing else in the payload names
   * one.
   */
  private currentRoute(): string | null {
    for (const interaction of store.interactions) {
      if (typeof interaction.route === 'string') {
        return interaction.route;
      }
    }
    return null;
  }

  /** Whether an answer is about the tab this panel is watching. */
  private isOwn(data: any): boolean {
    return !!data && data.uiId === store.uiId;
  }

  /**
   * A pushed interaction goes on top and the oldest goes off the end, at the
   * same count the server keeps. The selection is left alone unless nothing
   * was selected, so a row being read is not replaced by a row arriving.
   */
  private addPushed(interaction: Interaction): void {
    store.interactions = [interaction, ...store.interactions]
      .slice(0, MAX_INTERACTIONS);
    if (store.selectedId === null) {
      store.selectedId = interaction.id;
    }
  }

  /** Selects the newest visible row when what was selected is gone. */
  private settleSelection(): void {
    const visible = this.visibleInteractions();
    const stillThere = visible
      .some((interaction) => interaction.id === store.selectedId);
    if (!stillThere) {
      store.selectedId = visible.length > 0 ? visible[0].id : null;
    }
  }

  private onSelected(event: CustomEvent<{ id: number }>): void {
    store.selectedId = event.detail.id;
    this.changed();
  }

  private selectTab(tab: Tab): void {
    store.tab = tab;
    this.changed();
    this.syncMetersPoll();
  }

  private toggleRouteFilter(): void {
    store.routeFilter = !store.routeFilter;
    this.settleSelection();
    this.changed();
  }

  private refresh = (): void => {
    this.watchUi(true);
    if (store.tab === 'meters') {
      copilot?.send(COMMAND_REFRESH, {});
    }
  };

  private clear = (): void => {
    this.send(COMMAND_PROFILE_CLEAR);
    store.interactions = [];
    store.selectedId = null;
    this.changed();
  };

  /**
   * Makes sure the panel is watching the tab it is in. The id is re-read
   * rather than captured: a resync replaces the UI under the same page, and a
   * panel still subscribed to the old one would sit silent with nothing to
   * say about it.
   *
   * Subscribing again is what the server expects of a panel that reloaded --
   * it replaces the earlier subscription rather than doubling the messages --
   * so asking twice costs a round trip and nothing else.
   *
   * @param force
   *            ask again even when the tab has not changed, which is what a
   *            freshly opened panel and the Refresh button both want
   */
  private watchUi(force = false): void {
    const uiId = currentUiId();
    if (uiId === null) {
      return;
    }
    const moved = uiId !== store.uiId;
    if (!moved && !force) {
      return;
    }
    if (moved) {
      store.uiId = uiId;
      // A tab this panel has never seen starts empty rather than showing what
      // the previous UI did under a heading that now names a different one.
      store.interactions = [];
      store.selectedId = null;
      store.uiState = null;
    }
    this.send(COMMAND_PROFILE);
    this.send(COMMAND_PROFILE_SUBSCRIBE);
    this.changed();
  }

  private send(command: string): void {
    if (copilot !== null && store.uiId !== null) {
      copilot.send(command, { uiId: store.uiId });
    }
  }

  /** The meters are polled only while their own tab is the one being read. */
  private syncMetersPoll(): void {
    window.clearInterval(this.metersPoll);
    this.metersPoll = 0;
    if (store.tab !== 'meters') {
      return;
    }
    copilot?.send(COMMAND_REFRESH, {});
    this.metersPoll = window.setInterval(
      () => copilot?.send(COMMAND_REFRESH, {}), METERS_INTERVAL_MS);
  }

  private changed(): void {
    this._revision += 1;
  }
}

customElements.define(PANEL_TAG, ObservabilityKitPanel);

const plugin = {
  init(copilotInterface: CopilotInterface): void {
    copilot = copilotInterface;
    copilotInterface.addPanel({
      header: 'Observability',
      tag: PANEL_TAG,
      // A plain element is not self-positioned the way Copilot's own
      // BasePanel is, and the panel manager skips viewport adjustment when no
      // position is set -- so without this it opens off-screen. Taller than
      // the meters panel this grew out of: the query table is the payoff, and
      // at 460 it opened just below the fold every time.
      position: {
        top: 80,
        left: 80,
        width: 720,
        height: 620
      },
      toolbarOptions: {
        iconKey: 'barChart',
        // The toolbar only renders an icon for panels mapped to an active
        // mode, and 'play' hides the panel container, so the icon is offered
        // in the three modes that show panels.
        allowedModesWithOrder: {
          edit: 100,
          inspect: 100,
          test: 100
        }
      }
    });
  }
};

/**
 * Copilot resets `window.Vaadin.copilot.plugins` to `[]` once during
 * bootstrap, so pushing eagerly races that reset and gets wiped. Waiting for
 * `_uiState` -- created in the same synchronous block right after the reset --
 * is what tells us the reset has happened. By then either
 * `initializePlugins()` has already overridden `push`, so ours initializes
 * immediately, or the entry waits in the array until it runs; both register
 * the panel.
 */
function register(): void {
  let attempts = 0;
  const maxAttempts = 600; // ~60 s at 100 ms
  const timer = window.setInterval(() => {
    attempts += 1;
    const cp = (window as any).Vaadin?.copilot;
    if (cp && cp._uiState && Array.isArray(cp.plugins)) {
      window.clearInterval(timer);
      cp.plugins.push(plugin);
    } else if (attempts >= maxAttempts) {
      window.clearInterval(timer);
    }
  }, 100);
}

register();
