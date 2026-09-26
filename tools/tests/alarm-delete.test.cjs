const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred } = require("./frontend-harness.cjs");

const clone = (value) => JSON.parse(JSON.stringify(value));
const alarm = (id, hour, enabled = true) => ({
  id, label: id, hour, minute: 15, days: [0, 2, 4], enabled,
  source: { kind: "station", stationId: "radio" },
});
const row = (h, id) => h.el("#alarm-list").children.find(item => item.dataset.id === id);
const remove = (h, id) => row(h, id)?.querySelector(".alarm-delete");

function harness(options = {}) {
  const h = createHarness(options);
  h.el("#pane-alarms").classList.add("on");
  h.evaluate('clockNow={hour:6,minute:0}; state.stations=[{id:"radio",name:"Morning radio"}]; wire()');
  return h;
}

test("Delete on an alarm card saves directly and focuses the next card", async () => {
  let native = [alarm("early", 6), alarm("middle", 8, false), alarm("late", 20)];
  const h = harness({ invoke: (command, args) => {
    if (command === "save_alarms") native = clone(args.alarms);
    if (command === "get_state") return { alarms: clone(native) };
  } });
  h.context.initial = clone(native);
  h.evaluate("state.alarms=initial; renderAlarms()");

  const button = remove(h, "middle");
  assert.equal(button.className, "gel danger alarm-delete");
  assert.equal(button.getAttribute("aria-label"), "Delete middle at 08:15");
  button.focus();
  await button.dispatch("click");

  assert.deepEqual(native.map(item => item.id), ["early", "late"]);
  assert.deepEqual(Array.from(h.evaluate("state.alarms.map(item => item.id)")), ["early", "late"]);
  assert.equal(h.evaluate("editingAlarm"), null);
  assert.equal(h.el("#pane-alarms").classList.contains("editing"), false);
  assert.equal(h.document.activeElement, row(h, "late").querySelector(".alarm-card-main"));
  assert.equal(h.calls.filter(call => call.command === "save_alarms").length, 1);
  assert.equal(h.calls.filter(call => call.command === "get_state").length, 1);
});

test("deleting the last card focuses Add alarm", async () => {
  let native = [alarm("only", 7)];
  const h = harness({ invoke: (command, args) => {
    if (command === "save_alarms") native = clone(args.alarms);
    if (command === "get_state") return { alarms: clone(native) };
  } });
  h.context.initial = clone(native);
  h.evaluate("state.alarms=initial; renderAlarms()");
  const button = remove(h, "only");
  button.focus();
  await button.dispatch("click");
  assert.equal(h.evaluate("state.alarms.length"), 0);
  assert.equal(h.document.activeElement, h.el("#btn-add-alarm"));
});

test("a rejected card deletion keeps the alarm and allows retry", async () => {
  let native = [alarm("wake", 7), alarm("other", 20)];
  let saves = 0;
  const h = harness({ invoke: (command, args) => {
    if (command === "save_alarms" && ++saves === 1) throw new Error("disk full");
    if (command === "save_alarms") native = clone(args.alarms);
    if (command === "get_state") return { alarms: clone(native) };
  } });
  h.context.initial = clone(native);
  h.evaluate("state.alarms=initial; renderAlarms()");

  remove(h, "wake").focus();
  await remove(h, "wake").dispatch("click");
  assert.deepEqual(native.map(item => item.id), ["wake", "other"]);
  assert.deepEqual(Array.from(h.evaluate("state.alarms.map(item => item.id)")), ["wake", "other"]);
  assert.equal(h.document.activeElement, remove(h, "wake"));
  assert.equal(remove(h, "wake").disabled, false);
  assert.match(h.el("#status-msg").textContent, /disk full/);

  await remove(h, "wake").dispatch("click");
  assert.deepEqual(native.map(item => item.id), ["other"]);
  assert.equal(h.document.activeElement, row(h, "other").querySelector(".alarm-card-main"));
  assert.equal(saves, 2);
});

test("pending deletion blocks stale clicks, other alarm controls, and setup restore", async () => {
  const pending = deferred();
  let native = [alarm("wake", 7), alarm("other", 20)];
  const h = harness({ invoke: (command, args) => {
    if (command === "save_alarms") return pending.promise.then(() => { native = clone(args.alarms); });
    if (command === "get_state") return { alarms: clone(native) };
  } });
  h.context.initial = clone(native);
  h.evaluate("state.alarms=initial; renderAlarms()");

  const stale = remove(h, "wake");
  stale.focus();
  const saving = stale.dispatch("click");
  assert.equal(remove(h, "wake").textContent, "Deleting…");
  assert.equal(remove(h, "wake").disabled, true);
  assert.equal(remove(h, "other").disabled, true);
  assert.equal(row(h, "other").querySelector(".sw").disabled, true);
  assert.equal(row(h, "other").querySelector(".alarm-duplicate").disabled, true);
  await stale.dispatch("click");
  assert.equal(h.calls.filter(call => call.command === "save_alarms").length, 1);
  pending.resolve();
  await saving;
  assert.deepEqual(native.map(item => item.id), ["other"]);

  h.evaluate("setupRestorePending=true; renderAlarms()");
  const blocked = remove(h, "other");
  assert.equal(blocked.disabled, true);
  await blocked.dispatch("click");
  assert.equal(h.calls.filter(call => call.command === "save_alarms").length, 1);
});

test("an earlier unconfirmed write keeps card deletion busy until readback finishes", async () => {
  const readback = deferred();
  const native = [alarm("other", 20)];
  const h = harness({ invoke: command => {
    if (command === "get_state") return readback.promise;
  } });
  h.context.initial = [alarm("wake", 7), alarm("other", 20)];
  h.evaluate("state.alarms=initial; alarmReadbackPending=true; renderAlarms()");

  const stale = remove(h, "wake");
  stale.focus();
  const deleting = stale.dispatch("click");
  assert.equal(remove(h, "wake").textContent, "Deleting…");
  assert.equal(remove(h, "other").disabled, true);
  await stale.dispatch("click");
  await assert.rejects(h.evaluate("settleSetupWrites()"), /Wait for your alarm change/);
  assert.equal(h.calls.filter(call => call.command === "save_alarms").length, 0);

  readback.resolve({ alarms: clone(native) });
  await deleting;
  assert.equal(row(h, "wake"), undefined);
  assert.equal(h.document.activeElement, row(h, "other").querySelector(".alarm-card-main"));
  assert.equal(h.evaluate("alarmReadbackPending"), false);
  assert.equal(h.calls.filter(call => call.command === "save_alarms").length, 0);
});

for (const interruption of ["tab switch", "ringing alarm"]) {
  test(`card deletion does not take focus back after a ${interruption}`, async () => {
    const pending = deferred();
    let native = [alarm("wake", 7), alarm("other", 20)];
    const h = harness({ invoke: (command, args) => {
      if (command === "save_alarms") return pending.promise.then(() => { native = clone(args.alarms); });
      if (command === "get_state") return { alarms: clone(native) };
    } });
    h.context.initial = clone(native);
    h.evaluate("state.alarms=initial; renderAlarms()");
    const button = remove(h, "wake");
    button.focus();
    const deleting = button.dispatch("click");

    const destination = h.el(interruption === "tab switch" ? "#tab-radio" : "#ring-dismiss");
    if (interruption === "tab switch") h.el("#pane-alarms").classList.remove("on");
    else h.evaluate("ringing={alarmId:'other'}");
    destination.focus();
    pending.resolve();
    await deleting;

    assert.deepEqual(native.map(item => item.id), ["other"]);
    assert.equal(h.document.activeElement, destination);
  });
}

test("Android card deletion sends canonical alarms through native sync", async () => {
  let native = [alarm("wake", 7), alarm("other", 20)];
  let revision = 4;
  const snapshot = () => ({ initialized: true, revision, alarms: clone(native), occurrences: [],
    next: null, ringing: null, permissions: {}, error: null });
  const h = harness({ navigator: { userAgent: "Mozilla/5.0 (Linux; Android 15; Mobile)" },
    invoke: (command, args) => {
      if (command.endsWith("|sync_alarms")) {
        assert.equal(args.payload.expectedRevision, revision);
        native = clone(args.payload.alarms);
        revision++;
        return snapshot();
      }
      if (command.endsWith("|get_alarm_state")) return snapshot();
    } });
  h.context.initial = snapshot();
  h.evaluate("applyAndroidAlarmState(initial)");

  remove(h, "wake").focus();
  await remove(h, "wake").dispatch("click");
  assert.deepEqual(native.map(item => item.id), ["other"]);
  assert.deepEqual(Array.from(h.evaluate("state.alarms.map(item => item.id)")), ["other"]);
  assert.equal(h.calls.some(call => call.command === "save_alarms"), false);
  assert.equal(h.calls.filter(call => call.command.endsWith("|sync_alarms")).length, 1);
  assert.equal(h.document.activeElement, row(h, "other").querySelector(".alarm-card-main"));
});
