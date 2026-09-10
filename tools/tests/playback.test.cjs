const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

function folderHarness(pick) {
  return createHarness({ invoke: (command) => command === "random_track" ? pick.promise : undefined });
}

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
  assert.notEqual(h.el("#status-msg").textContent, "OLD WARNING");
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
