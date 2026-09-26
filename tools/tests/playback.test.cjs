const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

function folderHarness(pick) {
  return createHarness({ invoke: (command) => command === "random_track" ? pick.promise : undefined });
}

test("brief reconnects consume the retry budget before backup takes over", async () => {
  const h = createHarness();
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/live', title: 'Flaky' })");
  for (let attempt = 1; attempt <= 4; attempt++) {
    await h.audios[0].dispatch("playing");
    h.evaluate("failure('network dropped')");
    assert.equal(h.evaluate("player.retries"), attempt);
    assert.equal(h.evaluate("lastMediaTime"), 0);
    await h.fireTimer(h.evaluate("player.retryTimer"));
    await flush();
  }
  await h.audios[0].dispatch("playing");
  h.evaluate("failure('network dropped')");
  await flush();
  assert.equal(h.calls.filter(({ command }) => command === "backup_track").length, 1);
});

test("sustained decoded progress restores the reconnect budget", async () => {
  const h = createHarness();
  h.evaluate("globalThis.testNow = 1000; Date.now = () => testNow");
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/live', title: 'Recovering' })");
  h.evaluate("failure('network dropped')");
  await h.fireTimer(h.evaluate("player.retryTimer"));
  await flush();
  await h.audios[0].dispatch("playing");
  h.audios[0].readyState = 3;
  h.audios[0].currentTime = 1;
  await h.audios[0].dispatch("timeupdate");
  assert.equal(h.evaluate("player.retries"), 1);
  h.evaluate("testNow = 16001");
  h.audios[0].currentTime = 2;
  await h.audios[0].dispatch("timeupdate");
  assert.equal(h.evaluate("player.retries"), 1);
  for (let second = 3; second <= 15; second++) {
    h.audios[0].currentTime = second;
    await h.audios[0].dispatch("timeupdate");
  }
  assert.equal(h.evaluate("player.retries"), 0);
  h.evaluate("failure('another network drop')");
  assert.equal(h.evaluate("player.retries"), 1);
});

for (const routed of [null, "http://127.0.0.1:1234/f/private-token"]) {
  test(`local files use ${routed ? "the scoped media route" : "the platform asset URL"}`, async () => {
    const h = createHarness({ invoke: (command) => command === "local_file_url" ? routed : undefined });
    await h.evaluate("play({ kind: 'folder', path: '/music/track.wav', url: 'asset://track.wav', title: 'Track' })");
    assert.equal(h.audios[0].src, routed || "asset://track.wav");
    assert.equal(h.calls.find(({ command }) => command === "local_file_url").args.path, "/music/track.wav");
    assert.equal(h.calls.some(({ command }) => command === "relay_url"), false);
  });
}

for (const rejects of [false, true]) {
  test(`a late local-file route ${rejects ? "failure" : "reply"} cannot replace a newer station`, async () => {
    const route = deferred();
    const h = createHarness({ invoke: (command) => command === "local_file_url" ? route.promise : undefined });
    const old = h.evaluate("play({ kind: 'folder', path: '/music/old.wav', url: 'asset://old.wav', title: 'Old' })");
    await h.evaluate("play({ kind: 'station', url: 'https://radio.test/live', title: 'Live' })");
    const current = h.audios[0].src;
    if (rejects) route.reject(new Error("old file disappeared"));
    else route.resolve("http://127.0.0.1:1234/f/old");
    await old;
    assert.equal(h.evaluate("player.source.title"), "Live");
    assert.equal(h.audios[0].src, current);
    assert.equal(h.calls.some(({ command }) => command === "backup_track"), false);
  });
}

test("a delayed local route cannot restart an alarm that is fading out", async () => {
  const route = deferred();
  const h = createHarness({ invoke: (command) => command === "local_file_url" ? route.promise : undefined });
  h.evaluate("ringing = { alarmId: 'wake' }");
  const pending = h.evaluate("play({ kind: 'folder', path: '/music/alarm.wav', url: 'asset://alarm.wav', title: 'Alarm' }, { fadeSecs: 20 })");
  h.evaluate("giveUp()");
  const fade = h.evaluate("player.fadeTimer");
  route.resolve("http://127.0.0.1:1234/f/alarm");
  await pending;
  assert.equal(h.audios[0].src, "");
  assert.equal(h.evaluate("givingUp"), true);
  assert.equal(h.evaluate("player.fadeTimer"), fade);
});

test("pause while a local route is pending keeps the file paused until resume", async () => {
  const route = deferred();
  const h = createHarness({ invoke: (command) => command === "local_file_url" ? route.promise : undefined });
  const pending = h.evaluate("play({ kind: 'folder', path: '/music/track.wav', url: 'asset://track.wav', title: 'Track' })");
  h.evaluate("pausePlayback()");
  route.resolve("http://127.0.0.1:1234/f/track");
  await pending;
  assert.equal(h.audios[0].paused, true);
  assert.equal(h.evaluate("player.paused"), true);
  h.evaluate("resumePlayback()");
  await flush();
  assert.equal(h.audios[0].src, "http://127.0.0.1:1234/f/track");
  assert.equal(h.audios[0].paused, false);
});

test("a folder pick cannot restart playback after Stop", async () => {
  const pick = deferred();
  const h = folderHarness(pick);
  const pending = h.evaluate("playRandomFromFolder('music')");
  h.evaluate("stopPlayback()");
  pick.resolve({ path: "old.mp3", name: "old.mp3", total: 1 });
  await pending;
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.audios[0].src, "");
});

for (const fails of [false, true]) {
  test(`a ${fails ? "failed" : "successful"} old folder scan cannot replace a newer alarm`, async () => {
    const pick = deferred();
    const h = folderHarness(pick);
    const pending = h.evaluate("playRandomFromFolder('music')");
    h.evaluate("ringing = { alarmId: 'wake' }");
    await h.evaluate("play({ kind: 'folder', url: 'asset://alarm.mp3', title: 'Alarm' })");
    if (fails) pick.reject(new Error("old folder is gone"));
    else pick.resolve({ path: "old.mp3", name: "old.mp3", total: 1 });
    await pending;
    assert.equal(h.evaluate("player.source.title"), "Alarm");
    assert.equal(h.audios[0].src, "asset://alarm.mp3");
  });
}

test("the newest folder request wins even when both began in one playback generation", async () => {
  const first = deferred(), second = deferred();
  const h = createHarness({ invoke: (command, args) => command === "random_track" ? (args.path === "first" ? first : second).promise : undefined });
  const a = h.evaluate("playRandomFromFolder('first')");
  const b = h.evaluate("playRandomFromFolder('second')");
  first.resolve({ path: "first.mp3", name: "first.mp3", total: 1 });
  await a;
  assert.equal(h.evaluate("player.source"), null);
  second.resolve({ path: "second.mp3", name: "second.mp3", total: 1 });
  await b;
  await flush();
  assert.equal(h.evaluate("player.source.folder"), "second");
});

test("a late playlist probe cannot overwrite a newer station's resolved URL", async () => {
  const first = deferred();
  const h = createHarness({ invoke: (command, args) => command === "probe_stream" && args.url.endsWith("a.pls") ? first.promise : undefined });
  const a = h.evaluate("play({ kind: 'station', url: 'https://radio.test/a.pls', title: 'A' })");
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/b', title: 'B' })");
  first.resolve({ url: "https://radio.test/old", hls: true, warning: "old warning" });
  await a;
  assert.equal(h.evaluate("player.source.title"), "B");
  assert.equal(h.evaluate("player.resolved"), "https://radio.test/b");
  assert.equal(h.evaluate("player.hls"), false);
  assert.notEqual(h.el("#status-msg").textContent, "old warning");
});

test("extensionless HLS discovered by resolution uses hls.js without a relay", async () => {
  const h = createHarness({ invoke: (command) => command === "probe_stream" ? { url: "https://cdn.test/live.m3u8", hls: true } : undefined });
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/listen', title: 'Live' })");
  assert.equal(h.evaluate("player.hls"), true);
  assert.equal(h.hlsInstances[0].url, "https://cdn.test/live.m3u8");
  assert.equal(h.calls.some(({ command }) => command === "relay_url"), false);
});

test("browse's known HLS flag selects its decoder immediately", async () => {
  const h = createHarness();
  h.evaluate("previewBrowse({ url: 'https://radio.test/listen', name: 'Live', hls: true })");
  await flush();
  assert.equal(h.evaluate("player.hls"), true);
  assert.equal(h.calls.some(({ command }) => command === "probe_stream" || command === "relay_url"), false);
});

for (const wrapper of ["station.pls", "station.m3u", "listen"]) {
  test(`HLS reached through ${wrapper} resumes from its resolved manifest`, async () => {
    const h = createHarness({ invoke: (command) => command === "probe_stream" ? { url: "https://cdn.test/live.m3u8", hls: true } : undefined });
    h.context.wrapper = "https://radio.test/" + wrapper;
    await h.evaluate("play({ kind: 'station', url: wrapper, title: 'Live' })");
    await h.audios[0].dispatch("playing");
    h.evaluate("pausePlayback(); resumePlayback()");
    await flush();
    assert.equal(h.hlsInstances.length, 2);
    assert.equal(h.hlsInstances[0].destroyed, true);
    assert.equal(h.hlsInstances[1].url, "https://cdn.test/live.m3u8");
    assert.equal(h.evaluate("player.source.url"), "https://radio.test/" + wrapper);
    assert.equal(h.evaluate("player.resolved"), "https://cdn.test/live.m3u8");
    assert.equal(h.calls.filter(({ command }) => command === "probe_stream").length, 1);
  });
}

test("a known directory HLS hint still resolves an explicit playlist wrapper", async () => {
  const h = createHarness({ invoke: (command) => command === "probe_stream" ? { url: "https://cdn.test/live.m3u8", hls: true } : undefined });
  h.evaluate("previewBrowse({ url: 'https://radio.test/station.pls', name: 'Live', hls: true })");
  await flush();
  assert.equal(h.hlsInstances[0].url, "https://cdn.test/live.m3u8");
});

test("ordinary discovery headers replace the extra initial metadata request", async () => {
  const h = createHarness({ invoke: (command, args) => command === "probe_stream" ? { url: args.url, hls: false, bitrate: "128", genre: "Jazz" } : undefined });
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/live', title: 'Live' })");
  assert.equal(h.calls.filter(({ command }) => command === "probe_stream").length, 1);
  assert.equal(h.el("#np-meta").textContent, "128 kbps  ·  Jazz");
  assert.match(h.audios[0].src, /^http:\/\/127\.0\.0\.1\/relay\//);
});

test("HLS discovered after the initial probe fails still avoids a direct media load", async () => {
  let probes = 0;
  const h = createHarness({ invoke: (command) => {
    if (command !== "probe_stream") return undefined;
    if (++probes === 1) return Promise.reject(new Error("temporary timeout"));
    return { url: "https://radio.test/live.m3u8", hls: true };
  } });
  h.evaluate("state.settings.showMetadata = false");
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/listen', title: 'Live' })");
  await h.evaluate("fallBackToDirect(player.source, playGeneration)");
  assert.equal(h.evaluate("player.hls"), true);
  assert.equal(h.hlsInstances[0].url, "https://radio.test/live.m3u8");
  assert.notEqual(h.audios[0].src, "https://radio.test/live.m3u8");
});

test("a failed superseded retry probe cannot tear down a newer HLS station", async () => {
  const retryInfo = deferred();
  let probes = 0;
  const h = createHarness({ invoke: (command) => {
    if (command !== "probe_stream") return undefined;
    return ++probes === 1 ? Promise.reject(new Error("initial timeout")) : retryInfo.promise;
  } });
  h.evaluate("state.settings.showMetadata = false");
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/old', title: 'Old' })");
  h.evaluate("failure('first failure'); failure('second failure')");
  const retryId = [...h.timers].find(([, timer]) => timer.ms === 3000)[0];
  const retry = h.fireTimer(retryId);
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/new', title: 'New', hls: true })");
  retryInfo.reject(new Error("late timeout"));
  await retry;
  assert.equal(h.evaluate("player.source.title"), "New");
  assert.equal(h.evaluate("player.hls"), true);
  assert.equal(h.hlsInstances[0].destroyed, undefined);
});

test("HLS byte ranges and per-session loader URLs survive concurrent playback and TEST", () => {
  const h = createHarness();
  h.evaluate("globalThis.LoaderA = relayLoader(Hls.DefaultConfig.loader, 'http://hls.localhost/a'); globalThis.LoaderB = relayLoader(Hls.DefaultConfig.loader, 'http://hls.localhost/b')");
  const loader = h.evaluate("new LoaderA()");
  const context = { url: "https://cdn.test/audio.aac", rangeStart: 100, rangeEnd: 200 };
  let received;
  loader.load(context, {}, { onSuccess: (...args) => { received = args; } });
  const proxied = new URL(loader.request.context.url);
  assert.equal(proxied.pathname, "/a");
  assert.equal(proxied.searchParams.get("r"), "100-199");
  assert.equal(Buffer.from(proxied.searchParams.get("u"), "base64url").toString(), context.url);
  assert.equal(context.url, "https://cdn.test/audio.aac");
  loader.request.callbacks.onSuccess({ url: proxied.href }, {}, loader.request.context, { getResponseHeader: () => "https://cdn.test/final.aac" });
  assert.equal(received[0].url, "https://cdn.test/final.aac");
  assert.equal(received[2], context);
  const other = h.evaluate("new LoaderB()");
  other.load({ url: "https://cdn.test/list.m3u8" }, {}, {});
  assert.equal(new URL(other.request.context.url).searchParams.has("r"), false);
  assert.equal(new URL(other.request.context.url).pathname, "/b");
});

test("station decode waits past metadata and succeeds only when playable", async () => {
  const h = createHarness();
  const result = h.evaluate("canDecode('https://radio.test/live')");
  let finished = false;
  result.then(() => { finished = true; });
  await flush();
  const probe = h.audios[1];
  assert.match(probe.src, /^http:\/\/127\.0\.0\.1\/relay\//);
  probe.readyState = 1;
  await probe.dispatch("loadedmetadata");
  await flush();
  assert.equal(finished, false);
  probe.readyState = 3;
  await probe.dispatch("canplay");
  assert.equal((await result).ok, true);
  assert.equal(probe.src, "");
  assert.equal(probe.paused, true);
  assert.equal([...h.timers.values()].some((timer) => timer.ms === 9000), false);
});

test("metadata-only station decode times out", async () => {
  const h = createHarness();
  const result = h.evaluate("canDecode('https://radio.test/live', 500)");
  await flush();
  await h.audios[1].dispatch("loadedmetadata");
  const timer = [...h.timers].find(([, timer]) => timer.ms === 500)[0];
  await h.fireTimer(timer);
  assert.equal((await result).ok, false);
});

test("ordinary station TEST tries direct once when the relay cannot serve it", async () => {
  const h = createHarness();
  const result = h.evaluate("canDecode('https://radio.test/live')");
  await flush();
  const probe = h.audios[1];
  probe.error = { code: 4 };
  await probe.dispatch("error");
  assert.equal(probe.src, "https://radio.test/live");
  await probe.dispatch("canplay");
  assert.equal((await result).ok, true);
});

test("HLS TEST checks decoder readiness and closes its own session", async () => {
  const h = createHarness();
  const result = h.evaluate("canDecode('https://radio.test/listen', 1000, { hls: true })");
  await flush();
  const decoder = h.hlsInstances[0];
  decoder.emit("MANIFEST_PARSED", {});
  await h.audios[1].dispatch("loadedmetadata");
  assert.equal(decoder.destroyed, undefined);
  await h.audios[1].dispatch("canplay");
  assert.equal((await result).ok, true);
  assert.equal(decoder.destroyed, true);
  assert.equal(h.calls.filter(({ command }) => command === "hls_close").length, 1);
  assert.equal(h.calls.some(({ command }) => command === "relay_url"), false);
});

test("a cancelled decode closes an HLS session that arrives after cancellation", async () => {
  const session = deferred();
  const h = createHarness({ invoke: (command) => command === "hls_session" ? session.promise : undefined });
  h.evaluate("globalThis.cancel = new AbortController()");
  const result = h.evaluate("canDecode('https://radio.test/listen', 1000, { hls: true, signal: cancel.signal })");
  h.evaluate("cancel.abort()");
  assert.equal((await result).cancelled, true);
  session.resolve("http://hls.localhost/late");
  await flush();
  assert.equal(h.hlsInstances.length, 0);
  assert.equal(h.calls.find(({ command }) => command === "hls_close").args.session, "late");
});

test("station editor changes cancel a pending TEST without stale name or status updates", async () => {
  const info = deferred();
  const h = createHarness({ invoke: (command) => command === "probe_stream" ? info.promise : undefined });
  h.evaluate("wire(); openStationEditor(null)");
  h.el("#st-url").value = "https://radio.test/old";
  const pending = h.el("#st-test").dispatch("click");
  h.el("#st-url").value = "https://radio.test/new";
  await h.el("#st-url").dispatch("input");
  info.resolve({ url: "https://radio.test/old", name: "Old station", hls: true });
  await pending;
  assert.equal(h.el("#st-name").value, "");
  assert.equal(h.el("#st-note").textContent, "");
  assert.equal(h.hlsInstances.length, 0);
});

test("HLS teardown pause events do not stop the replacement attempt", async () => {
  const session = deferred();
  let sessions = 0;
  const h = createHarness({
    invoke: (command) => command === "hls_session" ? (++sessions === 1 ? "http://hls.localhost/old" : session.promise) : undefined,
    onDestroy: (decoder) => { decoder.media?.dispatch("pause"); },
  });
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/live', title: 'Live', hls: true })");
  await h.audios[0].dispatch("playing");
  h.evaluate("failure('temporary disconnect')");
  const retryId = [...h.timers].find(([, timer]) => timer.ms === 1500)[0];
  const retry = h.fireTimer(retryId);
  await flush();
  await h.audios[0].dispatch("pause");
  assert.equal(h.evaluate("player.source.title"), "Live");
  session.resolve("http://hls.localhost/new");
  await retry;
  assert.equal(h.evaluate("player.hls"), true);
  await h.audios[0].dispatch("playing");
  await h.audios[0].dispatch("pause");
  assert.equal(h.evaluate("player.source"), null);
});

test("superseded playback closes an HLS session returned after Stop", async () => {
  const session = deferred();
  const h = createHarness({ invoke: (command) => command === "hls_session" ? session.promise : undefined });
  const playback = h.evaluate("play({ kind: 'station', url: 'https://radio.test/live', title: 'Live', hls: true })");
  h.evaluate("stopPlayback()");
  session.resolve("http://hls.localhost/unused");
  await playback;
  assert.equal(h.hlsInstances.length, 0);
  assert.equal(h.calls.find(({ command }) => command === "hls_close").args.session, "unused");
});

const androidNavigator = { userAgent: "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36" };

test("Android sends a resolved HLS manifest to Media3 without hls.js or the relay", async () => {
  const h = createHarness({
    navigator: androidNavigator,
    invoke: (command, args) => {
      if (command === "plugin:android-audio|stop") {
        return { status: "idle", generation: 0, sourceUrl: null, title: null, stationId: null, positionMs: 0, volume: 0.8, error: null, trackTitle: null };
      }
      if (command === "plugin:android-audio|play") {
        return { status: "buffering", generation: args.payload.generation, sourceUrl: args.payload.sourceUrl, title: args.payload.title, stationId: null, positionMs: 0, volume: 0.8, error: null, trackTitle: null };
      }
      return undefined;
    },
  });

  await h.evaluate("play({ kind: 'station', url: 'https://cdn.test/live.m3u8', title: 'Android HLS', hls: true })");
  const call = h.calls.find(({ command }) => command === "plugin:android-audio|play");
  assert.equal(call.args.payload.url, "https://cdn.test/live.m3u8");
  assert.equal(call.args.payload.sourceUrl, "https://cdn.test/live.m3u8");
  assert.equal(call.args.payload.isHls, true);
  assert.equal(h.calls.some(({ command }) => command === "relay_url"), false);
  assert.equal(h.hlsInstances.length, 0);
  assert.equal(h.audios[0].src, "");
});

test("a stale Android play reply cannot replace a newer station", async () => {
  const first = deferred();
  let plays = 0;
  const h = createHarness({
    navigator: androidNavigator,
    invoke: (command, args) => {
      if (command === "plugin:android-audio|stop") {
        return { status: "idle", generation: 0, sourceUrl: null, title: null, stationId: null, positionMs: 0, volume: 0.8, error: null, trackTitle: null };
      }
      if (command === "plugin:android-audio|play") {
        if (++plays === 1) return first.promise;
        return { status: "playing", generation: args.payload.generation, sourceUrl: args.payload.sourceUrl, title: args.payload.title, stationId: null, positionMs: 1000, volume: 0.8, error: null, trackTitle: "New track" };
      }
      return undefined;
    },
  });

  const old = h.evaluate("play({ kind: 'station', url: 'https://radio.test/old', title: 'Old' })");
  await flush();
  const newer = h.evaluate("play({ kind: 'station', url: 'https://radio.test/new', title: 'New' })");
  await newer;
  const oldGeneration = h.calls.filter(({ command }) => command === "plugin:android-audio|play")[0].args.payload.generation;
  first.resolve({ status: "playing", generation: oldGeneration, sourceUrl: "https://radio.test/old", title: "Old", stationId: null, positionMs: 9000, volume: 0.8, error: null, trackTitle: "Old track" });
  await old;

  assert.equal(h.evaluate("player.source.title"), "New");
  assert.equal(h.el("#np-track").textContent, "New track");
  assert.equal(h.evaluate("player.nativeGeneration"), h.calls.filter(({ command }) => command === "plugin:android-audio|play")[1].args.payload.generation);
});

test("Android restores background playback and follows native pause and stop", async () => {
  let nativeState = {
    status: "playing", generation: 41, sourceUrl: "https://radio.test/live", title: "Restored radio",
    stationId: "saved", positionMs: 4000, volume: 0.65, error: null, trackTitle: "First track",
  };
  const h = createHarness({
    navigator: androidNavigator,
    invoke: (command) => command === "plugin:android-audio|get_state" ? { ...nativeState } : undefined,
  });
  h.evaluate("state = { stations: [{ id: 'saved', name: 'Saved station', url: 'https://radio.test/original' }], alarms: [], settings: { volume: 0.8 } }");

  await h.evaluate("refreshAndroidPlayback({ allowRestore: true })");
  assert.equal(h.evaluate("player.source.stationId"), "saved");
  assert.equal(h.evaluate("player.source.url"), "https://radio.test/original");
  assert.equal(h.el("#np-track").textContent, "First track");
  assert.equal(h.calls.some(({ command }) => command === "plugin:android-audio|play" || command === "plugin:android-audio|stop"), false);
  assert.equal(h.el("#statusline").textContent, "Starting audio");

  nativeState.positionMs = 5200;
  await h.evaluate("refreshAndroidPlayback()");
  assert.equal(h.el("#statusline").textContent, "Live radio");
  assert.equal(h.evaluate("player.lastProgress > 0"), true);

  nativeState.status = "paused";
  await h.evaluate("refreshAndroidPlayback()");
  assert.equal(h.evaluate("player.paused"), true);
  assert.equal(h.el("#statusline").textContent, "Paused");

  nativeState.status = "idle";
  await h.evaluate("refreshAndroidPlayback()");
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.el("#statusline").textContent, "Stopped");
});

test("Android keeps live status when an HLS sliding window resets its native position", async () => {
  const nativeState = {
    status: "playing", generation: 52, sourceUrl: "https://radio.test/live.m3u8", title: "France Inter",
    stationId: null, positionMs: 1501, volume: 0.7, error: null, trackTitle: null,
  };
  const h = createHarness({
    navigator: androidNavigator,
    invoke: (command) => command === "plugin:android-audio|get_state" ? { ...nativeState } : undefined,
  });
  const statuses = [];

  await h.evaluate("refreshAndroidPlayback({ allowRestore: true })");
  statuses.push(h.el("#statusline").textContent);
  for (const position of [0, 0, 585, 1603, 0, 0, 685]) {
    nativeState.positionMs = position;
    await h.evaluate("refreshAndroidPlayback()");
    statuses.push(h.el("#statusline").textContent);
  }

  assert.deepEqual(statuses, [
    "Starting audio", "Starting audio", "Starting audio", "Live radio",
    "Live radio", "Live radio", "Live radio", "Live radio",
  ]);

  nativeState.status = "paused";
  await h.evaluate("refreshAndroidPlayback()");
  nativeState.status = "playing";
  nativeState.positionMs = 900;
  await h.evaluate("refreshAndroidPlayback()");
  assert.equal(h.el("#statusline").textContent, "Starting audio");
  nativeState.positionMs = 901;
  await h.evaluate("refreshAndroidPlayback()");
  assert.equal(h.el("#statusline").textContent, "Live radio");
});

test("Android rebuilds a restored paused station when the user resumes after process death", async () => {
  const restored = {
    status: "paused", generation: 41, sourceUrl: "https://radio.test/stale-upstream", title: "Restored radio",
    stationId: "saved", positionMs: 1200, volume: 0.65, error: null, trackTitle: "Last track",
  };
  const h = createHarness({
    navigator: androidNavigator,
    invoke: (command, args) => {
      if (command === "plugin:android-audio|get_state") return { ...restored };
      if (command === "plugin:android-audio|stop") {
        return { ...restored, status: "idle", positionMs: 0, trackTitle: null };
      }
      if (command === "probe_stream") {
        return { url: "https://radio.test/fresh-upstream", hls: false };
      }
      if (command === "plugin:android-audio|play") {
        return {
          status: "buffering", generation: args.payload.generation, sourceUrl: args.payload.sourceUrl,
          title: args.payload.title, stationId: args.payload.stationId, positionMs: 0,
          volume: args.payload.volume, error: null, trackTitle: null,
        };
      }
      return undefined;
    },
  });
  h.evaluate("state = { stations: [{ id: 'saved', name: 'Saved station', url: 'https://radio.test/original' }], alarms: [], settings: { volume: 0.8 } }");

  await h.evaluate("refreshAndroidPlayback({ allowRestore: true })");
  assert.equal(h.evaluate("player.paused"), true);
  assert.equal(h.evaluate("player.source.subtitle"), "Paused — press Play to resume");
  assert.equal(h.calls.some(({ command }) => command === "plugin:android-audio|play" || command === "plugin:android-audio|stop"), false);

  h.evaluate("resumePlayback()");
  await flush();

  assert.equal(h.calls.some(({ command }) => command === "plugin:android-audio|resume"), false);
  assert.equal(h.calls.filter(({ command }) => command === "plugin:android-audio|pause").length, 1);
  assert.equal(h.calls.some(({ command }) => command === "plugin:android-audio|stop"), false);
  assert.equal(h.calls.find(({ command }) => command === "probe_stream").args.url, "https://radio.test/original");
  assert.equal(h.calls.find(({ command }) => command === "relay_url").args.url, "https://radio.test/fresh-upstream");
  const replay = h.calls.find(({ command }) => command === "plugin:android-audio|play");
  assert.equal(replay.args.payload.url, "http://127.0.0.1/relay/https%3A%2F%2Fradio.test%2Ffresh-upstream");
  assert.equal(replay.args.payload.sourceUrl, "https://radio.test/fresh-upstream");
  assert.equal(replay.args.payload.stationId, "saved");
  assert.equal(h.evaluate("player.source.nativeRestored"), false);
});
