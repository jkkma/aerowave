const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

function harness(options = {}) {
  return createHarness({
    ...options,
    onLoad(audio) {
      // A replacement resource cannot retain the previous file's playhead.
      audio.currentTime = 0;
      audio.readyState = 0;
      options.onLoad?.(audio);
    },
  });
}

async function playRadio(h) {
  await h.evaluate(`play({ kind: "station", url: "https://radio.test/listen", title: "Listening station",
    stationId: "listening" }, { volume: 0.35 })`);
  await h.audios[0].dispatch("playing");
}

async function playMusic(h) {
  await h.evaluate(`play({ kind: "folder", url: "asset:///music/current.mp3", path: "/music/current.mp3",
    folder: "/music", title: "Current song" }, { volume: 0.45 })`);
  await h.audios[0].dispatch("playing");
  h.audios[0].currentTime = 42.5;
}

async function ring(h, overrides = {}) {
  h.context.alarmPayload = {
    alarmId: "wake", trigger: "scheduled", kind: "folder", path: "/alarm/wake.mp3",
    folder: "/alarm", title: "Alarm song", snoozeMins: 10, hour: 7, minute: 0,
    volume: 0.9, fadeSecs: 0, autoStopMins: 1, autoSnoozes: 0, ...overrides,
  };
  h.evaluate("onAlarmFire(alarmPayload)");
  await flush();
  if (h.evaluate("player.source !== null")) await h.audios[0].dispatch("playing");
}

async function expireAlarm(h) {
  await h.fireTimer(h.evaluate("autoStopTimer"));
  await flush();
  const end = h.evaluate("autoStopTimer");
  if (h.timers.get(end)?.ms === h.evaluate("GIVE_UP_FADE_SECS * 1000")) await h.fireTimer(end);
  await flush();
}

test("an alarm that gives up resumes the interrupted radio at its previous volume", async () => {
  const h = harness();
  await playRadio(h);
  await ring(h);
  assert.equal(h.evaluate("player.source.title"), "Alarm song");
  await expireAlarm(h);
  assert.equal(h.evaluate("ringing"), null);
  assert.equal(h.el("#ringing").hidden, true);
  assert.equal(h.evaluate("player.source?.stationId"), "listening");
  assert.match(h.audios[0].src, /https%3A%2F%2Fradio\.test%2Flisten$/);
  assert.equal(h.audios[0].paused, false);
  assert.equal(h.audios[0].volume, 0.35);
  assert.equal(h.evaluate("player.target"), 0.35);
  assert.equal(h.calls.filter(({ command }) => command === "dismiss_alarm").length, 1);
});

test("give-up resumes the same local song at its playhead before play and keeps shuffling", async () => {
  const starts = [];
  const h = harness({
    onPlay: (audio) => starts.push({ src: audio.src, time: audio.currentTime }),
    invoke: (command) => command === "random_track"
      ? { path: "/music/next.mp3", name: "Next song.mp3", total: 3 } : undefined,
  });
  await playMusic(h);
  await ring(h);
  assert.equal(h.audios[0].currentTime, 0);
  await expireAlarm(h);
  assert.equal(h.evaluate("player.source?.path"), "/music/current.mp3");
  assert.equal(h.audios[0].currentTime, 42.5);
  assert.equal(starts.at(-1).time, 42.5);
  assert.equal(h.audios[0].volume, 0.45);
  assert.equal(h.audios[0].loop, false);
  await h.audios[0].dispatch("ended");
  await flush();
  assert.equal(h.evaluate("player.source?.title"), "Next song");
  assert.equal(h.evaluate("player.source?.folder"), "/music");
  assert.equal(h.calls.find(({ command }) => command === "random_track").args.path, "/music");
  assert.equal(h.audios[0].paused, false);
  assert.equal(h.audios[0].volume, 0.45);
});

test("the previous-track control still walks the interrupted shuffle history after give-up", async () => {
  let picked = 0;
  const h = harness({ invoke: (command) => command === "random_track"
    ? { path: `/music/song${++picked}.mp3`, name: `Song ${picked}.mp3`, total: 3 } : undefined });
  await h.evaluate("playRandomFromFolder('/music')");
  await flush();
  await h.evaluate("playRandomFromFolder('/music')");
  await flush();
  assert.equal(h.evaluate("player.source.title"), "Song 2");
  await ring(h);
  await expireAlarm(h);
  assert.equal(h.evaluate("player.source?.title"), "Song 2");
  h.evaluate("stepFolder(-1)");
  await flush();
  assert.equal(h.evaluate("player.source?.title"), "Song 1");
  assert.equal(picked, 2);
});

test("auto-snooze resumes listening and the returning alarm restores it after its final give-up", async () => {
  const h = harness();
  await playRadio(h);
  await ring(h, { autoSnoozes: 1 });
  await expireAlarm(h);
  assert.equal(h.evaluate("player.source?.stationId"), "listening");
  assert.equal(h.calls.filter(({ command }) => command === "snooze_alarm").length, 1);
  await ring(h, { trigger: "snooze", autoSnoozes: 1 });
  await expireAlarm(h);
  assert.equal(h.evaluate("player.source?.stationId"), "listening");
  assert.equal(h.audios[0].paused, false);
  assert.equal(h.calls.filter(({ command }) => command === "snooze_alarm").length, 1);
  assert.equal(h.calls.filter(({ command }) => command === "dismiss_alarm").length, 1);
});

test("overlapping alarms restore the original listening source when the last one gives up", async () => {
  const h = harness();
  await playMusic(h);
  await ring(h);
  await ring(h, { alarmId: "second", title: "Second alarm", path: "/alarm/second.mp3" });
  await expireAlarm(h);
  assert.equal(h.evaluate("player.source?.path"), "/music/current.mp3");
  assert.equal(h.audios[0].currentTime, 42.5);
  assert.equal(h.audios[0].volume, 0.45);
});

for (const previouslyPlayed of [false, true]) {
  test(`give-up leaves an ${previouslyPlayed ? "explicitly stopped" : "idle"} player stopped`, async () => {
    const h = harness();
    if (previouslyPlayed) {
      await playRadio(h);
      h.evaluate("stopPlayback()");
    }
    await ring(h);
    await expireAlarm(h);
    assert.equal(h.evaluate("player.source"), null);
    assert.equal(h.audios[0].paused, true);
    assert.equal(h.audios[0].src, "");
    assert.equal(h.evaluate("ringing"), null);
  });
}

test("a paused local song retains its playhead and stays paused after give-up", async () => {
  const starts = [];
  const h = harness({ onPlay: (audio) => starts.push(audio.src) });
  await playMusic(h);
  h.evaluate("pausePlayback()");
  await ring(h);
  const playsBeforeReturn = starts.length;
  await expireAlarm(h);
  assert.equal(h.evaluate("player.source?.path"), "/music/current.mp3");
  assert.equal(h.evaluate("player.paused"), true);
  assert.equal(h.audios[0].paused, true);
  assert.equal(h.audios[0].currentTime, 42.5);
  assert.equal(starts.length, playsBeforeReturn);
  h.evaluate("resumePlayback()");
  await flush();
  assert.equal(h.audios[0].paused, false);
  assert.equal(h.audios[0].currentTime, 42.5);
});

test("a silent alarm restores listening when its timeout expires without a fade", async () => {
  const h = harness();
  await playRadio(h);
  await ring(h, { kind: "none", note: "No usable source" });
  assert.equal(h.evaluate("player.source"), null);
  await expireAlarm(h);
  assert.equal(h.evaluate("ringing"), null);
  assert.equal(h.evaluate("player.source?.stationId"), "listening");
  assert.equal(h.audios[0].paused, false);
});

for (const action of ["dismissRing", "snoozeRing"]) {
  test(`manual ${action === "dismissRing" ? "dismissal" : "snooze"} stops audio and discards interrupted listening`, async () => {
    const h = harness();
    await playRadio(h);
    await ring(h);
    await h.evaluate(`${action}()`);
    await flush();
    assert.equal(h.evaluate("player.source"), null);
    assert.equal(h.audios[0].paused, true);
    await ring(h, { alarmId: "later" });
    await expireAlarm(h);
    assert.equal(h.evaluate("player.source"), null);
    assert.equal(h.audios[0].paused, true);
  });

  test(`manual ${action === "dismissRing" ? "dismissal" : "snooze"} during the give-up fade stops interrupted listening`, async () => {
    const h = harness();
    await playRadio(h);
    await ring(h);
    await h.fireTimer(h.evaluate("autoStopTimer"));
    assert.equal(h.evaluate("givingUp"), true);
    await h.evaluate(`${action}()`);
    await flush();
    assert.equal(h.evaluate("ringing"), null);
    assert.equal(h.evaluate("player.source"), null);
    assert.equal(h.audios[0].paused, true);
    assert.equal(h.evaluate("autoStopTimer"), null);
  });

  test(`manual ${action === "dismissRing" ? "dismissal" : "snooze"} stays manual when its reply arrives after give-up starts`, async () => {
    const request = deferred();
    const command = action === "dismissRing" ? "dismiss_alarm" : "snooze_alarm";
    const h = harness({ invoke: (cmd) => cmd === command ? request.promise : undefined });
    await playRadio(h);
    await ring(h);
    const pending = h.evaluate(`${action}()`);
    await expireAlarm(h);
    assert.equal(h.evaluate("givingUp"), true);
    request.resolve(null);
    await pending;
    await flush();
    assert.equal(h.evaluate("ringing"), null);
    assert.equal(h.evaluate("player.source"), null);
    assert.equal(h.audios[0].paused, true);
    assert.equal(h.calls.filter(({ command: cmd }) => cmd === command).length, 1);
  });
}

for (const autoSnoozes of [0, 1]) {
  const action = autoSnoozes ? "snoozeRing" : "dismissRing";
  const command = autoSnoozes ? "snooze_alarm" : "dismiss_alarm";
  for (const manualAction of ["dismissRing", "snoozeRing"]) {
    test(`manual ${manualAction === "dismissRing" ? "dismissal" : "snooze"} cancels restoration while automatic ${autoSnoozes ? "snooze" : "dismissal"} is pending`, async () => {
      const request = deferred();
      const h = harness({ invoke: (cmd) => cmd === command ? request.promise : undefined });
      const backendActions = () => h.calls.filter(({ command: cmd }) => cmd === "dismiss_alarm" || cmd === "snooze_alarm");
      await playRadio(h);
      await ring(h, { autoSnoozes });
      await expireAlarm(h);
      assert.equal(h.evaluate("ringing?.alarmId"), "wake");
      assert.equal(backendActions().length, 1);
      await h.evaluate(`${manualAction}()`);
      assert.equal(backendActions().length, 1);
      assert.equal(h.evaluate("ringing?.alarmId"), "wake");
      request.resolve(null);
      await flush();
      assert.equal(h.evaluate("ringing"), null);
      assert.equal(h.evaluate("player.source"), null);
      assert.equal(h.audios[0].paused, true);
      assert.equal(h.audios[0].src, "");
      assert.equal(backendActions().length, 1);
    });
  }

  test(`a manual retry after failed automatic ${autoSnoozes ? "snooze" : "dismissal"} deliberately stops playback`, async () => {
    let attempts = 0;
    const h = harness({ invoke: (cmd) => cmd === command && ++attempts === 1
      ? Promise.reject(new Error("temporarily unavailable")) : undefined });
    await playRadio(h);
    await ring(h, { autoSnoozes });
    await expireAlarm(h);
    assert.equal(h.evaluate("ringing?.alarmId"), "wake");
    assert.equal(h.evaluate("player.source?.title"), "Alarm song");
    assert.equal(h.el("#ringing").hidden, false);
    await h.evaluate(`${action}()`);
    await flush();
    assert.equal(h.evaluate("ringing"), null);
    assert.equal(h.evaluate("player.source"), null);
    assert.equal(h.audios[0].paused, true);
    await ring(h, { alarmId: "later" });
    await expireAlarm(h);
    assert.equal(h.evaluate("player.source"), null);
  });

  for (const rejects of [false, true]) {
    test(`a stale automatic ${autoSnoozes ? "snooze" : "dismissal"} ${rejects ? "failure" : "completion"} cannot restore audio over a newer ring`, async () => {
      const request = deferred();
      let attempts = 0;
      const h = harness({ invoke: (cmd) => cmd === command && ++attempts === 1 ? request.promise : undefined });
      await playRadio(h);
      await ring(h, { autoSnoozes });
      await expireAlarm(h);
      await ring(h, { title: "New occurrence", path: "/alarm/new.mp3" });
      const current = h.evaluate("ringing");
      if (rejects) request.reject(new Error("old request failed"));
      else request.resolve(null);
      await flush();
      assert.equal(h.evaluate("ringing"), current);
      assert.equal(h.evaluate("player.source?.title"), "New occurrence");
      assert.equal(h.el("#ringing").hidden, false);
      await expireAlarm(h);
      assert.equal(h.evaluate("player.source?.stationId"), "listening");
      assert.equal(h.audios[0].paused, false);
    });
  }
}

for (const rejects of [false, true]) {
  test(`a restored local route's late ${rejects ? "failure" : "reply"} cannot overwrite a newer alarm`, async () => {
    const route = deferred();
    let musicRoutes = 0;
    const h = harness({ invoke: (command, args) => command === "local_file_url"
      && args.path === "/music/current.mp3" && ++musicRoutes === 2 ? route.promise : undefined });
    await playMusic(h);
    await ring(h);
    await expireAlarm(h);
    assert.equal(h.evaluate("player.source?.path"), "/music/current.mp3");
    await ring(h, { alarmId: "later", title: "Later alarm", path: "/alarm/later.mp3" });
    const alarmUrl = h.audios[0].src;
    if (rejects) route.reject(new Error("old file route failed"));
    else route.resolve("http://127.0.0.1/restored-file");
    await flush();
    assert.equal(h.evaluate("player.source?.title"), "Later alarm");
    assert.equal(h.audios[0].src, alarmUrl);
    assert.equal(h.calls.some(({ command }) => command === "backup_track"), false);
    await expireAlarm(h);
    assert.equal(h.evaluate("player.source?.path"), "/music/current.mp3");
    assert.equal(h.audios[0].currentTime, 42.5);
    assert.equal(h.audios[0].volume, 0.45);
  });
}
