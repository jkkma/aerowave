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
  const android = createHarness({ navigator: {
    platform: "Linux aarch64",
    userAgent: "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36",
  } });

  assert.equal(linux.document.body.classList.contains("linux"), true);
  assert.equal(windows.document.body.classList.contains("linux"), false);
  assert.equal(android.document.body.classList.contains("android"), true);
  assert.equal(android.document.body.classList.contains("linux"), false);
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

test("station actions are separate native buttons and only Play starts playback", async () => {
  const h = createHarness();
  h.evaluate('state.stations = [{id:"one",name:"Station",url:"https://example.test/radio",favorite:false}]; renderStations()');
  const row = h.el("#station-list").children[0];
  const play = row.querySelector(".station-play");
  const star = row.querySelector(".star");
  const edit = row.querySelector(".station-edit");
  assert.equal(row.tagName, "LI");
  assert.equal(row.getAttribute("role"), null);
  assert.equal(row.tabIndex, undefined);
  assert.equal(play.tagName, "BUTTON");
  assert.equal(star.tagName, "BUTTON");
  assert.equal(edit.tagName, "BUTTON");
  assert.match(play.getAttribute("aria-label"), /Station/);
  assert.match(star.getAttribute("aria-label"), /Station/);
  assert.equal(star.getAttribute("aria-pressed"), "false");
  assert.match(edit.getAttribute("aria-label"), /Station/);

  const rowKey = await row.dispatch("keydown", {key:"Enter", code:"Enter"});
  assert.equal(rowKey.defaultPrevented, false);
  assert.equal(h.evaluate("player.source"), null);

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

  await h.el("#station-list").children[0].querySelector(".station-play").dispatch("click");
  assert.equal(h.evaluate("player.source.stationId"), "one");
});

test("Browse Listen and Save are separate native controls and Save does not play", async () => {
  const h = createHarness();
  h.evaluate('browseResults = [{name:"Candidate",url:"https://example.test/live",country:"",tags:""}]; renderBrowse()');
  const row = h.el("#browse-list").children[0];
  const listen = row.querySelector(".browse-listen");
  const save = row.querySelector(".browse-save");
  assert.equal(row.tagName, "LI");
  assert.equal(row.getAttribute("role"), null);
  assert.equal(row.tabIndex, undefined);
  assert.equal(listen.tagName, "BUTTON");
  assert.equal(save.tagName, "BUTTON");

  listen.focus();
  assert.equal(h.document.activeElement, listen);
  await listen.dispatch("click");
  assert.equal(h.evaluate("player.source.url"), "https://example.test/live");

  h.evaluate("stopPlayback(true)");
  const event = await save.dispatch("keydown", {key:"Enter", code:"Enter"});
  assert.equal(event.defaultPrevented, false);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(save.textContent, "+ Save");
  await save.dispatch("click");
  assert.equal(h.evaluate("state.stations.length"), 1);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(save.textContent, "Saved");
  assert.equal(h.calls.filter(c => c.command === "save_stations").length, 1);
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

test("settings saves serialize snapshots and retain the explicit startup intent", async () => {
  const first = deferred();
  let writes = 0;
  const h = createHarness({ invoke: command => command === "save_settings" && ++writes === 1
    ? first.promise : undefined });
  h.evaluate("state.settings = { volume: 0.2 }; saveSettings(true)");
  await h.fireTimer(h.evaluate("settingsSaveTimer"));
  await flush();
  h.evaluate("state.settings.volume = 0.4; saveSettings()");
  const draining = h.evaluate("flushSettings()");
  await flush();
  assert.equal(h.calls.filter(c => c.command === "save_settings").length, 1);
  first.resolve(null);
  assert.equal(await draining, true);
  const saves = h.calls.filter(c => c.command === "save_settings");
  assert.equal(saves.length, 2);
  assert.equal(saves[0].args.settings.volume, 0.2);
  assert.equal(saves[0].args.explicitAutostart, true);
  assert.equal(saves[1].args.settings.volume, 0.4);
  assert.equal(saves[1].args.explicitAutostart, null);
  assert.equal(saves[1].args.settings.startWithWindows, true);
});

test("window action acknowledges before flushing a pending settings save", async () => {
  const saving = deferred();
  const h = createHarness({ invoke: command => {
    if (command === "acknowledge_window_action") return true;
    if (command === "save_settings") return saving.promise;
  } });
  h.evaluate("state.settings.volume = 0.3; saveSettings()");
  const pending = h.evaluate("handleWindowAction({requestId: '7', action: 'quit'})");
  await flush();
  assert.equal(h.document.body.inert, true);
  assert.equal(h.calls[0].command, "acknowledge_window_action");
  assert.equal(h.calls.filter(c => c.command === "save_settings").length, 1);
  assert.equal(h.calls.some(c => c.command === "complete_window_action"), false);
  assert.equal(h.evaluate("settingsSaveTimer"), null);
  saving.resolve(null);
  await pending;
  assert.equal(h.document.body.inert, false);
  const complete = h.calls.find(c => c.command === "complete_window_action");
  assert.equal(complete.args.requestId, "7");
  assert.equal(complete.args.saved, true);
});

test("a failed settings flush cancels the close request and reports the error", async () => {
  const h = createHarness({ invoke: command => {
    if (command === "acknowledge_window_action") return true;
    if (command === "save_settings") throw new Error("disk unavailable");
    if (command === "get_state") return { stations: [], alarms: [], settings: { volume: 0.7 } };
  } });
  h.evaluate("state.settings.volume = 0.3; saveSettings()");
  await h.evaluate("handleWindowAction({requestId: '8', action: 'close'})");
  await flush();
  assert.equal(h.document.body.inert, false);
  assert.match(h.el("#status-msg").textContent, /disk unavailable/);
  assert.equal(h.calls.find(c => c.command === "complete_window_action").args.saved, false);
  assert.equal(h.evaluate("state.settings.volume"), 0.7);
  // The failed attempt has completed and the readback is visible. A later
  // close with no new edit must not be held open by that old result forever.
  await h.evaluate("handleWindowAction({requestId: '9', action: 'close'})");
  assert.equal(h.calls.filter(c => c.command === "complete_window_action")[1].args.saved, true);
});

for (const rejected of [false, true]) {
  test(`a ${rejected ? "rejected" : "stale"} native acknowledgement restores prior inert state`, async () => {
    const h = createHarness({ invoke: command => {
      if (command === "acknowledge_window_action") {
        if (rejected) throw new Error("ack failed");
        return false;
      }
    } });
    h.document.body.inert = rejected;
    await h.evaluate("handleWindowAction({requestId: '13', action: 'close'})");
    assert.equal(h.document.body.inert, rejected);
    assert.equal(h.evaluate("activeWindowActions.size"), 0);
    assert.equal(h.calls.some(c => c.command === "complete_window_action"), false);
  });
}

test("overlapping window actions keep controls inert until both complete", async () => {
  const saving = deferred();
  const secondComplete = deferred();
  const h = createHarness({ invoke: (command, args) => {
    if (command === "acknowledge_window_action") return true;
    if (command === "save_settings") return saving.promise;
    if (command === "complete_window_action" && args.requestId === "15") {
      return secondComplete.promise;
    }
  } });
  h.el("#app-content").inert = true;
  h.evaluate("state.settings.volume = 0.3; saveSettings()");
  const first = h.evaluate("handleWindowAction({requestId: '14', action: 'close'})");
  const second = h.evaluate("handleWindowAction({requestId: '15', action: 'quit'})");
  await flush();
  assert.equal(h.document.body.inert, true);
  assert.equal(h.evaluate("activeWindowActions.size"), 2);
  saving.resolve(null);
  await first;
  assert.equal(h.document.body.inert, true);
  assert.equal(h.evaluate("activeWindowActions.size"), 1);
  secondComplete.resolve(null);
  await second;
  assert.equal(h.document.body.inert, false);
  assert.equal(h.el("#app-content").inert, true);
  assert.equal(h.evaluate("activeWindowActions.size"), 0);
});

test("keyboard and volume controls cannot change playback or settings during close", async () => {
  const saving = deferred();
  const mediaHandlers = {};
  const h = createHarness({
    navigator: { mediaSession: { setActionHandler: (name, handler) => { mediaHandlers[name] = handler; } } },
    invoke: command => {
      if (command === "acknowledge_window_action") return true;
      if (command === "save_settings") return saving.promise;
    },
  });
  h.evaluate("wire()");
  ringFixture(h);
  h.evaluate("state.settings.volume = 0.3; saveSettings()");
  const pending = h.evaluate("handleWindowAction({requestId: '16', action: 'quit'})");
  await flush();
  h.el("#volume").value = "10";
  await h.el("#volume").dispatch("input");
  await h.document.dispatch("keydown", { code: "Space", key: " " });
  mediaHandlers.stop();
  assert.equal(h.evaluate("state.settings.volume"), 0.3);
  assert.equal(h.calls.some(c => c.command === "dismiss_alarm"), false);
  assert.equal(h.evaluate("player.source?.title"), "Wake up");
  saving.resolve(null);
  await pending;
  assert.equal(h.document.body.inert, false);
});

test("a failed older save cannot reload over a newer settings edit", async () => {
  const first = deferred();
  let writes = 0;
  const h = createHarness({ invoke: command => command === "save_settings" && ++writes === 1
    ? first.promise : undefined });
  h.evaluate("state.settings.volume = 0.2; saveSettings(true)");
  await h.fireTimer(h.evaluate("settingsSaveTimer"));
  await flush();
  h.evaluate("state.settings.volume = 0.4; saveSettings()");
  const draining = h.evaluate("flushSettings()");
  first.reject(new Error("older save failed"));
  assert.equal(await draining, false);
  assert.equal(h.evaluate("state.settings.volume"), 0.4);
  const saves = h.calls.filter(c => c.command === "save_settings");
  assert.equal(saves.length, 2);
  assert.equal(saves[0].args.explicitAutostart, true);
  assert.equal(saves[1].args.explicitAutostart, null);
  assert.equal(saves[1].args.settings.startWithWindows, true);
  assert.equal(h.calls.some(c => c.command === "get_state"), false);
});

test("a failed close waits for settings readback before it is cancelled", async () => {
  const readback = deferred();
  const h = createHarness({ invoke: command => {
    if (command === "acknowledge_window_action") return true;
    if (command === "save_settings") throw new Error("disk unavailable");
    if (command === "get_state") return readback.promise;
  } });
  h.evaluate("state.settings.volume = 0.3; saveSettings()");
  const pending = h.evaluate("handleWindowAction({requestId: '12', action: 'quit'})");
  await flush();
  assert.equal(h.calls.some(c => c.command === "complete_window_action"), false);
  readback.resolve({ stations: [], alarms: [], settings: { volume: 0.7 } });
  await pending;
  assert.equal(h.evaluate("state.settings.volume"), 0.7);
  assert.equal(h.calls.find(c => c.command === "complete_window_action").args.saved, false);
});

test("a second edit during a slow flush is saved before the window action completes", async () => {
  const first = deferred();
  let writes = 0;
  const h = createHarness({ invoke: command => {
    if (command === "acknowledge_window_action") return true;
    if (command === "save_settings" && ++writes === 1) return first.promise;
  } });
  h.evaluate("state.settings.volume = 0.2; saveSettings()");
  const pending = h.evaluate("handleWindowAction({requestId: '10', action: 'quit'})");
  await flush();
  h.evaluate("state.settings.volume = 0.5; saveSettings()");
  first.resolve(null);
  await pending;
  const saves = h.calls.filter(c => c.command === "save_settings");
  assert.equal(saves.length, 2);
  assert.equal(saves[1].args.settings.volume, 0.5);
  assert.equal(h.calls.find(c => c.command === "complete_window_action").args.saved, true);
});

test("close and quit controls use the native window-action gate", async () => {
  const h = createHarness();
  h.evaluate("wire()");
  await h.el("#btn-close").dispatch("click");
  await h.el("#btn-quit").dispatch("click");
  await flush();
  assert.deepEqual(h.calls.filter(c => c.command === "request_window_action")
    .map(c => c.args.action), ["close", "quit"]);
});

test("boot listens for native window actions before loading settings", async () => {
  let h;
  h = createHarness({ invoke: command => {
    if (command === "get_state") {
      assert.equal(h.listeners.get("window-action-requested")?.length, 1);
      return { stations: [], alarms: [], settings: {} };
    }
    if (command === "acknowledge_window_action") return true;
  } });
  await h.evaluate("boot()");
  await h.emit("window-action-requested", { requestId: "11", action: "close" });
  assert.equal(h.calls.some(c => c.command === "save_settings"), false);
  assert.equal(h.calls.find(c => c.command === "complete_window_action").args.saved, true);
});
