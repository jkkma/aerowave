const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const source = readFileSync(path.join(__dirname, "../../src/orb-render-loop.js"), "utf8");
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);

function setup(createOrbRenderLoop, { hidden = false, reduced = false } = {}) {
  let timestamp = 1000;
  let callback = null;
  let draws = 0;
  let pauses = 0;
  const steps = [];
  const loopChanges = [];
  const renderer = {
    setAnimationLoop(fn) {
      callback = fn;
      loopChanges.push(fn ? "start" : "stop");
    },
  };
  const loop = createOrbRenderLoop({
    renderer,
    draw: () => { draws++; },
    step: (dt) => { steps.push(dt); },
    visible: () => !hidden,
    reducedMotion: () => reduced,
    now: () => timestamp,
    pause: () => { pauses++; },
  });
  return {
    loop,
    get callback() { return callback; },
    get draws() { return draws; },
    get pauses() { return pauses; },
    steps,
    loopChanges,
    setTime: (value) => { timestamp = value; },
    setHidden: (value) => { hidden = value; },
    setReduced: (value) => { reduced = value; },
  };
}

test("visible animation follows late frames without a 50 ms slowdown", async () => {
  const { createOrbRenderLoop } = await modulePromise;
  const h = setup(createOrbRenderLoop);
  h.loop.sync();
  assert.deepEqual(h.loopChanges, ["start"]);
  h.callback(1016);
  h.callback(1086);
  h.callback(1286);
  h.callback(1786);
  assert.deepEqual(h.steps.map(dt => Math.round(dt * 1000)), [16, 70, 200, 250]);
  assert.equal(h.draws, 4);
});

test("hiding stops GPU redraws and showing restarts from the current time", async () => {
  const { createOrbRenderLoop } = await modulePromise;
  const h = setup(createOrbRenderLoop);
  h.loop.sync();
  const pending = h.callback;
  pending(1040);
  h.setHidden(true);
  pending(2000); // A pending animation callback can arrive after visibility changes.
  assert.deepEqual(h.loopChanges, ["start", "stop"]);
  assert.equal(h.draws, 1);
  h.loop.redraw();
  assert.equal(h.draws, 1);

  h.setTime(10000);
  h.setHidden(false);
  h.loop.sync();
  h.callback(10016);
  assert.deepEqual(h.loopChanges, ["start", "stop", "start"]);
  assert.deepEqual(h.steps.map(dt => Math.round(dt * 1000)), [40, 16]);
  assert.equal(h.draws, 2);
});

test("reduced motion renders once, redraws changed artwork, and resumes motion", async () => {
  const { createOrbRenderLoop } = await modulePromise;
  const h = setup(createOrbRenderLoop, { reduced: true });
  h.loop.sync();
  assert.equal(h.callback, null);
  assert.equal(h.draws, 1);
  h.loop.redraw(); // Called after a new texture replaces the current artwork.
  assert.equal(h.draws, 2);

  h.setReduced(false);
  h.loop.sync();
  h.callback(1020);
  assert.equal(h.draws, 3);
  h.setReduced(true);
  h.loop.sync();
  assert.deepEqual(h.loopChanges, ["start", "stop"]);
  assert.equal(h.draws, 4);
  assert.equal(h.pauses, 2);
});

test("spin and kick travel the same distance with even or uneven frames", async () => {
  const { advanceOrbMotion } = await modulePromise;
  const first = { spin: 0.26, kick: -6.5 };
  const second = { ...first };
  const wholeTurn = advanceOrbMotion(first, -0.7, 0.2, 1.5, 8);
  const splitTurn = [0.02, 0.07, 0.11]
    .reduce((sum, dt) => sum + advanceOrbMotion(second, -0.7, dt, 1.5, 8), 0);
  assert.ok(Math.abs(first.spin - second.spin) < 1e-12);
  assert.ok(Math.abs(first.kick - second.kick) < 1e-12);
  assert.ok(Math.abs(wholeTurn - splitTurn) < 1e-12);
});
