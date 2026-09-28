const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const source = { kind: "station", stationId: "radio", title: "Radio Paradise",
  url: "https://radio.test/live", hls: true };
const native = (status, positionMs = 0) => ({
  status, generation: 7, sourceUrl: source.url, stationId: source.stationId,
  title: source.title, positionMs, volume: 0.8,
});

function playerHarness(invoke) {
  const h = createHarness({ navigator: { userAgent: "Android 13" }, invoke });
  const names = ["player", "radio", "browse", "alarms", "settings"];
  h.queries.set(".tab", names.map((name, index) => {
    const tab = h.el(`#tab-${name}`);
    tab.dataset.pane = name;
    tab.classList.toggle("on", index === 0);
    return tab;
  }));
  h.queries.set(".pane", names.map((name, index) => {
    const pane = h.el(`#pane-${name}`);
    pane.id = `pane-${name}`;
    pane.classList.toggle("on", index === 0);
    return pane;
  }));
  h.el("#station-editor").classList.add("hidden");
  h.el("#alarm-editor").classList.add("hidden");
  h.el("#mini-player").hidden = true;
  h.el("#android-quick-access").hidden = true;
  h.el("#android-quick-empty").hidden = true;
  h.evaluate(`state.stations = [${JSON.stringify({ id: source.stationId, name: source.title,
    url: source.url, hls: true })}]; wire()`);
  return h;
}

test("paused mini player opens normal Player and Back returns directly to its origin", async () => {
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|resume") return native("playing", 1000);
    if (command === "plugin:android-audio|pause") return native("paused", 1000);
  });
  await h.el("#tab-radio").dispatch("click");
  h.evaluate(`player.source = ${JSON.stringify(source)}; player.nativeGeneration = 7;
    applyAndroidPlaybackState({ ...${JSON.stringify(native("paused"))}, trackTitle: "Current song" })`);
  assert.equal(h.el("#mini-player").hidden, false);
  assert.equal(h.el("#mini-title").textContent, source.title);
  assert.equal(h.el("#mini-state").textContent, "Paused");
  assert.equal(h.el("#mini-track").textContent, "Current song");
  assert.equal(h.el("#mini-toggle").getAttribute("aria-label"), "Resume Radio Paradise");

  await h.el("#mini-toggle").dispatch("click");
  await flush();
  assert.equal(h.calls.some(({ command }) => command === "plugin:android-audio|resume"), true);
  h.evaluate(`applyAndroidPlaybackState(${JSON.stringify(native("playing", 2000))})`);
  assert.equal(h.el("#mini-toggle").disabled, false);
  await h.el("#mini-toggle").dispatch("click");
  await flush();
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|pause").length, 1);
  assert.equal(h.el("#mini-state").textContent, "Paused");

  await h.el("#mini-open").dispatch("click");
  assert.equal(h.el("#pane-player").classList.contains("on"), true);
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.el("#mini-player").hidden, true);
  assert.equal(h.document.activeElement, h.el("#tab-player"));
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  assert.equal(h.el("#pane-radio").classList.contains("on"), true);
  assert.equal(h.el("#mini-player").hidden, false);
  assert.equal(h.document.activeElement, h.el("#tab-radio"));
  assert.equal(h.calls.some(({ command }) => command === "plugin:android-audio|set_player_fullscreen"), false);
});

test("mini Player returns in one step from every other destination, including Escape", async () => {
  const h = playerHarness();
  h.evaluate(`player.source = ${JSON.stringify(source)}; player.nativeGeneration = 7;
    applyAndroidPlaybackState(${JSON.stringify(native("paused"))})`);
  for (const pane of ["browse", "alarms", "settings"]) {
    await h.el("#tab-" + pane).dispatch("click");
    await h.el("#mini-open").dispatch("click");
    assert.equal(h.el("#pane-player").classList.contains("on"), true);
    assert.equal(h.document.activeElement, h.el("#tab-player"));
    const escape = await h.document.dispatch("keydown", { key: "Escape" });
    assert.equal(escape.defaultPrevented, true);
    assert.equal(h.el("#pane-" + pane).classList.contains("on"), true);
    assert.equal(h.document.activeElement, h.el("#tab-" + pane));
  }
  await h.el("#tab-player").dispatch("click");
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), false);
  assert.equal(h.calls.some(({ command }) => command === "plugin:android-audio|set_player_fullscreen"), false);
});

test("Shuffle keeps folder guidance and errors visible in regular Player", async () => {
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|random_track") return Promise.reject(new Error("folder unavailable"));
  });
  await h.el("#btn-play-folder").dispatch("click");
  assert.equal(h.el("#status-msg").textContent, "choose a folder first");
  assert.equal(h.el("#btn-pick-folder").hidden, undefined);

  h.evaluate('state.settings.shuffleFolder = "content://music/tree"');
  await h.el("#btn-play-folder").dispatch("click");
  assert.match(h.el("#status-msg").textContent, /folder unavailable/);
  assert.equal(h.el("#pane-player").classList.contains("on"), true);
});
test("Android full Player and Space resume, while its separate Stop ends playback", async () => {
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|resume") return native("playing", 1000);
  });
  h.evaluate(`player.source = ${JSON.stringify(source)}; player.nativeGeneration = 7;
    applyAndroidPlaybackState(${JSON.stringify(native("paused"))})`);
  await h.el("#btn-play").dispatch("click");
  await flush();
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|resume").length, 1);
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|stop").length, 0);

  h.evaluate(`applyAndroidPlaybackState(${JSON.stringify(native("paused"))})`);
  h.document.activeElement = h.document.body;
  await h.document.dispatch("keydown", { key: " ", code: "Space" });
  await flush();
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|resume").length, 2);
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|stop").length, 0);

  await h.el("#btn-stop-android").dispatch("click");
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|stop").length, 1);
});

test("initial native play cannot be paused until service progress, but Stop cancels it", async () => {
  const pending = deferred();
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|play") return pending.promise;
    if (command === "plugin:android-audio|pause") return native("paused");
  });
  await h.el("#tab-browse").dispatch("click");
  const start = h.evaluate(`play(${JSON.stringify(source)})`);
  await flush();
  assert.equal(h.calls.some(({ command }) => command === "plugin:android-audio|play"), true);
  assert.equal(h.el("#mini-player").hidden, false);
  assert.equal(h.el("#mini-toggle").disabled, true);
  assert.equal(h.el("#btn-play").disabled, true);
  assert.equal(h.el("#btn-stop-android").hidden, false);
  const pauses = h.calls.filter(({ command }) => command === "plugin:android-audio|pause").length;
  await h.el("#mini-toggle").dispatch("click");
  await h.el("#btn-play").dispatch("click");
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|pause").length, pauses);

  await h.el("#btn-stop-android").dispatch("click");
  assert.equal(h.evaluate("player.source"), null);
  pending.resolve(native("buffering"));
  await start;
  assert.equal(h.el("#mini-player").hidden, true);
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|stop").length, 1);
});

test("a second tap cannot Pause a resume command that has not reached Media3", async () => {
  const resumed = deferred();
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|resume") return resumed.promise;
  });
  await h.el("#tab-radio").dispatch("click");
  h.evaluate(`player.source = ${JSON.stringify(source)}; player.nativeGeneration = 7;
    applyAndroidPlaybackState(${JSON.stringify(native("paused", 1000))});
    androidReadyGeneration = 7`);
  await h.el("#mini-toggle").dispatch("click");
  assert.equal(h.el("#mini-toggle").disabled, true);
  assert.equal(h.el("#btn-play").disabled, true);
  await h.el("#mini-toggle").dispatch("click");
  await h.el("#btn-play").dispatch("click");
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|pause").length, 0);

  resumed.resolve(native("playing", 1000));
  await flush();
  assert.equal(h.el("#mini-toggle").disabled, true);
  h.evaluate(`applyAndroidPlaybackState(${JSON.stringify(native("playing", 2000))})`);
  assert.equal(h.el("#mini-toggle").disabled, false);
});

test("native buffering stays unpausable until decoded progress and error offers retry", async () => {
  const h = playerHarness((command, args) => {
    if (command === "plugin:android-audio|play") return { ...native("buffering"), generation: args.payload.generation };
    if (command === "plugin:android-audio|pause") return native("paused");
  });
  await h.el("#tab-radio").dispatch("click");
  h.evaluate(`player.source = ${JSON.stringify(source)}; player.nativeGeneration = 7;
    applyAndroidPlaybackState(${JSON.stringify(native("buffering"))})`);
  assert.equal(h.el("#mini-state").textContent, "Buffering");
  assert.equal(h.el("#mini-toggle").disabled, true);
  h.evaluate(`applyAndroidPlaybackState(${JSON.stringify(native("playing", 1000))});
    applyAndroidPlaybackState(${JSON.stringify(native("playing", 2000))})`);
  assert.equal(h.el("#mini-toggle").disabled, false);
  h.evaluate(`applyAndroidPlaybackState({ ...${JSON.stringify(native("error"))}, error: "Station failed" })`);
  assert.equal(h.el("#mini-state").textContent, "Playback unavailable");
  assert.equal(h.el("#mini-toggle").getAttribute("aria-label"), "Retry Radio Paradise");
  await h.el("#mini-toggle").dispatch("click");
  await flush();
  assert.equal(h.el("#status-msg").textContent, "Trying Radio Paradise again");
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|play").length, 1);
  assert.equal(h.el("#mini-state").textContent, "Buffering");
  assert.equal(h.el("#mini-toggle").disabled, true);
});

test("rejected Android starts retry and eventually clear their mini player", async () => {
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|play") return Promise.reject(new Error("native unavailable"));
    if (command === "plugin:android-audio|pause") return native("paused");
  });
  await h.el("#tab-radio").dispatch("click");
  await h.evaluate(`play(${JSON.stringify(source)})`);
  for (let retry = 1; retry <= 4; retry++) {
    assert.equal(h.el("#mini-player").hidden, false);
    assert.match(h.el("#mini-state").textContent, /Reconnecting/);
    assert.equal(h.el("#mini-toggle").disabled, true);
    const timer = [...h.timers].find(([, value]) => !value.interval && value.ms === 1500 * retry);
    assert.ok(timer, `retry ${retry} was scheduled`);
    await h.fireTimer(timer[0]);
  }
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.el("#mini-player").hidden, true);
  assert.equal(h.el("#statusline").textContent, "Stream unavailable");
});

test("Your music retains the folder pick and shuffle controls after moving into Player", async () => {
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|pick_folder") {
      return { path: "content://music/tree", name: "Music", count: 3 };
    }
    if (command === "plugin:android-audio|random_track") {
      return { path: "content://music/song.mp3", name: "Song.mp3", total: 3 };
    }
  });
  assert.equal(h.el("#station-music").parentElement, h.el("#pane-player"));
  await h.el("#btn-pick-folder").dispatch("click");
  assert.equal(h.evaluate("state.settings.shuffleFolder"), "content://music/tree");
  assert.match(h.el("#folder-path").textContent, /Music.*3 playable files/);
  await h.el("#btn-play-folder").dispatch("click");
  await flush();
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|pick_folder").length, 1);
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|random_track").length, 1);
  assert.equal(h.evaluate("player.source.kind"), "folder");
});

test("Jump back in leads Player, keeps focused recent cards stable, and plays a selection", async () => {
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|play") return native("buffering");
  });
  assert.deepEqual(h.el("#pane-player").children, [
    h.el("#android-quick-access"), h.el("#player-stage"),
    h.el("#station-music"), h.el("#player-sleep-timer"),
  ]);
  h.evaluate("state.stations = []; state.settings.recentStations = []; renderStations()");
  assert.equal(h.el("#android-quick-access").hidden, false);
  assert.equal(h.el("#android-quick-empty").hidden, false);
  assert.equal(h.el("#android-quick-list").children.length, 0);

  h.evaluate(`state.stations = [{ ...${JSON.stringify(source)}, id: "radio", name: "Radio Paradise", favorite: true },
    { id: "jazz", name: "Jazz FM", url: "https://radio.test/jazz", favorite: true, hls: true }];
    state.settings.recentStations = [{ ...state.stations[0] },
      { id: "duplicate", name: "Old name", url: "http://radio.test/live" }]; renderStations()`);
  const cards = h.el("#android-quick-list").children;
  assert.equal(h.el("#android-quick-empty").hidden, true);
  assert.equal(cards.length, 2);
  assert.equal(cards[0].getAttribute("aria-label"), "Play Radio Paradise");
  assert.equal(cards[1].getAttribute("aria-label"), "Play Jazz FM");
  cards[0].focus();
  h.evaluate("renderStations()");
  assert.equal(h.el("#android-quick-list").children[0], cards[0]);
  assert.equal(h.document.activeElement, cards[0]);
  await cards[1].dispatch("click");
  await flush();
  assert.equal(h.evaluate("player.source.stationId"), "jazz");
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|play").length, 1);
});

test("Jump back in refreshes a focused card when saved playback metadata changes", async () => {
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|play") return native("buffering");
  });
  h.evaluate(`state.stations = [{ id: "radio", name: "Radio Paradise", url: "https://radio.test/live",
    hls: false, logo: "https://radio.test/old.png", tag: "Old", favorite: true }];
    state.settings.recentStations = [{ ...state.stations[0] }]; renderStations()`);
  const oldCard = h.el("#android-quick-list").children[0];
  oldCard.focus();
  h.evaluate(`state.stations = [{ ...state.stations[0], hls: true,
    logo: "https://radio.test/new.png", tag: "New" }]; renderStations()`);
  const newCard = h.el("#android-quick-list").children[0];
  assert.notEqual(newCard, oldCard);
  assert.equal(h.document.activeElement, newCard);
  await newCard.dispatch("click");
  await flush();
  assert.equal(h.evaluate("player.source.hls"), true);
  assert.equal(h.evaluate("player.source.logo"), "https://radio.test/new.png");
  assert.equal(h.evaluate("player.source.tag"), "New");
});

test("Jump back in uses decoded artwork, refreshes a warmed logo, and falls back to letters", async () => {
  const png = "data:image/png;base64,aW1hZ2U=";
  const urls = [];
  const h = playerHarness((command, args) => {
    if (command === "station_logo") {
      urls.push(args.url);
      return png;
    }
  });
  h.context.Image = class {
    naturalWidth = 128;
    naturalHeight = 128;
    async decode() {}
  };
  const create = h.document.createElement;
  h.document.createElement = tag => tag === "canvas" ? {
    getContext: () => ({ drawImage() {} }), toDataURL: () => png,
  } : create(tag);
  h.evaluate(`state.stations = [{ id: "radio", name: "Radio Paradise", url: "https://radio.test/live",
    logo: "https://art.test/first.png", favorite: true }];
    state.settings.recentStations = [{ ...state.stations[0] }]; renderStations()`);
  await flush();
  const firstMark = h.el("#android-quick-list").children[0].children[0];
  assert.equal(firstMark.children[1].src, png);
  await firstMark.children[1].dispatch("load");
  assert.equal(firstMark.children[1].hidden, false);
  assert.equal(firstMark.children[0].hidden, true);

  h.evaluate('state.stations[0].tag = "Changed"; renderStations()');
  const warmedMark = h.el("#android-quick-list").children[0].children[0];
  assert.notEqual(warmedMark, firstMark);
  assert.equal(warmedMark.children[1].src, png);
  assert.deepEqual(urls, ["https://art.test/first.png"]);

  h.evaluate('state.stations[0].logo = "https://art.test/second.png"; renderStations()');
  await flush();
  const failedMark = h.el("#android-quick-list").children[0].children[0];
  assert.equal(failedMark.children[1].src, png);
  await failedMark.children[1].dispatch("error");
  assert.equal(failedMark.children[1].hidden, true);
  assert.equal(failedMark.children[0].hidden, false);
  assert.deepEqual(urls, ["https://art.test/first.png", "https://art.test/second.png"]);

  h.evaluate('state.stations[0].logo = ""; renderStations()');
  const plainMark = h.el("#android-quick-list").children[0].children[0];
  assert.equal(plainMark.children[0].textContent, "R");
  assert.equal(plainMark.children[0].hidden, false);
  assert.equal(plainMark.children[1].src, undefined);
});

test("Android Stations places Recent before All and keeps All sorted without reordering", async () => {
  const h = playerHarness();
  assert.deepEqual(h.el("#station-views").children, [h.el("#station-recent"), h.el("#station-all")]);
  h.el("#station-sort").value = "saved";
  h.evaluate(`state.stations = [
    { id: "z", name: "Zulu", url: "https://radio.test/z", favorite: true },
    { id: "a", name: "Alpha", url: "https://radio.test/a", favorite: false }
  ]; renderStations()`);
  assert.deepEqual(h.el("#station-list").children.map(row => row.dataset.id), ["a", "z"]);
  assert.equal(h.el("#station-sort").hidden, true);
  assert.equal(h.evaluate("canReorderStations()"), false);
  await h.el("#station-favorites").dispatch("click");
  assert.equal(h.evaluate("stationFavoritesOnly"), false);
  await h.el("#station-recent").dispatch("click");
  assert.equal(h.evaluate("stationRecentOnly"), true);
});

test("empty Android Alarms uses its Create action and restores focus after cancel", async () => {
  const h = playerHarness();
  h.evaluate("clockNow = { hour: 6, minute: 0 }; renderAlarms()");
  const create = h.el("#alarm-list").querySelector(".alarm-empty-create");
  assert.ok(create);
  assert.equal(h.el("#btn-add-alarm").hidden, true);
  create.focus();
  await create.dispatch("click");
  assert.equal(h.el("#pane-alarms").classList.contains("editing"), true);
  await h.el("#al-cancel").dispatch("click");
  assert.equal(h.document.activeElement, create);

  h.evaluate(`state.alarms = [{ id: "wake", label: "Wake", hour: 7, minute: 0,
    days: [], enabled: true, source: { kind: "station", stationId: "radio" } }]; renderAlarms()`);
  assert.equal(h.el("#btn-add-alarm").hidden, false);
});

test("deleting the last Android alarm focuses the empty-state Create action", async () => {
  let alarms = [{ id: "wake", label: "Wake", hour: 7, minute: 0,
    days: [], enabled: true, source: { kind: "station", stationId: "radio" } }];
  const snapshot = () => ({ revision: 1, alarms, occurrences: [], permissions: {} });
  const h = playerHarness((command, args) => {
    if (command === "plugin:android-audio|sync_alarms") {
      alarms = args.payload.alarms;
      return snapshot();
    }
    if (command === "plugin:android-audio|get_alarm_state") return snapshot();
  });
  h.el("#pane-alarms").classList.add("on");
  h.evaluate(`state.alarms = ${JSON.stringify(alarms)}; renderAlarms()`);
  const remove = h.el("#alarm-list").querySelector(".alarm-delete");
  remove.focus();
  await remove.dispatch("click");
  assert.equal(alarms.length, 0);
  assert.equal(h.el("#btn-add-alarm").hidden, true);
  assert.equal(h.document.activeElement, h.el("#alarm-list").querySelector(".alarm-empty-create"));
});
