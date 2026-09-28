const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, flush } = require("./frontend-harness.cjs");

const desktopNames = ["radio", "browse", "alarms", "settings"];
const androidNames = ["player", "radio", "browse", "alarms", "settings"];
const paneNames = ["player", "radio", "browse", "alarms", "settings"];

function tabHarness(android = false) {
  const h = createHarness({ navigator: { userAgent: android ? "Android 13" : "Windows NT 10.0" }, invoke: (command) => {
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
  h.android = android;
  const names = android ? androidNames : desktopNames;
  h.queries.set(".tab", names.map((name, index) => {
    const tab = h.el(`#tab-${name}`);
    tab.dataset.pane = name;
    tab.classList.toggle("on", index === 0);
    tab.tabIndex = index === 0 ? 0 : -1;
    tab.setAttribute("aria-selected", String(index === 0));
    return tab;
  }));
  h.queries.set(".pane", paneNames.map((name) => {
    const pane = h.el(`#pane-${name}`);
    pane.id = `pane-${name}`;
    pane.classList.toggle("on", name === (android ? "player" : "radio"));
    return pane;
  }));
  const categories = Array.from({ length: 4 }, () => {
    const category = h.document.createElement("details");
    category.open = true;
    return category;
  });
  h.queries.set(".settings-category", categories);
  h.evaluate("wire()");
  h.settingsCategories = categories;
  return h;
}

function assertActive(h, name, { focus = true } = {}) {
  const names = h.android ? androidNames : desktopNames;
  const pane = name;
  const active = h.el(`#tab-${name}`);
  if (focus) assert.equal(h.document.activeElement, active);
  assert.equal(active.classList.contains("on"), true);
  assert.equal(active.getAttribute("aria-selected"), "true");
  assert.equal(active.tabIndex, 0);
  assert.equal(h.el(`#pane-${pane}`).classList.contains("on"), true);
  for (const other of names.filter((item) => item !== name)) {
    assert.equal(h.el(`#tab-${other}`).classList.contains("on"), false);
    assert.equal(h.el(`#tab-${other}`).getAttribute("aria-selected"), "false");
    assert.equal(h.el(`#tab-${other}`).tabIndex, -1);
  }
  for (const other of paneNames.filter((item) => item !== pane)) {
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

test("Android starts in Player and exposes Stations and Browse directly", async () => {
  const h = tabHarness(true);
  assert.deepEqual(h.queries.get(".tab").map((tab) => tab.dataset.pane), androidNames);
  assert.equal(h.el("#player-stage").parentElement, h.el("#pane-player"));
  assert.equal(h.el("#station-music").parentElement, h.el("#pane-player"));
  assert.equal(h.el("#android-quick-access").parentElement, h.el("#pane-player"));
  assert.equal(h.el("#player-sleep-timer").parentElement, h.el("#pane-player"));
  assert.deepEqual(h.el("#pane-player").children, [
    h.el("#android-quick-access"), h.el("#player-stage"),
    h.el("#station-music"), h.el("#player-sleep-timer"),
  ]);
  assert.equal(h.el("#tab-radio").getAttribute("aria-selected"), "false");
  assertActive(h, "player", { focus: false });

  await h.el("#tab-radio").dispatch("click");
  assertActive(h, "radio");
  await h.el("#tab-browse").dispatch("click");
  assertActive(h, "browse");
  await h.el("#tab-player").dispatch("click");
  assertActive(h, "player");
  await h.el("#tab-radio").dispatch("click");
  assertActive(h, "radio");
});

test("Android bottom arrows traverse all five destinations", async () => {
  const h = tabHarness(true);
  h.el("#tab-player").focus();
  await h.el("#tab-player").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "radio");
  await h.el("#tab-radio").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "browse");
  await h.el("#tab-browse").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "alarms");
  await h.el("#tab-alarms").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "settings");
  await h.el("#tab-settings").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "player");
});

test("desktop skips Android-only tabs and leaves Settings categories expanded", async () => {
  const h = tabHarness();
  assert.deepEqual(h.queries.get(".tab").map((tab) => tab.dataset.pane), desktopNames);
  assert.equal(h.el("#tab-player").classList.contains("tab"), false);
  assert.notEqual(h.el("#player-stage").parentElement, h.el("#pane-player"));
  assert.notEqual(h.el("#station-music").parentElement, h.el("#pane-player"));
  assert.notEqual(h.el("#player-sleep-timer").parentElement, h.el("#pane-player"));
  assert.equal(h.settingsCategories.every((category) => category.open), true);
  h.el("#tab-settings").focus();
  await h.el("#tab-settings").dispatch("keydown", { key: "ArrowRight" });
  assertActive(h, "radio");
});

test("Android Settings categories start closed and remain open after rendering", () => {
  const h = tabHarness(true);
  assert.equal(h.settingsCategories.every((category) => !category.open), true);
  h.settingsCategories[0].open = true;
  h.evaluate("renderSettings()");
  assert.equal(h.settingsCategories[0].open, true);
  assert.equal(h.settingsCategories.slice(1).every((category) => !category.open), true);
});
