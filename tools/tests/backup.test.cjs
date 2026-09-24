const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const androidNavigator = { userAgent: "Mozilla/5.0 (Linux; Android 15; Mobile)" };

function jsonClone(value) {
  return JSON.parse(JSON.stringify(value));
}

function appData(overrides = {}) {
  return {
    stations: [{ id: "current", name: "Current station", url: "https://radio.test/current" }],
    alarms: [{ id: "current-alarm", label: "Current alarm", hour: 7, minute: 0, days: [], enabled: true,
      source: { kind: "station", stationId: "current" } }],
    settings: { volume: 0.4, backupFolder: null, shuffleFolder: null },
    ...overrides,
  };
}

function backupHarness(options = {}) {
  const h = createHarness(options);
  h.context.initialState = appData();
  h.evaluate("state=initialState; wire()");
  return h;
}

function stateSnapshot(h) {
  return JSON.stringify(h.evaluate("state"));
}

test("desktop export waits for pending settings writes before creating and saving its backup", async () => {
  const settingsWrite = deferred();
  let backupContent = null;
  const h = backupHarness({ invoke: (command, args) => {
    if (command === "save_settings") return settingsWrite.promise;
    if (command === "create_backup") { backupContent = "canonical desktop backup"; return backupContent; }
    if (command === "save_backup_file") return "C:/Backups/aerowave.json";
  } });
  h.evaluate("state.settings.volume=0.63; saveSettings() ");

  const exporting = h.el("#backup-export").dispatch("click");
  await flush();
  assert.equal(h.evaluate("backupBusy"), true);
  assert.equal(h.el("#backup-export").disabled, true);
  assert.equal(h.el("#backup-import").disabled, true);
  assert.equal(h.calls.some((call) => call.command === "create_backup"), false);

  settingsWrite.resolve(null);
  await exporting;
  const commands = h.calls.map((call) => call.command).filter((command) =>
    ["save_settings", "create_backup", "save_backup_file"].includes(command));
  assert.deepEqual(commands, ["save_settings", "create_backup", "save_backup_file"]);
  const create = h.calls.find((call) => call.command === "create_backup");
  assert.equal(create.args, undefined);
  const save = h.calls.find((call) => call.command === "save_backup_file");
  assert.equal(save.args.content, backupContent);
  assert.equal(h.evaluate("backupBusy"), false);
});

test("Android export uses the canonical backend snapshot and unified file commands", async () => {
  const h = backupHarness({ navigator: androidNavigator, invoke: (command) => {
    if (command === "create_backup") return "android-backup-content";
    if (command === "save_backup_file") return "/storage/emulated/0/Download/aerowave.json";
    if (command === "read_backup_file") return "android-backup-content";
    if (command === "inspect_backup") return { stationCount: 1, alarmCount: 1, warnings: [] };
  } });
  h.evaluate('state.alarms=[{id:"stale-webview-copy",enabled:true}]');

  await h.evaluate("exportSetup()");
  const create = h.calls.find((call) => call.command === "create_backup");
  assert.equal(create.args, undefined);
  const fileSave = h.calls.find((call) => call.command === "save_backup_file");
  assert.ok(fileSave);
  assert.deepEqual(jsonClone(fileSave.args), { content: "android-backup-content" });
  assert.equal(h.el("#backup-status").textContent, "Backup exported. Keep the file somewhere safe.");

  await h.evaluate("previewSetupRestore()");
  assert.equal(h.calls.filter((call) => call.command === "read_backup_file").length, 1);
  assert.equal(h.evaluate("backupRestoreContent"), "android-backup-content");
});

test("a valid backup preview shows its summary and warnings without changing current setup", async () => {
  const content = '{"version":1,"stations":[],"alarms":[]}';
  const h = backupHarness({ invoke: (command) => {
    if (command === "read_backup_file") return content;
    if (command === "inspect_backup") return { stationCount: 2, alarmCount: 3, warnings: ["Folders may need attention."] };
  } });
  h.evaluate('state.stations.push({id:"extra",name:"Extra station",url:"https://radio.test/extra"}); state.alarms.push({id:"extra-alarm",hour:8,minute:0,days:[],enabled:false,source:{kind:"station",stationId:"extra"}})');
  const before = stateSnapshot(h);

  await h.el("#backup-import").dispatch("click");
  assert.equal(h.el("#backup-preview").open, true);
  assert.match(h.el("#backup-summary").textContent, /2 stations · 3 alarms/);
  assert.match(h.el("#backup-summary").textContent, /current setup has 2 stations and 2 alarms/);
  assert.equal(h.el("#backup-error").textContent, "Folders may need attention.");
  assert.equal(h.evaluate("backupRestoreContent"), content);
  assert.equal(stateSnapshot(h), before);
  assert.equal(h.calls.some((call) => call.command === "restore_backup"), false);
});

test("backup validation failure never opens the preview or changes setup", async () => {
  const content = "not a valid backup";
  const h = backupHarness({ invoke: (command) => {
    if (command === "read_backup_file") return content;
    if (command === "inspect_backup") throw new Error("Backup data is invalid");
  } });
  const before = stateSnapshot(h);

  await h.evaluate("previewSetupRestore()");
  assert.notEqual(h.el("#backup-preview").open, true);
  assert.equal(h.evaluate("backupRestoreContent"), null);
  assert.equal(stateSnapshot(h), before);
  assert.match(h.el("#backup-status").textContent, /Backup data is invalid/);
  assert.equal(h.calls.some((call) => call.command === "restore_backup"), false);
});

test("cancelling a preview clears its pending restore without mutating setup", async () => {
  const content = "validated backup";
  const h = backupHarness({ invoke: (command) => {
    if (command === "read_backup_file") return content;
    if (command === "inspect_backup") return { stationCount: 1, alarmCount: 0, warnings: [] };
  } });
  const before = stateSnapshot(h);
  await h.evaluate("previewSetupRestore()");
  assert.equal(h.el("#backup-preview").open, true);
  await h.el("#backup-cancel").dispatch("click");
  assert.equal(h.el("#backup-preview").open, false);
  assert.equal(h.evaluate("backupRestoreContent"), null);
  assert.equal(h.document.activeElement, h.el("#backup-import"));
  assert.equal(stateSnapshot(h), before);
  assert.equal(h.calls.some((call) => call.command === "restore_backup"), false);
});

test("restore changes setup only after the backend returns the restored data", async () => {
  const restoreReply = deferred();
  const restored = appData({
    stations: [{ id: "imported", name: "Imported station", url: "https://radio.test/imported" }],
    alarms: [{ id: "imported-alarm", label: "Imported alarm", hour: 9, minute: 5, days: [0], enabled: false,
      source: { kind: "station", stationId: "imported" } }],
    settings: { volume: 0.8, backupFolder: null, shuffleFolder: null },
  });
  const h = backupHarness({ invoke: (command) => {
    if (command === "read_backup_file") return "restorable backup";
    if (command === "inspect_backup") return { stationCount: 1, alarmCount: 1, warnings: [] };
    if (command === "restore_backup") return restoreReply.promise;
  } });
  const before = stateSnapshot(h);
  await h.evaluate("previewSetupRestore()");
  const restoring = h.el("#backup-restore").dispatch("click");
  await flush();
  assert.equal(h.evaluate("backupBusy"), true);
  assert.equal(stateSnapshot(h), before);

  restoreReply.resolve(restored);
  await restoring;
  assert.deepEqual(jsonClone(h.evaluate("state")), restored);
  assert.equal(h.el("#backup-preview").open, false);
  assert.equal(h.evaluate("backupRestoreContent"), null);
  assert.match(h.el("#backup-status").textContent, /Backup restored/);
});

test("a rejected restore retains current setup and the validated preview for retry", async () => {
  let attempts = 0;
  const restored = appData({ stations: [{ id: "imported", name: "Imported", url: "https://radio.test/imported" }] });
  const h = backupHarness({ invoke: (command, args) => {
    if (command === "read_backup_file") return "backup with active alarm";
    if (command === "inspect_backup") return { stationCount: 1, alarmCount: 1, warnings: [] };
    if (command === "restore_backup") {
      assert.equal(args.content, "backup with active alarm");
      if (++attempts === 1) throw new Error("An alarm is active; finish it before restoring.");
      return restored;
    }
  } });
  const before = stateSnapshot(h);
  await h.evaluate("previewSetupRestore()");
  await h.evaluate("restoreSetup()");
  assert.equal(stateSnapshot(h), before);
  assert.equal(h.el("#backup-preview").open, true);
  assert.equal(h.evaluate("backupRestoreContent"), "backup with active alarm");
  assert.match(h.el("#backup-error").textContent, /alarm is active/);

  await h.evaluate("restoreSetup()");
  assert.equal(attempts, 2);
  assert.equal(h.el("#backup-preview").open, false);
  assert.equal(h.evaluate("backupRestoreContent"), null);
  assert.deepEqual(jsonClone(h.evaluate("state.stations")), restored.stations);
});

test("busy preview and restore actions ignore repeated clicks and cancellation", async () => {
  const fileRead = deferred();
  const restoreReply = deferred();
  const restored = appData({ stations: [{ id: "imported", name: "Imported", url: "https://radio.test/imported" }] });
  const h = backupHarness({ invoke: (command) => {
    if (command === "read_backup_file") return fileRead.promise;
    if (command === "inspect_backup") return { stationCount: 1, alarmCount: 1, warnings: [] };
    if (command === "restore_backup") return restoreReply.promise;
  } });

  const previewing = h.el("#backup-import").dispatch("click");
  await flush();
  assert.equal(h.evaluate("backupBusy"), true);
  assert.equal(h.el("#backup-export").disabled, true);
  assert.equal(h.el("#backup-import").disabled, true);
  assert.equal(h.el("#backup-restore").disabled, true);
  assert.equal(h.el("#backup-cancel").disabled, true);
  await h.el("#backup-export").dispatch("click");
  assert.equal(h.calls.some((call) => call.command === "create_backup"), false);
  await h.el("#backup-import").dispatch("click");
  assert.equal(h.calls.filter((call) => call.command === "read_backup_file").length, 1);
  fileRead.resolve("busy-guard backup");
  await previewing;
  assert.equal(h.el("#backup-preview").open, true);

  const restoring = h.el("#backup-restore").dispatch("click");
  await flush();
  assert.equal(h.evaluate("backupBusy"), true);
  await h.el("#backup-restore").dispatch("click");
  await h.el("#backup-cancel").dispatch("click");
  assert.equal(h.calls.filter((call) => call.command === "restore_backup").length, 1);
  assert.equal(h.el("#backup-preview").open, true);
  assert.equal(h.evaluate("backupRestoreContent"), "busy-guard backup");

  restoreReply.resolve(restored);
  await restoring;
  assert.equal(h.evaluate("backupBusy"), false);
  assert.equal(h.el("#backup-preview").open, false);
});

test("Previous setup previews the recovery copy and cancel leaves the live setup untouched", async () => {
  const h = backupHarness({ invoke: (command) => {
    if (command === "read_recovery_backup") return "previous setup content";
    if (command === "inspect_backup") return { stationCount: 4, alarmCount: 2, warnings: ["This is the previous setup."] };
  } });
  const before = stateSnapshot(h);

  await h.el("#backup-previous").dispatch("click");
  assert.equal(h.calls.filter((call) => call.command === "read_recovery_backup").length, 1);
  assert.equal(h.calls.some((call) => call.command === "read_backup_file"), false);
  assert.equal(h.el("#backup-preview").open, true);
  assert.equal(h.evaluate("backupRestoreContent"), "previous setup content");
  assert.match(h.el("#backup-summary").textContent, /4 stations · 2 alarms/);
  assert.equal(h.el("#backup-error").textContent, "This is the previous setup.");

  await h.el("#backup-cancel").dispatch("click");
  assert.equal(h.evaluate("backupRestoreContent"), null);
  assert.equal(h.el("#backup-preview").open, false);
  assert.equal(stateSnapshot(h), before);
  assert.equal(h.calls.some((call) => call.command === "restore_backup"), false);
});

test("Previous setup reports clearly when no recovery copy exists", async () => {
  const h = backupHarness({ invoke: (command) => {
    if (command === "read_recovery_backup") return null;
  } });
  const before = stateSnapshot(h);

  await h.el("#backup-previous").dispatch("click");
  assert.equal(h.calls.filter((call) => call.command === "read_recovery_backup").length, 1);
  assert.equal(h.calls.some((call) => call.command === "read_backup_file" || call.command === "inspect_backup"), false);
  assert.notEqual(h.el("#backup-preview").open, true);
  assert.equal(h.evaluate("backupRestoreContent"), null);
  assert.equal(h.el("#backup-status").textContent, "No previous setup yet. A recovery copy is kept when you restore a backup.");
  assert.equal(stateSnapshot(h), before);
});

test("restore drains writes already in flight and blocks new persistence and playback until it finishes", async () => {
  const settingsWrite = deferred();
  const stationWrite = deferred();
  const restoreReply = deferred();
  const restored = appData({
    stations: [{ id: "restored", name: "Restored station", url: "https://radio.test/restored" }],
    settings: { volume: 0.72, backupFolder: null, shuffleFolder: null },
  });
  const h = backupHarness({ invoke: (command) => {
    if (command === "read_backup_file") return "restore while playing";
    if (command === "inspect_backup") return { stationCount: 1, alarmCount: 1, warnings: [] };
    if (command === "save_settings") return settingsWrite.promise;
    if (command === "save_stations") return stationWrite.promise;
    if (command === "restore_backup") return restoreReply.promise;
  } });

  h.evaluate('state.settings.volume=0.55; saveSettings()');
  const oldStationWrite = h.evaluate("saveStations()");
  await flush();
  assert.equal(h.calls.some((call) => call.command === "save_stations"), true);

  await h.evaluate("previewSetupRestore()");
  const restoring = h.evaluate("restoreSetup()");
  assert.equal(h.evaluate("setupRestorePending"), true);

  // These are the same paths used by settings controls, station edits,
  // direct playback, and an advancing audio element during the restore.
  h.evaluate('state.settings.volume=0.99; saveSettings(); saveStations(); playStation(state.stations[0]); player.source={kind:"station",url:"https://radio.test/current",stationId:"current",title:"Current station"}; audio.paused=false; audio.readyState=3; audio.currentTime=1');
  await h.audios[0].dispatch("timeupdate");
  await flush();
  assert.equal(h.calls.filter((call) => call.command === "save_settings").length, 1);
  assert.equal(h.calls.filter((call) => call.command === "save_stations").length, 1);
  assert.equal(h.calls.some((call) => call.command === "probe_stream" || call.command === "relay_url"), false);
  assert.equal(h.evaluate("recentStations().length"), 0);
  assert.equal(h.calls.some((call) => call.command === "restore_backup"), false);

  stationWrite.resolve(null);
  settingsWrite.resolve(null);
  await oldStationWrite;
  await flush();
  assert.equal(h.calls.filter((call) => call.command === "save_settings").length, 1);
  assert.equal(h.calls.filter((call) => call.command === "save_stations").length, 1);
  assert.equal(h.calls.filter((call) => call.command === "restore_backup").length, 1);

  restoreReply.resolve(restored);
  await restoring;
  assert.equal(h.evaluate("setupRestorePending"), false);
  assert.deepEqual(jsonClone(h.evaluate("state")), restored);
  assert.equal(h.calls.filter((call) => call.command === "save_settings").length, 1);
  assert.equal(h.calls.filter((call) => call.command === "save_stations").length, 1);
});

test("get_state reads started before or during restore cannot replace restored setup", async () => {
  const beforeRead = deferred();
  const duringRead = deferred();
  const restoreReply = deferred();
  const restored = appData({
    stations: [{ id: "restored", name: "Restored station", url: "https://radio.test/restored" }],
    alarms: [{ id: "restored-alarm", label: "Restored alarm", hour: 10, minute: 30, days: [1], enabled: false,
      source: { kind: "station", stationId: "restored" } }],
    settings: { volume: 0.21, backupFolder: null, shuffleFolder: null },
  });
  const h = backupHarness({ invoke: (command) => {
    if (command === "get_state") {
      const reads = h.calls.filter((call) => call.command === "get_state").length;
      return reads === 1 ? beforeRead.promise : duringRead.promise;
    }
    if (command === "read_backup_file") return "restore with stale reads";
    if (command === "inspect_backup") return { stationCount: 1, alarmCount: 1, warnings: [] };
    if (command === "restore_backup") return restoreReply.promise;
  } });

  const readStartedBefore = h.evaluate("loadState()");
  await flush();
  assert.equal(h.calls.filter((call) => call.command === "get_state").length, 1);

  await h.evaluate("previewSetupRestore()");
  const restoring = h.evaluate("restoreSetup()");
  await flush();
  assert.equal(h.evaluate("setupRestorePending"), true);
  const readStartedDuring = h.evaluate("loadState()");
  await flush();
  assert.equal(h.calls.filter((call) => call.command === "get_state").length, 2);

  restoreReply.resolve(restored);
  await restoring;
  assert.deepEqual(jsonClone(h.evaluate("state")), restored);

  beforeRead.resolve(appData({
    stations: [{ id: "stale-before", name: "Stale before", url: "https://radio.test/stale-before" }],
    settings: { volume: 0.91, backupFolder: null, shuffleFolder: null },
  }));
  duringRead.resolve(appData({
    stations: [{ id: "stale-during", name: "Stale during", url: "https://radio.test/stale-during" }],
    settings: { volume: 0.88, backupFolder: null, shuffleFolder: null },
  }));
  await Promise.all([readStartedBefore, readStartedDuring]);
  assert.deepEqual(jsonClone(h.evaluate("state")), restored);
});
