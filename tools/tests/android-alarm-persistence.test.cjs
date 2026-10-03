const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const navigator = { userAgent: "Android" };
const radio = { id: "radio", name: "Radio", url: "https://radio.test/live" };
const wake = { id: "wake", label: "Wake", hour: 7, minute: 30, days: [], enabled: true,
  source: { kind: "station", stationId: radio.id }, volume: .8, fadeSecs: 20,
  snoozeMins: 10, autoStopMins: 30, autoSnoozes: 0 };
const clone = value => JSON.parse(JSON.stringify(value));
const snapshot = (revision, alarms = [wake], extra = {}) => ({ initialized: true,
  revision, alarms: clone(alarms), next: { alarmId: "wake", atMs: 1791000000000 },
  ringing: null, error: null, occurrences: [], permissions: { exact: "granted",
    notifications: "granted", fullScreen: "notRequired", alarmChannel: "high",
    batteryOptimized: false, dnd: { active: false } }, ...extra });

function harness(invoke) {
  const h = createHarness({ navigator, invoke });
  h.context.radio = radio;
  h.evaluate("clockNow={hour:6,minute:0}; state.stations=[radio]; wire()");
  return h;
}
function apply(h, value) {
  h.context.nativeFixture = value;
  h.evaluate("applyAndroidAlarmState(nativeFixture)");
}
const results = h => h.el("#readiness-list").children.map(row =>
  row.children.map(child => child.textContent).join(" ")).join("\n");

test("an Android editor cannot re-enable a one-shot that completed while it was open", async () => {
  let native = snapshot(1);
  const h = harness((command, args) => {
    if (command.endsWith("|get_alarm_state")) return native;
    if (command.endsWith("|sync_alarms")) {
      native = snapshot(native.revision + 1, args.payload.alarms || native.alarms);
      return native;
    }
  });
  apply(h, native);
  h.evaluate("openAlarmEditor(state.alarms[0])");
  h.el("#al-station").value = radio.id;
  native = snapshot(3, [{ ...wake, enabled: false }]);
  apply(h, native);
  await h.el("#alarm-editor").dispatch("submit");
  assert.equal(native.alarms[0].enabled, false);
  assert.equal(h.calls.some(call => call.command.endsWith("|sync_alarms")), false);
  assert.match(h.el("#al-sourcenote").textContent, /changed on Android.*Cancel and reopen/);
  assert.equal(h.el("#alarm-editor").classList.contains("hidden"), false);

  h.evaluate("closeAlarmEditor(); openAlarmEditor(state.alarms[0])");
  h.el("#al-station").value = radio.id;
  assert.equal(h.el("#al-save").textContent, "Save & turn on");
  await h.el("#alarm-editor").dispatch("submit");
  assert.equal(native.alarms[0].enabled, true);
});

test("source-only Android revision changes do not invalidate an unchanged alarm draft", async () => {
  let native = snapshot(4);
  const h = harness((command, args) => {
    if (command.endsWith("|get_alarm_state")) return native;
    if (command.endsWith("|sync_alarms")) {
      native = snapshot(native.revision + 1, args.payload.alarms || native.alarms);
      return native;
    }
  });
  apply(h, native);
  h.evaluate("openAlarmEditor(state.alarms[0])");
  h.el("#al-station").value = radio.id;
  await h.evaluate("syncAndroidAlarms(false)");
  await h.el("#alarm-editor").dispatch("submit");
  assert.match(h.el("#status-msg").textContent, /alarm saved/);
  assert.equal(h.el("#alarm-editor").classList.contains("hidden"), true);
});

test("a failed native refresh preserves canonical alarms and blocks replacement until a fresh read", async () => {
  let native = snapshot(5);
  let failRead = true;
  const h = harness((command, args) => {
    if (command === "get_state") return { stations: [radio], alarms: [], settings: {} };
    if (command.endsWith("|get_alarm_state")) {
      if (failRead) throw new Error("bridge unavailable");
      return native;
    }
    if (command.endsWith("|sync_alarms")) {
      assert.equal(args.payload.expectedRevision, native.revision);
      native = snapshot(native.revision + 1, args.payload.alarms || native.alarms);
      return native;
    }
  });
  apply(h, native);
  await h.evaluate("loadState()");
  assert.deepEqual(Array.from(h.evaluate("state.alarms.map(alarm => alarm.id)")), ["wake"]);
  assert.equal(await h.evaluate("saveAlarms([])"), false);
  assert.equal(h.calls.some(call => call.command.endsWith("|sync_alarms")), false);
  assert.match(h.el("#android-alarm-status").textContent, /Could not confirm Android alarms/);
  failRead = false;
  await h.el("#android-permissions-refresh").dispatch("click");
  assert.equal(await h.evaluate("saveAlarms(state.alarms)"), true);
  assert.deepEqual(native.alarms.map(alarm => alarm.id), ["wake"]);
});

test("settings recovery prevents ordinary alarm toggles from erasing native source metadata", async () => {
  let native = snapshot(5);
  const h = harness((command, args) => {
    if (command.endsWith("|get_alarm_state")) return native;
    if (command.endsWith("|sync_alarms")) {
      native = snapshot(6, args.payload.alarms);
      return native;
    }
  });
  apply(h, native);
  h.evaluate("configLoadError='Saved settings could not be read';state.stations=[];state.settings={}");
  const toggle = h.el("#alarm-list").querySelector(".sw");
  await toggle.dispatch("click");
  assert.equal(native.alarms[0].enabled, true);
  assert.equal(h.calls.some(call => call.command.endsWith("|sync_alarms")), false);
  assert.match(h.el("#status-msg").textContent, /Settings are protected.*before changing alarms/);
});

test("a malformed native store cannot publish empty defaults or permit ordinary alarm saves", async () => {
  const h = harness();
  apply(h, snapshot(5));
  apply(h, snapshot(0, [], { initialized: false, error: "Saved Android alarm state could not be read" }));
  assert.equal(h.evaluate("state.alarms.length"), 1);
  assert.equal(await h.evaluate("saveAlarms([])"), false);
  assert.equal(h.calls.some(call => call.command.endsWith("|sync_alarms")), false);
  assert.match(h.el("#android-alarm-status").textContent, /could not be read/);
});

test("operational scheduling errors allow explicit source-only repair without replacing alarm definitions", async () => {
  let native = snapshot(5, [wake], { error: "Could not arm the next Android alarm" });
  const h = harness((command, args) => {
    if (command.endsWith("|get_alarm_state")) return native;
    if (command.endsWith("|sync_alarms")) {
      assert.equal(args.payload.expectedRevision, native.revision);
      assert.equal(Object.hasOwn(args.payload, "alarms"), false);
      native = snapshot(native.revision + 1, native.alarms);
      return native;
    }
  });
  apply(h, native);
  assert.match(h.el("#android-alarm-status").textContent, /Could not arm/);
  assert.equal(h.el("#android-permissions-refresh").textContent, "Retry alarm setup");
  await h.el("#android-permissions-refresh").dispatch("click");
  assert.deepEqual(native.alarms, [wake]);
  assert.equal(h.calls.filter(call => call.command.endsWith("|sync_alarms")).length, 1);
  assert.doesNotMatch(h.el("#android-alarm-status").textContent, /Could not arm/);
  assert.equal(h.el("#android-permissions-refresh").textContent, "Check again");
});

test("failed native source synchronization stays visible in readiness until explicit retry succeeds", async () => {
  let native = snapshot(5);
  let failSync = true;
  let nativeBackup = "content://provider/tree/original";
  const h = harness((command, args) => {
    if (command === "save_settings") return true;
    if (command.endsWith("|sync_alarms")) {
      if (failSync) throw new Error("Android could not persist the alarm schedule");
      nativeBackup = args.payload.backupFolder;
      native = snapshot(native.revision + 1);
      return native;
    }
    if (command.endsWith("|get_alarm_state")) return native;
    if (command.endsWith("|folder_info")) return { path: args.payload.path, count: 3 };
    if (command === "config_location") return { loadError: null };
    if (command === "get_storage_status") return { writesBlocked: false, restartSafe: true,
      pendingCompletions: 0, error: null };
    if (command === "local_time") return { year: 2026, month: 10, day: 3, hour: 7, minute: 30 };
  });
  apply(h, native);
  h.evaluate("state.settings.backupFolder='content://provider/tree/new';saveSettings()");
  await h.evaluate("flushSettings()");
  await flush();
  assert.equal(nativeBackup, "content://provider/tree/original");
  assert.equal(h.el("#android-permissions-refresh").textContent, "Retry alarm setup");
  await h.evaluate("refreshAndroidAlarms()");
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Saved settings · Update needed.*have not reached Android/);
  assert.match(results(h), /Source & backup · Not checked/);
  assert.equal(h.calls.filter(call => call.command.endsWith("|folder_info")).length, 0);
  assert.equal(h.calls.filter(call => call.command.endsWith("|sync_alarms")).length, 1);

  failSync = false;
  await h.el("#android-permissions-refresh").dispatch("click");
  assert.equal(nativeBackup, "content://provider/tree/new");
  assert.equal(h.el("#android-permissions-refresh").textContent, "Check again");
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Backup folder · Files found/);
  assert.doesNotMatch(results(h), /have not reached Android/);
});

test("a superseded source-sync reply cannot clear the latest failed synchronization", async () => {
  const pending = deferred();
  let syncs = 0;
  const h = harness(command => {
    if (command.endsWith("|get_alarm_state")) return snapshot(6);
    if (command.endsWith("|sync_alarms")) {
      if (++syncs === 1) return pending.promise;
      throw new Error("latest write failed");
    }
  });
  apply(h, snapshot(5));
  const first = h.evaluate("syncAndroidAlarms(false)");
  h.evaluate("state.settings.backupFolder='content://provider/tree/new'");
  const second = h.evaluate("syncAndroidAlarms(false)");
  const settled = Promise.allSettled([first, second]);
  pending.resolve(snapshot(6));
  await settled;
  assert.match(h.el("#android-alarm-status").textContent, /latest write failed/);
});

test("a source save blocked by failed native read still needs synchronization after polling recovers", async () => {
  let failRead = true;
  let native = snapshot(5);
  let nativeBackup = "content://provider/tree/original";
  const h = harness((command, args) => {
    if (command === "save_settings") return true;
    if (command.endsWith("|get_alarm_state")) {
      if (failRead) throw new Error("bridge unavailable");
      return native;
    }
    if (command.endsWith("|sync_alarms")) {
      nativeBackup = args.payload.backupFolder;
      native = snapshot(native.revision + 1);
      return native;
    }
  });
  apply(h, native);
  await h.evaluate("refreshAndroidAlarms()");
  h.evaluate("state.settings.backupFolder='content://provider/tree/new';saveSettings()");
  await h.evaluate("flushSettings()");
  await flush();
  assert.equal(h.calls.some(call => call.command.endsWith("|sync_alarms")), false);
  failRead = false;
  await h.evaluate("refreshAndroidAlarms()");
  assert.match(h.el("#android-alarm-status").textContent, /have not reached Android/);
  assert.equal(nativeBackup, "content://provider/tree/original");
  await h.el("#android-permissions-refresh").dispatch("click");
  assert.equal(nativeBackup, "content://provider/tree/new");
  assert.equal(h.el("#android-permissions-refresh").textContent, "Check again");
});

for (const partialFailure of [false, true]) {
  test(`explicit native recovery accepts its new lower revision after ${partialFailure ? "a partial cross-store failure" : "successful restore"}`, async () => {
    const imported = { ...wake, enabled: false };
    const restored = { stations: [radio], alarms: [imported], settings: {} };
    let native = snapshot(9);
    const h = harness(command => {
      if (command === "restore_backup") {
        native = snapshot(1, [imported], { next: null });
        if (partialFailure) throw new Error("Imported alarms are off, but the settings receipt needs repair");
        return restored;
      }
      if (command === "get_state") return { stations: [radio], alarms: [wake], settings: {} };
      if (command === "config_location") return { path: "/settings", loadError: partialFailure ? "Receipt repair needed" : null };
      if (command === "get_storage_status") return { writesBlocked: partialFailure,
        restartSafe: !partialFailure, pendingCompletions: 0, error: partialFailure ? "Receipt repair needed" : null };
      if (command.endsWith("|get_alarm_state")) return native;
    });
    apply(h, native);
    apply(h, snapshot(0, [], { initialized: false, error: "Unreadable native alarm store" }));
    h.evaluate("backupRestoreContent='validated backup'");
    await h.evaluate("restoreSetup()");
    assert.equal(h.evaluate("androidAlarmSnapshot.revision"), 1);
    assert.equal(h.evaluate("androidAlarmReadError"), null);
    assert.equal(h.evaluate("state.alarms[0].enabled"), false);
    assert.equal(h.calls.some(call => call.command.endsWith("|sync_alarms")), false);
  });
}

test("readiness cannot validate local source metadata when its native read fails", async () => {
  const h = harness(command => {
    if (command.endsWith("|get_alarm_state")) throw new Error("bridge unavailable");
    if (command === "config_location") return { loadError: null };
    if (command === "get_storage_status") return { writesBlocked: false, restartSafe: true,
      pendingCompletions: 0, error: null };
    if (command.endsWith("|folder_info")) return { count: 3 };
  });
  apply(h, snapshot(5));
  h.evaluate("state.settings.backupFolder='content://provider/tree/music'");
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Saved settings · Update needed.*bridge unavailable/);
  assert.match(results(h), /Source & backup · Not checked/);
  assert.equal(h.calls.some(call => call.command.endsWith("|folder_info")), false);
});
