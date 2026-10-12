const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const source = readFileSync(path.join(__dirname, "../../src/orb-render-loop.js"), "utf8");
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);

async function setup(options = {}) {
  const { createOrbRenderLoop } = await modulePromise;
  const h = createHarness(options);
  let timestamp = 1000;
  let callback = null;
  let draws = 0;
  const steps = [];
  const changes = [];
  const loop = createOrbRenderLoop({
    renderer: { setAnimationLoop(fn) { callback = fn; changes.push(fn ? "start" : "stop"); } },
    draw: () => { draws++; },
    step: dt => steps.push(dt),
    visible: () => h.context.window.aerowaveVisuals.visible(),
    reducedMotion: () => false,
    now: () => timestamp,
    pause: () => {},
  });
  h.context.window.aerowaveOrb = { syncVisibility: loop.sync };
  loop.sync();
  h.evaluate("watchVisualVisibility()");
  return Object.assign(h, {
    loop, changes, steps,
    getCallback: () => callback,
    draws: () => draws,
    setTime: value => { timestamp = value; },
    visible: () => h.context.window.aerowaveVisuals.visible(),
    paused: () => h.document.body.classList.contains("visuals-paused"),
  });
}

test("a native hidden or minimized start stays paused until restored", async () => {
  const snapshot = deferred();
  const h = await setup({ invoke: command => command === "window_render_visible" ? snapshot.promise : undefined });
  assert.equal(h.visible(), false);
  assert.equal(h.paused(), true);
  assert.deepEqual(h.changes, []);
  snapshot.resolve(false);
  await flush();
  assert.equal(h.visible(), false);
  assert.equal(h.document.hidden, undefined); // Native hiding need not hide the document.
  h.loop.redraw();
  assert.equal(h.draws(), 0);

  await h.emit("window-render-visibility", true);
  assert.equal(h.visible(), true);
  assert.equal(h.paused(), false);
  assert.deepEqual(h.changes, ["start"]);
});

for (const restored of [false, true]) {
  test(`a late initial query cannot overwrite a newer ${restored ? "restore" : "hide"} event`, async () => {
    const snapshot = deferred();
    const h = await setup({ invoke: command => command === "window_render_visible" ? snapshot.promise : undefined });
    await flush();
    assert.equal(h.calls.filter(({ command }) => command === "window_render_visible").length, 1);
    await h.emit("window-render-visibility", restored);
    snapshot.resolve(!restored);
    await flush();
    assert.equal(h.visible(), restored);
    assert.equal(h.paused(), !restored);
    assert.deepEqual(h.changes, restored ? ["start"] : []);
  });
}

test("native and document visibility pause once and resume without catching up hidden time", async () => {
  const h = await setup();
  await flush();
  h.getCallback()(1016);
  await h.emit("window-render-visibility", true); // A visible window losing focus stays live.
  await h.context.window.dispatch("blur");
  assert.equal(h.visible(), true);
  assert.deepEqual(h.changes, ["start"]);

  const pending = h.getCallback();
  await h.emit("window-render-visibility", false);
  await h.emit("window-render-visibility", false);
  h.document.hidden = true;
  await h.document.dispatch("visibilitychange");
  pending(5000);
  h.loop.redraw(); // Artwork may arrive while the page is hidden.
  assert.equal(h.paused(), true);
  assert.equal(h.draws(), 1);
  assert.deepEqual(h.changes, ["start", "stop"]);

  h.setTime(100000);
  await h.emit("window-render-visibility", true);
  assert.equal(h.visible(), false); // Wait for the document's restore too.
  h.document.hidden = false;
  await h.document.dispatch("visibilitychange");
  await h.document.dispatch("visibilitychange");
  await h.emit("window-render-visibility", true);
  h.getCallback()(100016);
  assert.equal(h.paused(), false);
  assert.deepEqual(h.changes, ["start", "stop", "start"]);
  assert.deepEqual(h.steps.map(dt => Math.round(dt * 1000)), [16, 16]);
});

test("visual suspension preserves playing audio, fades, and the ringing watchdog", async () => {
  const h = await setup();
  await flush();
  await h.evaluate("play({ kind: 'station', url: 'https://radio.test/live', title: 'Live' }, { fadeSecs: 30 })");
  await h.audios[0].dispatch("playing");
  const audio = h.audios[0];
  const src = audio.src;
  const fade = h.evaluate("player.fadeTimer");
  await h.emit("window-render-visibility", false);
  assert.equal(audio.paused, false);
  assert.equal(audio.src, src);
  assert.equal(h.timers.has(fade), true);
  await h.fireTimer(fade);
  assert.equal(audio.paused, false);

  h.context.alarmPayload = {
    alarmId: "wake", trigger: "scheduled", kind: "folder", path: "/alarm/wake.mp3",
    title: "Alarm", snoozeMins: 10, hour: 7, minute: 0, volume: 0.9, fadeSecs: 10,
    autoStopMins: 1, autoSnoozes: 0, autoSnoozed: 0,
  };
  h.evaluate("onAlarmFire(alarmPayload)");
  await flush();
  assert.equal(h.evaluate("ringing.alarmId"), "wake");
  assert.equal(audio.paused, false);
  assert.equal(h.timers.has(h.evaluate("player.fadeTimer")), true);
  assert.equal(h.timers.has(h.evaluate("ringWatchdog")), true);
  assert.equal(h.timers.has(h.evaluate("autoStopTimer")), true);
  assert.equal(h.paused(), true);

  audio.readyState = 3;
  audio.currentTime = 1;
  await audio.dispatch("timeupdate");
  assert.ok(h.evaluate("player.lastProgress") > 0);
  await h.emit("window-render-visibility", true);
  assert.equal(h.paused(), false);
  assert.equal(audio.paused, false);
  assert.equal(h.evaluate("ringing.alarmId"), "wake");
});

test("Android uses document visibility without desktop window IPC", async () => {
  const h = await setup({ navigator: { userAgent: "Android" } });
  assert.equal(h.visible(), true);
  assert.equal(h.calls.some(({ command }) => command === "window_render_visible"), false);
  assert.equal(h.listeners.has("window-render-visibility"), false);
  h.document.hidden = true;
  await h.document.dispatch("visibilitychange");
  assert.equal(h.paused(), true);
  h.document.hidden = false;
  await h.document.dispatch("visibilitychange");
  assert.equal(h.paused(), false);
});
