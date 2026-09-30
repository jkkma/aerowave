const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

function fire(h, changes = {}) {
  h.context.payload = {
    alarmId: "wake", occurrenceId: "10", kind: "none", trigger: "scheduled",
    volume: 0.65, snoozeMins: 5, autoStopMins: 1, autoSnoozes: 0,
    hour: 7, minute: 0, label: "Morning", note: "No playable backup", ...changes,
  };
  h.evaluate("onAlarmFire(payload)");
  return flush();
}

const calls = (h, name) => h.calls.filter(({ command }) => command === name);

test("missing source and backup use the native offline tone for this occurrence", async () => {
  const h = createHarness();
  await fire(h);
  assert.deepEqual(calls(h, "start_emergency_tone").map(({ args }) => ({ ...args })),
    [{ alarmId: "wake", occurrenceId: "10" }]);
  assert.equal(h.evaluate("ringing.emergencyTone"), "playing");
  assert.equal(h.el("#ring-source").textContent, "Built-in emergency tone");
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.evaluate("ringWatchdog"), null);
});

test("radio failure followed by an unusable backup starts the native tone", async () => {
  const h = createHarness({ invoke: command => command === "backup_track"
    ? Promise.reject(new Error("Backup folder unavailable")) : undefined });
  await fire(h, { kind: "station", url: "https://radio.test/dead", title: "Radio" });
  h.evaluate('failure("network lost")');
  await flush();
  assert.equal(calls(h, "backup_track").length, 1);
  assert.equal(calls(h, "start_emergency_tone").length, 1);
  assert.equal(h.evaluate("ringing.emergencyTone"), "playing");
});

test("four failed backup tracks advance to the tone without an endless retry loop", async () => {
  const h = createHarness({ invoke: command => command === "backup_track"
    ? { path: "/backup/dead.mp3", name: "Dead.mp3", total: 1 } : undefined });
  await fire(h, { kind: "folder", path: "/backup/dead.mp3", title: "Dead" });
  for (let i = 0; i < 4; i++) {
    h.evaluate('failure("decode failed")');
    await flush();
  }
  assert.equal(calls(h, "backup_track").length, 3);
  assert.equal(calls(h, "start_emergency_tone").length, 1);
});

test("repeated failures do not restart an already pending native tone", async () => {
  const pending = deferred();
  const h = createHarness({ invoke: command => command === "start_emergency_tone" ? pending.promise : undefined });
  await fire(h);
  h.evaluate('goSilent("another callback")');
  assert.equal(calls(h, "start_emergency_tone").length, 1);
  pending.resolve();
  await flush();
  h.evaluate('goSilent("another callback")');
  assert.equal(calls(h, "start_emergency_tone").length, 1);
});

for (const action of ["dismissRing()", "snoozeRing()"])
  test(`a late tone reply cannot reopen the card after ${action}`, async () => {
    const pending = deferred();
    const h = createHarness({ invoke: command => command === "start_emergency_tone" ? pending.promise : undefined });
    await fire(h);
    await h.evaluate(action);
    pending.resolve();
    await flush();
    assert.equal(h.evaluate("ringing"), null);
    assert.equal(h.el("#ringing").hidden, true);
    assert.equal(calls(h, "start_emergency_tone").length, 1);
  });

test("a stale tone failure cannot overwrite a replacement alarm", async () => {
  const pending = deferred();
  const h = createHarness({ invoke: command => command === "start_emergency_tone" ? pending.promise : undefined });
  await fire(h);
  await fire(h, { occurrenceId: "11", kind: "station", title: "Replacement", url: "https://radio.test/new" });
  pending.reject(new Error("old output failed"));
  await flush();
  assert.equal(h.el("#ring-source").textContent, "Station · Replacement");
  assert.equal(h.evaluate("ringing.occurrenceId"), "11");
});

test("native output failure remains a visible no-sound warning", async () => {
  const h = createHarness({ invoke: command => command === "start_emergency_tone"
    ? Promise.reject(new Error("No output device")) : undefined });
  await fire(h);
  assert.equal(h.evaluate("ringing.emergencyTone"), "failed");
  assert.equal(h.el("#ring-trigger").textContent, "Alarm · no sound");
  assert.match(h.el("#ring-note").textContent, /No output device/);
});

test("automatic completion fades the native tone before dismissing", async () => {
  const h = createHarness();
  await fire(h);
  await h.fireTimer(h.evaluate("autoStopTimer"));
  await flush();
  assert.equal(calls(h, "fade_emergency_tone")[0].args.milliseconds, 6000);
  assert.equal(calls(h, "dismiss_alarm").length, 0);
  await h.fireTimer(h.evaluate("autoStopTimer"));
  await flush();
  assert.equal(calls(h, "dismiss_alarm").length, 1);
  assert.equal(h.evaluate("ringing"), null);
});

test("failed automatic dismissal restores native tone volume for manual control", async () => {
  const h = createHarness({ invoke: command => command === "dismiss_alarm"
    ? Promise.reject(new Error("Try again")) : undefined });
  await fire(h);
  await h.fireTimer(h.evaluate("autoStopTimer"));
  await h.fireTimer(h.evaluate("autoStopTimer"));
  await flush();
  assert.equal(calls(h, "set_emergency_tone_volume")[0].args.volume, 0.65);
  assert.equal(h.evaluate("givingUp"), false);
  assert.equal(h.evaluate("ringing.occurrenceId"), "10");
});

test("a restored tone ring does not retry the failed station or backup", async () => {
  const h = createHarness();
  await fire(h, { kind: "tone" });
  assert.equal(calls(h, "start_emergency_tone").length, 1);
  assert.equal(calls(h, "backup_track").length, 0);
  assert.equal(calls(h, "relay_url").length, 0);
});
