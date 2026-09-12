const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { createHarness, deferred, flush, Element } = require("./frontend-harness.cjs");

function option(value, label) {
  const el = new Element("option");
  el.value = value;
  el.textContent = label;
  return el;
}

test("only Linux webviews receive the reduced-compositing class", () => {
  const linux = createHarness({ navigator: {
    platform: "Linux x86_64",
    userAgent: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15",
  } });
  const windows = createHarness({ navigator: {
    platform: "Win32",
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
  } });

  assert.equal(linux.document.body.classList.contains("linux"), true);
  assert.equal(windows.document.body.classList.contains("linux"), false);
});

test("the Linux reduced-compositing backdrop is present and referenced", () => {
  const root = path.resolve(__dirname, "../..");
  const styles = fs.readFileSync(path.join(root, "src/styles.css"), "utf8");
  const backdrop = fs.readFileSync(path.join(root, "src/linux-backdrop.png"));

  assert.match(styles, /body\.linux \.sky \{[^}]*linux-backdrop\.png/s);
  assert.equal(backdrop.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
});

test("clearing a country invalidates its pending dynamic and fixed facet responses", async () => {
  const facets = deferred();
  const h = createHarness({ invoke: command => command === "browse_facets" ? facets.promise : undefined });
  h.el("#browse-tag").append(option("", "Any genre"));
  h.el("#browse-country").append(option("", "Any country"));
  h.el("#browse-codec").append(option("", "Any format"), option("MP3", "MP3"), option("FLAC", "FLAC"));
  h.el("#browse-bitrate").append(option("", "Any bitrate"), option("128", "128k"));
  h.evaluate(`browseTags = [{value:"jazz",name:"Jazz",stations:50},{value:"rock",name:"Rock",stations:50}];
    browseCountries = [{code:"FR",name:"France",stations:100}]; browseCountry = "FR";`);
  const old = h.evaluate("refreshBrowseFilters()");
  await h.evaluate('browseCountry = ""; refreshBrowseFilters()');
  assert.equal(h.el("#browse-tag").disabled, false);
  facets.resolve({ tags: [{value:"jazz",name:"Jazz",stations:10}], countries: [],
    codecs: [{key:"MP3",stations:10}], bitrates: [{key:"128",stations:10}], sampled:false });
  await old;
  assert.deepEqual(h.el("#browse-tag").options.map(o => o.value), ["", "jazz", "rock"]);
  assert.equal(h.el("#browse-codec").options[2].disabled, false);
  assert.equal(h.el("#browse-codec").options[2].textContent, "FLAC");
});

test("station row keys leave Favourite and Edit buttons available", async () => {
  const h = createHarness();
  h.evaluate('state.stations = [{id:"one",name:"Station",url:"https://example.test/radio",favorite:false}]; renderStations()');
  const row = h.el("#station-list").children[0];
  const [star, edit] = row.children.slice(-2);
  for (const button of [star, edit]) {
    for (const key of ["Enter", "Space"]) {
      const event = await button.dispatch("keydown", {key:key === "Space" ? " " : key, code:key});
      assert.equal(event.defaultPrevented, false);
      assert.equal(h.evaluate("player.source"), null);
    }
  }
  await star.dispatch("click");
  assert.equal(h.evaluate("state.stations[0].favorite"), true);
  assert.equal(h.calls.filter(c => c.command === "save_stations").length, 1);
  await row.dispatch("keydown", {key:"Enter", code:"Enter"});
  assert.equal(h.evaluate("player.source.stationId"), "one");
});

test("the browse Add button does not start its station on Enter", async () => {
  const h = createHarness();
  h.evaluate('browseResults = [{name:"Candidate",url:"https://example.test/live",country:"",tags:""}]; renderBrowse()');
  const row = h.el("#browse-list").children[0];
  const add = row.children.at(-1);
  const event = await add.dispatch("keydown", {key:"Enter", code:"Enter"});
  assert.equal(event.defaultPrevented, false);
  assert.equal(h.evaluate("player.source"), null);
  await add.dispatch("click");
  assert.equal(h.evaluate("state.stations.length"), 1);
  assert.equal(h.evaluate("player.source"), null);
});

test("test rings cannot schedule a snooze or dismiss a real pending occurrence", async () => {
  const h = createHarness();
  h.evaluate('onAlarmFire({alarmId:"saved",trigger:"test",kind:"none",snoozeMins:10,hour:7,minute:0})');
  assert.equal(h.el("#ring-snooze").disabled, true);
  await h.evaluate("snoozeRing()");
  assert.equal(h.el("#ringing").hidden, false);
  await h.evaluate("dismissRing()");
  assert.equal(h.calls.some(c => c.command === "snooze_alarm" || c.command === "dismiss_alarm"), false);
  h.evaluate('onAlarmFire({alarmId:"saved",trigger:"scheduled",kind:"none",snoozeMins:10,hour:7,minute:0})');
  assert.equal(h.el("#ring-snooze").disabled, false);
});

function ringFixture(h, title = "Wake up") {
  h.context.ringTitle = title;
  h.evaluate(`onAlarmFire({alarmId:"saved",trigger:"scheduled",kind:"folder",path:"wake.mp3",
    folder:"music",title:ringTitle,snoozeMins:10,hour:7,minute:0,volume:0.8,fadeSecs:0})`);
}

for (const [action, command, verb] of [["snoozeRing", "snooze_alarm", "snooze"], ["dismissRing", "dismiss_alarm", "dismiss"]]) {
  test(`a rejected ${verb} keeps the ring visible and playing, and allows retry`, async () => {
    const request = deferred();
    let attempts = 0;
    const h = createHarness({ invoke: (cmd) => {
      if (cmd !== command) return undefined;
      return ++attempts === 1 ? request.promise : null;
    } });
    ringFixture(h);
    await flush();
    const active = h.evaluate("ringing");
    const pending = h.evaluate(`${action}()`);
    assert.equal(h.el("#ringing").hidden, false);
    assert.equal(h.audios[0].paused, false);
    request.reject(new Error("backend refused the action"));
    await pending;
    assert.equal(h.evaluate("ringing"), active);
    assert.equal(h.el("#ringing").hidden, false);
    assert.equal(h.audios[0].paused, false);
    assert.match(h.el("#ring-note").textContent, new RegExp("Could not " + verb));
    await h.evaluate(`${action}()`);
    assert.equal(h.evaluate("ringing"), null);
    assert.equal(h.el("#ringing").hidden, true);
    assert.equal(h.audios[0].paused, true);
  });

  for (const rejects of [false, true]) {
    test(`a stale ${verb} ${rejects ? "rejection" : "success"} cannot change a newer occurrence of the same alarm`, async () => {
      const request = deferred();
      const h = createHarness({ invoke: (cmd) => cmd === command ? request.promise : undefined });
      ringFixture(h, "First ring");
      await flush();
      const pending = h.evaluate(`${action}()`);
      ringFixture(h, "New ring");
      await flush();
      const current = h.evaluate("ringing");
      if (rejects) request.reject(new Error("old action failed"));
      else request.resolve(null);
      await pending;
      assert.equal(h.evaluate("ringing"), current);
      assert.equal(h.evaluate("player.source.title"), "New ring");
      assert.equal(h.el("#ringing").hidden, false);
      assert.equal(h.el("#ring-note").textContent, "");
      assert.equal(h.audios[0].paused, false);
      assert.doesNotMatch(h.el("#status-msg").textContent, /snoozed|old action failed/i);
    });
  }
}

test("repeated ring controls do not send competing actions while one is pending", async () => {
  const request = deferred();
  const h = createHarness({ invoke: (cmd) => cmd === "snooze_alarm" ? request.promise : undefined });
  ringFixture(h);
  const first = h.evaluate("snoozeRing()");
  await h.evaluate("snoozeRing()");
  await h.evaluate("dismissRing()");
  assert.equal(h.calls.filter(({ command }) => command === "snooze_alarm").length, 1);
  assert.equal(h.calls.some(({ command }) => command === "dismiss_alarm"), false);
  request.resolve(null);
  await first;
  assert.equal(h.evaluate("ringing"), null);
});

test("a debounced volume save retains the latest explicit startup choice", async () => {
  const h = createHarness();
  h.evaluate('state.settings = {startWithWindows:false}; saveSettings(true); state.settings.volume = 0.4; saveSettings()');
  await h.fireTimer(h.evaluate("settingsSaveTimer"));
  await flush();
  const first = h.calls.find(c => c.command === "save_settings");
  assert.equal(first.args.explicitAutostart, true);
  assert.equal(first.args.settings.startWithWindows, true);
  h.evaluate("saveSettings()");
  await h.fireTimer(h.evaluate("settingsSaveTimer"));
  assert.equal(h.calls.filter(c => c.command === "save_settings")[1].args.explicitAutostart, null);
});
