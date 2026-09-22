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

test("changing playback during the final sleep fade starts fading the new source", async () => {
  let elapsed = 1000;
  const h = powerHarness({ performance: { now: () => elapsed } });
  h.context.now = 100000;
  h.evaluate("Date.now = () => now");
  await h.evaluate(`play({ kind: "folder", path: "/music/old.mp3", url: "asset:///music/old.mp3",
    title: "Old song" }, { volume: 0.8 })`);
  h.context.sleepState = {
    revision: 1, sampledAtMs: 1000,
    timer: { minutes: 15, action: "stop", endsAtMs: 110000, remainingMs: 10000, executeAtMs: null },
    outcome: null, error: null,
  };
  h.evaluate("applySleepSnapshot(sleepState)");
  const oldFade = h.evaluate("player.fadeTimer");
  elapsed = 6000;
  await h.fireTimer(oldFade);
  const fadedVolume = h.audios[0].volume;
  await h.evaluate(`play({ kind: "folder", path: "/music/new.mp3", url: "asset:///music/new.mp3",
    title: "New song" }, { volume: 0.8 })`);
  const newFade = h.evaluate("player.fadeTimer");
  assert.equal(h.evaluate("sleepFading"), true);
  assert.ok(newFade && newFade !== oldFade);
  assert.ok(h.audios[0].volume <= fadedVolume);
  elapsed = 8000;
  await h.fireTimer(newFade);
  assert.ok(h.audios[0].volume < fadedVolume);
  assert.equal(h.evaluate("player.source?.title"), "New song");
});

for (const elapsedAtResolution of [8000, 12000]) {
  test(`a slow replacement ${elapsedAtResolution < 10000 ? "stays faded when ready" : "cannot start after sleep expiry"}`, async () => {
    const route = deferred();
    let elapsed = 1000;
    let plays = 0;
    const h = powerHarness({
      performance: { now: () => elapsed },
      onPlay: () => { plays++; },
      invoke: (command, args) => command === "local_file_url" && args.path === "/music/new.mp3"
        ? route.promise : undefined,
    });
    h.context.now = 100000;
    h.evaluate("Date.now = () => now");
    await h.evaluate(`play({ kind: "folder", path: "/music/old.mp3", url: "asset:///music/old.mp3",
      title: "Old song" }, { volume: 0.8 })`);
    h.context.sleepState = {
      revision: 1, sampledAtMs: 1000,
      timer: { minutes: 15, action: "stop", endsAtMs: 110000, remainingMs: 10000, executeAtMs: null },
      outcome: null, error: null,
    };
    h.evaluate("applySleepSnapshot(sleepState)");
    elapsed = 6000;
    await h.fireTimer(h.evaluate("player.fadeTimer"));
    const fadedVolume = h.audios[0].volume;
    const starting = h.evaluate(`play({ kind: "folder", path: "/music/new.mp3",
      url: "asset:///music/new.mp3", title: "New song" }, { volume: 0.8 })`);
    await flush();
    assert.ok(h.audios[0].volume <= fadedVolume);
    assert.equal(plays, 1);
    elapsed = elapsedAtResolution;
    route.resolve("asset:///music/new.mp3");
    await starting;
    if (elapsedAtResolution < 10000) {
      assert.equal(plays, 2);
      assert.ok(h.audios[0].volume <= fadedVolume);
      assert.ok(h.evaluate("player.fadeTimer"));
    } else {
      assert.equal(plays, 1);
      assert.equal(h.evaluate("player.source"), null);
      assert.equal(h.audios[0].paused, true);
    }
  });
}

test("desktop sleep fade follows monotonic samples across wall-clock changes", async () => {
  let elapsed = 1000;
  const h = powerHarness({ performance: { now: () => elapsed } });
  h.context.now = 100000;
  h.evaluate("Date.now = () => now; player.source = { kind: 'folder' }; player.target = 0.8; audio.volume = 0.8");
  h.context.first = {
    revision: 7, sampledAtMs: 1000,
    timer: { minutes: 15, action: "stop", endsAtMs: 115000, remainingMs: 15000, executeAtMs: null },
    outcome: null, error: null,
  };
  h.evaluate("applySleepSnapshot(first)");
  const fade = h.evaluate("player.fadeTimer");
  elapsed = 6000;
  h.context.now += 3600000;
  h.evaluate("renderSleepTimer()");
  assert.match(h.el("#sleep-left").textContent, /10s left/);
  await h.fireTimer(fade);
  assert.ok(h.audios[0].volume < 0.8);

  h.context.fresher = { ...h.context.first, sampledAtMs: 2000,
    timer: { ...h.context.first.timer, endsAtMs: h.context.now + 9000, remainingMs: 9000 } };
  h.evaluate("applySleepSnapshot(fresher)");
  assert.match(h.el("#sleep-left").textContent, /9s left/);
  assert.equal(h.evaluate("player.fadeTimer"), fade);
  h.context.older = { ...h.context.fresher, sampledAtMs: 1500,
    timer: { ...h.context.fresher.timer, remainingMs: 90000 } };
  h.evaluate("applySleepSnapshot(older)");
  assert.match(h.el("#sleep-left").textContent, /9s left/);
});

test("desktop power countdown uses monotonic samples and rejects an older sample", () => {
  let elapsed = 1000;
  const h = powerHarness({ performance: { now: () => elapsed } });
  h.context.now = 100000;
  h.evaluate("Date.now = () => now");
  h.context.first = {
    revision: 7, sampledAtMs: 1000,
    timer: { minutes: 15, action: "sleep", endsAtMs: 100000, remainingMs: 0,
      executeAtMs: 130000, executeRemainingMs: 30000 },
    outcome: null, error: null,
  };
  h.evaluate("applySleepSnapshot(first)");
  elapsed = 6000;
  h.context.now += 3600000;
  h.evaluate("renderSleepTimer()");
  assert.equal(h.el("#power-seconds").textContent, "25s");
  h.context.older = { ...h.context.first, sampledAtMs: 500,
    timer: { ...h.context.first.timer, executeRemainingMs: 90000 } };
  h.evaluate("applySleepSnapshot(older)");
  assert.equal(h.el("#power-seconds").textContent, "25s");
  h.context.fresher = { ...h.context.first, sampledAtMs: 2000,
    timer: { ...h.context.first.timer, executeAtMs: h.context.now + 22000, executeRemainingMs: 22000 } };
  h.evaluate("applySleepSnapshot(fresher)");
  assert.equal(h.el("#power-seconds").textContent, "22s");
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

test("a wake alarm replaces the sleep fade and keeps its own fade through delayed timer updates", async () => {
  const cancellation = deferred();
  const h = powerHarness({ invoke: command => command === "cancel_sleep_timer"
    ? cancellation.promise : undefined });
  h.context.now = 100000;
  h.evaluate(`Date.now = () => now;
    player.source = { kind: "folder", title: "Bedtime music" };
    player.target = 0.55;
    audio.volume = 0.55`);
  const timer = { minutes: 15, action: "sleep", endsAtMs: 110000, executeAtMs: null };

  snapshot(h, 1, timer);
  const sleepFade = h.evaluate("player.fadeTimer");
  assert.equal(h.evaluate("sleepFading"), true);
  h.audios[0].volume = 0.1;

  snapshot(h, 2, { ...timer, executeAtMs: 140000 });
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.evaluate("sleepFading"), false);
  assert.equal(h.timers.has(sleepFade), false);

  h.evaluate(`onAlarmFire({
    alarmId: "wake", trigger: "scheduled", kind: "folder", path: "wake.mp3", folder: "music",
    title: "Wake", snoozeMins: 10, hour: 7, minute: 0, volume: 0.9, fadeSecs: 20,
    autoStopMins: 0
  })`);
  await flush();
  const alarmFade = h.evaluate("player.fadeTimer");
  assert.notEqual(alarmFade, sleepFade);
  assert.equal(h.audios[0].volume, 0.02);
  assert.equal(h.evaluate("player.target"), 0.9);

  snapshot(h, 3, null, "finished");
  assert.equal(h.evaluate("player.source.title"), "Wake");
  assert.equal(h.evaluate("player.fadeTimer"), alarmFade);
  assert.equal(h.audios[0].paused, false);

  cancellation.resolve({ revision: 4, timer: null, outcome: "cancelled", error: null });
  await flush();
  assert.equal(h.evaluate("player.source.title"), "Wake");
  assert.equal(h.evaluate("player.fadeTimer"), alarmFade);
  h.context.now = 110000;
  await h.fireTimer(alarmFade);
  assert.ok(h.audios[0].volume > 0.02 && h.audios[0].volume < 0.9);
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

test("persistent alarm wake status clears with the preference and does not claim a failed request succeeded", async () => {
  let error = null;
  const h = powerHarness({ invoke: command => command === "power_status"
    ? { ...capabilities, stayingAwake: true, error } : undefined });
  await h.evaluate("refreshPowerStatus()");
  assert.match(h.el("#power-status").textContent, /Keeping this PC awake after an alarm/);
  error = "Windows refused the keep-awake request";
  await h.evaluate("refreshPowerStatus()");
  assert.doesNotMatch(h.el("#power-status").textContent, /Keeping this PC awake/);
  assert.match(h.el("#power-status").textContent, /Windows refused/);
  error = null;
  h.evaluate("state.settings.wakeForAlarms = false");
  await h.evaluate("refreshPowerStatus()");
  assert.doesNotMatch(h.el("#power-status").textContent, /Keeping this PC awake/);
});

test("a completed scheduler update replaces a previously successful wake status", async () => {
  let status = { ...capabilities, armedAtMs: 1789019005000 };
  let h;
  h = powerHarness({ invoke: command => {
    if (command === "local_time") return wallTime;
    if (command === "get_state") {
      assert.equal(h.listeners.get("power-status-updated").length, 1);
      return { stations: [], alarms: [], settings: {} };
    }
    if (command === "power_status") return status;
  } });
  await h.evaluate("boot()");
  await flush();
  assert.match(h.el("#power-status").textContent, /Wake timer armed for/);

  status = { ...capabilities, error: "Windows refused the wake timer" };
  await h.emit("power-status-updated", null);
  assert.doesNotMatch(h.el("#power-status").textContent, /Wake timer armed for/);
  assert.match(h.el("#power-status").textContent, /Windows refused the wake timer/);
  assert.equal(h.el("#power-status").classList.contains("bad"), true);
});

test("wake capabilities refresh periodically when Windows power policy changes", async () => {
  let status = capabilities;
  const h = powerHarness({ invoke: command => {
    if (command === "local_time") return wallTime;
    if (command === "get_state") return { stations: [], alarms: [], settings: {} };
    if (command === "power_status") return status;
  } });
  await h.evaluate("boot()");
  await flush();
  assert.equal(h.el("#power-status").classList.contains("bad"), false);

  status = { ...capabilities, onBattery: true, wakeAllowed: false, message: "Wake timers are blocked on battery." };
  const periodic = [...h.timers].find(([, timer]) => timer.interval && timer.fn === h.evaluate("refreshPowerStatus"));
  assert.ok(periodic, "boot installs periodic power capability refresh");
  assert.equal(periodic[1].ms, 20000);
  await h.fireTimer(periodic[0]);
  assert.match(h.el("#power-status").textContent, /blocked on battery/);
  assert.equal(h.el("#power-status").classList.contains("bad"), true);
  assert.equal(h.evaluate("powerStatus.onBattery"), true);
});

test("focus and becoming visible refresh power status after returning to the app", async () => {
  let status = capabilities;
  const h = powerHarness({ invoke: command => command === "power_status" ? status : undefined });
  h.evaluate("wire()");
  await h.evaluate("refreshPowerStatus()");
  status = { ...capabilities, wakeAllowed: false, message: "Wake timers are blocked." };
  await h.context.window.dispatch("focus");
  assert.match(h.el("#power-status").textContent, /Wake timers are blocked/);

  status = capabilities;
  const before = h.calls.filter(call => call.command === "power_status").length;
  h.document.hidden = true;
  await h.document.dispatch("visibilitychange");
  assert.equal(h.calls.filter(call => call.command === "power_status").length, before);
  h.document.hidden = false;
  await h.document.dispatch("visibilitychange");
  await flush();
  assert.match(h.el("#power-status").textContent, /Wake timers allowed/);
  assert.equal(h.el("#power-status").classList.contains("bad"), false);
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
