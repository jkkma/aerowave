const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const androidNavigator = { userAgent: "Mozilla/5.0 (Linux; Android 15; Mobile)" };
const firstAtMs = Date.UTC(2031, 1, 3, 4, 5);
const followingAtMs = Date.UTC(2031, 1, 4, 6, 7);

function makeAlarm(overrides = {}) {
  return {
    id: "wake", label: "Morning", hour: 7, minute: 0, days: [0, 1, 2, 3, 4, 5, 6],
    enabled: true, source: { kind: "folder", path: "/music" }, ...overrides,
  };
}

function row(h, id = "wake") {
  return h.el("#alarm-list").children.find((item) => item.dataset.id === id);
}

function skipButton(h, id = "wake") {
  return row(h, id)?.querySelector(".alarm-skip") || null;
}

function text(node) {
  return node.textContent + node.children.map(text).join("");
}

function powerStatus() {
  return {
    sleepSupported: true, shutdownSupported: true, wakeSupported: true,
    wakeAllowed: true, onBattery: false, message: "Wake timers allowed.", armedAtMs: null, error: null,
  };
}

function androidSnapshot(revision, alarms, occurrences) {
  return {
    initialized: true, revision, alarms, occurrences, next: null, ringing: null,
    permissions: { exact: "granted", notifications: "granted", fullScreen: "granted", batteryOptimized: false },
    error: null,
  };
}

test("skip and undo target the exact native occurrence and show OS-local times", async () => {
  let skipped = false;
  let alarms = [makeAlarm()];
  const h = createHarness({ invoke: (command, args) => {
    if (command === "alarm_occurrences") return [{
      alarmId: "wake", nextAtMs: skipped ? followingAtMs : firstAtMs,
      skippedAtMs: skipped ? firstAtMs : null,
    }];
    if (command === "local_time") return args.atMs === firstAtMs
      ? { year: 2031, month: 2, day: 3, hour: 4, minute: 5, second: 0 }
      : { year: 2031, month: 2, day: 4, hour: 6, minute: 7, second: 0 };
    if (command === "skip_alarm") {
      skipped = args.skip;
      alarms = alarms.map((alarm) => ({ ...alarm, skipDate: skipped ? "2031-02-03" : null }));
      return alarms;
    }
    if (command === "next_alarm") return null;
    if (command === "power_status") return powerStatus();
  } });
  h.context.alarms = alarms;
  h.evaluate("state.alarms = alarms; renderAlarms()");
  h.evaluate(`
    for (const name of ["getFullYear", "getMonth", "getDate", "getHours", "getMinutes"]) {
      Date.prototype[name] = () => { throw new Error("browser-local time used"); };
    }
    Date.prototype.toLocaleDateString = function(_locale, options) {
      if (options.timeZone !== "UTC") throw new Error("UTC formatting required");
      return "OS DATE " + this.toISOString().slice(0, 10);
    };
  `);

  await h.evaluate("refreshAlarmOccurrences()");
  assert.match(text(row(h).querySelector(".alarm-occurrence")), /OS DATE 2031-02-03 at 04:05/);
  assert.deepEqual(h.calls.filter((call) => call.command === "local_time").map((call) => call.args.atMs), [firstAtMs]);

  await skipButton(h).dispatch("click");
  const skip = h.calls.find((call) => call.command === "skip_alarm");
  assert.deepEqual(JSON.parse(JSON.stringify(skip.args)), { id: "wake", skip: true, expectedAtMs: firstAtMs });
  assert.equal(skipButton(h).textContent, "Undo skip");
  assert.match(text(row(h).querySelector(".alarm-occurrence")), /Skipping OS DATE 2031-02-03 at 04:05/);
  assert.match(text(row(h).querySelector(".alarm-occurrence")), /Next: OS DATE 2031-02-04 at 06:07/);

  await skipButton(h).dispatch("click");
  const undo = h.calls.filter((call) => call.command === "skip_alarm")[1];
  assert.deepEqual(JSON.parse(JSON.stringify(undo.args)), { id: "wake", skip: false, expectedAtMs: firstAtMs });
  assert.equal(skipButton(h).textContent, "Skip next");
  const formattedTimes = new Set(h.calls.filter((call) => call.command === "local_time").map((call) => call.args.atMs));
  assert.ok(formattedTimes.has(firstAtMs));
  assert.ok(formattedTimes.has(followingAtMs));
});

test("only enabled recurring alarms expose skip controls", async () => {
  const enabled = makeAlarm();
  const disabled = makeAlarm({ id: "disabled", enabled: false });
  const oneShot = makeAlarm({ id: "once", days: [] });
  const h = createHarness({ invoke: (command) => command === "alarm_occurrences" ? [
    { alarmId: enabled.id, nextAtMs: firstAtMs, skippedAtMs: null },
    { alarmId: disabled.id, nextAtMs: firstAtMs, skippedAtMs: null },
    { alarmId: oneShot.id, nextAtMs: firstAtMs, skippedAtMs: null },
  ] : command === "local_time" ? { year: 2031, month: 2, day: 3, hour: 4, minute: 5 } : undefined });
  h.context.alarms = [enabled, disabled, oneShot];
  h.evaluate("state.alarms = alarms; renderAlarms()");

  await h.evaluate("refreshAlarmOccurrences()");
  assert.ok(skipButton(h, "wake"));
  assert.equal(skipButton(h, "disabled"), null);
  assert.equal(skipButton(h, "once"), null);
});

test("undo stays enabled when the skipped occurrence has no following ring", async () => {
  let skipped = true;
  let alarms = [makeAlarm({ skipDate: "2031-02-03" })];
  const h = createHarness({ invoke: (command, args) => {
    if (command === "alarm_occurrences") return [{
      alarmId: "wake", nextAtMs: skipped ? null : followingAtMs,
      skippedAtMs: skipped ? firstAtMs : null,
    }];
    if (command === "local_time") return { year: 2031, month: 2, day: 3, hour: 4, minute: 5 };
    if (command === "skip_alarm") {
      skipped = args.skip;
      alarms = alarms.map((alarm) => ({ ...alarm, skipDate: skipped ? "2031-02-03" : null }));
      return alarms;
    }
    if (command === "next_alarm") return null;
    if (command === "power_status") return powerStatus();
  } });
  h.context.alarms = alarms;
  h.evaluate("state.alarms = alarms; renderAlarms()");

  await h.evaluate("refreshAlarmOccurrences()");
  assert.equal(skipButton(h).textContent, "Undo skip");
  assert.equal(skipButton(h).disabled, false);

  await skipButton(h).dispatch("click");
  assert.deepEqual(JSON.parse(JSON.stringify(h.calls.find((call) => call.command === "skip_alarm").args)), {
    id: "wake", skip: false, expectedAtMs: firstAtMs,
  });
  assert.equal(skipButton(h).textContent, "Skip next");
});

test("a schedule edit prevents a stale occurrence response from being rendered", async () => {
  const occurrences = deferred();
  const h = createHarness({ invoke: (command) => command === "alarm_occurrences" ? occurrences.promise
    : command === "local_time" ? { year: 2031, month: 2, day: 3, hour: 4, minute: 5 } : undefined });
  h.context.alarms = [makeAlarm()];
  h.evaluate("state.alarms = alarms; renderAlarms()");
  const refresh = h.evaluate("refreshAlarmOccurrences()");
  h.evaluate("state.alarms[0].minute = 15; renderAlarms()");
  occurrences.resolve([{ alarmId: "wake", nextAtMs: firstAtMs, skippedAtMs: null }]);
  await refresh;

  assert.equal(skipButton(h).disabled, true);
  assert.match(text(row(h).querySelector(".alarm-occurrence")), /Checking schedule/);
  assert.doesNotMatch(text(row(h).querySelector(".alarm-occurrence")), /2031/);
});

test("a pending desktop skip blocks a second click without changing the schedule early", async () => {
  const result = deferred();
  const h = createHarness({ invoke: (command, args) => {
    if (command === "alarm_occurrences") return [{ alarmId: "wake", nextAtMs: firstAtMs, skippedAtMs: null }];
    if (command === "local_time") return { year: 2031, month: 2, day: 3, hour: 4, minute: 5 };
    if (command === "skip_alarm") return result.promise;
    if (command === "next_alarm") return null;
    if (command === "power_status") return powerStatus();
  } });
  h.context.alarms = [makeAlarm()];
  h.evaluate("state.alarms = alarms; renderAlarms()");
  await h.evaluate("refreshAlarmOccurrences()");

  const firstClick = skipButton(h).dispatch("click");
  await flush();
  assert.equal(h.evaluate("state.alarms[0].skipDate"), undefined);
  assert.equal(skipButton(h).textContent, "Saving…");
  assert.equal(skipButton(h).disabled, true);
  await h.evaluate(`changeAlarmSkip("wake", true, ${firstAtMs})`);
  assert.equal(h.calls.filter((call) => call.command === "skip_alarm").length, 1);

  result.resolve([makeAlarm({ skipDate: "2031-02-03" })]);
  await firstClick;
  assert.equal(h.evaluate("state.alarms[0].skipDate"), "2031-02-03");
  assert.equal(h.calls.filter((call) => call.command === "skip_alarm").length, 1);
});

test("a rejected desktop skip keeps the last confirmed schedule", async () => {
  const result = deferred();
  const confirmed = [makeAlarm()];
  const h = createHarness({ invoke: (command) => {
    if (command === "alarm_occurrences") return [{ alarmId: "wake", nextAtMs: firstAtMs, skippedAtMs: null }];
    if (command === "local_time") return { year: 2031, month: 2, day: 3, hour: 4, minute: 5 };
    if (command === "skip_alarm") return result.promise;
    if (command === "get_state") return { alarms: confirmed };
    if (command === "next_alarm") return null;
    if (command === "power_status") return powerStatus();
  } });
  h.context.alarms = confirmed;
  h.evaluate("state.alarms = alarms; renderAlarms()");
  await h.evaluate("refreshAlarmOccurrences()");

  const request = skipButton(h).dispatch("click");
  await flush();
  assert.equal(h.evaluate("state.alarms[0].skipDate"), undefined);
  result.reject(new Error("The next alarm changed; refresh and try again"));
  await request;

  assert.equal(h.evaluate("state.alarms[0].skipDate"), undefined);
  assert.equal(h.calls.filter((call) => call.command === "skip_alarm").length, 1);
  assert.ok(h.calls.some((call) => call.command === "get_state"));
});

test("Android skips queue behind sync writes and use the revision returned by that save", async () => {
  const syncResult = deferred();
  const alarm = makeAlarm();
  const first = { alarmId: "wake", nextAtMs: firstAtMs, skippedAtMs: null };
  const later = { alarmId: "wake", nextAtMs: followingAtMs, skippedAtMs: firstAtMs };
  let latest = androidSnapshot(7, [alarm], [first]);
  let skipPayload = null;
  const h = createHarness({ navigator: androidNavigator, invoke: (command, args) => {
    if (command.endsWith("|sync_alarms")) return syncResult.promise;
    if (command.endsWith("|skip_alarm")) {
      skipPayload = args.payload;
      latest = androidSnapshot(9, [makeAlarm({ skipDate: "2031-02-03" })], [later]);
      return latest;
    }
    if (command.endsWith("|get_alarm_state")) return latest;
    if (command.endsWith("|local_time")) return { year: 2031, month: 2, day: 3, hour: 4, minute: 5 };
  } });
  h.context.fixture = latest;
  h.evaluate("applyAndroidAlarmState(fixture)");
  await h.evaluate("refreshAlarmOccurrences()");

  const sync = h.evaluate("syncAndroidAlarms(true)");
  await flush();
  const syncCall = h.calls.find((call) => call.command.endsWith("|sync_alarms"));
  assert.equal(syncCall.args.payload.expectedRevision, 7);

  const click = skipButton(h).dispatch("click");
  await flush();
  assert.equal(h.calls.some((call) => call.command.endsWith("|skip_alarm")), false);

  latest = androidSnapshot(8, [alarm], [first]);
  syncResult.resolve(latest);
  await flush();
  const skipCall = h.calls.find((call) => call.command.endsWith("|skip_alarm"));
  assert.ok(skipCall);
  assert.deepEqual(JSON.parse(JSON.stringify(skipPayload)), {
    id: "wake", skip: true, expectedAtMs: firstAtMs, expectedRevision: 8,
  });
  assert.deepEqual(JSON.parse(JSON.stringify(skipCall.args)), {
    payload: JSON.parse(JSON.stringify(skipPayload)),
  });

  await Promise.all([sync, click]);
  assert.equal(h.evaluate("androidAlarmSnapshot.revision"), 9);
  assert.equal(h.evaluate("state.alarms[0].skipDate"), "2031-02-03");
});
