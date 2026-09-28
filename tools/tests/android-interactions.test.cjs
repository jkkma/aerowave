const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness } = require("./frontend-harness.cjs");

function makeHarness(android) {
  const h = createHarness({ navigator: { userAgent: android ? "Android 13" : "Windows NT 10.0" } });
  const names = ["radio", "browse", "alarms", "settings"];
  h.queries.set(".tab", names.map((name, index) => {
    const tab = h.el(`#tab-${name}`);
    tab.dataset.pane = name;
    tab.classList.toggle("on", index === 0);
    return tab;
  }));
  h.queries.set(".pane", names.map((name, index) => {
    const pane = h.el(`#pane-${name}`);
    pane.id = `pane-${name}`;
    pane.classList.toggle("on", index === 0);
    return pane;
  }));
  h.el("#station-editor").classList.add("hidden");
  h.el("#alarm-editor").classList.add("hidden");
  for (const id of ["#station-filter", "#st-name", "#al-hour", "#al-minute", "#al-label", "#al-name-input"]) {
    h.el(id).tagName = "INPUT";
  }
  h.queries.set(".stepper", ["hour", "minute"].map((step) => {
    const button = h.document.createElement("button");
    button.dataset.step = step;
    button.dataset.dir = "1";
    return button;
  }));
  h.evaluate('clockNow = { hour: 6, minute: 0 }; state.stations = [{ id: "radio", name: "Morning radio", url: "https://radio.test/stream" }]; wire(); renderStations()');
  return h;
}

test("Android editors open with a visible title focused, then close the keyboard and restore their trigger", async () => {
  const h = makeHarness(true);
  const stationTrigger = h.el("#btn-add-station");
  stationTrigger.focus();
  await stationTrigger.dispatch("click");
  assert.equal(h.document.activeElement, h.el("#station-editor-title"));
  const stationName = h.el("#st-name");
  let stationBlurred = false;
  const blurStationName = stationName.blur.bind(stationName);
  stationName.blur = () => { stationBlurred = true; blurStationName(); };
  stationName.focus();
  stationName.value = "Unsaved";
  await h.el("#st-cancel").dispatch("click");
  assert.equal(stationBlurred, true);
  assert.equal(h.document.activeElement, stationTrigger);
  assert.equal(h.el("#station-editor").classList.contains("hidden"), true);
  assert.equal(h.evaluate("state.stations.length"), 1);

  h.evaluate("renderAlarms()");
  const alarmTrigger = h.el("#alarm-list").querySelector(".alarm-empty-create");
  assert.equal(h.el("#btn-add-alarm").hidden, true);
  alarmTrigger.focus();
  await alarmTrigger.dispatch("click");
  assert.equal(h.document.activeElement, h.el("#al-editor-title"));
  const hour = h.el("#al-hour");
  let hourBlurred = false;
  const blurHour = hour.blur.bind(hour);
  hour.blur = () => { hourBlurred = true; blurHour(); };
  hour.focus();
  h.el("#al-label").value = "Unsaved";
  await h.el("#al-cancel").dispatch("click");
  assert.equal(hourBlurred, true);
  assert.equal(h.document.activeElement, alarmTrigger);
  assert.equal(h.el("#alarm-editor").classList.contains("hidden"), true);
  assert.equal(h.evaluate("state.alarms.length"), 0);
  assert.equal(h.calls.some(({ command }) => command === "save_stations" || command === "save_alarms"), false);
});

test("desktop editors still focus the first field", () => {
  const h = makeHarness(false);
  h.evaluate("openStationEditor(null)");
  assert.equal(h.document.activeElement, h.el("#st-name"));
  h.evaluate("closeStationEditor(); openAlarmEditor(null)");
  assert.equal(h.document.activeElement, h.el("#al-hour"));
  assert.equal(h.el("#al-hour").readOnly, undefined);
  assert.equal(h.el("#al-minute").readOnly, undefined);
  assert.equal(h.el("#al-label").readOnly, undefined);
});

test("Android alarm time stays keyboard-free while its stepper controls still change it", async () => {
  const h = makeHarness(true);
  h.evaluate("openAlarmEditor(null)");
  for (const id of ["#al-hour", "#al-minute"]) {
    assert.equal(h.el(id).readOnly, true);
    assert.equal(h.el(id).getAttribute("inputmode"), "none");
  }
  assert.equal(h.el("#al-label").readOnly, true);
  assert.equal(h.el("#al-name-dialog").open, undefined);
  const [hourUp, minuteUp] = h.queries.get(".stepper");
  await hourUp.dispatch("click");
  await minuteUp.dispatch("click");
  assert.equal(h.el("#al-hour").value, "08");
  assert.equal(h.el("#al-minute").value, "01");
});

test("Android Edit name is explicit; Done commits and Cancel keeps the prior name", async () => {
  const h = makeHarness(true);
  h.evaluate("openAlarmEditor(null)");
  assert.equal(h.el("#al-name-value").textContent, "Optional name");
  assert.equal(h.el("#al-name-dialog").open, undefined);
  await h.el("#al-name-edit").dispatch("click");
  assert.equal(h.el("#al-name-dialog").open, true);
  assert.equal(h.document.activeElement, h.el("#al-name-input"));
  h.el("#al-name-input").value = "  Morning  ";
  await h.el("#al-name-done").dispatch("click");
  assert.equal(h.el("#al-name-dialog").open, false);
  assert.equal(h.el("#al-label").value, "Morning");
  assert.equal(h.el("#al-name-value").textContent, "Morning");
  assert.equal(h.document.activeElement, h.el("#al-name-edit"));

  await h.el("#al-name-edit").dispatch("click");
  h.el("#al-name-input").value = "Discard me";
  await h.el("#al-name-cancel").dispatch("click");
  assert.equal(h.el("#al-label").value, "Morning");
  assert.equal(h.el("#al-name-value").textContent, "Morning");
  h.el("#al-station").value = "radio";
  assert.equal(h.evaluate("readAlarmEditor().alarm.label"), "Morning");
});

test("Android station Search dismisses its keyboard while keeping the filtered results", async () => {
  const h = makeHarness(true);
  const filter = h.el("#station-filter");
  filter.value = "missing";
  filter.focus();
  await filter.dispatch("input");
  assert.equal(h.evaluate("visibleStations.length"), 0);
  const event = await filter.dispatch("keydown", { key: "Enter" });
  assert.equal(event.defaultPrevented, true);
  assert.equal(h.document.activeElement, h.document.body);
  assert.equal(filter.value, "missing");
  assert.equal(h.evaluate("visibleStations.length"), 0);
});

test("Android navigation closes the keyboard without discarding an editor draft", async () => {
  const h = makeHarness(true);
  h.evaluate("openStationEditor(null)");
  const name = h.el("#st-name");
  name.value = "Draft";
  name.focus();
  await h.el("#tab-browse").dispatch("click");
  assert.equal(h.document.activeElement, h.el("#tab-browse"));
  assert.equal(h.el("#pane-browse").classList.contains("on"), true);
  assert.equal(h.el("#station-editor").classList.contains("hidden"), false);
  assert.equal(name.value, "Draft");
});

test("Android Back returns from each visible editor to its list without saving a draft", async () => {
  const h = makeHarness(true);
  const stationTrigger = h.el("#btn-add-station");
  stationTrigger.focus();
  h.evaluate("openStationEditor(null)");
  h.el("#st-name").value = "Draft";
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  assert.equal(h.el("#station-editor").classList.contains("hidden"), true);
  assert.equal(h.document.activeElement, stationTrigger);

  await h.el("#tab-alarms").dispatch("click");
  const alarmTrigger = h.el("#alarm-list").querySelector(".alarm-empty-create");
  assert.equal(h.el("#btn-add-alarm").hidden, true);
  alarmTrigger.focus();
  h.evaluate("openAlarmEditor(null)");
  await h.el("#al-name-edit").dispatch("click");
  h.el("#al-name-input").value = "Discard";
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  assert.equal(h.el("#al-name-dialog").open, false);
  assert.equal(h.el("#alarm-editor").classList.contains("hidden"), false);
  assert.equal(h.el("#al-label").value, "");
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  assert.equal(h.el("#alarm-editor").classList.contains("hidden"), true);
  assert.equal(h.document.activeElement, alarmTrigger);
  assert.equal(h.calls.some(({ command }) => command === "save_stations" || command === "save_alarms"), false);
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), false);
});

test("Android Back is consumed while an editor save is pending", async () => {
  const h = makeHarness(true);
  h.evaluate("openStationEditor(null); stationEditorSaving = true");
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  assert.equal(h.el("#station-editor").classList.contains("hidden"), false);
  h.evaluate("stationEditorSaving = false; closeStationEditor()");

  await h.el("#tab-alarms").dispatch("click");
  h.evaluate("openAlarmEditor(null); alarmEditorSaving = true");
  assert.equal(h.evaluate("window.__aerowaveHandleAndroidBack()"), true);
  assert.equal(h.el("#alarm-editor").classList.contains("hidden"), false);
});

test("Browse filters stay open and Android cannot apply its hidden Genre filter", async () => {
  const android = makeHarness(true);
  const toggle = android.el("#browse-filters-toggle");
  const fields = android.el("#browse-filter-fields");
  assert.equal(fields.classList.contains("open"), true);
  assert.equal(toggle.getAttribute("aria-expanded"), "true");
  await toggle.dispatch("click");
  assert.equal(fields.classList.contains("open"), true);
  android.el("#browse-tag").value = "jazz";
  await android.el("#browse-tag").dispatch("change");
  assert.equal(android.evaluate("browseTag"), "");

  const desktop = makeHarness(false);
  assert.equal(desktop.el("#browse-filter-fields").classList.contains("open"), true);
  desktop.el("#browse-tag").value = "jazz";
  await desktop.el("#browse-tag").dispatch("change");
  assert.equal(desktop.evaluate("browseTag"), "jazz");
});
