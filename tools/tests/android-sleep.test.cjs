const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush, Element } = require("./frontend-harness.cjs");

const navigator = { userAgent: "Mozilla/5.0 (Linux; Android 13; Mobile)" };
const timer = (remainingMs = 15000) => ({
  minutes: 15, action: "stop", endsAtMs: 1, executeAtMs: null, remainingMs,
});
const snapshot = (revision, active = null, outcome = null) => ({ revision, timer: active, outcome, error: null });
const native = (sleepTimer, status = "playing") => ({
  status, generation: 5, sourceUrl: "https://radio.test/live", title: "Radio",
  positionMs: 5000, volume: 0.7, sleepTimer,
});

test("Android sleep chips use the native timer and ignore saved PC power actions", async () => {
  const h = createHarness({ navigator, invoke: (command) => {
    if (command === "plugin:android-audio|set_sleep_timer") return snapshot(1, timer());
    if (command === "plugin:android-audio|cancel_sleep_timer") return snapshot(2, null, "cancelled");
  } });
  const chips = [0, 15].map(minutes => Object.assign(new Element("button"), { dataset: { mins: String(minutes) } }));
  h.queries.set("#sleep-chips .chip", chips);
  h.el("#sleep-action").value = "shutdown";
  h.evaluate("wire()");
  await chips[1].dispatch("click");
  assert.equal(h.calls[0].command, "plugin:android-audio|set_sleep_timer");
  assert.deepEqual(JSON.parse(JSON.stringify(h.calls[0].args)), { payload: { minutes: 15 } });
  assert.equal(chips[1].getAttribute("aria-pressed"), "true");
  assert.match(h.el("#sleep-hint").textContent, /screen locked/);
  assert.equal(h.el("#power-countdown").open, undefined);
  await chips[0].dispatch("click");
  assert.equal(h.calls[1].command, "plugin:android-audio|cancel_sleep_timer");
  assert.equal(chips[0].getAttribute("aria-pressed"), "true");
});

test("Android countdown uses native remaining time and never fades webview audio", () => {
  let monotonic = 1000;
  const h = createHarness({ navigator, performance: { now: () => monotonic } });
  h.context.nativeFixture = native(snapshot(2, timer()));
  h.evaluate("applyAndroidPlaybackState(nativeFixture, { allowRestore: true })");
  assert.equal(h.el("#sleep-left").textContent, "15s left");
  monotonic += 5000;
  h.evaluate("renderSleepTimer()");
  assert.equal(h.el("#sleep-left").textContent, "10s left");
  assert.equal(h.evaluate("sleepFading"), false);
  assert.equal(h.evaluate("player.fadeTimer"), null);
  assert.equal(h.audios[0].volume, 1);
  assert.equal(h.el("#volume").value, 70);
  h.context.nativeFixture = native(snapshot(2, timer(4000)));
  h.evaluate("applyAndroidPlaybackState(nativeFixture)");
  assert.equal(h.el("#sleep-left").textContent, "4s left");
});

test("returning after native expiry clears playback without sending a second Stop", async () => {
  let current = native(snapshot(1, timer()));
  const h = createHarness({ navigator, invoke: (command) => command === "plugin:android-audio|get_state" ? current : undefined });
  await h.evaluate("refreshAndroidPlayback({ allowRestore: true })");
  h.document.hidden = true;
  current = native(snapshot(2, null, "finished"), "idle");
  await h.evaluate("refreshAndroidPlayback()");
  assert.notEqual(h.evaluate("sleepSnapshot.timer"), null);
  h.document.hidden = false;
  await h.evaluate("refreshAndroidPlayback()");
  assert.equal(h.evaluate("sleepSnapshot.timer"), null);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.calls.some(c => c.command === "plugin:android-audio|stop"), false);
});

test("idle startup restores the native timer outcome before trying to restore a source", async () => {
  const h = createHarness({ navigator, invoke: (command) => command === "plugin:android-audio|get_state"
    ? native(snapshot(8, null, "finished"), "idle") : undefined });
  await h.evaluate("refreshAndroidPlayback({ allowRestore: true })");
  assert.equal(h.evaluate("sleepSnapshot.revision"), 8);
  assert.equal(h.evaluate("sleepSnapshot.outcome"), "finished");
  assert.equal(h.evaluate("player.source"), null);
});

test("a delayed native timer reply cannot restore a cancelled timer", async () => {
  const delayed = deferred();
  const h = createHarness({ navigator, invoke: command => {
    if (command === "plugin:android-audio|set_sleep_timer") return delayed.promise;
    if (command === "plugin:android-audio|cancel_sleep_timer") return snapshot(4, null, "cancelled");
  } });
  const setting = h.evaluate("setSleep(15)");
  await h.evaluate("setSleep(0)");
  delayed.resolve(snapshot(3, timer()));
  await setting;
  assert.equal(h.evaluate("sleepSnapshot.revision"), 4);
  assert.equal(h.evaluate("sleepSnapshot.timer"), null);
});

test("station replacement carries a native expiry fence across slow resolution", async () => {
  const probe = deferred();
  const h = createHarness({ navigator, invoke: (command) => {
    if (command === "plugin:android-audio|pause") return native(snapshot(7, timer()));
    if (command === "probe_stream") return probe.promise;
    if (command === "plugin:android-audio|play") return native(snapshot(8, null, "finished"), "idle");
  } });
  const playing = h.evaluate("play({ kind: 'station', url: 'https://radio.test/new', title: 'New' })");
  await flush();
  probe.resolve({ url: "https://radio.test/resolved", hls: false });
  await playing;
  const request = h.calls.find(c => c.command === "plugin:android-audio|play");
  assert.equal(request.args.payload.sleepRevision, 7);
  assert.equal(h.calls.some(c => c.command === "plugin:android-audio|stop"), false);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.evaluate("sleepSnapshot.outcome"), "finished");
});
