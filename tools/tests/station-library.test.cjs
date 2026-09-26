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

function savedIds(h) {
  return jsonClone(h.evaluate("state.stations.map((item) => item.id)"));
}

function stationWrites(h) {
  return h.calls.filter((call) => call.command === "save_stations");
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

test("a station moves directly before or after a nonadjacent target", async () => {
  const items = ["one", "two", "three", "four"].map((id) => station(id, id));
  const h = libraryHarness({ stations: items });

  await h.evaluate('moveStation("four", "one")');
  assert.deepEqual(savedIds(h), ["four", "one", "two", "three"]);
  await h.evaluate('moveStation("four", "three", true)');
  assert.deepEqual(savedIds(h), ["one", "two", "three", "four"]);
  assert.deepEqual(stationWrites(h).map((call) => jsonClone(call.args.stations.map((item) => item.id))), [
    ["four", "one", "two", "three"],
    ["one", "two", "three", "four"],
  ]);
  assert.deepEqual(rowIds(h), savedIds(h));
  assert.equal(rows(h).some((row) => row.querySelector(".station-up, .station-down")), false);
});

test("a failed reorder preserves order, keeps play focus, and a retry does not leak it", async () => {
  let attempts = 0;
  const items = [station("one", "One"), station("two", "Two"), station("three", "Three")];
  const h = libraryHarness({ stations: items, invoke: (command) => {
    if (command === "save_stations" && ++attempts === 1) throw new Error("disk unavailable");
  } });
  rows(h).find((row) => row.dataset.id === "two").querySelector(".station-play").focus();

  await h.evaluate('moveStation("two", "one")');
  assert.deepEqual(savedIds(h), ["one", "two", "three"]);
  assert.deepEqual(rowIds(h), ["one", "two", "three"]);
  assert.equal(h.document.activeElement, rows(h).find((row) => row.dataset.id === "two").querySelector(".station-play"));

  await h.evaluate('moveStation("two", "one")');
  assert.deepEqual(savedIds(h), ["two", "one", "three"]);
  assert.deepEqual(stationWrites(h).map((call) => jsonClone(call.args.stations.map((item) => item.id))), [
    ["two", "one", "three"],
    ["two", "one", "three"],
  ]);
});

test("a queued reorder uses the latest library and preserves a concurrent addition", async () => {
  const firstWrite = deferred();
  let writes = 0;
  const items = [station("one", "One"), station("two", "Two"), station("three", "Three")];
  const staged = station("staged", "Staged");
  const h = libraryHarness({ stations: items, invoke: (command) => {
    if (command === "save_stations" && ++writes === 1) return firstWrite.promise;
  } });
  h.context.staged = staged;
  const queuedSave = h.evaluate("saveStations({extraStation:staged,onSuccess:()=>state.stations.push(staged)})");
  await flush();
  rows(h).find((row) => row.dataset.id === "two").querySelector(".station-play").focus();
  const move = h.evaluate('moveStation("two", "one")');
  await flush();
  assert.equal(stationWrites(h).length, 1);
  assert.deepEqual(savedIds(h), ["one", "two", "three"]);

  firstWrite.resolve(null);
  await Promise.all([queuedSave, move]);
  const calls = stationWrites(h);
  assert.equal(calls.length, 2);
  assert.deepEqual(jsonClone(calls[1].args.stations.map((item)=>item.id)), ["two", "one", "three", "staged"]);
  assert.deepEqual(savedIds(h), ["two", "one", "three", "staged"]);
  const moved = rows(h).find((row) => row.dataset.id === "two");
  assert.equal(h.document.activeElement, moved.querySelector(".station-play"));
});

test("self, missing, and already positioned moves do not write", async () => {
  const h = libraryHarness({ stations: [station("one", "One"), station("two", "Two"), station("three", "Three")] });
  for (const expression of [
    'moveStation("one", "one")',
    'moveStation("absent", "one")',
    'moveStation("one", "absent")',
    'moveStation("one", "two")',
    'moveStation("two", "one", true)',
  ]) await h.evaluate(expression);
  assert.deepEqual(savedIds(h), ["one", "two", "three"]);
  assert.equal(stationWrites(h).length, 0);
});

test("a pending move rejects a duplicate and changes the live order only after the write succeeds", async () => {
  const pending = deferred();
  const h = libraryHarness({ stations: [station("one", "One"), station("two", "Two"), station("three", "Three")],
    invoke: (command) => command === "save_stations" ? pending.promise : undefined });
  const move = h.evaluate('moveStation("three", "one")');
  await flush();
  assert.deepEqual(savedIds(h), ["one", "two", "three"]);
  assert.deepEqual(rowIds(h), ["one", "two", "three"]);
  await h.evaluate('moveStation("two", "one")');
  assert.equal(stationWrites(h).length, 1);
  pending.resolve(null);
  await move;
  assert.deepEqual(savedIds(h), ["three", "one", "two"]);
});

test("a queued move is skipped if its target was deleted before the queue reaches it", async () => {
  const firstWrite = deferred();
  let writes = 0;
  const h = libraryHarness({ stations: [station("one", "One"), station("two", "Two"), station("three", "Three")],
    invoke: (command) => command === "save_stations" && ++writes === 1 ? firstWrite.promise : undefined });
  const first = h.evaluate("saveStations()");
  await flush();
  const removal = h.evaluate('saveStations({removeId:"two"})');
  const move = h.evaluate('moveStation("three", "two")');
  firstWrite.resolve(null);
  await Promise.all([first, removal, move]);
  assert.deepEqual(savedIds(h), ["one", "three"]);
  assert.deepEqual(stationWrites(h).map((call) => jsonClone(call.args.stations.map((item) => item.id))), [
    ["one", "two", "three"],
    ["one", "three"],
  ]);
});

test("filtered saved-order moves preserve the relative order of hidden stations", async () => {
  const items = [
    station("one", "One", { tag: "jazz", favorite: true }),
    station("two", "Two", { tag: "pop" }),
    station("three", "Three", { tag: "jazz", favorite: true }),
    station("four", "Four", { tag: "pop" }),
    station("five", "Five", { tag: "jazz", favorite: true }),
  ];
  const h = libraryHarness({ stations: items });
  h.el("#station-filter").value = "jazz";
  await h.el("#station-filter").dispatch("input");
  assert.deepEqual(rowIds(h), ["one", "three", "five"]);
  await h.evaluate('moveStation("five", "one")');
  assert.deepEqual(savedIds(h), ["five", "one", "two", "three", "four"]);
  assert.deepEqual(rowIds(h), ["five", "one", "three"]);

  h.el("#station-filter").value = "";
  await h.el("#station-filter").dispatch("input");
  await h.el("#station-favorites").dispatch("click");
  assert.deepEqual(rowIds(h), ["five", "one", "three"]);
  await h.evaluate('moveStation("one", "three", true)');
  assert.deepEqual(savedIds(h), ["five", "two", "three", "one", "four"]);
  assert.deepEqual(rowIds(h), ["five", "three", "one"]);
  assert.equal(stationWrites(h).length, 2);
});

test("Recent, name sort, a single visible row, and setup restore block reordering", async () => {
  const h = libraryHarness({ stations: [station("one", "One"), station("two", "Two"), station("three", "Three")],
    recentStations: [station("three", "Three"), station("two", "Two")] });
  h.el("#station-sort").value = "name";
  await h.el("#station-sort").dispatch("change");
  await h.evaluate('moveStation("three", "one")');
  h.el("#station-sort").value = "saved";
  await h.el("#station-sort").dispatch("change");
  await h.el("#station-recent").dispatch("click");
  await h.evaluate('moveStation("three", "two")');
  await h.el("#station-all").dispatch("click");
  h.el("#station-filter").value = "One";
  await h.el("#station-filter").dispatch("input");
  await h.evaluate('moveStation("one", "two")');
  h.el("#station-filter").value = "";
  await h.el("#station-filter").dispatch("input");
  h.evaluate("setupRestorePending = true");
  await h.evaluate('moveStation("three", "one")');
  assert.deepEqual(savedIds(h), ["one", "two", "three"]);
  assert.equal(stationWrites(h).length, 0);
});

test("mouse dragging a play row past the movement threshold drops it before another row", async () => {
  const h = libraryHarness({ stations: [station("one", "One"), station("two", "Two"), station("three", "Three")] });
  const list = h.el("#station-list");
  let captured = null;
  list.setPointerCapture = (id) => { captured = id; };
  list.hasPointerCapture = (id) => captured === id;
  list.releasePointerCapture = () => { captured = null; };
  list.getBoundingClientRect = () => ({ left: 0, right: 200, top: 0, bottom: 120, height: 120 });
  rows(h).forEach((row, index) => {
    row.getBoundingClientRect = () => ({ top: index * 40, bottom: (index + 1) * 40, height: 40 });
  });
  const play = rows(h)[2].querySelector(".station-play");
  const pointer = { pointerType: "mouse", button: 0, buttons: 1, pointerId: 7, clientX: 10 };
  await play.dispatch("pointerdown", { ...pointer, clientY: 100 });
  await list.dispatch("pointermove", { ...pointer, clientY: 96 });
  await list.dispatch("pointerup", { ...pointer, clientY: 96 });
  assert.equal(stationWrites(h).length, 0);

  await play.dispatch("pointerdown", { ...pointer, clientY: 100 });
  await list.dispatch("pointermove", { ...pointer, clientY: 10 });
  assert.equal(captured, 7);
  assert.equal(stationWrites(h).length, 0);
  await list.dispatch("pointerup", { ...pointer, clientY: 10 });
  await flush();
  assert.equal(captured, null);
  assert.deepEqual(savedIds(h), ["three", "one", "two"]);
  assert.equal(stationWrites(h).length, 1);
  assert.equal(h.evaluate("player.source"), null);
});

test("Escape cancels a drag and suppresses its delayed mouse click", async () => {
  const h = libraryHarness({ stations: [station("one", "One"), station("two", "Two")] });
  const list = h.el("#station-list");
  let captured = null;
  list.setPointerCapture = (id) => { captured = id; };
  list.hasPointerCapture = (id) => captured === id;
  list.releasePointerCapture = () => { captured = null; };
  list.getBoundingClientRect = () => ({ left: 0, right: 200, top: 0, bottom: 80, height: 80 });
  rows(h).forEach((row, index) => {
    row.getBoundingClientRect = () => ({ top: index * 40, bottom: (index + 1) * 40, height: 40 });
  });
  const play = rows(h)[1].querySelector(".station-play");
  const pointer = { pointerType: "mouse", button: 0, buttons: 1, pointerId: 8, clientX: 10 };
  await play.dispatch("pointerdown", { ...pointer, clientY: 60 });
  await list.dispatch("pointermove", { ...pointer, clientY: 10 });
  assert.equal(captured, 8);
  const escape = await h.document.dispatch("keydown", { key: "Escape" });
  assert.equal(escape.defaultPrevented, true);
  assert.equal(captured, null);
  // The test DOM does not model capture listeners, so exercise the click guard directly.
  for (const [id, timer] of h.timers) if (!timer.interval && timer.ms === 0) await h.fireTimer(id);
  await list.dispatch("pointerup", { ...pointer, clientY: 10 });
  const clickGuard = list.handlers.get("click")[0];
  const checkedClick = (detail) => {
    const result = { prevented: false, stopped: false };
    clickGuard({ detail, preventDefault() { result.prevented = true; },
      stopImmediatePropagation() { result.stopped = true; } });
    return result;
  };
  assert.deepEqual(checkedClick(1), { prevented: true, stopped: true });
  assert.deepEqual(checkedClick(0), { prevented: false, stopped: false });
  assert.equal(stationWrites(h).length, 0);
  await play.dispatch("pointerdown", { ...pointer, clientY: 60 });
  assert.deepEqual(checkedClick(1), { prevented: false, stopped: false });
});

test("Alt+Arrow moves a focused play row without starting playback", async () => {
  const h = libraryHarness({ stations: [station("one", "One"), station("two", "Two"), station("three", "Three")] });
  const play = rows(h).find((row) => row.dataset.id === "three").querySelector(".station-play");
  play.focus();
  const up = await play.dispatch("keydown", { key: "ArrowUp", altKey: true });
  await flush();
  assert.equal(up.defaultPrevented, true);
  assert.deepEqual(savedIds(h), ["one", "three", "two"]);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.document.activeElement, rows(h).find((row) => row.dataset.id === "three").querySelector(".station-play"));

  const movedPlay = rows(h).find((row) => row.dataset.id === "three").querySelector(".station-play");
  const down = await movedPlay.dispatch("keydown", { key: "ArrowDown", altKey: true });
  await flush();
  assert.equal(down.defaultPrevented, true);
  assert.deepEqual(savedIds(h), ["one", "two", "three"]);
  assert.equal(stationWrites(h).length, 2);
});
