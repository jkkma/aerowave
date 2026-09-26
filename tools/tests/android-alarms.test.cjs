const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const navigator = { userAgent: "Mozilla/5.0 (Linux; Android 13; Mobile)" };
const snapshot = (revision, ringing = null, extra = {}) => ({
  initialized: true, revision, alarms: [], next: null, ringing,
  permissions: { exact: "granted", notifications: "granted", fullScreen: "granted", batteryOptimized: true },
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
