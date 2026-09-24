const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, flush } = require("./frontend-harness.cjs");

const names = ["radio", "browse", "alarms", "settings"];

function tabHarness() {
  const h = createHarness({ invoke: (command) => {
    if (command === "get_state") return { stations: [], alarms: [], settings: {} };
    if (command === "next_alarm") return null;
    if (command === "power_status") return {
      sleepSupported: true, shutdownSupported: true, wakeSupported: true,
      wakeAllowed: true, onBattery: false, message: "Wake timers allowed.", armedAtMs: null, error: null,
    };
    if (command === "browse_countries" || command === "browse_tags") return [];
    if (command === "browse_facets") return { tags: [], countries: [], codecs: [], bitrates: [], sampled: false };
    if (command === "browse_stations") return { stations: [], offered: 0, hasMore: false };
  } });
  h.queries.set(".tab", names.map((name, index) => {
    const tab = h.el(`#tab-${name}`);
    tab.dataset.pane = name;
    tab.classList.toggle("on", index === 0);
    tab.tabIndex = index === 0 ? 0 : -1;
    tab.setAttribute("aria-selected", String(index === 0));
    return tab;
  }));
  h.queries.set(".pane", names.map((name, index) => {
    const pane = h.el(`#pane-${name}`);
    pane.id = `pane-${name}`;
    pane.classList.toggle("on", index === 0);
    return pane;
  }));
  h.evaluate("wire()");
  return h;
}

function assertActive(h, name) {
  const active = h.el(`#tab-${name}`);
  assert.equal(h.document.activeElement, active);
  assert.equal(active.classList.contains("on"), true);
  assert.equal(active.getAttribute("aria-selected"), "true");
  assert.equal(active.tabIndex, 0);
  assert.equal(h.el(`#pane-${name}`).classList.contains("on"), true);
  for (const other of names.filter((item) => item !== name)) {
    assert.equal(h.el(`#tab-${other}`).classList.contains("on"), false);
    assert.equal(h.el(`#tab-${other}`).getAttribute("aria-selected"), "false");
    assert.equal(h.el(`#tab-${other}`).tabIndex, -1);
    assert.equal(h.el(`#pane-${other}`).classList.contains("on"), false);
  }
}

test("ArrowLeft and ArrowRight move tabs and wrap at both ends", async () => {
  const h = tabHarness();
  h.el("#tab-radio").focus();

  const first = await h.el("#tab-radio").dispatch("keydown", { key: "ArrowRight" });
  assert.equal(first.defaultPrevented, true);
  assert.equal(first.stopped, true);
  assertActive(h, "browse");
  await flush();

  await h.el("#tab-browse").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "alarms");
  await flush();

  await h.el("#tab-alarms").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "settings");

  await h.el("#tab-settings").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "radio");
  await h.el("#tab-radio").dispatch("keydown", { key: "ArrowLeft" });
  assertActive(h, "settings");
});

test("Home and End select the first and last tab; modified or vertical arrows do nothing", async () => {
  const h = tabHarness();
  h.el("#tab-alarms").focus();
  const home = await h.el("#tab-alarms").dispatch("keydown", { key: "Home" });
  assert.equal(home.defaultPrevented, true);
  assertActive(h, "radio");

  const end = await h.el("#tab-radio").dispatch("keydown", { key: "End" });
  assert.equal(end.defaultPrevented, true);
  assertActive(h, "settings");

  const modified = await h.el("#tab-settings").dispatch("keydown", { key: "ArrowRight", ctrlKey: true });
  assert.equal(modified.defaultPrevented, false);
  assert.equal(h.document.activeElement, h.el("#tab-settings"));
  assert.equal(h.el("#tab-settings").getAttribute("aria-selected"), "true");

  const vertical = await h.el("#tab-settings").dispatch("keydown", { key: "ArrowDown" });
  assert.equal(vertical.defaultPrevented, false);
  assertActive(h, "settings");
});
