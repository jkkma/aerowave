const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const occurrence = { alarmId: "wake", atMs: Date.UTC(2026, 8, 13, 10), inSecs: 3661, label: "Morning radio" };
const wallTime = { year: 2026, month: 9, day: 13, hour: 7, minute: 30 };
const androidNavigator = { userAgent: "Mozilla/5.0 (Linux; Android 15; Mobile)" };

test("the next alarm appears in the footer with its OS local time and countdown", async () => {
  const h = createHarness({ invoke: (command) => {
    if (command === "next_alarm") return occurrence;
    if (command === "local_time") return wallTime;
  } });
  await h.evaluate("refreshNextAlarm()");
  assert.equal(h.el("#status-next").textContent, "Next Morning radio · 07:30 · in 1h 01m");
  assert.equal(h.calls.find(call => call.command === "local_time").args.atMs, occurrence.atMs);
  assert.equal(h.elements.has("#next-alarm"), false);
});

test("the footer clears when no occurrence remains and reports schedule lookup failures", async () => {
  let next = occurrence;
  const h = createHarness({ invoke: (command) => {
    if (command === "next_alarm") {
      if (next instanceof Error) throw next;
      return next;
    }
    if (command === "local_time") return wallTime;
  } });
  await h.evaluate("refreshNextAlarm()");
  next = null;
  await h.evaluate("refreshNextAlarm()");
  assert.equal(h.el("#status-next").textContent, "");
  next = new Error("schedule unavailable");
  await h.evaluate("refreshNextAlarm()");
  assert.equal(h.el("#status-next").textContent, "Next alarm unavailable");
});

test("an older next-alarm response cannot replace a newer empty footer", async () => {
  const first = deferred();
  let calls = 0;
  const h = createHarness({ invoke: (command) => {
    if (command === "next_alarm") return ++calls === 1 ? first.promise : null;
  } });
  const old = h.evaluate("refreshNextAlarm()");
  await flush();
  await h.evaluate("refreshNextAlarm()");
  first.resolve(occurrence);
  await old;
  assert.equal(h.el("#status-next").textContent, "");
  assert.equal(h.calls.filter(call => call.command === "local_time").length, 0);
});

test("Android uses the native snapshot and recalculates its countdown", async () => {
  const h = createHarness({ navigator: androidNavigator, invoke: (command) => {
    if (command === "local_time") return wallTime;
  } });
  h.context.occurrence = { ...occurrence, snoozed: true, inSecs: 9999 };
  h.evaluate(`androidAlarmSnapshot = { next: occurrence, occurrences: [] }; Date.now = () => occurrence.atMs - 61000`);
  await h.evaluate("refreshNextAlarm()");
  assert.equal(h.el("#status-next").textContent, "Snoozed Morning radio · 07:30 · in 1m 01s");
  assert.equal(h.calls.some(call => call.command === "next_alarm"), false);
});

test("alarm occurrence dates use the OS wall date without browser local getters", () => {
  const h = createHarness();
  h.evaluate(`
    clockNow = { year: 2026, month: 9, day: 10 };
    for (const name of ["getHours", "getMinutes", "getFullYear", "getMonth", "getDate"]) {
      Date.prototype[name] = () => { throw new Error("browser timezone used"); };
    }
    Date.prototype.toLocaleDateString = function(locale, options) {
      if (options.timeZone !== "UTC") throw new Error("UTC formatting required");
      return this.toISOString().slice(0, 10);
    };
  `);
  assert.equal(h.evaluate("alarmDateLabel({ year: 2026, month: 9, day: 10 })"), "Today");
  assert.equal(h.evaluate("alarmDateLabel({ year: 2026, month: 9, day: 11 })"), "Tomorrow");
  assert.equal(h.evaluate("alarmOccurrenceLabel({ year: 2026, month: 9, day: 13, hour: 7, minute: 30 })"), "2026-09-13 at 07:30");
});
