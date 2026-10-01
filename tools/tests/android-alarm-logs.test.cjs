const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { createHarness, deferred } = require("./frontend-harness.cjs");

const command = "plugin:android-audio|export_alarm_logs";
const saved = (extra = {}) => ({
  name: "Aerowave-alarm-logs-20260930-070000.jsonl", mimeType: "application/x-ndjson",
  bytes: 1024, partial: false, droppedRecords: 0, writeFailures: 0,
  historyTruncated: false, recoveredIncompleteData: false, ...extra,
});
const android = (invoke) => createHarness({ navigator: { userAgent: "Android 13" }, invoke });

test("alarm log export suppresses duplicate requests and leaves setup and playback alone", async () => {
  const pending = deferred();
  const h = android((name) => name === command ? pending.promise : undefined);
  const before = h.evaluate("JSON.stringify(state)");
  const first = h.evaluate("exportAndroidAlarmLogs()");
  assert.equal(h.el("#android-alarm-log-export").disabled, true);
  await h.evaluate("exportAndroidAlarmLogs()");
  assert.equal(h.calls.filter(({ command: name }) => name === command).length, 1);
  pending.resolve(saved());
  await first;
  assert.equal(h.el("#android-alarm-log-export").disabled, false);
  assert.equal(h.el("#android-alarm-log-export").textContent, "Export alarm logs");
  assert.match(h.el("#android-alarm-log-status").textContent, /Saved alarm logs/);
  assert.equal(h.evaluate("JSON.stringify(state)"), before);
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].args, undefined);
});

test("cancelling log export permits a later save", async () => {
  let count = 0;
  const h = android(() => ++count === 1 ? null : saved());
  await h.evaluate("exportAndroidAlarmLogs()");
  assert.equal(h.el("#android-alarm-log-status").textContent, "Export cancelled.");
  assert.equal(h.el("#android-alarm-log-export").disabled, false);
  await h.evaluate("exportAndroidAlarmLogs()");
  assert.match(h.el("#android-alarm-log-status").textContent, /Saved/);
  assert.equal(count, 2);
});

test("a failed save reports the error and retry clears the failure state", async () => {
  let count = 0;
  const h = android(() => {
    if (++count === 1) throw new Error("Document provider refused the write");
    return saved();
  });
  await h.evaluate("exportAndroidAlarmLogs()");
  assert.match(h.el("#android-alarm-log-status").textContent, /refused the write.*Try again/);
  assert.equal(h.el("#android-alarm-log-status").classList.contains("bad"), true);
  assert.equal(h.el("#android-alarm-log-export").disabled, false);
  await h.evaluate("exportAndroidAlarmLogs()");
  assert.equal(h.el("#android-alarm-log-status").classList.contains("bad"), false);
  assert.match(h.el("#android-alarm-log-status").textContent, /Saved/);
});

test("saved logs report dropped, unwritten and recovered incomplete entries", async () => {
  for (const extra of [
    { partial: true }, { droppedRecords: 4 }, { writeFailures: 2 }, { recoveredIncompleteData: true },
  ]) {
    const h = android(() => saved(extra));
    await h.evaluate("exportAndroidAlarmLogs()");
    const message = h.el("#android-alarm-log-status");
    assert.match(message.textContent, /Some log entries were unavailable/);
    assert.equal(message.classList.contains("warn"), true);
  }
});

test("normal bounded-history rotation is shown as a neutral note", async () => {
  const h = android(() => saved({ historyTruncated: true }));
  await h.evaluate("exportAndroidAlarmLogs()");
  const message = h.el("#android-alarm-log-status");
  assert.match(message.textContent, /Older entries were rotated out/);
  assert.doesNotMatch(message.textContent, /unavailable|failures/);
  assert.equal(message.classList.contains("warn"), false);
});

test("export timeout and an unfinished provider both release the UI for a later retry", async () => {
  const failures = [
    "Alarm log export timed out. Android may still be finishing the selected file.",
    "An earlier alarm log export is still finishing. Try again later.",
  ];
  let count = 0;
  const h = android(() => {
    if (count < failures.length) throw new Error(failures[count++]);
    return saved();
  });
  for (const failure of failures) {
    await h.evaluate("exportAndroidAlarmLogs()");
    assert.equal(h.el("#android-alarm-log-export").disabled, false);
    assert.equal(h.el("#android-alarm-log-export").textContent, "Export alarm logs");
    assert.equal(h.el("#android-alarm-log-status").classList.contains("bad"), true);
    assert.equal(h.el("#android-alarm-log-status").textContent.includes(failure), true);
  }
  await h.evaluate("exportAndroidAlarmLogs()");
  assert.equal(h.el("#android-alarm-log-status").textContent, "Saved alarm logs.");
  assert.equal(h.el("#android-alarm-log-status").classList.contains("bad"), false);
});

test("Android export wiring uses its plugin command and desktop has no export action", async () => {
  const h = android(() => saved());
  h.evaluate("wire()");
  await h.el("#android-alarm-log-export").dispatch("click");
  assert.equal(h.calls.filter(({ command: name }) => name === command).length, 1);
  const desktop = createHarness();
  desktop.evaluate("wire()");
  await desktop.evaluate("exportAndroidAlarmLogs()");
  assert.equal(desktop.calls.length, 0);
  assert.equal(desktop.el("#android-alarm-log-export").handlers.has("click"), false);
});

test("alarm log export is registered and allowed across the Rust and Android bridge", () => {
  const root = path.resolve(__dirname, "../..");
  const plugin = path.join(root, "src-tauri/plugins/tauri-plugin-android-audio");
  const read = (relative) => fs.readFileSync(path.join(plugin, relative), "utf8");
  assert.match(read("src/lib.rs"), /generate_handler!\[[\s\S]*?\bexport_alarm_logs\b/);
  assert.match(read("build.rs"), /"export_alarm_logs"/);
  assert.match(read("permissions/default.toml"), /"allow-export-alarm-logs"/);
  assert.match(read("src/mobile.rs"), /run_mobile_plugin\("exportAlarmLogs", \(\)\)/);
  assert.match(read("android/src/main/java/com/aerowave/audio/AndroidAudioPlugin.kt"), /@Command\s+fun exportAlarmLogs\(/);
});
