const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const now = { atMs: 1789019005000, year: 2026, month: 9, day: 10, hour: 2, minute: 43, second: 25 };

function refuseBrowserTimezone(h) {
  h.evaluate(`
    for (const name of ["getHours", "getMinutes", "getSeconds", "getFullYear", "getMonth", "getDate"]) {
      Date.prototype[name] = () => { throw new Error("browser timezone must not be used"); };
    }
    Date.prototype.toLocaleDateString = function(locale, options) {
      if (options.timeZone !== "UTC") throw new Error("locale formatting must use UTC");
      return this.toISOString().slice(0, 10);
    };
  `);
}

test("the face and 12-hour alarm labels use OS wall time despite stale browser timezone rules", async () => {
  const h = createHarness({ invoke: command => command === "local_time" ? now : undefined });
  refuseBrowserTimezone(h);
  await h.evaluate("tickClock()");
  assert.equal(h.el("#tb-clock").textContent, "02:43:25");
  assert.equal(h.el("#tb-date").textContent, "2026-09-10");
  h.evaluate("state.settings.clock24h = false");
  await h.evaluate("tickClock()");
  assert.equal(h.el("#tb-clock").textContent, "2:43:25 AM");
  assert.equal(h.evaluate("fmtAlarmTime(0, 7)"), "12:07 AM");
  assert.equal(h.evaluate("fmtAlarmTime(12, 7)"), "12:07 PM");
  h.evaluate("openAlarmEditor(null)");
  assert.equal(h.el("#al-hour").value, "3");
  assert.equal(h.el("#al-minute").value, "00");
});

test("a delayed older clock response cannot overwrite the latest OS reading", async () => {
  const first = deferred();
  let calls = 0;
  const h = createHarness({ invoke: command => command === "local_time" ? (++calls === 1 ? first.promise : { ...now, hour: 3 }) : undefined });
  const old = h.evaluate("tickClock()");
  await h.evaluate("tickClock()");
  first.resolve(now);
  await old;
  assert.equal(h.el("#tb-clock").textContent, "03:43:25");
  assert.equal(h.evaluate("clockNow.hour"), 3);
});

test("next-alarm labels ask the OS for the timezone at the future timestamp", async () => {
  const atMs = now.atMs + 86400000;
  const h = createHarness({ invoke: command => {
    if (command === "next_alarm") return { atMs, inSecs: 86400, label: "Wake" };
    if (command === "local_time") return { ...now, atMs, day: 11, hour: 7, minute: 30 };
  } });
  refuseBrowserTimezone(h);
  await h.evaluate("refreshNextAlarm()");
  assert.match(h.el("#next-alarm").textContent, /Wake · 07:30/);
  assert.equal(h.calls.find(call => call.command === "local_time").args.atMs, atMs);
});

test("an old next-alarm timezone lookup cannot bring back a removed alarm", async () => {
  const local = deferred();
  let calls = 0;
  const h = createHarness({ invoke: command => {
    if (command === "next_alarm") return ++calls === 1 ? { atMs: now.atMs, inSecs: 60 } : null;
    if (command === "local_time") return local.promise;
  } });
  const old = h.evaluate("refreshNextAlarm()");
  await flush();
  await h.evaluate("refreshNextAlarm()");
  local.resolve(now);
  await old;
  assert.equal(h.el("#next-alarm").textContent, "No alarm set");
  assert.equal(h.el("#status-next").textContent, "");
});

test("Ring In rounds epoch milliseconds up before OS conversion across midnight", async () => {
  const stamp = Date.UTC(2026, 8, 11, 2, 59, 30, 1);
  const h = createHarness({ invoke: command => command === "local_time" ? { ...now, day: 11, hour: 0, minute: 15 } : undefined });
  refuseBrowserTimezone(h);
  h.context.stamp = stamp;
  h.evaluate("Date.now = () => stamp; editorDays = [0, 1]");
  await h.evaluate("setEditorTimeIn(15)");
  assert.equal(h.calls.find(call => call.command === "local_time").args.atMs, Date.UTC(2026, 8, 11, 3, 15));
  assert.equal(h.el("#al-hour").value, "00");
  assert.equal(h.el("#al-minute").value, "15");
  assert.equal(h.evaluate("editorDays.length"), 0);
  assert.equal(h.evaluate("editorQuickMins"), 15);
});

for (const supersede of ["setEditorTime(7, 45)", "closeAlarmEditor()", "openAlarmEditor({hour:7,minute:45})"]) {
  test(`a delayed Ring In reply cannot override ${supersede}`, async () => {
    const local = deferred();
    const h = createHarness({ invoke: command => command === "local_time" ? local.promise : undefined });
    h.evaluate("setEditorTime(7,45); editorDays = [0,1]");
    const old = h.evaluate("setEditorTimeIn(15)");
    h.evaluate(supersede);
    local.resolve({ ...now, hour: 3, minute: 0 });
    await old;
    assert.equal(h.el("#al-hour").value, "07");
    assert.equal(h.el("#al-minute").value, "45");
    assert.equal(h.evaluate("editorQuickMins"), 0);
  });
}

test("the latest quick-time choice wins when IPC replies arrive out of order", async () => {
  const first = deferred(), second = deferred();
  let calls = 0;
  const h = createHarness({ invoke: command => command === "local_time" ? (++calls === 1 ? first.promise : second.promise) : undefined });
  const old = h.evaluate("setEditorTimeIn(15)");
  const latest = h.evaluate("setEditorTimeIn(30)");
  second.resolve({ ...now, hour: 3, minute: 30 });
  await latest;
  first.resolve({ ...now, hour: 3, minute: 15 });
  await old;
  assert.equal(h.el("#al-minute").value, "30");
  assert.equal(h.evaluate("editorQuickMins"), 30);
});

test("Save and Test cannot use an older time while Ring In is still resolving", async () => {
  const local = deferred();
  const h = createHarness({ invoke: command => command === "local_time" ? local.promise : undefined });
  h.evaluate("setEditorTime(7,45)");
  h.el("#al-station").value = "station";
  const pending = h.evaluate("setEditorTimeIn(15)");
  assert.match(h.evaluate("readAlarmEditor().error"), /time to finish updating/);
  local.resolve({ ...now, hour: 3, minute: 0 });
  await pending;
  assert.equal(h.evaluate("readAlarmEditor().alarm.hour"), 3);
  assert.equal(h.evaluate("readAlarmEditor().alarm.minute"), 0);
});
