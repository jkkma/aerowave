const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const recovered = { stations: [{ id: "radio", name: "Saved radio", url: "https://radio.test/live" }],
  alarms: [], settings: { volume: 0.45, clock24h: true } };
const healthy = { error: null, writesBlocked: false, pendingCompletions: false, restartSafe: true };

function recoveryHarness(options = {}) {
  return createHarness({ invoke: (command, args) => {
    const override = options.invoke?.(command, args);
    if (override !== undefined) return override;
    if (command === "retry_settings_storage") return structuredClone(recovered);
    if (command === "get_storage_status") return healthy;
    if (command === "config_location") return { path: "/settings/aerowave.json", portable: true, loadError: null };
    if (command === "get_state") return structuredClone(recovered);
  } });
}

test("ordinary listening cannot schedule a save over unreadable settings", async () => {
  const h = recoveryHarness();
  h.evaluate('configLoadError = "Original could not be read"; state.settings.volume = 0.2; saveSettings()');
  assert.equal(h.evaluate("settingsDirty"), false);
  assert.equal(h.evaluate("settingsSaveTimer"), null);
  await h.evaluate('playStation({ id: "new", name: "Radio", url: "https://radio.test/live" })');
  await flush();
  assert.equal(h.calls.some(({ command }) => command === "save_settings"), false);
  assert.match(h.el("#status-msg").textContent, /protected/);
});

test("storage failure remains visible and offers an explicit read retry", () => {
  const h = recoveryHarness();
  h.evaluate('applyStorageStatus({ error: "Original preserved", writesBlocked: true, pendingCompletions: false, restartSafe: true })');
  assert.equal(h.el("#storage-status").hidden, false);
  assert.equal(h.el("#storage-status").textContent, "Original preserved");
  assert.equal(h.el("#storage-retry").hidden, false);
  assert.equal(h.el("#storage-retry").textContent, "Retry reading saved settings");
  assert.equal(h.evaluate("configLoadError"), "Original preserved");
});

test("retry discards only temporary defaults and renders the successfully recovered file", async () => {
  const h = recoveryHarness();
  h.evaluate('state.settings.volume = 0.1; saveSettings(); configLoadError = "Locked"');
  await h.evaluate("retrySettingsStorage()");
  assert.equal(h.calls.some(({ command }) => command === "save_settings"), false);
  assert.equal(h.evaluate("state.stations[0].name"), "Saved radio");
  assert.equal(h.evaluate("state.settings.volume"), 0.45);
  assert.equal(h.evaluate("configLoadError"), null);
  assert.equal(h.evaluate("setupRestorePending"), false);
  assert.equal(h.el("#storage-retry").disabled, false);
});

test("failed recovery keeps the original blocked and does not publish defaults as recovered", async () => {
  const h = recoveryHarness({ invoke: command => {
    if (command === "retry_settings_storage") return Promise.reject(new Error("Still locked"));
    if (command === "get_storage_status") return { ...healthy, writesBlocked: true, error: "Still locked" };
  } });
  h.evaluate('configLoadError = "Locked"; state.settings.volume = 0.1');
  await h.evaluate("retrySettingsStorage()");
  assert.equal(h.evaluate("state.settings.volume"), 0.1);
  assert.equal(h.evaluate("configLoadError"), "Still locked");
  assert.equal(h.evaluate("setupRestorePending"), false);
  assert.match(h.el("#status-msg").textContent, /still blocked/);
});

test("retrying one-shot completion preserves and flushes ordinary unsaved settings", async () => {
  const h = recoveryHarness();
  h.evaluate('state.settings.volume = 0.2; saveSettings(); applyStorageStatus({ error: "Retrying completion", writesBlocked: false, pendingCompletions: true, restartSafe: true })');
  await h.evaluate("retrySettingsStorage()");
  const save = h.calls.find(({ command }) => command === "save_settings");
  assert.equal(save.args.settings.volume, 0.2);
  assert.ok(h.calls.indexOf(save) < h.calls.findIndex(({ command }) => command === "retry_settings_storage"));
});

test("an unsaved one-shot receipt clearly reports that restart protection is pending", () => {
  const h = recoveryHarness();
  h.evaluate('applyStorageStatus({ error: "May repeat after restarting", writesBlocked: false, pendingCompletions: true, restartSafe: false })');
  assert.equal(h.el("#storage-retry").textContent, "Retry saving completion");
  assert.match(h.el("#status-msg").textContent, /May repeat after restarting/);
  assert.equal(h.evaluate("configLoadError"), null);
});

test("an alarm editor retains its opened arm revision after a background readback", () => {
  const h = recoveryHarness();
  h.evaluate(`state.stations = [{ id: "radio", name: "Radio" }];
    state.alarms = [{ id: "wake", armingRevision: "opened", hour: 7, minute: 0, enabled: true,
      days: [], source: { kind: "folder", path: "/music" }, volume: 0.8 }];
    openAlarmEditor(state.alarms[0]);
    state.alarms = [{ ...state.alarms[0], armingRevision: "consumed:opened", enabled: false }];`);
  assert.equal(h.evaluate("readAlarmEditor().alarm.armingRevision"), "opened");
});

test("duplicating an alarm cannot inherit a consumed arm revision", () => {
  const h = recoveryHarness();
  h.evaluate(`state.stations = [{ id: "radio", name: "Radio" }];
    openAlarmEditor({ id: "wake", armingRevision: "consumed:old", hour: 7, minute: 0,
      enabled: false, days: [], source: { kind: "folder", path: "/music" }, volume: 0.8 }, { copy: true });`);
  assert.equal(h.evaluate("readAlarmEditor().alarm.armingRevision"), undefined);
});
