const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness } = require("./frontend-harness.cjs");

function stations(h, items) {
  h.evaluate(`state.stations = ${JSON.stringify(items)}; renderStations()`);
}

function stationRows(h) {
  return h.el("#station-list").children.filter(row => row.classList.contains("station-row"));
}

function stationNames(h) {
  return stationRows(h).map(row => row.querySelector(".station-play").getAttribute("aria-label").replace(/^Listen to /, ""));
}

function wireTabs(h) {
  const names = ["radio", "browse", "alarms", "settings"];
  h.queries.set(".tab", names.map(name => {
    const tab = h.el(`#tab-${name}`);
    tab.dataset.pane = name;
    tab.classList.toggle("on", name === "radio");
    tab.setAttribute("aria-selected", String(name === "radio"));
    return tab;
  }));
  h.queries.set(".pane", names.map(name => {
    const pane = h.el(`#pane-${name}`);
    pane.id = `pane-${name}`;
    pane.classList.toggle("on", name === "radio");
    return pane;
  }));
}

function wire(h) {
  wireTabs(h);
  h.evaluate("wire()");
}

test("station search and favourites combine, name sorting is natural, and saved order stays intact", async () => {
  const h = createHarness();
  stations(h, [
    { id: "z", name: "Zulu", url: "https://example.test/z", tag: "ambient", favorite: true },
    { id: "ten", name: "Jazz 10", url: "https://example.test/10", tag: "", favorite: true },
    { id: "two", name: "Jazz 2", url: "https://example.test/2", tag: "", favorite: true },
    { id: "tag-only", name: "Beta", url: "https://example.test/b", tag: "JAZZ", favorite: false },
    { id: "a", name: "alpha", url: "https://example.test/a", tag: "classical", favorite: true },
  ]);
  const savedOrder = h.evaluate("state.stations.map(station => station.id)");
  wire(h);

  h.el("#station-sort").value = "name";
  await h.el("#station-sort").dispatch("change");
  h.el("#station-filter").value = "JAZZ";
  await h.el("#station-filter").dispatch("input");
  await h.el("#station-favorites").dispatch("click");

  assert.deepEqual(stationNames(h), ["Jazz 2", "Jazz 10"]);
  assert.equal(h.evaluate("JSON.stringify(visibleStations.map(station => station.id))"), '["two","ten"]');
  assert.deepEqual(h.evaluate("state.stations.map(station => station.id)"), savedOrder);
  assert.match(h.el("#station-count").textContent, /2/);
  assert.equal(h.el("#station-reset").hidden, false);
  assert.equal(h.el("#station-all").getAttribute("aria-pressed"), "false");
  assert.equal(h.el("#station-favorites").getAttribute("aria-pressed"), "true");

  await h.el("#station-all").dispatch("click");
  assert.equal(h.evaluate("stationFavoritesOnly"), false);
  assert.equal(h.el("#station-all").getAttribute("aria-pressed"), "true");
  assert.equal(h.el("#station-favorites").getAttribute("aria-pressed"), "false");
});

test("station search matches tags without regard to case", async () => {
  const h = createHarness();
  stations(h, [
    { id: "tag", name: "Night Stream", url: "https://example.test/night", tag: "AMBIENT", favorite: false },
    { id: "other", name: "Morning News", url: "https://example.test/news", tag: "news", favorite: false },
  ]);
  wire(h);

  h.el("#station-filter").value = "ambient";
  await h.el("#station-filter").dispatch("input");

  assert.deepEqual(stationNames(h), ["Night Stream"]);
  assert.equal(h.evaluate("JSON.stringify(visibleStations.map(station => station.id))"), '["tag"]');
});

test("empty results offer reset, which clears search and favourites while keeping the sort", async () => {
  const h = createHarness();
  stations(h, [
    { id: "one", name: "Only station", url: "https://example.test/one", tag: "", favorite: false },
  ]);
  wire(h);

  h.el("#station-sort").value = "name";
  await h.el("#station-sort").dispatch("change");
  await h.el("#station-favorites").dispatch("click");
  h.el("#station-filter").value = "no match";
  await h.el("#station-filter").dispatch("input");

  assert.equal(stationRows(h).length, 0);
  assert.match(h.el("#station-count").textContent, /0/);
  assert.equal(h.el("#station-reset").hidden, false);
  const action = h.el("#station-list").querySelector(".station-empty-action");
  assert.equal(action.tagName, "BUTTON");
  assert.equal(action.textContent, "Clear filters");
  await action.dispatch("click");

  assert.equal(h.el("#station-filter").value, "");
  assert.equal(h.evaluate("stationFavoritesOnly"), false);
  assert.equal(h.el("#station-sort").value, "name");
  assert.equal(h.el("#station-reset").hidden, true);
  assert.deepEqual(stationNames(h), ["Only station"]);
});

test("an empty favourites view offers a direct way back to all stations", async () => {
  const h = createHarness();
  stations(h, [
    { id: "one", name: "Only station", url: "https://example.test/one", tag: "", favorite: false },
  ]);
  wire(h);

  await h.el("#station-favorites").dispatch("click");
  const action = h.el("#station-list").querySelector(".station-empty-action");
  assert.equal(action.textContent, "Show all stations");
  await action.dispatch("click");

  assert.equal(h.evaluate("stationFavoritesOnly"), false);
  assert.equal(h.el("#station-all").getAttribute("aria-pressed"), "true");
  assert.deepEqual(stationNames(h), ["Only station"]);
});

test("an empty station library links to Browse", async () => {
  const h = createHarness();
  stations(h, []);
  wire(h);
  // Element.click() is not implemented by the lightweight harness. Model its
  // browser behavior by dispatching the already-wired click handlers.
  h.el("#tab-browse").click = () => { void h.el("#tab-browse").dispatch("click"); };

  const action = h.el("#station-list").querySelector(".station-empty-action");
  assert.equal(action.tagName, "BUTTON");
  assert.equal(action.textContent, "Discover stations");
  await action.dispatch("click");

  assert.equal(h.el("#tab-browse").getAttribute("aria-selected"), "true");
  assert.equal(h.el("#pane-browse").classList.contains("on"), true);
});

test("favouriting stays separate from playback and restores focus to the redrawn star", async () => {
  const h = createHarness();
  stations(h, [
    { id: "one", name: "First station", url: "https://example.test/one", tag: "", favorite: false },
    { id: "two", name: "Second station", url: "https://example.test/two", tag: "", favorite: false },
  ]);
  wire(h);

  const originalStar = stationRows(h)[0].querySelector(".star");
  originalStar.focus();
  await originalStar.dispatch("click");

  const replacementStar = stationRows(h)[0].querySelector(".star");
  assert.equal(h.evaluate("state.stations[0].favorite"), true);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(replacementStar.getAttribute("aria-pressed"), "true");
  assert.notEqual(replacementStar, originalStar);
  assert.equal(h.document.activeElement, replacementStar);
});

test("removing the last visible favourite returns focus to the Favourites control", async () => {
  const h = createHarness();
  stations(h, [
    { id: "one", name: "First station", url: "https://example.test/one", tag: "", favorite: true },
  ]);
  wire(h);
  await h.el("#station-favorites").dispatch("click");
  const star = stationRows(h)[0].querySelector(".star");
  star.focus();
  await star.dispatch("click");

  assert.equal(h.evaluate("state.stations[0].favorite"), false);
  assert.equal(stationRows(h).length, 0);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.document.activeElement, h.el("#station-favorites"));
});

test("station editor names its mode and returns focus to Edit or Add after cancel", async () => {
  const h = createHarness();
  stations(h, [
    { id: "one", name: "Station", url: "https://example.test/one", tag: "", favorite: false },
  ]);
  wire(h);
  const edit = stationRows(h)[0].querySelector(".station-edit");
  edit.isConnected = true;
  edit.focus();
  await edit.dispatch("click");

  assert.equal(h.el("#pane-radio").classList.contains("editing"), true);
  assert.equal(h.el("#station-editor-title").textContent, "Edit station");
  assert.equal(h.document.activeElement, h.el("#st-name"));
  await h.el("#st-cancel").dispatch("click");
  assert.equal(h.el("#pane-radio").classList.contains("editing"), false);
  assert.equal(h.document.activeElement, edit);

  edit.focus();
  await edit.dispatch("click");
  h.evaluate("state.stations = []; renderStations()");
  edit.isConnected = false;
  await h.el("#st-cancel").dispatch("click");
  assert.equal(h.document.activeElement, h.el("#btn-add-station"));

  h.el("#btn-add-station").focus();
  await h.el("#btn-add-station").dispatch("click");
  assert.equal(h.el("#pane-radio").classList.contains("editing"), true);
  assert.equal(h.el("#station-editor-title").textContent, "Add station");
  await h.el("#st-cancel").dispatch("click");
  assert.equal(h.el("#pane-radio").classList.contains("editing"), false);
  assert.equal(h.document.activeElement, h.el("#btn-add-station"));
});

test("direct play and stop update the selected station in place and preserve focus", async () => {
  const h = createHarness();
  stations(h, [
    { id: "one", name: "Station", url: "https://example.test/one", tag: "", favorite: false },
  ]);
  const row = stationRows(h)[0];
  const playButton = row.querySelector(".station-play");
  playButton.focus();
  h.evaluate(`directStationSource = {
    kind: "station", stationId: "one", url: "https://example.test/one",
    title: "Station", subtitle: ""
  }`);

  await h.evaluate("play(directStationSource)");
  assert.equal(row.classList.contains("on"), true);
  assert.equal(playButton.getAttribute("aria-current"), "true");
  assert.equal(playButton.getAttribute("aria-pressed"), null);
  assert.equal(h.el("#station-list").children[0], row);
  assert.equal(h.document.activeElement, playButton);

  h.evaluate("stopPlayback(true)");
  assert.equal(row.classList.contains("on"), false);
  assert.equal(playButton.getAttribute("aria-current"), null);
  assert.equal(h.el("#station-list").children[0], row);
  assert.equal(h.document.activeElement, playButton);

  await h.evaluate("play(directStationSource)");
  assert.equal(row.classList.contains("on"), true);
  assert.equal(playButton.getAttribute("aria-current"), "true");
  assert.equal(h.el("#station-list").children[0], row);
  assert.equal(h.document.activeElement, playButton);
});
