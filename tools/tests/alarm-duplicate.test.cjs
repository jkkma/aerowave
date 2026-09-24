const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush, Element } = require("./frontend-harness.cjs");

const androidNavigator = { userAgent: "Mozilla/5.0 (Linux; Android 15; Mobile)" };

function alarm(overrides = {}) {
  return {
    id: "original",
    label: "Morning",
    hour: 7,
    minute: 15,
    days: [0, 2, 4],
    enabled: true,
    source: { kind: "folder", path: "/music/morning" },
    volume: 0.72,
    fadeSecs: 13,
    snoozeMins: 12,
    autoStopMins: 17,
    autoSnoozes: 4,
    skipDate: "2031-02-03",
    ...overrides,
  };
}

function editorHarness(options = {}) {
  const h = createHarness(options);
  const days = Array.from({ length: 7 }, (_, day) => {
    const button = new Element("button");
    button.dataset.day = String(day);
    return button;
  });
  const presets = ["once", "weekdays", "weekends", "daily"].map((repeat) => {
    const button = new Element("button");
    button.dataset.repeat = repeat;
    return button;
  });
  h.queries.set("#al-days button", days);
  h.queries.set("#al-repeat-presets .chip", presets);
  h.evaluate('clockNow={hour:6,minute:0}; state.stations=[{id:"radio",name:"Morning radio"}]; wire()');
  return h;
}

function row(h, id) {
  return h.el("#alarm-list").children.find((item) => item.dataset.id === id);
}

function duplicateButton(h, id) {
  return row(h, id)?.querySelector(".alarm-duplicate") || null;
}

function draft(h) {
  return JSON.parse(JSON.stringify(h.evaluate("readAlarmEditor().alarm")));
}

function jsonClone(value) {
  return JSON.parse(JSON.stringify(value));
}

function savedAlarmSnapshot(h, id) {
  return JSON.stringify(h.evaluate(`state.alarms.find((item) => item.id === ${JSON.stringify(id)})`));
}

function androidSnapshot(revision, alarms) {
  return {
    initialized: true,
    revision,
    alarms,
    occurrences: [],
    next: null,
    ringing: null,
    permissions: { exact: "granted", notifications: "granted", fullScreen: "granted", batteryOptimized: false },
    error: null,
  };
}

test("every saved alarm row can be duplicated, including disabled and one-shot alarms", async () => {
  const h = editorHarness();
  const disabled = alarm({ id: "disabled", enabled: false });
  const once = alarm({ id: "once", label: "One time", days: [], skipDate: null });
  const recurring = alarm({ id: "recurring", label: "Routine" });
  h.context.alarms = [disabled, once, recurring];
  h.evaluate("state.alarms=alarms; renderAlarms()");

  for (const id of ["disabled", "once", "recurring"]) {
    const button = duplicateButton(h, id);
    assert.ok(button, `${id} row has a Duplicate action`);
    assert.equal(button.disabled, false);
  }

  const original = savedAlarmSnapshot(h, "disabled");
  await duplicateButton(h, "disabled").dispatch("click");
  assert.equal(h.evaluate("editingAlarm"), null);
  assert.equal(h.el("#al-editor-title").textContent, "Duplicate alarm");
  const copy = draft(h);
  assert.notEqual(copy.id, disabled.id);
  assert.equal(copy.label, "Morning (copy)");
  assert.equal(copy.hour, disabled.hour);
  assert.equal(copy.minute, disabled.minute);
  assert.deepEqual(copy.days, disabled.days);
  assert.equal(copy.enabled, false);
  assert.deepEqual(copy.source, disabled.source);
  assert.equal(copy.volume, disabled.volume);
  assert.equal(copy.fadeSecs, disabled.fadeSecs);
  assert.equal(copy.snoozeMins, disabled.snoozeMins);
  assert.equal(copy.autoStopMins, disabled.autoStopMins);
  assert.equal(copy.autoSnoozes, disabled.autoSnoozes);
  assert.equal(Object.hasOwn(copy, "skipDate"), false);
  assert.equal(savedAlarmSnapshot(h, "disabled"), original);
  await h.el("#al-cancel").dispatch("click");

  await duplicateButton(h, "once").dispatch("click");
  assert.deepEqual(draft(h).days, []);
  assert.equal(draft(h).enabled, true);
  await h.el("#al-cancel").dispatch("click");
});

test("duplicate labels use the first free copy suffix and blank labels use Alarm", async () => {
  const h = editorHarness();
  const source = alarm();
  const blank = alarm({ id: "blank", label: "", days: [] });
  h.context.alarms = [source, alarm({ id: "existing-copy", label: "Morning (copy)" }),
    blank, alarm({ id: "existing-alarm-copy", label: "Alarm (copy)" })];
  h.evaluate("state.alarms=alarms; renderAlarms()");

  await duplicateButton(h, "original").dispatch("click");
  assert.equal(h.el("#al-label").value, "Morning (copy 2)");
  await h.el("#al-cancel").dispatch("click");

  await duplicateButton(h, "blank").dispatch("click");
  assert.equal(h.el("#al-label").value, "Alarm (copy 2)");
});

test("opening, testing, and cancelling a copy leaves the saved alarm untouched", async () => {
  const h = editorHarness({ invoke: (command) => command === "test_alarm" ? {
    alarmId: "preview", trigger: "test", kind: "none", label: "Preview", title: "",
    hour: 8, minute: 15, snoozeMins: 10, volume: 0.8, fadeSecs: 0, autoStopMins: 0,
  } : undefined });
  const original = alarm();
  h.context.alarms = [original];
  h.evaluate("state.alarms=alarms; renderAlarms()");
  const before = savedAlarmSnapshot(h, "original");

  await duplicateButton(h, "original").dispatch("click");
  const firstId = draft(h).id;
  assert.equal(draft(h).id, firstId);
  h.el("#al-hour").value = "08";
  await h.el("#al-test").dispatch("click");
  const preview = h.calls.find((call) => call.command === "test_alarm");
  assert.equal(preview.args.alarm.id, firstId);
  assert.equal(preview.args.alarm.hour, 8);
  assert.equal(h.calls.some((call) => call.command === "save_alarms"), false);
  assert.equal(h.evaluate("state.alarms.length"), 1);
  assert.equal(savedAlarmSnapshot(h, "original"), before);

  await h.el("#al-cancel").dispatch("click");
  assert.equal(h.evaluate("editingAlarm"), null);
  assert.equal(h.evaluate("alarmEditorSaving"), false);
  assert.equal(h.evaluate("state.alarms.length"), 1);
  assert.equal(savedAlarmSnapshot(h, "original"), before);
  assert.equal(h.calls.some((call) => call.command === "save_alarms"), false);
});

test("saving a copy adds one new alarm and preserves zero volume and the original skip", async () => {
  const original = alarm({ volume: 0 });
  let backendAlarms = [jsonClone(original)];
  const h = editorHarness({ invoke: (command, args) => {
    if (command === "save_alarms") backendAlarms = jsonClone(args.alarms);
    if (command === "get_state") return { stations: [], alarms: jsonClone(backendAlarms), settings: {} };
  } });
  h.context.alarms = [original];
  h.evaluate("state.alarms=alarms; renderAlarms()");
  const before = savedAlarmSnapshot(h, "original");

  await duplicateButton(h, "original").dispatch("click");
  const copyId = draft(h).id;
  assert.equal(h.el("#al-volume").min, 0);
  assert.equal(draft(h).volume, 0);
  await h.el("#alarm-editor").dispatch("submit");

  assert.equal(h.calls.filter((call) => call.command === "save_alarms").length, 1);
  assert.equal(h.evaluate("state.alarms.length"), 2);
  assert.equal(savedAlarmSnapshot(h, "original"), before);
  const saved = JSON.parse(JSON.stringify(h.evaluate(`state.alarms.find((item) => item.id === ${JSON.stringify(copyId)})`)));
  assert.ok(saved);
  assert.equal(saved.id, copyId);
  assert.equal(saved.label, "Morning (copy)");
  assert.equal(saved.volume, 0);
  assert.equal(saved.skipDate, undefined);
});

test("a pending copy save ignores a second submit", async () => {
  const request = deferred();
  const original = alarm();
  let backendAlarms = [jsonClone(original)];
  const h = editorHarness({ invoke: (command, args) => {
    if (command === "save_alarms") return request.promise.then(() => {
      backendAlarms = jsonClone(args.alarms);
      return null;
    });
    if (command === "get_state") return { stations: [], alarms: jsonClone(backendAlarms), settings: {} };
  } });
  h.context.alarms = [original];
  h.evaluate("state.alarms=alarms; renderAlarms()");
  await duplicateButton(h, "original").dispatch("click");

  const first = h.el("#alarm-editor").dispatch("submit");
  await flush();
  assert.equal(h.evaluate("alarmEditorSaving"), true);
  await h.el("#alarm-editor").dispatch("submit");
  assert.equal(h.calls.filter((call) => call.command === "save_alarms").length, 1);

  request.resolve(null);
  await first;
  assert.equal(h.evaluate("state.alarms.length"), 2);
  assert.equal(h.calls.filter((call) => call.command === "save_alarms").length, 1);
});

test("a failed copy save leaves no phantom row and retries with the same draft id", async () => {
  let saves = 0;
  const original = alarm();
  let backendAlarms = [jsonClone(original)];
  const h = editorHarness({ invoke: (command, args) => {
    if (command === "save_alarms" && ++saves === 1) return Promise.reject(new Error("temporary failure"));
    if (command === "save_alarms") {
      backendAlarms = jsonClone(args.alarms);
      return null;
    }
    if (command === "get_state") return { stations: [], alarms: jsonClone(backendAlarms), settings: {} };
  } });
  h.context.alarms = [original];
  h.evaluate("state.alarms=alarms; renderAlarms()");
  const before = savedAlarmSnapshot(h, "original");
  await duplicateButton(h, "original").dispatch("click");
  const copyId = draft(h).id;

  await h.el("#alarm-editor").dispatch("submit");
  assert.equal(h.evaluate("state.alarms.length"), 1);
  assert.equal(savedAlarmSnapshot(h, "original"), before);
  assert.equal(h.evaluate("editingAlarm"), null);
  assert.equal(draft(h).id, copyId);

  await h.el("#alarm-editor").dispatch("submit");
  assert.equal(h.calls.filter((call) => call.command === "save_alarms").length, 2);
  assert.equal(h.evaluate("state.alarms.length"), 2);
  assert.equal(h.evaluate(`state.alarms.filter((item) => item.id === ${JSON.stringify(copyId)}).length`), 1);
  assert.equal(savedAlarmSnapshot(h, "original"), before);
});

test("a one-shot disabled during a delayed copy save stays disabled after readback", async () => {
  const saveReply = deferred();
  const original = alarm({ days: [], skipDate: null });
  let backendAlarms = [jsonClone(original)];
  let submitted = null;
  const h = editorHarness({ invoke: (command, args) => {
    if (command === "save_alarms") {
      submitted = jsonClone(args.alarms);
      backendAlarms = jsonClone(args.alarms);
      return saveReply.promise;
    }
    if (command === "get_state") return { stations: [], alarms: jsonClone(backendAlarms), settings: {} };
  } });
  h.context.alarms = [original];
  h.evaluate("state.alarms=alarms; renderAlarms()");
  await duplicateButton(h, "original").dispatch("click");
  const copyId = draft(h).id;

  const saving = h.el("#alarm-editor").dispatch("submit");
  await flush();
  assert.equal(submitted.length, 2);
  backendAlarms = backendAlarms.map((item) => item.id === copyId ? { ...item, enabled: false } : item);
  await h.evaluate("refreshDesktopAlarms()");
  assert.equal(h.evaluate(`state.alarms.find((item) => item.id === ${JSON.stringify(copyId)}).enabled`), false);

  saveReply.resolve(null);
  await saving;
  assert.equal(h.evaluate(`state.alarms.find((item) => item.id === ${JSON.stringify(copyId)}).enabled`), false);
  assert.equal(h.evaluate("state.alarms.length"), 2);
});

test("a delayed older desktop read cannot replace a newer alarm refresh", async () => {
  const olderRead = deferred();
  const oldAlarms = [alarm({ enabled: true })];
  const newAlarms = [alarm({ enabled: false })];
  let reads = 0;
  const h = editorHarness({ invoke: (command) => {
    if (command !== "get_state") return undefined;
    return ++reads === 1 ? olderRead.promise : { stations: [], alarms: jsonClone(newAlarms), settings: {} };
  } });
  h.context.alarms = oldAlarms;
  h.evaluate("state.alarms=alarms; renderAlarms()");

  const older = h.evaluate("refreshDesktopAlarms()");
  await flush();
  await h.evaluate("refreshDesktopAlarms()");
  assert.equal(h.evaluate("state.alarms[0].enabled"), false);
  olderRead.resolve({ stations: [], alarms: jsonClone(oldAlarms), settings: {} });
  await older;
  assert.equal(h.evaluate("state.alarms[0].enabled"), false);
});

test("Android saves a copied draft through revision-checked alarm sync", async () => {
  let revision = 7;
  let canonical = [alarm({ enabled: false, days: [] })];
  const h = editorHarness({ navigator: androidNavigator, invoke: (command, args) => {
    if (command.endsWith("|sync_alarms")) {
      const payload = args.payload;
      assert.equal(payload.expectedRevision, revision);
      canonical = payload.alarms || canonical;
      revision += 1;
      return androidSnapshot(revision, canonical);
    }
    if (command.endsWith("|get_alarm_state")) return androidSnapshot(revision, canonical);
  } });
  h.context.androidFixture = androidSnapshot(revision, canonical);
  h.evaluate("applyAndroidAlarmState(androidFixture); renderAlarms()");
  const before = savedAlarmSnapshot(h, "original");
  await duplicateButton(h, "original").dispatch("click");
  const copyId = draft(h).id;
  await h.el("#alarm-editor").dispatch("submit");

  const sync = h.calls.find((call) => call.command.endsWith("|sync_alarms"));
  assert.ok(sync);
  const payload = JSON.parse(JSON.stringify(sync.args.payload));
  assert.equal(payload.expectedRevision, 7);
  assert.equal(payload.alarms.length, 2);
  const copied = payload.alarms.find((item) => item.id === copyId);
  assert.ok(copied);
  assert.equal(copied.enabled, false);
  assert.deepEqual(copied.days, []);
  assert.equal(copied.skipDate, undefined);
  assert.equal(h.evaluate("state.alarms.length"), 2);
  assert.equal(savedAlarmSnapshot(h, "original"), before);
});
