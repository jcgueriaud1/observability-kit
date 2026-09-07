// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// Dev-mode Vaadin Copilot panels for observability-kit. Injected per UI by
// ObservabilityDevToolsClient via Page.executeJs (development mode only).
// Registers one Copilot plugin carrying two panels, both fed by
// ObservabilityDevToolsHandler over the dev-tools websocket:
//
//   Observability   the live vaadin.* Micrometer meters -- every user, every
//                   view, which is what a meter holds.
//   View profiler   what this one tab just did: each interaction with its
//                   server time, the queries it ran (counted, so an N+1 reads
//                   as one statement run N times), the code frame it went
//                   through, and the state the view is holding. Scope is the
//                   developer's own tab, the question being "what did my click
//                   just cost" rather than "how is the fleet".
//
// The IIFE is idempotent so repeated injection does not re-register either
// panel; the UI id it profiles is re-read from the prelude global on every
// poll, so a resync that replaces the UI is followed rather than cached.
(function () {
  if (window.__vaadinObservabilityDevToolsInstalled) {
    return;
  }
  window.__vaadinObservabilityDevToolsInstalled = true;

  var PANEL_TAG = 'observability-kit-metrics-panel';
  var COMMAND_REFRESH = 'observability-kit-refresh';
  var COMMAND_METRICS = 'observability-kit-metrics';
  var REFRESH_INTERVAL_MS = 3000;

  var PROFILER_TAG = 'observability-kit-profiler-panel';
  var COMMAND_PROFILE = 'observability-kit-profile';
  var COMMAND_PROFILE_DATA = 'observability-kit-profile-data';
  var COMMAND_ROUTE_SUMMARY = 'observability-kit-route-summary';
  var COMMAND_ROUTE_SUMMARY_DATA = 'observability-kit-route-summary-data';
  // Polled faster than the meters: this panel answers a question about the
  // click the developer just made, so the answer has to arrive while they are
  // still looking for it.
  var PROFILE_INTERVAL_MS = 1500;

  // CopilotInterface captured at plugin init; used by the panel to talk to the
  // server over the dev-tools websocket.
  var copilot = null;
  // Last snapshot received from the server, shared so a freshly opened panel
  // can render immediately before its first refresh round-trips.
  var latest = null;
  // Per-meter ring buffer of recent trend values, keyed by name+tags. Survives
  // panel close/reopen (module scope) so the sparkline keeps its history.
  var history = {};
  var HISTORY_MAX = 20;

  // Last profile and route summary received, module-scoped for the same reason
  // as `latest`: a reopened panel renders what is already known instead of a
  // blank frame while its first poll round-trips.
  var profile = null;
  var routeSummary = null;
  // Interaction ids the developer has opened. The panel rewrites its innerHTML
  // on every poll, so what is expanded cannot live in the DOM.
  var expanded = {};

  function num(value, decimals) {
    if (typeof value !== 'number' || !isFinite(value)) {
      return String(value);
    }
    if (Number.isInteger(value)) {
      return String(value);
    }
    return value.toFixed(decimals == null ? 1 : decimals);
  }

  function formatTags(tags) {
    var keys = Object.keys(tags || {});
    if (keys.length === 0) {
      return '';
    }
    return keys
      .map(function (k) {
        return k + '=' + tags[k];
      })
      .join(', ');
  }

  // Stable identity for a meter across polls (name + its tag values).
  function meterKey(meter) {
    return meter.name + '|' + formatTags(meter.tags);
  }

  // The single scalar plotted in the sparkline for this meter.
  function trendValue(meter) {
    if (typeof meter.mean === 'number') {
      return meter.mean;
    }
    if (typeof meter.value === 'number') {
      return meter.value;
    }
    if (typeof meter.count === 'number') {
      return meter.count;
    }
    if (meter.measurements && meter.measurements.length) {
      return meter.measurements[0].value;
    }
    return null;
  }

  // Append this poll's trend value to each meter's ring buffer, and drop
  // history for meters no longer reported so the map can't grow unbounded.
  function recordHistory(meters) {
    var live = {};
    (meters || []).forEach(function (meter) {
      var key = meterKey(meter);
      live[key] = true;
      var v = trendValue(meter);
      if (typeof v !== 'number' || !isFinite(v)) {
        return;
      }
      var buf = history[key] || (history[key] = []);
      buf.push(v);
      if (buf.length > HISTORY_MAX) {
        buf.shift();
      }
    });
    Object.keys(history).forEach(function (key) {
      if (!live[key]) {
        delete history[key];
      }
    });
  }

  // Inline SVG sparkline for a series of values.
  function sparkline(values) {
    if (!values || values.length < 2) {
      return '';
    }
    var w = 84;
    var h = 18;
    var pad = 2;
    var min = Math.min.apply(null, values);
    var max = Math.max.apply(null, values);
    var range = max - min || 1;
    var n = values.length;
    var pts = values
      .map(function (v, i) {
        var x = pad + (i / (n - 1)) * (w - 2 * pad);
        var y = h - pad - ((v - min) / range) * (h - 2 * pad);
        return x.toFixed(1) + ',' + y.toFixed(1);
      })
      .join(' ');
    return (
      '<svg width="' +
      w +
      '" height="' +
      h +
      '" viewBox="0 0 ' +
      w +
      ' ' +
      h +
      '" style="display:block;color:var(--lumo-primary-color,#1676f3)">' +
      '<polyline points="' +
      pts +
      '" fill="none" stroke="currentColor" stroke-width="1.25" ' +
      'stroke-linejoin="round" stroke-linecap="round"/>' +
      '</svg>'
    );
  }

  // Renders a meter's value cell from the type-aware fields sent by the server.
  function formatMeterValue(meter) {
    var unit = meter.unit ? ' ' + meter.unit : '';
    // Timer / DistributionSummary: cumulative mean is the stable figure; count
    // gives weight; max is shown only when non-zero (it decays to 0 between
    // polls in SimpleMeterRegistry).
    if (typeof meter.mean === 'number') {
      var parts = ['mean ' + num(meter.mean) + unit];
      if (typeof meter.max === 'number' && meter.max > 0) {
        parts.push('max ' + num(meter.max) + unit);
      }
      if (typeof meter.count === 'number') {
        parts.push('n=' + meter.count);
      }
      return parts.join(' · ');
    }
    if (typeof meter.value === 'number') {
      return num(meter.value, 3);
    }
    if (typeof meter.count === 'number') {
      return String(meter.count);
    }
    // Unknown meter type fallback.
    return (meter.measurements || [])
      .map(function (m) {
        return m.statistic + ': ' + num(m.value, 3);
      })
      .join(', ');
  }

  class ObservabilityMetricsPanel extends HTMLElement {
    connectedCallback() {
      this.style.display = 'block';
      this.style.height = '100%';
      this.style.overflow = 'auto';
      this.render();
      this.requestRefresh();
      this._timer = setInterval(() => this.requestRefresh(), REFRESH_INTERVAL_MS);
    }

    disconnectedCallback() {
      if (this._timer) {
        clearInterval(this._timer);
        this._timer = null;
      }
    }

    requestRefresh() {
      if (copilot) {
        copilot.send(COMMAND_REFRESH, {});
      }
    }

    // Copilot's panel manager calls this on its panel content element (the
    // method is provided by its internal BasePanel). We position the panel
    // explicitly, so there is nothing to recompute - just satisfy the contract.
    requestLayoutUpdate() {
      return Promise.resolve();
    }

    // Called by Copilot for every server message; we claim the metrics command.
    handleMessage(message) {
      if (message && message.command === COMMAND_METRICS) {
        latest = message.data;
        recordHistory(latest.meters);
        this.render();
        return true;
      }
      return false;
    }

    render() {
    if (!latest || !latest.meters || latest.meters.length === 0) {
      this.innerHTML =
        '<div style="padding:12px;font:13px sans-serif;color:var(--dev-tools-text-color-secondary,#888)">' +
        'No Vaadin meters yet. Interact with the application to generate metrics.' +
        '</div>';
      return;
    }

    var meters = latest.meters.slice().sort(function (a, b) {
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
    });

    var rows = meters
      .map(function (meter) {
        var tagText = formatTags(meter.tags);
        var nameCell =
          meter.name +
          (tagText
            ? '<div style="color:#888;font-size:11px">' + tagText + '</div>'
            : '');
        return (
          '<tr style="border-bottom:1px solid rgba(128,128,128,.15)">' +
          '<td style="padding:5px 8px;vertical-align:top;word-break:break-word">' +
          nameCell +
          '</td>' +
          '<td style="padding:5px 8px;vertical-align:top;white-space:nowrap;font-variant-numeric:tabular-nums">' +
          formatMeterValue(meter) +
          '</td>' +
          '<td style="padding:5px 8px;vertical-align:middle;width:84px">' +
          sparkline(history[meterKey(meter)]) +
          '</td>' +
          '</tr>'
        );
      })
      .join('');

    var when = latest.timestamp ? new Date(latest.timestamp).toLocaleTimeString() : '';

    this.innerHTML =
      '<div style="padding:8px 12px;font:13px sans-serif">' +
      '<div style="margin-bottom:8px;color:#888">' +
      meters.length +
      ' meter(s) &middot; updated ' +
      when +
      '</div>' +
      '<table style="border-collapse:collapse;width:100%">' +
      '<thead><tr style="text-align:left;color:#888;border-bottom:1px solid rgba(128,128,128,.3)">' +
      '<th style="padding:4px 8px">Meter</th>' +
      '<th style="padding:4px 8px">Value</th>' +
      '<th style="padding:4px 8px">Trend</th>' +
      '</tr></thead>' +
      '<tbody>' +
      rows +
      '</tbody>' +
      '</table>' +
      '</div>';
    }
  }


  // Interaction and query text is application data - SQL, exception messages,
  // component and route names - and this panel builds its DOM as markup, so
  // everything interpolated goes through here. A statement containing a "<"
  // would otherwise take the rest of the row with it.
  function esc(value) {
    if (value == null) {
      return '';
    }
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  var MUTED = 'var(--dev-tools-text-color-secondary,#888)';
  var ERROR_COLOR = 'var(--lumo-error-color,#e53935)';
  var WARN_COLOR = 'var(--lumo-warning-color,#e67700)';
  var LINE = '1px solid rgba(128,128,128,.15)';

  function clockTime(epochMs) {
    return typeof epochMs === 'number' ? new Date(epochMs).toLocaleTimeString() : '';
  }

  // How stale a figure is. A tab is measured on its own session's thread, so an
  // idle tab's state is as old as its last interaction - which is the part the
  // developer has to be able to see before trusting the numbers.
  function ago(ms) {
    if (typeof ms !== 'number' || !isFinite(ms) || ms < 0) {
      return '';
    }
    return ms < 1000 ? Math.round(ms) + ' ms ago' : (ms / 1000).toFixed(1) + ' s ago';
  }

  // A count with a noun that agrees with it, since these all read as prose.
  function count(n, singular, plural) {
    return n + ' ' + (n === 1 ? singular : plural || singular + 's');
  }

  // The three figures a profiler toolbar leads with, read off the server's
  // queryGroups so this panel never has to know how a statement is
  // parameterised. `worst` is the largest group: above one, the view ran one
  // query that many times, which is the bug worth finding.
  function queryTotals(interaction) {
    var queries = interaction.queries || [];
    if (queries.length === 0) {
      return null;
    }
    var groups = interaction.queryGroups || [];
    var totals = { queries: queries.length, statements: groups.length, durationMs: 0, rows: 0, worst: 0 };
    queries.forEach(function (query) {
      totals.durationMs += query.durationMs || 0;
      totals.rows += query.rows || 0;
    });
    groups.forEach(function (group) {
      if (group.count > totals.worst) {
        totals.worst = group.count;
      }
    });
    return totals;
  }

  // "101 queries in 640 ms" plus, when there is one to report, the repetition
  // behind it. Symfony reports the same shape as a count of different
  // statements; the wording here names what it means instead.
  function queryHeadline(totals) {
    if (!totals) {
      return '<span style="color:' + MUTED + '">no queries</span>';
    }
    var text = count(totals.queries, 'query', 'queries') + ' in ' + num(totals.durationMs) + ' ms';
    if (totals.worst > 1) {
      text +=
        ' &middot; <span style="color:' +
        WARN_COLOR +
        '">1 statement &times; ' +
        totals.worst +
        '</span>';
      return text;
    }
    return text;
  }

  // One row per distinct statement, most repeated first as the server sorted
  // them. A group of one is that single query; a group above one is the finding.
  function queryGroupRows(groups) {
    return (groups || [])
      .map(function (group) {
        var repeated = group.count > 1;
        return (
          '<tr style="border-top:' +
          LINE +
          '">' +
          '<td style="padding:3px 6px;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums' +
          (repeated ? ';color:' + WARN_COLOR + ';font-weight:600' : '') +
          '">' +
          group.count +
          '&times;</td>' +
          '<td style="padding:3px 6px;color:' +
          MUTED +
          ';white-space:nowrap">' +
          esc(group.kind) +
          '</td>' +
          '<td style="padding:3px 6px;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums">' +
          num(group.durationMs) +
          ' ms</td>' +
          '<td style="padding:3px 6px;word-break:break-word"><code style="font-size:11px">' +
          esc(group.statement) +
          '</code></td>' +
          '</tr>'
        );
      })
      .join('');
  }

  // What an interaction did, shown when its row is opened: where it ran, what
  // it threw, and its queries grouped.
  function interactionDetail(interaction) {
    var totals = queryTotals(interaction);
    var blocks = [];

    if (interaction.applicationFrame) {
      blocks.push(
        '<div style="margin-bottom:6px">' +
          '<span style="color:' +
          MUTED +
          '">frame</span> <code style="font-size:11px">' +
          esc(interaction.applicationFrame) +
          '</code></div>'
      );
    }
    if (interaction.exceptionType) {
      blocks.push(
        '<div style="margin-bottom:6px;color:' +
          ERROR_COLOR +
          '"><code style="font-size:11px">' +
          esc(interaction.exceptionType) +
          (interaction.exceptionMessage ? ': ' + esc(interaction.exceptionMessage) : '') +
          '</code></div>'
      );
    }
    if (interaction.location && interaction.location !== interaction.route) {
      blocks.push(
        '<div style="margin-bottom:6px"><span style="color:' +
          MUTED +
          '">at</span> ' +
          esc(interaction.location) +
          '</div>'
      );
    }
    if (totals) {
      blocks.push(
        '<table style="border-collapse:collapse;width:100%;margin-bottom:4px">' +
          '<tbody>' +
          queryGroupRows(interaction.queryGroups) +
          '</tbody></table>' +
          '<div style="color:' +
          MUTED +
          '">' +
          count(totals.statements, 'different statement') +
          ' &middot; ' +
          count(totals.rows, 'row') +
          ' fetched</div>'
      );
    }
    if (blocks.length === 0) {
      blocks.push('<div style="color:' + MUTED + '">Nothing else was recorded for this interaction.</div>');
    }
    return (
      '<tr><td colspan="4" style="padding:6px 10px 10px 26px;background:rgba(128,128,128,.06)">' +
      blocks.join('') +
      '</td></tr>'
    );
  }

  // One interaction as a summary row, plus its detail row when opened.
  function interactionRows(interaction) {
    var open = !!expanded[interaction.id];
    var failed = interaction.outcome === 'error';
    var label = interaction.event || interaction.rpcType || 'interaction';
    var component = interaction.component ? interaction.component.split('.').pop() : '';
    var totals = queryTotals(interaction);

    var summary =
      '<tr data-interaction="' +
      esc(interaction.id) +
      '" style="border-top:' +
      LINE +
      ';cursor:pointer">' +
      '<td style="padding:5px 4px 5px 10px;white-space:nowrap;color:' +
      MUTED +
      ';font-variant-numeric:tabular-nums">' +
      (open ? '&#9662; ' : '&#9656; ') +
      clockTime(interaction.timestamp) +
      '</td>' +
      '<td style="padding:5px 8px;word-break:break-word">' +
      (failed ? '<span style="color:' + ERROR_COLOR + '">&#9679;</span> ' : '') +
      '<strong>' +
      esc(label) +
      '</strong>' +
      (component ? ' <span style="color:' + MUTED + '">' + esc(component) + '</span>' : '') +
      '</td>' +
      '<td style="padding:5px 8px;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums' +
      (failed ? ';color:' + ERROR_COLOR : '') +
      '">' +
      num(interaction.durationMs) +
      ' ms</td>' +
      '<td style="padding:5px 10px 5px 8px;white-space:nowrap">' +
      queryHeadline(totals) +
      '</td>' +
      '</tr>';

    return open ? summary + interactionDetail(interaction) : summary;
  }

  // What the tab is holding now, rather than what any one click cost.
  function uiStateLine(state) {
    if (!state) {
      return (
        '<span style="color:' +
        MUTED +
        '">state not measured yet</span>'
      );
    }
    return (
      count(state.nodes, 'node') +
      ' &middot; ' +
      count(state.components, 'component') +
      ' &middot; ' +
      count(state.views, 'view') +
      (state.staleViews > 0
        ? ' &middot; <span style="color:' + WARN_COLOR + '">' + count(state.staleViews, 'stale view') + '</span>'
        : '') +
      ' <span style="color:' +
      MUTED +
      '">' +
      esc(ago(state.sampleAgeMs)) +
      '</span>'
    );
  }

  // The meters attributed to this view - everyone's, not just this developer's,
  // which is what a meter holds and so what this section says.
  function routeSummarySection(route) {
    if (!routeSummary || routeSummary.route !== route) {
      return '';
    }
    var meters = (routeSummary.meters || []).slice().sort(function (a, b) {
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
    });
    if (meters.length === 0) {
      return '';
    }
    var rows = meters
      .map(function (meter) {
        var tagText = formatTags(meter.tags);
        return (
          '<tr style="border-top:' +
          LINE +
          '">' +
          '<td style="padding:3px 8px 3px 10px;word-break:break-word">' +
          esc(meter.name) +
          (tagText ? '<div style="color:' + MUTED + ';font-size:11px">' + esc(tagText) + '</div>' : '') +
          '</td>' +
          '<td style="padding:3px 10px 3px 8px;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums">' +
          esc(formatMeterValue(meter)) +
          '</td>' +
          '</tr>'
        );
      })
      .join('');
    return (
      '<div style="margin-top:14px;padding-top:8px;border-top:1px solid rgba(128,128,128,.3)">' +
      '<div style="color:' +
      MUTED +
      ';margin-bottom:2px">This view across all users</div>' +
      '<table style="border-collapse:collapse;width:100%"><tbody>' +
      rows +
      '</tbody></table></div>'
    );
  }

  // The route the panel summarises: the one this tab is on, which is the route
  // of its most recent interaction. Nothing else in the payload names it, and
  // the browser path is not a template.
  function currentRoute() {
    var interactions = (profile && profile.interactions) || [];
    for (var i = 0; i < interactions.length; i++) {
      if (typeof interactions[i].route === 'string') {
        return interactions[i].route;
      }
    }
    return null;
  }

  class ObservabilityProfilerPanel extends HTMLElement {
    connectedCallback() {
      this.style.display = 'block';
      this.style.height = '100%';
      this.style.overflow = 'auto';
      this._onClick = (event) => this.toggle(event);
      this.addEventListener('click', this._onClick);
      this.render();
      this.requestRefresh();
      this._timer = setInterval(() => this.requestRefresh(), PROFILE_INTERVAL_MS);
    }

    disconnectedCallback() {
      if (this._timer) {
        clearInterval(this._timer);
        this._timer = null;
      }
      if (this._onClick) {
        this.removeEventListener('click', this._onClick);
        this._onClick = null;
      }
    }

    // Rows are rewritten on every poll, so opening one is delegated from the
    // panel and remembered by interaction id rather than held in the DOM.
    toggle(event) {
      var row = event.target && event.target.closest ? event.target.closest('[data-interaction]') : null;
      if (!row) {
        return;
      }
      var id = row.getAttribute('data-interaction');
      if (expanded[id]) {
        delete expanded[id];
      } else {
        expanded[id] = true;
      }
      this.render();
    }

    requestRefresh() {
      if (!copilot) {
        return;
      }
      // Read live rather than captured at init: a resync replaces the UI under
      // the same page, and the prelude rewrites this global when it does.
      var uiId = window.__vaadinObservabilityUiId;
      if (typeof uiId !== 'number') {
        return;
      }
      copilot.send(COMMAND_PROFILE, { uiId: uiId });
      var route = currentRoute();
      if (route !== null) {
        copilot.send(COMMAND_ROUTE_SUMMARY, { route: route });
      }
    }

    requestLayoutUpdate() {
      return Promise.resolve();
    }

    // Called by Copilot for every server message; we claim the two the
    // profiler asked for.
    handleMessage(message) {
      if (!message) {
        return false;
      }
      if (message.command === COMMAND_PROFILE_DATA) {
        // A late answer for a UI this panel has moved off - a resync, or a
        // second tab - is not this tab's profile, so it is dropped rather than
        // rendered as one.
        if (message.data && message.data.uiId === window.__vaadinObservabilityUiId) {
          profile = message.data;
          this.render();
        }
        return true;
      }
      if (message.command === COMMAND_ROUTE_SUMMARY_DATA) {
        routeSummary = message.data;
        this.render();
        return true;
      }
      return false;
    }

    render() {
      var interactions = (profile && profile.interactions) || [];
      if (interactions.length === 0) {
        this.innerHTML =
          '<div style="padding:12px;font:13px sans-serif;color:' +
          MUTED +
          '">' +
          (profile
            ? 'Nothing recorded for this tab yet. Click something, or navigate, and it appears here.'
            : 'Waiting for the profile of this tab&hellip;') +
          '</div>';
        return;
      }

      var route = currentRoute();
      var header =
        '<div style="margin-bottom:8px">' +
        '<div style="font-weight:600;word-break:break-word">' +
        (route ? esc(route) : '<span style="color:' + MUTED + '">unknown route</span>') +
        '</div>' +
        '<div>' +
        uiStateLine(profile.uiState) +
        '</div>' +
        '<div style="color:' +
        MUTED +
        ';font-size:11px;margin-top:2px">' +
        count(interactions.length, 'interaction') +
        ' &middot; this tab only &middot; updated ' +
        clockTime(profile.timestamp) +
        '</div>' +
        '</div>';

      this.innerHTML =
        '<div style="padding:8px 2px;font:13px sans-serif">' +
        '<div style="padding:0 10px">' +
        header +
        '</div>' +
        '<table style="border-collapse:collapse;width:100%"><tbody>' +
        interactions.map(interactionRows).join('') +
        '</tbody></table>' +
        routeSummarySection(route) +
        '</div>';
    }
  }

  try {
    if (!customElements.get(PANEL_TAG)) {
      customElements.define(PANEL_TAG, ObservabilityMetricsPanel);
    }
    if (!customElements.get(PROFILER_TAG)) {
      customElements.define(PROFILER_TAG, ObservabilityProfilerPanel);
    }
  } catch (e) {
    // Defining failed (e.g. unsupported in this context): give up quietly.
    return;
  }

  var plugin = {
    init: function (copilotInterface) {
      copilot = copilotInterface;
      copilotInterface.addPanel({
        header: 'Observability',
        tag: PANEL_TAG,
        // Plain HTMLElements don't self-position the way Copilot's BasePanel
        // does, and the panel manager skips viewport adjustment when no
        // position is set - so it would open off-screen. Give it an explicit
        // on-screen position and size.
        position: {
          top: 80,
          left: 80,
          width: 720,
          height: 460
        },
        toolbarOptions: {
          iconKey: 'barChart',
          // The toolbar only renders an icon for panels mapped to an active
          // mode; 'common' alone gives no entry point. 'play' hides the panel
          // container, so expose the icon in the remaining modes.
          allowedModesWithOrder: {
            edit: 100,
            inspect: 100,
            test: 100
          }
        }
      });
      copilotInterface.addPanel({
        header: 'View profiler',
        tag: PROFILER_TAG,
        // Positioned explicitly for the same reason the metrics panel is, and
        // offset from it so opening both does not stack one exactly over the
        // other. Cascaded rather than placed beside it: a fixed left of a
        // panel width away would open off-screen on a narrow display, which is
        // the failure the explicit position exists to avoid.
        position: {
          top: 120,
          left: 120,
          width: 640,
          height: 460
        },
        toolbarOptions: {
          // A speedometer: this panel is about what things cost.
          iconKey: 'speed',
          allowedModesWithOrder: {
            edit: 101,
            inspect: 101,
            test: 101
          }
        }
      });
    }
  };

  // Copilot resets window.Vaadin.copilot.plugins to [] once during bootstrap,
  // so pushing eagerly races that reset and gets wiped. Wait until Copilot has
  // bootstrapped (_uiState is created in the same synchronous block right after
  // the reset) and only then push. At that point either initializePlugins() has
  // already overridden push (so our push inits immediately) or our entry sits in
  // the array until it runs - both register the panel.
  var attempts = 0;
  var maxAttempts = 600; // ~60s at 100ms
  var timer = setInterval(function () {
    attempts++;
    var cp = window.Vaadin && window.Vaadin.copilot;
    if (cp && cp._uiState && Array.isArray(cp.plugins)) {
      clearInterval(timer);
      cp.plugins.push(plugin);
    } else if (attempts >= maxAttempts) {
      clearInterval(timer);
    }
  }, 100);
})();
