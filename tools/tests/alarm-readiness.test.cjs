const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const alarm = { id: "wake", label: "Morning", enabled: true, hour: 7, minute: 30, volume: .8,
  source: { kind: "station", stationId: "radio" } };
const next = { alarmId: "wake", label: "Morning", atMs: 1790739000000 };
const permissions = { exact: "granted", notifications: "granted", fullScreen: "granted",
  alarmChannel: "high", batteryOptimized: false, dnd: { active: false } };
const defaults = {
  config_location: { loadError: null },
  get_storage_status: { error: null, writesBlocked: false, restartSafe: true, pendingCompletions: 0 },
  next_alarm: next,
  local_time: { year: 2026, month: 9, day: 30, hour: 7, minute: 30 },
  power_status: { wakeSupported: true, wakeAllowed: true, message: "Wake timers enabled on AC", error: null },
  folder_info: { count: 3 },
};
function harness({ android = false, invoke, alarms = [alarm], settings = { backupFolder: "/backup", wakeForAlarms: true } } = {}) {
  const h = createHarness({
    ...(android ? { navigator: { userAgent: "Android" } } : {}),
    invoke: (command, args) => {
      const override = invoke?.(command, args);
      if (override !== undefined) return override;
      if (command.endsWith("|get_alarm_state")) return { revision: 1, initialized: true, alarms, next, permissions, audio: { mediaVolume: 4, mediaVolumeMax: 15, mediaMuted: false, outputs: [] } };
      return defaults[command.replace("plugin:android-audio|", "")];
    },
  });
  h.context.setup = { stations: [{ id: "radio", name: "Morning radio", url: "https://radio.test/live" }], alarms, settings };
  h.evaluate("state = setup");
  return h;
}
const results = h => h.el("#readiness-list").children.map(row => row.children.map(child => child.textContent).join(" ")).join("\n");

test("desktop readiness inspects configuration without HTTP probes or playback and never verifies audible wake", async () => {
  const h = harness();
  await h.evaluate("alarmReadiness.refresh()");
  const text = results(h);
  assert.match(text, /Next scheduled alarm · Scheduled Morning · .*07:30/);
  assert.match(text, /Selected station · Saved link found Morning radio/);
  assert.match(text, /Network availability and decoding were not checked/);
  assert.match(text, /Backup folder · Files found 3 supported audio files/);
  assert.match(text, /System volume & output · Check manually/);
  assert.match(text, /Screen-off audible wake · Not verified/);
  assert.match(text, /foreground Test sound button do not verify/);
  assert.match(h.el("#readiness-summary").textContent, /unverified/);
  assert.equal(h.audios.length, 1);
  assert.equal(h.audios[0].paused, true);
  assert.deepEqual([...new Set(h.calls.map(call => call.command))].sort(), Object.keys(defaults).sort());
});

test("the same source and backup folder uses one non-consuming folder inspection", async () => {
  const h = harness({ alarms: [{ ...alarm, source: { kind: "folder", path: "/backup" } }] });
  await h.evaluate("alarmReadiness.refresh()");
  assert.equal(h.calls.filter(call => call.command === "folder_info").length, 1);
  assert.match(results(h), /Selected source folder · Files found/);
  assert.match(results(h), /Backup folder · Files found/);
  assert.equal(h.calls.some(call => /random|preview_track|backup_track/.test(call.command)), false);
});

test("missing stations and empty backup folders explain recovery", async () => {
  const h = harness({ invoke: command => command === "folder_info" ? { count: 0 } : undefined });
  h.evaluate("state.stations = []");
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Selected station · Missing or invalid/);
  assert.match(results(h), /Backup folder · Empty/);
});

test("Android checks actual media volume and available Bluetooth devices without assuming the route", async () => {
  const h = harness({ android: true, invoke: command => command.endsWith("|get_alarm_state") ? {
    alarms: [alarm], next, permissions, audio: { mediaVolume: 0, mediaVolumeMax: 15, mediaMuted: true,
      outputs: [{ name: "Bedside speaker", kind: "Bluetooth audio", bluetooth: true }] },
  } : undefined });
  await h.evaluate("alarmReadiness.refresh()");
  const text = results(h);
  assert.match(text, /Phone media volume · Silent/);
  assert.match(text, /Bedside speaker \(Bluetooth\)/);
  assert.match(text, /Exact playback route is unknown/);
  assert.match(text, /Android access · Inspected/);
  assert.match(text, /Access does not verify audible screen-off wake/);
  assert.equal(h.calls.some(call => call.command === "power_status" || call.command === "next_alarm" || call.command.endsWith("|sync_alarms")), false);
});

test("unknown Android values stay unknown rather than being treated as granted or audible", async () => {
  const h = harness({ android: true, invoke: command => command.endsWith("|get_alarm_state") ? { alarms: [alarm], next, permissions: {} } : undefined });
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Android access · Partly unknown/);
  assert.match(results(h), /Phone media volume · Unknown/);
  assert.match(results(h), /Audio outputs · Unknown/);
});

test("revoked Android access, DND and battery limits produce attention warnings", async () => {
  const h = harness({ android: true, invoke: command => command.endsWith("|get_alarm_state") ? {
    alarms: [alarm], next, permissions: { ...permissions, exact: "denied", notifications: "denied", fullScreen: "denied",
      alarmChannel: "quiet", batteryOptimized: true,
      dnd: { active: true, mediaAllowed: false, alarmsAllowed: false, alarmBypass: false, fullScreenSuppressed: true } },
  } : undefined });
  await h.evaluate("alarmReadiness.refresh()");
  for (const expected of [/Alarms & reminders is off/, /notifications is off/, /alarm alerts need high priority/,
    /battery optimization is on/, /Do Not Disturb blocks media sound/, /Do Not Disturb may hide the alarm screen/]) {
    assert.match(results(h), expected);
  }
});

test("revoked Android folder access remains an unavailable result", async () => {
  const h = harness({ android: true, invoke: command => {
    if (command.endsWith("|folder_info")) throw new Error("Read permission was revoked");
  } });
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Backup folder · Unavailable Read permission was revoked/);
  assert.match(results(h), /Choose the folder again/);
});

test("settings recovery prevents checking default sources as if saved", async () => {
  const h = harness({ invoke: command => command === "config_location" ? { loadError: "Corrupt settings" } : undefined });
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Saved settings · Recovery needed Corrupt settings/);
  assert.match(results(h), /Source & backup · Not checked/);
  assert.equal(h.calls.some(call => call.command === "folder_info"), false);
});

test("pending alarm completions explicitly warn against restarting", async () => {
  const h = harness({ invoke: command => command === "get_storage_status" ? {
    error: null, pendingCompletions: 1, restartSafe: false, writesBlocked: true,
  } : undefined });
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Saved settings · Save pending/);
  assert.match(results(h), /Keep Aerowave open.*Retry.*before restarting/);
  assert.equal(h.calls.some(call => call.command === "folder_info"), false);
});

test("a failed storage status call cannot become a positive durability result", async () => {
  const h = harness({ invoke: command => {
    if (command === "get_storage_status") throw new Error("Storage unavailable");
  } });
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Saved settings · Durability unknown/);
});

test("unsupported wake and unknown policy remain warnings even with a registered timer", async () => {
  for (const status of [{ wakeSupported: false, wakeAllowed: null }, { wakeSupported: true, wakeAllowed: null }]) {
    const h = harness({ invoke: command => command === "power_status" ? { ...status, armedAtMs: next.atMs, message: "Modern Standby wake is unverified." } : undefined });
    await h.evaluate("alarmReadiness.refresh()");
    assert.match(results(h), /PC wake · Needs attention/);
    assert.match(results(h), /Modern Standby wake is unverified/);
    assert.match(results(h), /shut-down PC cannot wake/);
  }
});

test("an explicit disabled alarm can be inspected without inventing a schedule", async () => {
  const h = harness({ alarms: [{ ...alarm, enabled: false, volume: 0 }], invoke: command => command === "next_alarm" ? null : undefined });
  h.el("#readiness-alarm").value = "wake";
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Next scheduled alarm · None/);
  assert.match(results(h), /Selected alarm · Off/);
  assert.match(results(h), /Increase its app volume/);
});

test("changing setup invalidates completed results", async () => {
  const h = harness();
  await h.evaluate("alarmReadiness.refresh()");
  h.evaluate("state.settings.backupFolder = '/new'; renderSettings()");
  assert.equal(h.el("#readiness-list").children.length, 0);
  assert.match(h.el("#readiness-summary").textContent, /Setup changed/);
});

test("repeated clicks do not duplicate checks and delayed results cannot overwrite newer setup", async () => {
  const pending = deferred();
  const h = harness({ invoke: command => command === "folder_info" ? pending.promise : undefined });
  const check = h.evaluate("alarmReadiness.refresh()");
  await flush();
  await h.evaluate("alarmReadiness.refresh()");
  assert.equal(h.calls.filter(call => call.command === "folder_info").length, 1);
  h.evaluate("state.settings.backupFolder = '/changed'; alarmReadiness.invalidate()");
  pending.resolve({ count: 5 });
  await check;
  assert.equal(h.el("#readiness-list").children.length, 0);
  assert.match(h.el("#readiness-summary").textContent, /Setup changed/);
  assert.equal(h.el("#readiness-check").disabled, false);
});

test("slow folder providers time out; retries reuse existing work and cap unfinished scans", async () => {
  const pending = deferred();
  const h = harness({ invoke: command => command === "folder_info" ? pending.promise : undefined });
  const runUntilTimeout = async () => {
    const check = h.evaluate("alarmReadiness.refresh()");
    await flush();
    for (const [id, timer] of h.timers) if (timer.ms === 10000) await h.fireTimer(id);
    await check;
  };
  await runUntilTimeout();
  assert.match(results(h), /Check timed out/);
  await runUntilTimeout();
  assert.equal(h.calls.filter(call => call.command === "folder_info").length, 1);
  h.evaluate("state.settings.backupFolder = '/two'");
  await runUntilTimeout();
  h.evaluate("state.settings.backupFolder = '/three'");
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Earlier folder checks are still running/);
  assert.equal(h.calls.filter(call => call.command === "folder_info").length, 2);
  pending.resolve({ count: 7 });
  await flush();
});

test("returning from system settings expires the old snapshot instead of retaining stale volume", async () => {
  const h = harness();
  h.evaluate("wire()");
  await h.evaluate("alarmReadiness.refresh()");
  await h.context.window.dispatch("focus");
  assert.equal(h.el("#readiness-list").children.length, 0);
  assert.match(h.el("#readiness-summary").textContent, /current volume, outputs/);
});

test("a selected alarm change supersedes an in-flight source check", async () => {
  const pending = deferred();
  const h = harness({ alarms: [alarm, { ...alarm, id: "other", label: "Other", source: { kind: "folder", path: "/other" } }],
    invoke: (command, args) => command === "folder_info" && args.path === "/backup" ? pending.promise : undefined });
  const previous = h.evaluate("alarmReadiness.refresh()");
  await flush();
  h.el("#readiness-alarm").value = "other";
  const newer = h.evaluate("alarmReadiness.selectionChanged()");
  await flush();
  pending.resolve({ count: 2 });
  await Promise.all([previous, newer]);
  assert.match(results(h), /Selected alarm · Enabled Other/);
  assert.doesNotMatch(results(h), /Selected station ·/);
  assert.equal(h.calls.filter(call => call.command === "folder_info" && call.args.path === "/backup").length, 1);
});


test("fresh storage recovery supersedes an old startup load error", async () => {
  const h = harness();
  h.evaluate("configLoadError = 'Previous startup read failure'");
  await h.evaluate("alarmReadiness.refresh()");
  assert.match(results(h), /Saved settings · Readable/);
  assert.doesNotMatch(results(h), /Recovery needed/);
  assert.match(results(h), /Backup folder · Files found/);
});
