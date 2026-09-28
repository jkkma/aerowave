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
  h.evaluate(`state.stations = [${JSON.stringify({ id: source.stationId, name: source.title,
    url: source.url, hls: true })}]; wire()`);
  return h;
}

test("paused mini player opens fullscreen and Back returns directly to its origin", async () => {
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
  await flush();
  assert.equal(h.el("#pane-player").classList.contains("on"), true);
  assert.equal(h.document.body.classList.contains("player-fullscreen"), true);
  assert.equal(h.el("#mini-player").hidden, true);
  assert.equal(h.document.activeElement, h.el("#btn-player-fullscreen"));
  assert.equal(h.el("#btn-player-fullscreen").getAttribute("aria-label"), "Exit fullscreen Player");
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  await flush();
  assert.equal(h.el("#pane-radio").classList.contains("on"), true);
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.el("#mini-player").hidden, false);
  assert.equal(h.document.activeElement, h.el("#tab-radio"));
  assert.deepEqual(h.calls.filter(({ command }) => command === "plugin:android-audio|set_player_fullscreen")
    .map(({ args }) => args.payload.enabled), [true, false]);
});

test("Player fullscreen toggle exits to regular Player, while mini Exit returns to its origin", async () => {
  const h = playerHarness();
  await h.el("#btn-player-fullscreen").dispatch("click");
  await flush();
  assert.equal(h.document.body.classList.contains("player-fullscreen"), true);
  assert.equal(h.el("#btn-play-folder").textContent, "Shuffle your music");
  assert.equal(h.el("#btn-player-fullscreen").getAttribute("aria-pressed"), "true");
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  await flush();
  assert.equal(h.el("#pane-player").classList.contains("on"), true);
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.el("#btn-play-folder").textContent, "Shuffle");
  assert.equal(h.document.activeElement, h.el("#btn-player-fullscreen"));

  await h.el("#tab-settings").dispatch("click");
  await h.el("#mini-open").dispatch("click");
  await flush();
  assert.equal(h.document.body.classList.contains("player-fullscreen"), true);
  await h.el("#btn-player-fullscreen").dispatch("click");
  await flush();
  assert.equal(h.el("#pane-settings").classList.contains("on"), true);
  assert.equal(h.document.activeElement, h.el("#tab-settings"));
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), false);
  assert.deepEqual(h.calls.filter(({ command }) => command === "plugin:android-audio|set_player_fullscreen")
    .map(({ args }) => args.payload.enabled), [true, false, true, false]);
});

test("Back during a pending native fullscreen enter still restores the origin and system bars", async () => {
  const entering = deferred();
  const h = playerHarness((command, args) => {
    if (command === "plugin:android-audio|set_player_fullscreen" && args.payload.enabled) return entering.promise;
  });
  await h.el("#tab-radio").dispatch("click");
  await h.el("#mini-open").dispatch("click");
  await flush();
  assert.deepEqual(h.calls.filter(({ command }) => command === "plugin:android-audio|set_player_fullscreen")
    .map(({ args }) => args.payload.enabled), [true]);
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  assert.equal(h.el("#pane-radio").classList.contains("on"), true);
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  entering.resolve(null);
  await flush();
  assert.deepEqual(h.calls.filter(({ command }) => command === "plugin:android-audio|set_player_fullscreen")
    .map(({ args }) => args.payload.enabled), [true, false]);
});

test("native fullscreen rejection restores visible navigation without trapping Player", async () => {
  const h = playerHarness((command, args) => {
    if (command === "plugin:android-audio|set_player_fullscreen" && args.payload.enabled) {
      return Promise.reject(new Error("window unavailable"));
    }
  });
  await h.el("#tab-radio").dispatch("click");
  await h.el("#mini-open").dispatch("click");
  await flush();
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.el("#pane-radio").classList.contains("on"), true);
  assert.equal(h.document.activeElement, h.el("#tab-radio"));
  assert.match(h.el("#status-msg").textContent, /Could not enter fullscreen: .*window unavailable/);
});

test("Escape, tab navigation, and an alarm leave fullscreen safely", async () => {
  const h = playerHarness();
  await h.el("#btn-player-fullscreen").dispatch("click");
  await flush();
  const escape = await h.document.dispatch("keydown", { key: "Escape" });
  await flush();
  assert.equal(escape.defaultPrevented, true);
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.document.activeElement, h.el("#btn-player-fullscreen"));

  await h.el("#btn-player-fullscreen").dispatch("click");
  await flush();
  await h.el("#tab-alarms").dispatch("click");
  await flush();
  assert.equal(h.el("#pane-alarms").classList.contains("on"), true);
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.document.activeElement, h.el("#tab-alarms"));

  await h.el("#tab-player").dispatch("click");
  await h.el("#btn-player-fullscreen").dispatch("click");
  await flush();
  h.evaluate(`applyAndroidAlarmState({ revision: 1, alarms: [], permissions: {},
    ringing: { occurrenceId: "wake", alarmId: "wake", hour: 7, minute: 0, title: "Morning" } })`);
  await flush();
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.el("#ringing").hidden, false);
  assert.deepEqual(h.calls.filter(({ command }) => command === "plugin:android-audio|set_player_fullscreen")
    .map(({ args }) => args.payload.enabled), [true, false, true, false, true, false]);
});

test("a native exit error leaves the regular Player and controls visible", async () => {
  const h = playerHarness((command, args) => {
    if (command === "plugin:android-audio|set_player_fullscreen" && !args.payload.enabled) {
      return Promise.reject(new Error("bars unavailable"));
    }
  });
  await h.el("#btn-player-fullscreen").dispatch("click");
  await flush();
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  await flush();
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.el("#pane-player").classList.contains("on"), true);
  assert.equal(h.document.activeElement, h.el("#btn-player-fullscreen"));
  assert.match(h.el("#status-msg").textContent, /Could not restore system bars/);
});

test("fullscreen Shuffle reveals the folder picker or a random-track error", async () => {
  const h = playerHarness((command) => {
    if (command === "plugin:android-audio|random_track") return Promise.reject(new Error("folder unavailable"));
  });
  await h.el("#btn-player-fullscreen").dispatch("click");
  await flush();
  await h.el("#btn-play-folder").dispatch("click");
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.document.activeElement, h.el("#btn-pick-folder"));
  assert.equal(h.el("#status-msg").textContent, "choose a folder first");

  h.evaluate('state.settings.shuffleFolder = "content://music/tree"');
  await h.el("#btn-player-fullscreen").dispatch("click");
  await flush();
  await h.el("#btn-play-folder").dispatch("click");
  assert.equal(h.document.body.classList.contains("player-fullscreen"), false);
  assert.equal(h.document.activeElement, h.el("#btn-play-folder"));
  assert.match(h.el("#status-msg").textContent, /folder unavailable/);
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
