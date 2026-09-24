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
  const { h } = editorHarness();
  h.evaluate(`state.alarms = [{id:"saved",hour:7,minute:30,days:[0,2],enabled:true,
    label:"<img src=x>",source:{kind:"station",stationId:"radio"}}]; renderAlarms()`);
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
