const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const navigator = { userAgent: "Mozilla/5.0 (Linux; Android 13; Mobile)" };
const snapshot = (revision, ringing = null, extra = {}) => ({
  initialized: true, revision, alarms: [], next: null, ringing,
  permissions: {
    exact: "granted", notifications: "granted", alarmChannel: "high", fullScreen: "granted",
    batteryOptimized: true, manufacturer: "Google", brand: "Pixel", alarmScreenOverlay: "denied",
    dnd: { active: false, access: "denied", alarmBypass: false, fullScreenSuppressed: false,
      alarmsAllowed: true, mediaAllowed: true },
  },
  error: null, ...extra,
});
const ring = (occurrenceId = "one") => ({
  alarmId: "wake", occurrenceId, trigger: "scheduled", label: "Wake",
  hour: 7, minute: 0, snoozeMins: 10, canSnooze: true, sourceKind: "tone",
  title: "Alarm sound", volume: .8,
});

test("native alarm rendering never starts webview playback or a desktop timer", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(4, ring());
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#ringing").hidden, false);
  assert.equal(h.el("#ring-label").textContent, "Wake");
  assert.equal(h.evaluate("ringing.native"), true);
  assert.equal(h.evaluate("autoStopTimer"), null);
  assert.equal(h.calls.length, 0);
  h.context.fixture = snapshot(5);
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#ringing").hidden, true);
  assert.equal(h.calls.length, 0);
});

test("a stale action reply cannot hide a later native occurrence", async () => {
  const delayed = deferred();
  const h = createHarness({ navigator, invoke: command => {
    if (command.endsWith("|dismiss_alarm")) return delayed.promise;
  } });
  h.context.fixture = snapshot(4, ring());
  h.evaluate("applyAndroidAlarmState(fixture)");
  const action = h.evaluate("dismissRing()");
  h.context.fixture = snapshot(6, ring("two"));
  h.evaluate("applyAndroidAlarmState(fixture)");
  delayed.resolve(snapshot(5));
  await action;
  assert.equal(h.evaluate("ringing.occurrenceId"), "two");
  assert.equal(h.el("#ringing").hidden, false);
  const request = h.calls.find(c => c.command.endsWith("|dismiss_alarm"));
  assert.deepEqual(JSON.parse(JSON.stringify(request.args)), { payload: { id: "wake", occurrenceId: "one" } });
  assert.equal(h.calls.some(c => c.command === "dismiss_alarm" || c.command.endsWith("|stop")), false);
});

test("polling cannot enable a second action while native snooze is pending", async () => {
  const delayed = deferred();
  const h = createHarness({ navigator, invoke: command => command.endsWith("|snooze_alarm") ? delayed.promise : undefined });
  h.context.fixture = snapshot(4, ring());
  h.evaluate("applyAndroidAlarmState(fixture)");
  const action = h.evaluate("snoozeRing()");
  h.evaluate("applyAndroidAlarmState(fixture)");
  await h.evaluate("snoozeRing()");
  assert.equal(h.el("#ring-snooze").disabled, true);
  assert.equal(h.calls.filter(c => c.command.endsWith("|snooze_alarm")).length, 1);
  delayed.resolve(snapshot(5));
  await action;
  assert.equal(h.evaluate("ringing"), null);
});

test("native canonical alarms survive reload without rearming the saved desktop copy", async () => {
  const h = createHarness({ navigator, invoke: command => {
    if (command === "get_state") return { stations: [], alarms: [{ id: "old", enabled: true }], settings: {} };
    if (command.endsWith("|get_alarm_state")) return snapshot(8);
    if (command.endsWith("|sync_alarms")) return snapshot(9);
  } });
  await h.evaluate("loadState()");
  assert.equal(h.evaluate("state.alarms.length"), 0);
  const sync = h.calls.find(c => c.command.endsWith("|sync_alarms"));
  assert.equal(Object.hasOwn(sync.args.payload, "alarms"), false);
  assert.deepEqual(JSON.parse(JSON.stringify(sync.args.payload.stations)), []);
});

test("Android keeps native alarm sources when settings failed to load", async () => {
  const h = createHarness({ navigator, invoke: command => {
    if (command === "config_location") return {
      portable: false, path: "/data/user/0/com.aerowave.radio/files/aerowave.json",
      loadError: "Could not read settings",
    };
    if (command === "get_state") return { stations: [], alarms: [], settings: {} };
    if (command.endsWith("|get_alarm_state")) return snapshot(8, null, {
      alarms: [{ id: "wake", enabled: true }],
    });
  } });
  const problems = [];
  h.el("#config-where").after = problem => problems.push(problem);
  await h.evaluate("boot()");
  assert.equal(h.el("#config-details").open, true);
  assert.match(problems[0].textContent, /Android alarm sources were kept/);
  assert.match(h.el("#status-msg").textContent, /restore a backup or repair settings/);
  assert.equal(h.evaluate("state.alarms[0].id"), "wake");
  assert.equal(h.calls.some(call => call.command.endsWith("|sync_alarms")), false);
});

test("alarm edits serialize and source-only saves do not replace canonical alarms", async () => {
  const delayed = deferred();
  let syncs = 0;
  const h = createHarness({ navigator, invoke: command => {
    if (command.endsWith("|sync_alarms")) return ++syncs === 1 ? delayed.promise : snapshot(2);
    if (command.endsWith("|get_alarm_state")) return snapshot(2);
  } });
  const edit = h.evaluate("syncAndroidAlarms(true)");
  const source = h.evaluate("syncAndroidAlarms(false)");
  await flush();
  assert.equal(syncs, 1);
  delayed.resolve(snapshot(1));
  await Promise.all([edit, source]);
  const calls = h.calls.filter(c => c.command.endsWith("|sync_alarms"));
  assert.equal(Array.isArray(calls[0].args.payload.alarms), true);
  assert.equal(Object.hasOwn(calls[1].args.payload, "alarms"), false);
});

test("revoked exact permission has visible guidance and no invented next alarm", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { exact: "denied", notifications: "denied" } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.match(h.el("#android-alarm-status").textContent, /Alarms & reminders/);
  assert.match(h.el("#android-alarm-status").textContent, /notifications/);
  assert.equal(h.el("#android-alarm-status").classList.contains("warn"), true);
});

test("alarm channel priority is checked separately from app notifications", () => {
  for (const [channel, label] of [["blocked", "Off"], ["quiet", "Low priority"]]) {
    const h = createHarness({ navigator });
    h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions, alarmChannel: channel } });
    h.evaluate("applyAndroidAlarmState(fixture)");
    assert.equal(h.el("#android-notifications-status").textContent, "On");
    assert.equal(h.el("#android-channel-status").textContent, label);
    assert.equal(h.el("#android-channel-status").classList.contains("attention"), true);
    assert.match(h.el("#android-alarm-status").textContent, /Needs attention: Alarm alerts/);
    assert.match(h.el("#android-alarm-hint").textContent, /Review Android permissions/);
  }
});

test("a new alarm channel is pending rather than reported as blocked", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions, alarmChannel: "notCreated" } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-channel-status").textContent, "Not created yet");
  assert.equal(h.el("#android-channel-status").classList.contains("attention"), false);
  assert.match(h.el("#android-alarm-status").textContent, /Run a test alarm/);
});

test("Do Not Disturb off does not ask for policy access", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3);
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-dnd-status").textContent, "Off");
  assert.equal(h.el("#android-dnd-status").classList.contains("attention"), false);
  assert.doesNotMatch(h.el("#android-dnd-help").textContent, /grant|access/i);
  assert.doesNotMatch(h.el("#android-alarm-status").textContent, /Do Not Disturb/);
});

test("Do Not Disturb warns about a hidden alarm screen even when alarm sound is allowed", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions,
    dnd: { active: true, access: "denied", alarmBypass: false, fullScreenSuppressed: true,
      alarmsAllowed: true, mediaAllowed: true },
  } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-dnd-status").classList.contains("attention"), true);
  assert.match(h.el("#android-dnd-help").textContent, /hide the ringing screen even when alarm sound is allowed/);
  assert.match(h.el("#android-alarm-status").textContent, /Test a scheduled alarm with the screen off/);
  assert.doesNotMatch(h.el("#android-alarm-status").textContent, /Needs attention/);
  assert.equal(h.el("#android-alarm-status").classList.contains("warn"), true);
});

test("Do Not Disturb media and alert restrictions are shown separately", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions,
    dnd: { active: true, access: "denied", alarmBypass: false, fullScreenSuppressed: false,
      alarmsAllowed: false, mediaAllowed: false },
  } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.match(h.el("#android-dnd-help").textContent, /blocks media sound/);
  assert.match(h.el("#android-dnd-help").textContent, /Alarm alerts are not allowed/);
  assert.match(h.el("#android-alarm-status").textContent, /Do Not Disturb media sound/);
  assert.match(h.el("#android-alarm-status").textContent, /Do Not Disturb alarm alerts/);
});

test("active Do Not Disturb stays a screen-off check on Xiaomi with allowed sound", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions,
    manufacturer: "Xiaomi", brand: "POCO",
    dnd: { active: true, access: "denied", alarmBypass: false, fullScreenSuppressed: false,
      alarmsAllowed: true, mediaAllowed: true },
  } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-dnd-status").textContent, "On · check alarm screen");
  assert.match(h.el("#android-dnd-help").textContent, /background windows/);
  assert.match(h.el("#android-dnd-help").textContent, /scheduled alarm with the screen off/);
  assert.equal(h.el("#android-alarm-status").classList.contains("warn"), true);
  assert.doesNotMatch(h.el("#android-alarm-status").textContent, /looks ready/);
});

test("Xiaomi needs alarm screen access only while Do Not Disturb is active", () => {
  const h = createHarness({ navigator });
  const permissions = { ...snapshot(3).permissions, manufacturer: "Xiaomi", brand: "POCO" };
  h.context.fixture = snapshot(3, null, { permissions });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-alarm-screen-row").hidden, false);
  assert.equal(h.el("#android-alarm-screen-status").textContent, "Needs access");
  assert.equal(h.el("#android-alarm-screen-status").classList.contains("attention"), false);
  assert.match(h.el("#android-alarm-screen-help").textContent, /Optional while Do Not Disturb is off/);
  assert.doesNotMatch(h.el("#android-alarm-status").textContent, /Display over other apps/);

  h.context.fixture = snapshot(4, null, { permissions: { ...permissions, dnd: {
    active: true, access: "denied", alarmBypass: false, fullScreenSuppressed: false,
    alarmsAllowed: true, mediaAllowed: true,
  } } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-alarm-screen-status").textContent, "Needs access");
  assert.equal(h.el("#android-alarm-screen-status").classList.contains("attention"), true);
  assert.match(h.el("#android-alarm-screen-help").textContent, /Display over other apps/);
  assert.match(h.el("#android-alarm-status").textContent, /Needs attention: Display over other apps for the alarm screen/);
});

test("granted Xiaomi alarm screen access still needs a real DND screen-off test", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions,
    manufacturer: "Xiaomi", brand: "Redmi", alarmScreenOverlay: "granted",
    dnd: { active: true, access: "denied", alarmBypass: false, fullScreenSuppressed: false,
      alarmsAllowed: true, mediaAllowed: true },
  } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-alarm-screen-row").hidden, false);
  assert.equal(h.el("#android-alarm-screen-status").textContent, "Allowed");
  assert.equal(h.el("#android-alarm-screen-status").classList.contains("ready"), true);
  assert.match(h.el("#android-alarm-status").textContent, /Test a scheduled alarm with the screen off/);
  assert.doesNotMatch(h.el("#android-alarm-status").textContent, /Needs attention/);
});

test("unknown Xiaomi alarm screen access stays unknown", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions,
    manufacturer: "Xiaomi", alarmScreenOverlay: "unknown",
  } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-alarm-screen-row").hidden, false);
  assert.equal(h.el("#android-alarm-screen-status").textContent, "Unknown");
  assert.equal(h.el("#android-alarm-screen-status").classList.contains("ready"), false);
  assert.match(h.el("#android-alarm-status").textContent, /could not be checked/);
});

test("other Android brands do not show or require Xiaomi alarm screen access", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions,
    alarmScreenOverlay: "denied",
    dnd: { active: true, access: "denied", alarmBypass: false, fullScreenSuppressed: false,
      alarmsAllowed: true, mediaAllowed: true },
  } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-alarm-screen-row").hidden, true);
  assert.doesNotMatch(h.el("#android-alarm-status").textContent, /Display over other apps/);
});

test("unknown Do Not Disturb data is never shown as ready", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: { ...snapshot(3).permissions, dnd: {
    active: true, access: "unknown", alarmBypass: null, fullScreenSuppressed: null,
    alarmsAllowed: null, mediaAllowed: null,
  } } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-dnd-status").textContent, "On · partly unknown");
  assert.match(h.el("#android-dnd-help").textContent, /could not be checked/);
  assert.match(h.el("#android-alarm-status").textContent, /could not be checked/);
  assert.equal(h.el("#android-alarm-status").classList.contains("warn"), true);
  h.context.fixture = snapshot(4, null, { permissions: { ...snapshot(3).permissions, dnd: {
    active: null, access: "unknown", alarmBypass: null, fullScreenSuppressed: null,
    alarmsAllowed: null, mediaAllowed: null,
  } } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-dnd-status").textContent, "Could not check");
  assert.equal(h.el("#android-dnd-status").classList.contains("ready"), false);
});

test("Alarm alerts, Do Not Disturb and alarm screen buttons open their own settings pages", async () => {
  const html = fs.readFileSync(path.join(__dirname, "../../src/index.html"), "utf8");
  const settingFor = title => {
    const row = html.match(new RegExp(`<li class="android-permission-row"[^>]*>(?:(?!</li>)[\\s\\S])*?<strong>${title}</strong>(?:(?!</li>)[\\s\\S])*?</li>`))?.[0];
    assert.ok(row, `${title} row exists`);
    return row.match(/data-android-setting="([^"]+)"/)?.[1];
  };
  const h = createHarness({ navigator });
  const channel = h.el("#channel-settings-button");
  channel.dataset.androidSetting = settingFor("Alarm alerts");
  const dnd = h.el("#dnd-settings-button");
  dnd.dataset.androidSetting = settingFor("Do Not Disturb");
  const alarmScreen = h.el("#alarm-screen-settings-button");
  alarmScreen.dataset.androidSetting = settingFor("Alarm screen during Do Not Disturb");
  h.queries.set("[data-android-setting]", [channel, dnd, alarmScreen]);
  h.evaluate("wire()");
  await channel.dispatch("click");
  await dnd.dispatch("click");
  await alarmScreen.dispatch("click");
  assert.deepEqual(h.calls.filter(c => c.command.endsWith("|open_alarm_settings"))
    .map(c => c.args.payload.setting), ["alarmChannel", "dndSettings", "alarmScreen"]);
});

test("Xiaomi restrictions stay manual when Android has no separate full-screen switch", () => {
  const h = createHarness({ navigator });
  h.context.fixture = snapshot(3, null, { permissions: {
    ...snapshot(3).permissions, manufacturer: "Xiaomi", brand: "POCO", fullScreen: "notRequired",
  } });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(h.el("#android-fullscreen-status").textContent, "No extra switch");
  assert.equal(h.el("#android-fullscreen-action").textContent, "Open app info");
  assert.match(h.el("#android-phone-help").textContent, /Other permissions/);
  assert.match(h.el("#android-phone-help").textContent, /Show on Lock screen/);
  assert.match(h.el("#android-phone-help").textContent, /Open new windows while running in the background/);
  assert.match(h.el("#android-phone-help").textContent, /cannot read/);
  assert.match(h.el("#android-fullscreen-help").textContent, /Test with the screen off/);
});

test("Check again refreshes the native permission snapshot", async () => {
  let permission = "denied";
  const h = createHarness({ navigator, invoke: command => {
    if (command.endsWith("|get_alarm_state")) return snapshot(3, null, {
      permissions: { ...snapshot(3).permissions, exact: permission },
    });
  } });
  h.evaluate("wire()");
  await h.el("#android-permissions-refresh").dispatch("click");
  assert.equal(h.el("#android-exact-status").textContent, "Not allowed");
  permission = "granted";
  await h.el("#android-permissions-refresh").dispatch("click");
  assert.equal(h.el("#android-exact-status").textContent, "Allowed");
  assert.equal(h.el("#android-permissions-refresh").disabled, false);
  assert.equal(h.calls.filter(call => call.command.endsWith("|get_alarm_state")).length, 2);
});

for (const firstIncludesAlarms of [true, false]) {
  test(`queued alarm edits retain revisions after ${firstIncludesAlarms ? "another edit" : "a source-only save"}`, async () => {
    const delayed = deferred();
    let revision = 4;
    let calls = 0;
    let alarms = [];
    const h = createHarness({ navigator, invoke: async (command, args) => {
      if (command.endsWith("|get_alarm_state")) return snapshot(revision, null, { alarms });
      if (!command.endsWith("|sync_alarms")) return;
      if (++calls === 1) await delayed.promise;
      assert.equal(args.payload.expectedRevision, revision);
      if (args.payload.alarms) alarms = args.payload.alarms;
      revision += args.payload.alarms ? 2 : 1;
      return snapshot(revision, null, { alarms });
    } });
    h.context.fixture = snapshot(4);
    h.evaluate("applyAndroidAlarmState(fixture); state.alarms=[{id:'wake',enabled:true}]");
    const first = h.evaluate(`syncAndroidAlarms(${firstIncludesAlarms})`);
    h.evaluate("state.alarms[0].enabled=false");
    const second = h.evaluate("syncAndroidAlarms(true)");
    await flush();
    assert.equal(calls, 1);
    delayed.resolve();
    await Promise.all([first, second]);
    assert.equal(calls, 2);
    assert.equal(h.evaluate("state.alarms[0].enabled"), false);
  });
}

test("a native conflict invalidates later queued edits instead of adopting the newer revision", async () => {
  const delayed = deferred();
  let syncs = 0;
  const h = createHarness({ navigator, invoke: async command => {
    if (command.endsWith("|get_alarm_state")) return snapshot(8, null, { alarms:[{id:"wake",enabled:false}] });
    if (!command.endsWith("|sync_alarms")) return;
    syncs++;
    await delayed.promise;
    throw new Error("Alarms changed on Android; refresh and try again");
  } });
  h.context.fixture = snapshot(4, null, { alarms:[{id:"wake",enabled:true}] });
  h.evaluate("applyAndroidAlarmState(fixture)");
  const first = h.evaluate("syncAndroidAlarms(false)");
  const second = h.evaluate("syncAndroidAlarms(true)");
  const results = Promise.allSettled([first, second]);
  delayed.resolve();
  assert.deepEqual((await results).map(result => result.status), ["rejected", "rejected"]);
  await flush();
  assert.equal(syncs, 1);
  assert.equal(h.evaluate("state.alarms[0].enabled"), false);
});

test("local Android tracks retain their granted content URI and native shuffle folder", async () => {
  const path = "content://provider/tree/music/document/song";
  const h = createHarness({ navigator, invoke: (command, args) => {
    if (command.endsWith("|random_track")) return { path, name: "Song.mp3", total: 2 };
    if (command.endsWith("|pause")) return { sleepTimer: { revision: 0, timer: null } };
    if (command.endsWith("|play")) return { status: "buffering", generation: args.payload.generation };
  } });
  await h.evaluate("playRandomFromFolder('content://provider/tree/music')");
  await flush();
  const call = h.calls.find(c => c.command.endsWith("|play"));
  assert.equal(call.args.payload.url, path);
  assert.equal(call.args.payload.sourceFolder, "content://provider/tree/music");
  assert.equal(h.calls.some(c => c.command === "local_file_url" || c.command === "relay_url"), false);
});

test("a native playback error does not start a second frontend reconnect loop", () => {
  const h = createHarness({ navigator });
  h.evaluate("restoreAndroidSource({ status:'playing', generation:3, sourceUrl:'https://radio.test/live' })");
  h.evaluate("applyAndroidPlaybackState({ status:'error', generation:3, error:'Backup folder access was revoked' })");
  assert.equal(h.evaluate("player.retryTimer"), null);
  assert.match(h.el("#status-msg").textContent, /revoked/);
  assert.equal(h.calls.some(c => /\|(play|resume|stop)$/.test(c.command) ||
    ["relay_url", "probe_stream", "backup_track"].includes(c.command)), false);
});

test("an alarm edit carries its observed revision and refreshes after a native conflict", async () => {
  const h = createHarness({ navigator, invoke: command => {
    if (command.endsWith("|sync_alarms")) throw new Error("Alarms changed on Android; refresh and try again");
    if (command.endsWith("|get_alarm_state")) return snapshot(5, null, { alarms: [{ id: "wake", enabled: false }] });
  } });
  h.context.fixture = snapshot(4, null, { alarms: [{ id: "wake", enabled: true }] });
  h.evaluate("applyAndroidAlarmState(fixture)");
  assert.equal(await h.evaluate("saveAlarms()"), false);
  await flush();
  assert.equal(h.calls.find(c => c.command.endsWith("|sync_alarms")).args.payload.expectedRevision, 4);
  assert.equal(h.evaluate("state.alarms[0].enabled"), false);
});

test("restoring extensionless HLS keeps the native stream type", () => {
  const h = createHarness({ navigator });
  h.evaluate("state.stations=[{id:'saved',url:'https://radio.test/listen'}]; restoreAndroidSource({ status:'paused', generation:3, stationId:'saved', sourceUrl:'https://radio.test/live', isHls:true })");
  assert.equal(h.evaluate("player.source.hls"), true);
  assert.equal(h.evaluate("player.hls"), true);
  assert.equal(h.evaluate("player.source.url"), "https://radio.test/listen");
  assert.equal(h.evaluate("player.source.hlsUrl"), "https://radio.test/live");
});

test("a station rejected before native playback uses the selected backup at the listening volume", async () => {
  const h = createHarness({ navigator, invoke: (command, args) => {
    if (command.endsWith("|random_track")) return { path:"content://provider/tree/music/document/song", name:"Piano.ogg", total:1 };
    if (command.endsWith("|pause")) return { sleepTimer:{ revision:0, timer:null } };
    if (command.endsWith("|play")) return { status:"buffering", generation:args.payload.generation };
  } });
  h.evaluate("state.settings.backupFolder='content://provider/tree/music'; player.source={kind:'station',url:'https://radio.test/broken.pls',title:'Station'}; player.target=0.08; failure('Playlist unavailable',{fatal:true})");
  await flush();
  const call = h.calls.find(c => c.command.endsWith("|play"));
  assert.equal(call.args.payload.sourceFolder, "content://provider/tree/music");
  assert.equal(call.args.payload.volume, 0.08);
});
