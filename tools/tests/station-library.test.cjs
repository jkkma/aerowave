const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const androidNavigator = { userAgent: "Mozilla/5.0 (Linux; Android 15; Mobile)" };

function station(id, name, overrides = {}) {
  return {
    id,
    name,
    url: `https://radio.test/${id}`,
    tag: "",
    favorite: false,
    ...overrides,
  };
}

function libraryHarness({ stations = [], recentStations = [], navigator, invoke } = {}) {
  const h = createHarness({ navigator, invoke });
  h.context.fixture = {
    stations,
    alarms: [],
    settings: { volume: 0.8, recentStations },
  };
  h.evaluate("state=fixture; renderStations(); wire()");
  return h;
}

function rows(h) {
  return h.el("#station-list").children.filter((row) => row.classList.contains("station-row"));
}

function rowIds(h) {
  return rows(h).map((row) => row.dataset.id);
}

function rowNames(h) {
  return rows(h).map((row) => row.querySelector(".station-play").getAttribute("aria-label").replace(/^Listen to /, ""));
}

function jsonClone(value) {
  return JSON.parse(JSON.stringify(value));
}

test("loadedmetadata and non-ready timeupdates do not count as listening; advancing ready audio does", async () => {
  const saved = station("saved", "Saved station");
  const h = libraryHarness({ stations: [saved] });
  h.context.source = { kind: "station", stationId: saved.id, url: saved.url, title: saved.name };
  h.evaluate("player.source=source; player.paused=false; lastMediaTime=0");
  const audio = h.audios[0];
  audio.paused = false;

  audio.currentTime = 1;
  await audio.dispatch("loadedmetadata");
  assert.equal(h.evaluate("recentStations().length"), 0);

  audio.readyState = 2;
  audio.currentTime = 2;
  await audio.dispatch("timeupdate");
  audio.readyState = 3;
  audio.seeking = true;
  audio.currentTime = 3;
  await audio.dispatch("timeupdate");
  audio.seeking = false;
  audio.paused = true;
  audio.currentTime = 4;
  await audio.dispatch("timeupdate");
  assert.equal(h.evaluate("recentStations().length"), 0);

  audio.paused = false;
  audio.currentTime = 5;
  await audio.dispatch("timeupdate");
  assert.equal(h.evaluate("recentStations().length"), 1);
  assert.equal(h.evaluate("recentStations()[0].id"), saved.id);
  assert.equal(h.evaluate("recentStations()[0].name"), saved.name);
  assert.equal(h.calls.some((call) => call.command === "save_stations"), false);
});

test("folder playback and a ringing alarm do not enter station history", async () => {
  const h = libraryHarness();
  const audio = h.audios[0];
  audio.readyState = 3;
  audio.paused = false;
  h.evaluate('player.source={kind:"folder",url:"asset:///music/track.mp3",path:"/music/track.mp3"}; lastMediaTime=0');
  audio.currentTime = 1;
  await audio.dispatch("timeupdate");

  h.evaluate('player.source={kind:"station",url:"https://radio.test/live",title:"Live station"}; ringing={trigger:"scheduled"}');
  audio.currentTime = 2;
  await audio.dispatch("timeupdate");
  assert.equal(h.evaluate("recentStations().length"), 0);
});

test("Android records a station only after native playback position advances", () => {
  const saved = station("android", "Android station");
  const h = libraryHarness({ navigator: androidNavigator, stations: [saved] });
  h.context.source = { kind: "station", stationId: saved.id, url: saved.url, title: saved.name };
  h.evaluate(`player.source=source; player.nativeGeneration=41;
    applyAndroidPlaybackState({status:"buffering",generation:41,positionMs:0});`);
  assert.equal(h.evaluate("recentStations().length"), 0);

  h.evaluate('applyAndroidPlaybackState({status:"playing",generation:41,positionMs:1000})');
  assert.equal(h.evaluate("recentStations().length"), 0);
  h.evaluate('applyAndroidPlaybackState({status:"playing",generation:41,positionMs:1500})');
  assert.equal(h.evaluate("recentStations().length"), 1);
  assert.equal(h.evaluate("recentStations()[0].id"), saved.id);
});

test("recent history is a deduplicated most-recent-first list capped at twenty", () => {
  const all = Array.from({ length: 22 }, (_, index) => station(`s${index}`, `Station ${index}`));
  const h = libraryHarness({ stations: all });
  for (let index = 0; index < all.length; index++) {
    h.context.source = { kind: "station", stationId: all[index].id, url: all[index].url, title: all[index].name };
    h.evaluate("player.source=source; rememberPlayedStation()");
  }
  let history = jsonClone(h.evaluate("recentStations()"));
  assert.equal(history.length, 20);
  assert.equal(history[0].id, "s21");
  assert.equal(history.at(-1).id, "s2");

  h.context.source = { kind: "station", stationId: "s5", url: all[5].url, title: all[5].name };
  h.evaluate("player.source=source; rememberPlayedStation()");
  history = jsonClone(h.evaluate("recentStations()"));
  assert.equal(history.length, 20);
  assert.equal(history[0].id, "s5");
  assert.equal(new Set(history.map((item) => item.url)).size, 20);
});

test("Recent stays newest-first while name sorting and search filter its results", async () => {
  const news = station("news", "Daily News", { tag: "news" });
  const zulu = station("zulu", "Zulu Jazz", { tag: "jazz" });
  const alpha = station("alpha", "Alpha Jazz", { tag: "jazz" });
  const h = libraryHarness({ stations: [alpha, news, zulu], recentStations: [news, zulu, alpha] });
  h.el("#station-sort").value = "name";

  await h.el("#station-recent").dispatch("click");
  assert.deepEqual(rowNames(h), ["Daily News", "Zulu Jazz", "Alpha Jazz"]);
  assert.equal(h.el("#station-sort").hidden, true);
  assert.match(h.el("#station-count").textContent, /newest first/);

  h.el("#station-filter").value = "jazz";
  await h.el("#station-filter").dispatch("input");
  assert.deepEqual(rowNames(h), ["Zulu Jazz", "Alpha Jazz"]);
});

test("saving an unsaved recent station waits in the station write queue and uses the latest library", async () => {
  const firstWrite = deferred();
  const secondWrite = deferred();
  const existing = station("existing", "Existing");
  const staged = station("staged", "Queued station");
  const discovered = station("discovered", "Heard in Browse", { tag: "ambient" });
  let writes = 0;
  const h = libraryHarness({ stations: [existing], recentStations: [discovered], invoke: (command, args) => {
    if (command !== "save_stations") return undefined;
    if (++writes === 1) return firstWrite.promise;
    if (writes === 2) return secondWrite.promise;
    return undefined;
  } });
  h.context.staged = staged;
  await h.el("#station-recent").dispatch("click");
  const first = h.evaluate("saveStations({extraStation:staged,onSuccess:()=>state.stations.push(staged)})");
  await flush();
  assert.equal(h.calls.filter((call) => call.command === "save_stations").length, 1);

  const save = rows(h)[0].querySelector(".station-save-recent");
  assert.ok(save);
  await save.dispatch("click");
  const queued = h.evaluate("stationSaveTail");
  assert.equal(rows(h)[0].querySelector(".station-save-recent").textContent, "Saving…");
  firstWrite.resolve(null);
  await first;
  await flush();
  const writesAfterFirst = h.calls.filter((call) => call.command === "save_stations");
  assert.equal(writesAfterFirst.length, 2);
  const latest = jsonClone(writesAfterFirst[1].args.stations);
  assert.deepEqual(latest.map((item) => item.id).slice(0, 2), [existing.id, staged.id]);
  assert.ok(latest.some((item) => item.url === discovered.url));

  secondWrite.resolve(null);
  await queued;
  await flush();
  const persisted = jsonClone(h.evaluate("state.stations"));
  assert.deepEqual(persisted.map((item) => item.id).slice(0, 2), [existing.id, staged.id]);
  assert.equal(persisted.filter((item) => item.url === discovered.url).length, 1);
  assert.equal(rows(h)[0].querySelector(".station-save-recent"), null);
});

test("a failed reorder write preserves saved order and restores focus to the moved row", async () => {
  const items = [station("one", "One"), station("two", "Two"), station("three", "Three")];
  const h = libraryHarness({ stations: items, invoke: (command) => {
    if (command === "save_stations") throw new Error("disk unavailable");
  } });
  await h.el("#station-reorder").dispatch("click");
  const movedUp = rows(h).find((row) => row.dataset.id === "two").querySelector(".station-up");
  movedUp.focus();
  await h.evaluate('moveStation("two",-1)');

  assert.deepEqual(jsonClone(h.evaluate("state.stations.map((item)=>item.id)")), ["one", "two", "three"]);
  assert.deepEqual(jsonClone(h.calls.find((call) => call.command === "save_stations").args.stations.map((item)=>item.id)),
    ["two", "one", "three"]);
  const row = rows(h).find((item) => item.dataset.id === "two");
  assert.equal(h.document.activeElement, row.querySelector(".station-up"));
});

test("a queued reorder follows the latest library order and returns focus to an available move", async () => {
  const firstWrite = deferred();
  let writes = 0;
  const items = [station("one", "One"), station("two", "Two"), station("three", "Three")];
  const staged = station("staged", "Staged");
  const h = libraryHarness({ stations: items, invoke: (command) => {
    if (command === "save_stations" && ++writes === 1) return firstWrite.promise;
  } });
  h.context.staged = staged;
  await h.el("#station-reorder").dispatch("click");
  const queuedSave = h.evaluate("saveStations({extraStation:staged,onSuccess:()=>state.stations.push(staged)})");
  await flush();
  const move = h.evaluate('moveStation("two",-1)');
  await flush();
  assert.equal(h.calls.filter((call) => call.command === "save_stations").length, 1);

  firstWrite.resolve(null);
  await Promise.all([queuedSave, move]);
  const calls = h.calls.filter((call) => call.command === "save_stations");
  assert.equal(calls.length, 2);
  assert.deepEqual(jsonClone(calls[1].args.stations.map((item)=>item.id)), ["two", "one", "three", "staged"]);
  assert.deepEqual(jsonClone(h.evaluate("state.stations.map((item)=>item.id)")), ["two", "one", "three", "staged"]);
  const moved = rows(h).find((row) => row.dataset.id === "two");
  assert.equal(h.document.activeElement, moved.querySelector(".station-down"));
});
