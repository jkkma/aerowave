const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const androidNavigator = { userAgent: "Mozilla/5.0 (Linux; Android 15; Mobile)" };
const occurrence = { alarmId: "wake", atMs: Date.UTC(2026, 8, 13, 10), inSecs: 3600 };
const station = { id: "radio", name: "Morning radio", url: "https://radio.test/live" };

function readyHarness(options = {}, { android = false, source = { kind: "station", stationId: "radio" },
  backupFolder = "/backup", volume = 0.8 } = {}) {
  const h = createHarness({ ...options, navigator: android ? androidNavigator : options.navigator });
  h.context.alarm = { id: "wake", enabled: true, hour: 7, minute: 0, volume, source };
  h.context.occurrence = occurrence;
  h.context.savedStation = station;
  h.context.backupFolder = backupFolder;
  h.evaluate(`
    state.alarms = [alarm];
    state.stations = [savedStation];
    state.settings = { backupFolder, wakeForAlarms: true };
    readinessNext = occurrence;
    powerStatus = { wakeSupported: true, wakeAllowed: true, armedAtMs: occurrence.atMs, error: null };
  `);
  if (android) h.evaluate(`androidAlarmSnapshot = { permissions: {
    exact: "granted", notifications: "granted", batteryOptimized: false, fullScreen: "granted"
  }, error: null }`);
  h.evaluate("renderAlarmReadiness()");
  return h;
}

function detail(h, kind) { return h.el(`#readiness-${kind}-detail`); }
function calls(h, command) { return h.calls.filter((call) => call.command === command); }

test("missing desktop backup and zero alarm volume both demand attention", () => {
  const h = readyHarness({}, { backupFolder: null, volume: 0 });
  assert.equal(h.el("#readiness-status").textContent, "Needs attention");
  assert.match(detail(h, "backup").textContent, /Choose backup music/);
  assert.equal(detail(h, "backup").dataset.tone, "warn");
  assert.equal(h.el("#readiness-backup-check").textContent, "Choose backup");
  assert.match(h.el("#readiness-note").textContent, /volume is zero/);
  assert.match(h.el("#readiness-description").textContent, /Alarm volume 0%/);
});

test("readiness date uses OS wall date for a future occurrence", async () => {
  const now = { year: 2026, month: 9, day: 10, hour: 2, minute: 43, second: 0 };
  const future = { year: 2026, month: 9, day: 13, hour: 7, minute: 30 };
  const h = readyHarness({ invoke: (command, args) => {
    if (command === "next_alarm") return occurrence;
    if (command === "local_time") return args?.atMs === occurrence.atMs ? future : now;
  } });
  h.evaluate(`
    for (const name of ["getHours", "getMinutes", "getFullYear", "getMonth", "getDate"]) {
      Date.prototype[name] = () => { throw new Error("browser timezone used"); };
    }
    Date.prototype.toLocaleDateString = function(locale, options) {
      if (options.timeZone !== "UTC") throw new Error("UTC formatting required");
      return this.toISOString().slice(0, 10);
    };
  `);
  await h.evaluate("tickClock()");
  await h.evaluate("refreshNextAlarm()");
  assert.match(h.el("#readiness-description").textContent, /^2026-09-13 · Alarm volume 80%/);
  assert.equal(calls(h, "local_time").at(-1).args.atMs, occurrence.atMs);
  assert.equal(h.evaluate("readinessDate({year:2026,month:9,day:11})"), "Tomorrow");
});

test("an armed wake timer still warns when this PC has no verified wake support", () => {
  const h = readyHarness();
  h.evaluate("powerStatus.wakeSupported = false; renderAlarmReadiness()");
  assert.equal(h.el("#readiness-wake-detail").dataset.tone, "warn");
  assert.match(h.el("#readiness-wake-detail").textContent, /Wake is unverified/);
  assert.equal(h.el("#readiness-status").textContent, "Needs attention");
});

test("an armed timer with unknown Windows wake permission remains unverified", () => {
  const h = readyHarness();
  h.evaluate("powerStatus.wakeAllowed = null; renderAlarmReadiness()");
  assert.equal(h.el("#readiness-wake-detail").dataset.tone, "warn");
  assert.match(h.el("#readiness-wake-detail").textContent, /Automatic wake is unverified/);
  assert.doesNotMatch(h.el("#readiness-wake-detail").textContent, /blocked/i);
  assert.equal(h.el("#readiness-status").textContent, "Needs attention");
});

test("no scheduled occurrence and a failed next-alarm fetch have distinct states", async () => {
  let fail = false;
  const h = readyHarness({ invoke: (command) => {
    if (command === "next_alarm") return fail ? Promise.reject(new Error("clock unavailable")) : null;
  } });
  await h.evaluate("refreshNextAlarm()");
  assert.equal(h.el("#readiness-status").textContent, "No upcoming alarm");
  assert.equal(h.el("#next-alarm").textContent, "No upcoming alarm");
  assert.equal(h.el("#readiness-checks").hidden, true);
  fail = true;
  await h.evaluate("refreshNextAlarm()");
  assert.equal(h.el("#readiness-status").textContent, "Could not check");
  assert.equal(h.el("#next-alarm").textContent, "Next alarm unavailable");
  assert.match(h.el("#readiness-description").textContent, /Could not read the next alarm/);
});

test("desktop station check waits for decodable media and leaves listening untouched", async () => {
  const h = readyHarness();
  h.evaluate(`player.source = { kind: "folder", path: "/music/current.mp3", title: "Listening" };
    audio.src = "asset:///music/current.mp3"; audio.paused = false; audio.currentTime = 42;`);
  const pending = h.evaluate("checkAlarmReadiness('source')");
  await flush();
  const probe = h.audios[1];
  assert.equal(calls(h, "probe_stream")[0].args.url, station.url);
  assert.match(probe.src, /^http:\/\/127\.0\.0\.1\/relay\//);
  probe.readyState = 1;
  await probe.dispatch("loadedmetadata");
  await flush();
  assert.equal(detail(h, "source").dataset.tone, "checking");
  probe.readyState = 3;
  await probe.dispatch("loadeddata");
  await pending;
  assert.equal(detail(h, "source").dataset.tone, "good");
  assert.match(detail(h, "source").textContent, /Audio decoded/);
  assert.equal(h.evaluate("player.source.title"), "Listening");
  assert.equal(h.audios[0].src, "asset:///music/current.mp3");
  assert.equal(h.audios[0].currentTime, 42);
  assert.equal(h.audios[0].paused, false);
});

test("HLS source check uses an isolated HLS session and waits past metadata", async () => {
  const h = readyHarness({ invoke: (command) => command === "probe_stream"
    ? { url: "https://cdn.test/live.m3u8", hls: true } : undefined });
  const pending = h.evaluate("checkAlarmReadiness('source')");
  await flush();
  assert.equal(h.hlsInstances.length, 1);
  assert.equal(h.hlsInstances[0].url, "https://cdn.test/live.m3u8");
  assert.equal(calls(h, "relay_url").length, 0);
  const probe = h.audios[1];
  probe.readyState = 1;
  await probe.dispatch("loadedmetadata");
  assert.equal(detail(h, "source").dataset.tone, "checking");
  probe.readyState = 3;
  await probe.dispatch("canplay");
  await pending;
  assert.equal(detail(h, "source").dataset.tone, "good");
  assert.equal(h.hlsInstances[0].destroyed, true);
  assert.equal(calls(h, "hls_close").length, 1);
});

test("backup check samples one file through the local file URL without shuffling or relaying", async () => {
  const h = readyHarness({ invoke: (command) => {
    if (command === "preview_track") return { path: "/backup/sample.mp3", name: "sample.mp3" };
    if (command === "local_file_url") return "http://127.0.0.1/local/sample";
  } });
  const pending = h.evaluate("checkAlarmReadiness('backup')");
  await flush();
  assert.equal(calls(h, "preview_track")[0].args.path, "/backup");
  assert.equal(calls(h, "local_file_url")[0].args.path, "/backup/sample.mp3");
  assert.equal(h.audios[1].src, "http://127.0.0.1/local/sample");
  assert.equal(calls(h, "relay_url").length, 0);
  assert.equal(calls(h, "random_track").length, 0);
  h.audios[1].readyState = 3;
  await h.audios[1].dispatch("canplay");
  await pending;
  assert.match(detail(h, "backup").textContent, /One track decoded. Other files were not checked/);
});

for (const change of ["change source", "remove alarm"]) {
  test(`${change} cancels a pending source check without accepting its late reply`, async () => {
    const info = deferred();
    const h = readyHarness({ invoke: (command) => command === "probe_stream" ? info.promise : undefined });
    const pending = h.evaluate("checkAlarmReadiness('source')");
    await flush();
    if (change === "change source") {
      h.evaluate(`state.stations.push({ id: "other", name: "Other", url: "https://radio.test/other" });
        state.alarms[0].source = { kind: "station", stationId: "other" }; renderAlarmReadiness()`);
    } else h.evaluate("state.alarms = []; renderAlarmReadiness()");
    info.resolve({ url: station.url, hls: false });
    await pending;
    assert.equal(h.evaluate("readinessChecks.source"), null);
    assert.equal(h.audios.length, 1);
    if (change === "change source") assert.match(detail(h, "source").textContent, /^Other · Not checked yet/);
    else assert.equal(h.el("#readiness-status").textContent, "No upcoming alarm");
  });
}

test("a completed source check does not return after changing away and back", async () => {
  const h = readyHarness();
  const pending = h.evaluate("checkAlarmReadiness('source')");
  await flush();
  h.audios[1].readyState = 3;
  await h.audios[1].dispatch("canplay");
  await pending;
  assert.equal(detail(h, "source").dataset.tone, "good");
  assert.match(detail(h, "source").textContent, /Audio decoded/);

  h.evaluate(`state.stations.push({ id: "other", name: "Other", url: "https://radio.test/other" });
    state.alarms[0].source = { kind: "station", stationId: "other" }; renderAlarmReadiness()`);
  assert.equal(h.evaluate("readinessChecks.source"), null);
  assert.match(detail(h, "source").textContent, /^Other · Not checked yet/);

  h.evaluate(`state.alarms[0].source = { kind: "station", stationId: "radio" }; renderAlarmReadiness()`);
  assert.equal(h.evaluate("readinessNext.atMs"), occurrence.atMs);
  assert.equal(detail(h, "source").dataset.tone, "neutral");
  assert.match(detail(h, "source").textContent, /^Morning radio · Not checked yet/);
  assert.equal(calls(h, "probe_stream").length, 1);
});

test("a failed probe and a timed-out check report failures without a success badge", async () => {
  const h = readyHarness({ invoke: (command) => command === "probe_stream"
    ? Promise.reject(new Error("station unavailable")) : undefined });
  await h.evaluate("checkAlarmReadiness('source')");
  assert.equal(detail(h, "source").dataset.tone, "bad");
  assert.match(detail(h, "source").textContent, /station unavailable/);
  assert.equal(h.el("#readiness-status").textContent, "Needs attention");

  const info = deferred();
  const slow = readyHarness({ invoke: (command) => command === "probe_stream" ? info.promise : undefined });
  const pending = slow.evaluate("checkAlarmReadiness('source')");
  const timer = [...slow.timers].find(([, entry]) => entry.ms === 25000)?.[0];
  assert.ok(timer, "the readiness check must have a deadline");
  await slow.fireTimer(timer);
  await pending;
  assert.equal(detail(slow, "source").dataset.tone, "bad");
  assert.match(detail(slow, "source").textContent, /timed out/i);
  info.resolve({ url: station.url, hls: false });
  await flush();
  assert.equal(detail(slow, "source").dataset.tone, "bad");
});

test("a prior success expires using monotonic elapsed time", async () => {
  let elapsed = 100;
  const h = readyHarness({ performance: { now: () => elapsed } });
  const pending = h.evaluate("checkAlarmReadiness('source')");
  await flush();
  h.audios[1].readyState = 3;
  await h.audios[1].dispatch("canplay");
  await pending;
  assert.equal(detail(h, "source").dataset.tone, "good");
  elapsed += 5 * 60 * 1000 + 1;
  h.evaluate("renderAlarmReadiness()");
  assert.equal(detail(h, "source").dataset.tone, "neutral");
  assert.match(detail(h, "source").textContent, /Last check expired/);
  assert.equal(h.el("#readiness-status").textContent, "Not checked");
});

test("Android source and folder checks report availability without claiming decoded playback", async () => {
  const h = readyHarness({ invoke: (command) => {
    if (command === "probe_stream") return { url: station.url, hls: false };
    if (command === "plugin:android-audio|folder_info") return { count: 2 };
  } }, { android: true });
  await h.evaluate("checkAlarmReadiness('source')");
  await h.evaluate("checkAlarmReadiness('backup')");
  assert.match(detail(h, "source").textContent, /Server answered. Audio on this phone has not been tested/);
  assert.match(detail(h, "backup").textContent, /2 audio files found. Playback has not been tested/);
  assert.equal(detail(h, "source").dataset.tone, "neutral");
  assert.equal(detail(h, "backup").dataset.tone, "neutral");
  assert.equal(h.el("#readiness-status").textContent, "Availability checked");
  assert.match(h.el("#readiness-note").textContent, /do not test native playback/);
  assert.equal(calls(h, "relay_url").length, 0);
  assert.equal(h.audios.length, 1);
});

test("Android permission failures and unavailable folders remain warnings or errors", async () => {
  const h = readyHarness({ invoke: (command) => command === "plugin:android-audio|folder_info"
    ? Promise.reject(new Error("folder permission denied")) : undefined }, { android: true });
  h.evaluate("androidAlarmSnapshot.permissions.exact = 'denied'; renderAlarmReadiness()");
  assert.equal(h.el("#readiness-wake-detail").dataset.tone, "warn");
  assert.match(h.el("#readiness-wake-detail").textContent, /Allow Alarms & reminders/);
  await h.evaluate("checkAlarmReadiness('backup')");
  assert.equal(detail(h, "backup").dataset.tone, "bad");
  assert.match(detail(h, "backup").textContent, /folder permission denied/);
  assert.equal(h.el("#readiness-status").textContent, "Needs attention");
  h.evaluate("androidReadinessError = 'refresh failed'; renderAlarmReadiness()");
  assert.match(h.el("#readiness-wake-detail").textContent, /Could not refresh Android alarm status/);
});

test("older Android versions do not need runtime permissions marked notRequired", () => {
  const h = readyHarness({}, { android: true });
  h.evaluate(`androidAlarmSnapshot.permissions.exact = "notRequired";
    androidAlarmSnapshot.permissions.notifications = "notRequired"; renderAlarmReadiness()`);
  assert.equal(h.el("#readiness-wake-detail").dataset.tone, "good");
  assert.match(h.el("#readiness-wake-detail").textContent, /permissions allowed/);
});

test("an older failed Android refresh cannot obscure a newer successful snapshot", async () => {
  const oldReply = deferred();
  let requests = 0;
  const h = readyHarness({ invoke: (command) => {
    if (command !== "plugin:android-audio|get_alarm_state") return undefined;
    if (++requests === 1) return oldReply.promise;
    return {
      revision: 2, initialized: true, alarms: [h.context.alarm], next: occurrence,
      ringing: null, error: null,
      permissions: { exact: "granted", notifications: "granted", batteryOptimized: false, fullScreen: "granted" },
    };
  } }, { android: true });
  const old = h.evaluate("refreshAndroidAlarms()");
  await h.evaluate("refreshAndroidAlarms()");
  await flush();
  assert.equal(h.evaluate("androidAlarmSnapshot.revision"), 2);
  assert.equal(h.evaluate("androidReadinessError"), null);
  assert.match(h.el("#readiness-wake-detail").textContent, /Alarm permissions allowed/);

  oldReply.reject(new Error("stale request failed"));
  await old;
  assert.equal(h.evaluate("androidAlarmSnapshot.revision"), 2);
  assert.equal(h.evaluate("androidReadinessError"), null);
  assert.match(h.el("#readiness-wake-detail").textContent, /Alarm permissions allowed/);
  assert.doesNotMatch(h.el("#readiness-wake-detail").textContent, /Could not refresh/);
});
