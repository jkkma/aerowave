const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, Element } = require("./frontend-harness.cjs");

function editorHarness(options) {
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
  h.evaluate('clockNow = {hour:6, minute:0}; state.stations = [{id:"radio",name:"Morning radio"}]; wire()');
  return { h, days, presets };
}

test("repeat presets, custom days and Ring in keep the saved repeat and accessible state in sync", async () => {
  const { h, days, presets } = editorHarness({ invoke: command => command === "local_time" ? { hour: 8, minute: 15 } : undefined });
  h.evaluate("openAlarmEditor(null)");
  await presets[1].dispatch("click");
  assert.deepEqual(Array.from(h.evaluate("editorDays")), [0, 1, 2, 3, 4]);
  assert.equal(presets[1].getAttribute("aria-pressed"), "true");
  await days[4].dispatch("click");
  assert.deepEqual(Array.from(h.evaluate("editorDays")), [0, 1, 2, 3]);
  assert.equal(presets.every(button => button.getAttribute("aria-pressed") === "false"), true);
  assert.match(h.el("#al-dayhint").textContent, /Mon, Tue, Wed, Thu/);
  await h.evaluate("setEditorTimeIn(15)");
  assert.equal(days.every(button => button.getAttribute("aria-pressed") === "false"), true);
  assert.equal(presets[0].getAttribute("aria-pressed"), "true");
  assert.equal(h.evaluate("editorDays.length"), 0);
  assert.match(h.el("#al-dayhint").textContent, /Once.*08:15/);
});

test("a repeat choice supersedes an unresolved Ring in without resetting the chosen schedule", async () => {
  const local = deferred();
  const { h, presets } = editorHarness({ invoke: command => command === "local_time" ? local.promise : undefined });
  h.evaluate("openAlarmEditor(null)");
  const pending = h.evaluate("setEditorTimeIn(30)");
  await presets[2].dispatch("click");
  local.resolve({ hour: 8, minute: 30 });
  await pending;
  assert.deepEqual(Array.from(h.evaluate("editorDays")), [5, 6]);
  assert.equal(h.el("#al-hour").value, "07");
  assert.equal(h.evaluate("editorQuickMins"), 0);
});

test("cancel closes the focused editor and restores its trigger without saving draft changes", async () => {
  const { h, presets } = editorHarness();
  const trigger = h.el("#btn-add-alarm");
  trigger.focus();
  await trigger.dispatch("click");
  assert.equal(h.document.activeElement, h.el("#al-hour"));
  assert.equal(h.el("#pane-alarms").classList.contains("editing"), true);
  h.el("#al-label").value = "Unsaved";
  await presets[3].dispatch("click");
  await h.el("#al-cancel").dispatch("click");
  assert.equal(h.document.activeElement, trigger);
  assert.equal(h.el("#pane-alarms").classList.contains("editing"), false);
  assert.equal(h.evaluate("state.alarms.length"), 0);
  assert.equal(h.calls.some(call => call.command === "save_alarms"), false);
});

test("collapsed snooze options retain custom values and summarize their actual behavior", () => {
  const { h } = editorHarness();
  h.evaluate(`openAlarmEditor({id:"saved",hour:7,minute:30,days:[0,2,4],enabled:false,
    source:{kind:"folder",path:"C:/Music/Morning"},volume:0.67,fadeSecs:13,
    snoozeMins:12,autoStopMins:17,autoSnoozes:4})`);
  assert.equal(h.el("#al-options").open, false);
  assert.match(h.el("#al-options-summary").textContent, /Snooze 12 min · Ring 17 min · Auto-snooze 4×/);
  const alarm = h.evaluate("readAlarmEditor().alarm");
  assert.equal(alarm.snoozeMins, 12);
  assert.equal(alarm.autoStopMins, 17);
  assert.equal(alarm.autoSnoozes, 4);
  assert.equal(alarm.fadeSecs, 13);
  assert.equal(alarm.volume, 0.67);
  assert.equal(alarm.enabled, false);
  assert.equal(h.el("#al-folderpath").textContent, "Morning");
  assert.equal(h.el("#al-folderpath").title, "C:/Music/Morning");
  h.el("#al-autostop").value = "0";
  h.evaluate("syncAutoSnooze()");
  assert.equal(h.el("#al-autosnooze").disabled, true);
  assert.match(h.el("#al-options-summary").textContent, /Ring until dismissed/);
  assert.doesNotMatch(h.el("#al-options-summary").textContent, /Auto-snooze/);
});

test("alarm enable and edit are independent button controls, and labels stay plain text", async () => {
  let native = [{id:"saved",hour:7,minute:30,days:[0,2],enabled:true,
    label:"<img src=x>",source:{kind:"station",stationId:"radio"}}];
  const { h } = editorHarness({ invoke: (command, args) => {
    if (command === "save_alarms") native = args.alarms;
    if (command === "get_state") return { alarms: native };
  } });
  h.context.initial = native;
  h.evaluate("state.alarms = initial; renderAlarms()");
  const row = h.el("#alarm-list").children[0];
  const [edit, toggle] = row.children;
  const sw = toggle.children[1];
  assert.equal(edit.tagName, "BUTTON");
  assert.equal(edit.children[1].textContent, "<img src=x>");
  assert.equal(edit.children[2].textContent, "Mon, Wed");
  await sw.dispatch("click");
  assert.equal(h.evaluate("state.alarms[0].enabled"), false);
  assert.equal(h.evaluate("editingAlarm"), null);
  assert.equal(h.calls.filter(call => call.command === "save_alarms").length, 1);
  await h.el("#alarm-list").children[0].children[0].dispatch("click");
  assert.equal(h.evaluate("editingAlarm.id"), "saved");
});

for (const enabled of [true, false]) {
  test(`a failed alarm ${enabled ? "disable" : "enable"} leaves the confirmed schedule intact`, async () => {
    const clone = (value) => JSON.parse(JSON.stringify(value));
    const wake = { id: "wake", label: "Wake", hour: 7, minute: 0, days: [0, 1, 2, 3, 4],
      enabled, source: { kind: "station", stationId: "radio" } };
    const other = { ...wake, id: "other", label: "Other", hour: 20 };
    let native = clone([wake, other]);
    let saves = 0;
    const { h } = editorHarness({ invoke: (command, args) => {
      if (command === "save_alarms" && ++saves === 1) return Promise.reject(new Error("disk full"));
      if (command === "save_alarms") native = clone(args.alarms);
      if (command === "get_state") return { alarms: clone(native) };
    } });
    h.context.initial = clone(native);
    h.evaluate("state.alarms = initial; renderAlarms()");

    const row = h.el("#alarm-list").children.find((item) => item.dataset.id === "wake");
    await row.children[1].children[1].dispatch("click");
    assert.equal(h.evaluate("state.alarms.find(a => a.id === 'wake').enabled"), enabled);
    assert.equal(native[0].enabled, enabled);
    assert.equal(h.el("#alarm-list").children.find((item) => item.dataset.id === "wake")
      .children[1].children[1].getAttribute("aria-pressed"), String(enabled));

    await h.evaluate("saveAlarms(state.alarms.map(a => a.id === 'other' ? {...a, label: 'Changed'} : a))");
    assert.equal(native.find((alarm) => alarm.id === "wake").enabled, enabled);
    assert.equal(native.find((alarm) => alarm.id === "other").label, "Changed");
  });
}

test("a failed alarm deletion leaves the row and later saves preserve it", async () => {
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const wake = { id: "wake", label: "Wake", hour: 7, minute: 0, days: [0], enabled: true,
    source: { kind: "station", stationId: "radio" } };
  const other = { ...wake, id: "other", label: "Other", hour: 20 };
  let native = clone([wake, other]);
  let saves = 0;
  const { h } = editorHarness({ invoke: (command, args) => {
    if (command === "save_alarms" && ++saves === 1) return Promise.reject(new Error("disk full"));
    if (command === "save_alarms") native = clone(args.alarms);
    if (command === "get_state") return { alarms: clone(native) };
  } });
  h.context.initial = clone(native);
  h.evaluate("state.alarms = initial; renderAlarms(); openAlarmEditor(state.alarms[0])");

  await h.el("#al-delete").dispatch("click");
  assert.deepEqual(Array.from(h.evaluate("state.alarms.map(a => a.id)")), ["wake", "other"]);
  assert.equal(h.evaluate("editingAlarm.id"), "wake");
  assert.equal(h.evaluate("alarmEditorSaving"), false);
  assert.equal(h.el("#alarm-editor").getAttribute("aria-busy"), null);

  await h.evaluate("saveAlarms(state.alarms.map(a => a.id === 'other' ? {...a, label: 'Changed'} : a))");
  assert.deepEqual(native.map((alarm) => alarm.id), ["wake", "other"]);
  assert.equal(native[1].label, "Changed");
});

test("a successful deletion with failed readback cannot be revived by a stale alarm list", async () => {
  const wake = { id: "wake", label: "Wake", hour: 7, minute: 0, days: [0], enabled: true,
    source: { kind: "station", stationId: "radio" } };
  let native = [wake];
  let readbackFails = true;
  const { h } = editorHarness({ invoke: (command, args) => {
    if (command === "save_alarms") native = args.alarms;
    if (command === "get_state") return readbackFails
      ? Promise.reject(new Error("readback unavailable")) : { alarms: native };
  } });
  h.context.initial = [wake];
  h.evaluate("state.alarms = initial; renderAlarms(); openAlarmEditor(state.alarms[0])");

  await h.el("#al-delete").dispatch("click");
  assert.deepEqual(native, []);
  assert.equal(h.evaluate("state.alarms.length"), 1);
  assert.equal(h.evaluate("alarmReadbackPending"), true);
  assert.match(h.el("#al-sourcenote").textContent, /may have saved/);

  assert.equal(await h.evaluate("saveAlarms()"), false);
  assert.equal(h.calls.filter((call) => call.command === "save_alarms").length, 1);
  assert.deepEqual(native, []);

  readbackFails = false;
  assert.equal(await h.evaluate("saveAlarms()"), false);
  assert.equal(h.evaluate("state.alarms.length"), 0);
  assert.equal(h.evaluate("alarmReadbackPending"), false);
  assert.equal(h.calls.filter((call) => call.command === "save_alarms").length, 1);
  assert.equal(await h.evaluate("saveAlarms()"), true);
  assert.deepEqual(native, []);
});

test("alarm controls ignore another action while their save is pending", async () => {
  const pending = deferred();
  const wake = { id: "wake", label: "Wake", hour: 7, minute: 0, days: [0], enabled: true,
    source: { kind: "station", stationId: "radio" } };
  let native = [wake];
  const { h } = editorHarness({ invoke: (command, args) => {
    if (command === "save_alarms") return pending.promise.then(() => { native = args.alarms; });
    if (command === "get_state") return { alarms: native };
  } });
  h.context.initial = [wake];
  h.evaluate("state.alarms = initial; renderAlarms()");
  const switchButton = h.el("#alarm-list").children[0].children[1].children[1];
  const saving = switchButton.dispatch("click");
  assert.equal(h.el("#alarm-list").children[0].children[1].children[1].disabled, true);
  await switchButton.dispatch("click");
  assert.equal(h.calls.filter((call) => call.command === "save_alarms").length, 1);
  pending.resolve();
  await saving;
  assert.equal(h.evaluate("state.alarms[0].enabled"), false);
});
