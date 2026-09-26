const assert = require("node:assert/strict");
const test = require("node:test");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const original = { id: "saved", name: "Saved", url: "https://radio.test/saved", tag: "Jazz", favorite: false, logo: "old.png" };
const clone = value => JSON.parse(JSON.stringify(value));

function fixture(invoke) {
  const h = createHarness({ invoke });
  h.context.original = clone(original);
  h.evaluate("state.stations=[original]; wire(); renderStations()");
  return h;
}

for (const operation of ["add", "edit", "delete"]) {
  test(`failed station ${operation} keeps the saved collection and draft, without leaking into another write`, async () => {
    let fail = true;
    const writes = [];
    const h = fixture((command, args) => {
      if (command !== "save_stations") return;
      writes.push(clone(args.stations));
      if (fail) throw new Error("disk full");
      return null;
    });
    h.evaluate(operation === "add" ? "openStationEditor(null)" : "openStationEditor(state.stations[0])");
    h.el("#st-name").value = "Draft";
    h.el("#st-url").value = "https://radio.test/draft";
    const draftId = h.evaluate("stationDraftId");
    await h.el(operation === "delete" ? "#st-delete" : "#station-editor").dispatch(operation === "delete" ? "click" : "submit");
    assert.deepEqual(clone(h.evaluate("state.stations")), [original]);
    assert.equal(h.el("#station-editor").classList.contains("hidden"), false);
    assert.match(h.el("#st-note").textContent, /Could not save/);
    assert.equal(h.el("#st-name").value, "Draft");
    fail = false;
    await h.evaluate("saveStations()");
    assert.deepEqual(writes.at(-1), [original]);
    await h.el(operation === "delete" ? "#st-delete" : "#station-editor").dispatch(operation === "delete" ? "click" : "submit");
    assert.equal(h.el("#station-editor").classList.contains("hidden"), true);
    const saved = clone(h.evaluate("state.stations"));
    if (operation === "delete") assert.deepEqual(saved, []);
    else {
      assert.equal(saved.find(station => station.id === draftId).name, "Draft");
      assert.equal(saved.length, operation === "add" ? 2 : 1);
    }
  });
}

test("a rejected favourite stays unchanged and can be retried", async () => {
  let fail = true;
  const h = fixture(command => {
    if (command === "save_stations") {
      if (fail) throw new Error("disk full");
      return null;
    }
  });
  await h.el("#station-list").querySelector(".star").dispatch("click");
  assert.equal(h.evaluate("state.stations[0].favorite"), false);
  fail = false;
  await h.el("#station-list").querySelector(".star").dispatch("click");
  assert.equal(h.evaluate("state.stations[0].favorite"), true);
});

test("a pending station save keeps the editor busy and rejects a duplicate submit", async () => {
  const pending = deferred();
  const h = fixture(command => command === "save_stations" ? pending.promise : undefined);
  h.evaluate("openStationEditor(null)");
  h.el("#st-name").value = "New";
  h.el("#st-url").value = "https://radio.test/new";
  const saving = h.el("#station-editor").dispatch("submit");
  await flush();
  assert.equal(h.el("#station-editor").inert, true);
  await h.el("#station-editor").dispatch("submit");
  h.evaluate("closeStationEditor()");
  assert.equal(h.el("#station-editor").classList.contains("hidden"), false);
  assert.equal(h.calls.filter(call => call.command === "save_stations").length, 1);
  assert.equal(h.evaluate("state.stations.length"), 1);
  pending.resolve(null);
  await saving;
  assert.equal(h.evaluate("state.stations.length"), 2);
  assert.equal(h.el("#station-editor").inert, false);
});

test("an accepted station edit preserves artwork queued while its write was pending", async () => {
  const pending = deferred();
  let writes = 0;
  const h = fixture(command => command === "save_stations" && ++writes === 1 ? pending.promise : undefined);
  h.evaluate("openStationEditor(state.stations[0])");
  h.el("#st-name").value = "Renamed";
  const saving = h.el("#station-editor").dispatch("submit");
  await flush();
  const artworkSave = h.evaluate('state.stations[0].logo="new.png"; saveStations()');
  pending.resolve(null);
  await saving;
  await artworkSave;
  const last = h.calls.filter(call => call.command === "save_stations").at(-1).args.stations[0];
  assert.equal(last.name, "Renamed");
  assert.equal(last.logo, "new.png");
});

for (const fail of [false, true]) {
  test(`quit waits for an in-flight station save and ${fail ? "stays open on failure" : "closes after success"}`, async () => {
    const pending = deferred();
    const h = fixture(command => {
      if (command === "save_stations") return pending.promise;
      if (command === "acknowledge_window_action") return true;
    });
    const saving = h.evaluate("saveStations()");
    await flush();
    const closing = h.evaluate('handleWindowAction({requestId:"station-close"})');
    await flush();
    assert.equal(h.calls.some(call => call.command === "complete_window_action"), false);
    if (fail) pending.reject(new Error("disk full"));
    else pending.resolve(null);
    await saving;
    await closing;
    const completed = h.calls.find(call => call.command === "complete_window_action");
    assert.equal(completed.args.saved, !fail);
  });
}

test("a station failure during close acknowledgement keeps the failed draft open", async () => {
  const save = deferred();
  const acknowledgement = deferred();
  const h = fixture(command => {
    if (command === "save_stations") return save.promise;
    if (command === "acknowledge_window_action") return acknowledgement.promise;
  });
  const saving = h.evaluate("saveStations()");
  await flush();
  const closing = h.evaluate('handleWindowAction({requestId:"slow-ack"})');
  save.reject(new Error("disk full"));
  await saving;
  acknowledgement.resolve(true);
  await closing;
  assert.equal(h.calls.find(call => call.command === "complete_window_action").args.saved, false);
});
