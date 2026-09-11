const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush, Element } = require("./frontend-harness.cjs");

const capabilities = {
  sleepSupported: true, shutdownSupported: true, wakeSupported: true,
  wakeAllowed: true, onBattery: false, message: "Wake timers allowed.", armedAtMs: null, error: null,
};
const wallTime = { year: 2026, month: 9, day: 11, hour: 7, minute: 30, second: 0 };

function powerHarness(options = {}) {
  const h = createHarness(options);
  h.el("#sleep-action").value = "stop";
  const chips = [0, 15, 30].map(minutes => {
    const chip = new Element("button");
    chip.dataset.mins = String(minutes);
    return chip;
  });
  h.queries.set("#sleep-chips .chip", chips);
  return h;
}

function snapshot(h, revision, timer = null, outcome = null) {
  h.context.snapshotFixture = { revision, timer, outcome, error: null };
  h.evaluate("applySleepSnapshot(snapshotFixture)");
}

function pendingPower(h, revision = 10, action = "shutdown") {
  snapshot(h, revision, { minutes: 15, action, endsAtMs: Date.now(), executeAtMs: Date.now() + 30000 });
}

test("changing the saved sleep action leaves an active timer's captured action until restart", async () => {
  const h = powerHarness();
  h.evaluate("wire()");
  h.el("#sleep-action").value = "shutdown";
  await h.evaluate("setSleep(15)");
  h.el("#sleep-action").value = "sleep";
  await h.el("#sleep-action").dispatch("change");
  assert.equal(h.evaluate("state.settings.sleepTimerAction"), "sleep");
  assert.equal(h.evaluate("sleepSnapshot.timer.action"), "shutdown");
  assert.match(h.el("#sleep-hint").textContent, /This timer: Shut down PC/);
  assert.equal(h.calls.filter(c => c.command === "set_sleep_timer").length, 1);
  await h.evaluate("setSleep(30)");
  assert.equal(h.evaluate("sleepSnapshot.timer.action"), "sleep");
  assert.equal(h.evaluate("sleepSnapshot.timer.minutes"), 30);
});

test("cancelling during the final fade restores the listening volume", async () => {
  const h = powerHarness({ invoke: command => command === "cancel_sleep_timer"
    ? { revision: 2, timer: null, outcome: "cancelled", error: null } : undefined });
  h.evaluate('player.source = {kind:"folder"}; player.target = 0.8; audio.volume = 0.8');
  snapshot(h, 1, { minutes: 15, action: "sleep", endsAtMs: Date.now() + 10000, executeAtMs: null });
  assert.equal(h.evaluate("sleepFading"), true);
  const fade = h.evaluate("player.fadeTimer");
  h.audios[0].volume = 0.2;
  await h.evaluate("setSleep(0)");
  assert.equal(h.audios[0].volume, 0.8);
  assert.equal(h.evaluate("sleepFading"), false);
  assert.equal(h.timers.has(fade), false);
});

test("power countdown stops ordinary audio and keeps keyboard focus on Cancel", async () => {
  const h = powerHarness();
  h.evaluate('wire(); player.source = {kind:"folder"}');
  h.el("#sleep-action").focus();
  pendingPower(h);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.el("#power-countdown").open, true);
  assert.equal(h.document.activeElement, h.el("#power-cancel"));
  for (const shiftKey of [false, true]) {
    const event = await h.el("#power-countdown").dispatch("keydown", { key: "Tab", shiftKey });
    assert.equal(event.defaultPrevented, true);
    assert.equal(h.document.activeElement, h.el("#power-cancel"));
  }
  snapshot(h, 11, null, "cancelled");
  assert.equal(h.el("#power-countdown").open, false);
  assert.equal(h.document.activeElement, h.el("#sleep-action"));
});

test("a rejected Escape cancellation leaves the power countdown and a visible error", async () => {
  const h = powerHarness({ invoke: command => command === "cancel_sleep_timer"
    ? Promise.reject(new Error("Windows power action has already started")) : undefined });
  h.evaluate("wire()");
  pendingPower(h);
  const event = await h.el("#power-countdown").dispatch("cancel");
  await flush();
  assert.equal(event.defaultPrevented, true);
  assert.equal(h.el("#power-countdown").open, true);
  assert.match(h.el("#power-error").textContent, /Could not cancel:.*already started/);
  assert.equal(h.el("#power-error").classList.contains("hidden"), false);
});

test("an older set-timer reply cannot reopen power after a newer cancellation event", async () => {
  const request = deferred();
  const h = powerHarness({ invoke: command => command === "set_sleep_timer" ? request.promise : undefined });
  const setting = h.evaluate("setSleep(15)");
  snapshot(h, 3, null, "cancelled");
  request.resolve({ revision: 2, timer: { minutes: 15, action: "shutdown", endsAtMs: Date.now(), executeAtMs: Date.now() + 30000 }, outcome: null, error: null });
  await setting;
  assert.equal(h.evaluate("sleepSnapshot.timer"), null);
  assert.equal(!!h.el("#power-countdown").open, false);
  assert.doesNotMatch(h.el("#status-msg").textContent, /in 15 minutes/);
});

test("an alarm closes power UI, refuses another timer, and survives a late finish event", async () => {
  const h = powerHarness();
  pendingPower(h);
  h.evaluate('onAlarmFire({alarmId:"wake",trigger:"scheduled",kind:"folder",path:"wake.mp3",folder:"music",title:"Wake",snoozeMins:10,hour:7,minute:0,volume:0.8,fadeSecs:0})');
  await flush();
  assert.equal(h.el("#power-countdown").open, false);
  assert.equal(h.document.activeElement, h.el("#ring-dismiss"));
  assert.equal(h.el("#ringing").hidden, false);
  await h.evaluate("setSleep(15)");
  assert.equal(h.calls.some(c => c.command === "set_sleep_timer"), false);
  snapshot(h, 11, null, "finished");
  assert.equal(h.evaluate("player.source.title"), "Wake");
  assert.equal(h.audios[0].paused, false);
});

test("dismissing a test releases only the backend preview", async () => {
  const h = powerHarness();
  h.evaluate('onAlarmFire({alarmId:"saved",trigger:"test",kind:"none",snoozeMins:10,hour:7,minute:0})');
  await h.evaluate("snoozeRing()");
  assert.equal(h.el("#ringing").hidden, false);
  await h.evaluate("dismissRing()");
  assert.equal(h.calls.filter(c => c.command === "dismiss_test_alarm").length, 1);
  assert.equal(h.calls.some(c => c.command === "dismiss_alarm" || c.command === "snooze_alarm"), false);
});

test("rejected preview dismissal keeps the test available for retry", async () => {
  const h = powerHarness({ invoke: command => command === "dismiss_test_alarm" ? Promise.reject(new Error("busy")) : undefined });
  h.evaluate('onAlarmFire({alarmId:"saved",trigger:"test",kind:"none",snoozeMins:10,hour:7,minute:0})');
  await h.evaluate("dismissRing()");
  assert.equal(h.el("#ringing").hidden, false);
  assert.match(h.el("#ring-note").textContent, /Could not dismiss.*busy/);
});

test("boot subscribes before restoring a pending power countdown", async () => {
  let h;
  h = powerHarness({ invoke: command => {
    if (command === "local_time") return wallTime;
    if (command === "get_state") return { stations: [], alarms: [], settings: { sleepTimerAction: "sleep" } };
    if (command === "get_sleep_timer") {
      assert.equal(h.listeners.get("sleep-timer-updated").length, 1);
      return { revision: 7, timer: { minutes: 15, action: "sleep", endsAtMs: Date.now(), executeAtMs: Date.now() + 30000 }, outcome: null, error: null };
    }
  } });
  await h.evaluate("boot()");
  assert.equal(h.el("#power-countdown").open, true);
  await h.emit("sleep-timer-updated", { revision: 8, timer: null, outcome: "cancelled", error: null });
  assert.equal(h.el("#power-countdown").open, false);
});

test("unsupported capabilities disable PC actions while preserving stop audio", async () => {
  const h = powerHarness({ invoke: command => command === "power_status"
    ? { ...capabilities, sleepSupported: false, shutdownSupported: false, wakeSupported: false, wakeAllowed: false, message: "PC power actions are only supported on Windows." } : undefined });
  await h.evaluate("refreshPowerStatus()");
  assert.equal(h.el("#sleep-action option[value='sleep']").disabled, true);
  assert.equal(h.el("#sleep-action option[value='shutdown']").disabled, true);
  assert.equal(h.el("#wake-switch").disabled, true);
  assert.match(h.el("#power-status").textContent, /only supported on Windows/);
  await h.evaluate("setSleep(15)");
  assert.equal(h.evaluate("sleepSnapshot.timer.action"), "stop");
});

test("the wake switch saves the preference without changing startup intent", async () => {
  const h = powerHarness();
  const row = new Element("li");
  row.dataset.setting = "wakeForAlarms";
  const label = new Element("span");
  const toggle = h.el("#wake-switch");
  toggle.className = "sw";
  toggle.setAttribute("aria-pressed", "true");
  row.append(label, toggle);
  h.queries.set(".settings .row", [row]);
  h.evaluate("state.settings.wakeForAlarms = true; wire()");
  await toggle.dispatch("click");
  await h.fireTimer(h.evaluate("settingsSaveTimer"));
  await flush();
  const saved = h.calls.find(c => c.command === "save_settings");
  assert.equal(saved.args.settings.wakeForAlarms, false);
  assert.equal(saved.args.explicitAutostart, null);
  assert.match(h.el("#power-status").textContent, /Wake for alarms is off/);
});

test("wake status formats the armed deadline using OS timezone rules", async () => {
  const h = powerHarness({ invoke: command => {
    if (command === "power_status") return { ...capabilities, armedAtMs: 1789019005000 };
    if (command === "local_time") return wallTime;
  } });
  h.evaluate(`Date.prototype.getHours = () => { throw new Error("browser timezone is stale"); };
    Date.prototype.toLocaleDateString = function(locale, options) {
      if (options.timeZone !== "UTC") throw new Error("date must use UTC");
      return "Sep 11";
    }`);
  await h.evaluate("refreshPowerStatus()");
  assert.match(h.el("#power-status").textContent, /Wake timer armed for Sep 11, 07:30/);
  assert.equal(h.calls.find(c => c.command === "local_time").args.atMs, 1789019005000);
});

test("a delayed wake-time lookup cannot resurrect an alarm that is no longer armed", async () => {
  const local = deferred();
  let statuses = 0;
  const h = powerHarness({ invoke: command => {
    if (command === "power_status") return { ...capabilities, armedAtMs: ++statuses === 1 ? 1789019005000 : null };
    if (command === "local_time") return local.promise;
  } });
  const old = h.evaluate("refreshPowerStatus()");
  await flush();
  await h.evaluate("refreshPowerStatus()");
  local.resolve(wallTime);
  await old;
  assert.doesNotMatch(h.el("#power-status").textContent, /armed for/);
  assert.equal(h.evaluate("powerStatus.armedAtMs"), null);
});
