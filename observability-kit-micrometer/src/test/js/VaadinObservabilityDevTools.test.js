// Copyright 2000-2026 Vaadin Ltd.
// Licensed under the Vaadin Commercial License and Service Terms.
//
// Runs the real Copilot panels against a stubbed browser. Covers what the
// server-side handler tests cannot reach: what the profiler makes of a profile
// payload once it has one -- which figures it leads with, which tab it asks
// about, and what it does with application text that fights being put in an
// innerHTML.
//
//   node observability-kit-micrometer/src/test/js/VaadinObservabilityDevTools.test.js
//
// No dependencies and no runner, for the same reason as the collector's suite
// next to it: the module has no JavaScript build. Exits non-zero on failure,
// and `mvn test` runs it that way. Skipped with -Dskip.js.tests when node is
// not on PATH.
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(
  path.join(__dirname, '../../main/resources/META-INF/frontend/VaadinObservabilityDevTools.js'),
  'utf8'
);

// The panels and their module state live inside the IIFE. Reach them by
// evaluating the body with a probe appended -- the probe is part of this file,
// not of the shipped script, so nothing test-only is exported to the browser.
function load() {
  const open = src.indexOf('(function () {');
  const close = src.lastIndexOf('})();');
  const body = src.slice(open + '(function () {'.length, close);

  class HTMLElementStub {
    constructor() {
      this.style = {};
      this.innerHTML = '';
      this.listeners = {};
    }
    addEventListener(name, cb) {
      (this.listeners[name] = this.listeners[name] || []).push(cb);
    }
    removeEventListener(name, cb) {
      const list = this.listeners[name] || [];
      const at = list.indexOf(cb);
      if (at >= 0) list.splice(at, 1);
    }
  }

  const defined = {};
  const win = {};
  const probe =
    '\n; return {' +
    '  esc: esc, queryTotals: queryTotals, queryHeadline: queryHeadline,' +
    '  uiStateLine: uiStateLine, interactionRows: interactionRows,' +
    '  currentRoute: currentRoute, routeSummarySection: routeSummarySection,' +
    '  Profiler: ObservabilityProfilerPanel,' +
    '  setProfile: function (v) { profile = v; },' +
    '  setRouteSummary: function (v) { routeSummary = v; },' +
    '  setCopilot: function (v) { copilot = v; },' +
    '  expanded: expanded,' +
    '  defined: arguments[5]' +
    '};';

  const internals = new Function(
    'window',
    'customElements',
    'HTMLElement',
    'setInterval',
    'clearInterval',
    'defined',
    body + probe
  )(
    win,
    { get: (tag) => defined[tag], define: (tag, cls) => { defined[tag] = cls; } },
    HTMLElementStub,
    () => 0,
    () => undefined,
    defined
  );
  internals.window = win;
  return internals;
}

const kit = load();

let failures = 0;
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : `\n        got ${JSON.stringify(actual)} want ${JSON.stringify(expected)}`}`);
}
function checkTrue(label, actual) {
  check(label, actual === true, true);
}

// A JDBC query as the server sends one.
const query = (statement, rows, durationMs) => ({
  kind: 'jdbc',
  sql: statement,
  rows: rows,
  durationMs: durationMs,
  startOffsetMs: 0
});

// The N+1 the profiler exists to make visible: one listing, then one lookup per
// row, which the server has already grouped into two statements.
function nPlusOne() {
  const queries = [query('select * from orders', 100, 12)];
  for (let id = 1; id <= 100; id++) {
    queries.push(query('select * from customer where id=' + id, 1, 6));
  }
  return {
    id: 42,
    timestamp: 1767225599880,
    route: 'orders/:orderId',
    location: 'orders/17',
    component: 'com.example.OrdersGrid',
    event: 'click',
    rpcType: 'event',
    outcome: 'success',
    durationMs: 712,
    exceptionType: null,
    exceptionMessage: null,
    applicationFrame: 'com.example.OrdersGrid.onClick(OrdersGrid.java:42)',
    queries: queries,
    queryGroups: [
      { statement: 'select * from customer where id=?', kind: 'jdbc', count: 100, durationMs: 600 },
      { statement: 'select * from orders', kind: 'jdbc', count: 1, durationMs: 12 }
    ]
  };
}

console.log('\n-- the figures the panel leads with --');

const totals = kit.queryTotals(nPlusOne());
check('every query is counted, not every statement', totals.queries, 101);
check('the statements behind them are the groups', totals.statements, 2);
check('the largest group is the repetition to report', totals.worst, 100);
check('time in queries is summed over the queries', totals.durationMs, 612);
check('so are the rows they fetched', totals.rows, 200);
check('an interaction that ran none has no totals at all', kit.queryTotals({ queries: [] }), null);

const headline = kit.queryHeadline(totals);
checkTrue('the headline counts the queries', headline.indexOf('101 queries') >= 0);
checkTrue('and the time they took', headline.indexOf('612 ms') >= 0);
checkTrue('and names the repetition behind them', headline.indexOf('1 statement &times; 100') >= 0);

const single = kit.queryTotals({
  queries: [query('select * from orders', 100, 12)],
  queryGroups: [{ statement: 'select * from orders', kind: 'jdbc', count: 1, durationMs: 12 }]
});
check('one query reads as one, not "1 queries"', kit.queryHeadline(single).indexOf('1 query in') >= 0, true);
checkTrue(
  'nothing is reported as repeated when nothing is',
  kit.queryHeadline(single).indexOf('&times;') < 0
);
checkTrue('and no queries says so', kit.queryHeadline(null).indexOf('no queries') >= 0);

console.log('\n-- application text goes in as text --');

check('a less-than cannot open a tag', kit.esc('a < b'), 'a &lt; b');
check('an ampersand is not an entity', kit.esc('a & b'), 'a &amp; b');
check('a quote cannot close an attribute', kit.esc('say "hi"'), 'say &quot;hi&quot;');
check('null is nothing rather than "null"', kit.esc(null), '');

kit.expanded[7] = true;
const hostile = kit.interactionRows({
  id: 7,
  timestamp: 1767225599880,
  route: 'orders',
  outcome: 'error',
  event: 'click',
  component: 'com.example.<img src=x>',
  durationMs: 10,
  exceptionType: 'java.lang.IllegalStateException',
  exceptionMessage: 'x < y && y > z',
  applicationFrame: null,
  queries: [query("select * from t where a < 5 and b = 'it''s'", 1, 2)],
  queryGroups: [
    { statement: "select * from t where a < ? and b = ?", kind: 'jdbc', count: 1, durationMs: 2 }
  ]
});
checkTrue('a component name cannot smuggle a tag', hostile.indexOf('<img src=x>') < 0);
checkTrue('the escaped form is what is rendered', hostile.indexOf('&lt;img src=x&gt;') >= 0);
checkTrue('nor can an exception message', hostile.indexOf('x &lt; y &amp;&amp; y &gt; z') >= 0);
checkTrue('nor a statement', hostile.indexOf('a &lt; ?') >= 0);
delete kit.expanded[7];

console.log('\n-- opening a row --');

const row = nPlusOne();
const collapsed = kit.interactionRows(row);
checkTrue('a collapsed row shows no frame', collapsed.indexOf('OrdersGrid.java:42') < 0);
checkTrue('but does show what it cost', collapsed.indexOf('712 ms') >= 0);
kit.expanded[42] = true;
const opened = kit.interactionRows(row);
checkTrue('an opened row shows the code frame it ran through', opened.indexOf('OrdersGrid.java:42') >= 0);
checkTrue('and the statements, most repeated first', opened.indexOf('customer where id=?') >= 0);
checkTrue('and how many rows came back', opened.indexOf('200 rows fetched') >= 0);
checkTrue('and how many statements there were', opened.indexOf('2 different statements') >= 0);
delete kit.expanded[42];

console.log('\n-- which tab, and which route --');

kit.setProfile({
  uiId: 3,
  timestamp: 1767225600000,
  uiState: null,
  // A background interaction with no route sits in front of a routed one; the
  // route of the tab is the newest one that has it.
  interactions: [{ id: 9, route: null, queries: [] }, { id: 8, route: 'orders/:orderId', queries: [] }]
});
check('the route is the newest interaction that names one', kit.currentRoute(), 'orders/:orderId');
kit.setProfile({ uiId: 3, interactions: [] });
check('with nothing recorded there is no route to summarise', kit.currentRoute(), null);

const sent = [];
kit.setCopilot({ send: (command, data) => sent.push([command, data]) });
const panel = new kit.Profiler();

kit.window.__vaadinObservabilityUiId = 3;
panel.requestRefresh();
check('the profile is asked for the tab the server named', sent, [['observability-kit-profile', { uiId: 3 }]]);

// A resync replaces the UI under the same page and the prelude rewrites the
// global; the panel must follow it rather than keep asking about a tab that is
// gone.
sent.length = 0;
kit.window.__vaadinObservabilityUiId = 7;
panel.requestRefresh();
check('and follows the id when a resync changes it', sent, [['observability-kit-profile', { uiId: 7 }]]);

sent.length = 0;
delete kit.window.__vaadinObservabilityUiId;
panel.requestRefresh();
check('with no id from the server nothing is asked at all', sent, []);

kit.window.__vaadinObservabilityUiId = 7;
kit.setProfile({ uiId: 7, interactions: [{ id: 1, route: 'orders', queries: [] }] });
sent.length = 0;
panel.requestRefresh();
check(
  'the route summary is asked for the route the tab is on',
  sent,
  [
    ['observability-kit-profile', { uiId: 7 }],
    ['observability-kit-route-summary', { route: 'orders' }]
  ]
);

console.log('\n-- answers meant for another tab --');

kit.window.__vaadinObservabilityUiId = 7;
kit.setProfile({ uiId: 7, interactions: [{ id: 1, route: 'orders', event: 'click', durationMs: 5, queries: [] }] });
const claimed = panel.handleMessage({
  command: 'observability-kit-profile-data',
  data: { uiId: 3, interactions: [{ id: 99, route: 'elsewhere', event: 'click', durationMs: 5, queries: [] }] }
});
checkTrue('a profile for another tab is still claimed, not left to others', claimed);
check('but it is not rendered as this tab', kit.currentRoute(), 'orders');
checkTrue(
  'a command the panel does not own is left alone',
  panel.handleMessage({ command: 'observability-kit-metrics', data: {} }) === false
);

console.log('\n-- what the tab is holding --');

checkTrue(
  'a tab not measured yet says so rather than showing zeroes',
  kit.uiStateLine(null).indexOf('not measured yet') >= 0
);
const state = kit.uiStateLine({ nodes: 812, components: 210, views: 2, staleViews: 0, sampleAgeMs: 1400 });
checkTrue('the figures are the sample', state.indexOf('812 nodes') >= 0);
checkTrue('one view is not "1 views"', kit.uiStateLine({ nodes: 1, components: 1, views: 1, staleViews: 0, sampleAgeMs: 10 }).indexOf('1 view ') >= 0);
checkTrue('how stale it is shows with it', state.indexOf('1.4 s ago') >= 0);
checkTrue('a fresh sample reads in milliseconds', kit.uiStateLine({ nodes: 1, components: 1, views: 1, staleViews: 0, sampleAgeMs: 620 }).indexOf('620 ms ago') >= 0);
checkTrue('no stale views is not worth a warning', state.indexOf('stale') < 0);
checkTrue(
  'a stale view is',
  kit.uiStateLine({ nodes: 1, components: 1, views: 2, staleViews: 1, sampleAgeMs: 10 }).indexOf('1 stale view') >= 0
);

console.log('\n-- the route summary --');

kit.setRouteSummary({
  route: 'orders',
  meters: [{ name: 'vaadin.navigation', type: 'TIMER', tags: { route: 'orders' }, mean: 88, count: 3 }]
});
checkTrue('the meters of this view are shown', kit.routeSummarySection('orders').indexOf('vaadin.navigation') >= 0);
check(
  'a summary for a route the tab has left is not shown against this one',
  kit.routeSummarySection('customers'),
  ''
);
kit.setRouteSummary({ route: 'orders', meters: [] });
check('a view nobody has visited adds no section', kit.routeSummarySection('orders'), '');

console.log('\n-- both panels are registered --');

checkTrue('the metrics panel is defined', !!kit.defined['observability-kit-metrics-panel']);
checkTrue('and the profiler is defined beside it', !!kit.defined['observability-kit-profiler-panel']);

console.log(`\n${failures === 0 ? 'ALL PASSED' : failures + ' FAILURE(S)'}\n`);
process.exit(failures === 0 ? 0 : 1);
