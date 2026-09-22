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

for (const hls of [false, true]) {
  for (const unavailable of ["stalled", "paused", "seeking", "not ready"]) {
    test(`${hls ? "HLS" : "ordinary"} alarm falls back when ${unavailable} media keeps emitting timeupdate`, async () => {
      const h = harness({ invoke: (command, args) => {
        if (command === "probe_stream") return { url: args.url, hls };
        if (command === "backup_track") return { path: "/backup/rescue.mp3", name: "Rescue.mp3", total: 1 };
      } });
      h.context.now = 1000;
      h.evaluate("Date.now = () => now");
      await ring(h, { kind: "station", url: "https://radio.test/listen", autoStopMins: 0 });
      const audio = h.audios[0];
      audio.readyState = unavailable === "not ready" ? 2 : 3;
      audio.paused = unavailable === "paused";
      audio.seeking = unavailable === "seeking";
      for (let tick = 1; tick <= 13; tick++) {
        h.context.now = 1000 + tick * 1000;
        audio.currentTime = unavailable === "stalled" ? 0 : tick;
        await audio.dispatch("timeupdate");
        await h.fireTimer(h.evaluate("ringWatchdog"));
        await flush();
      }
      assert.equal(h.calls.filter(({ command }) => command === "backup_track").length, 1);
      assert.equal(h.evaluate("player.source?.path"), "/backup/rescue.mp3");
      assert.equal(audio.volume, 0.9);
    });
  }
}

test("a replacement alarm measures its own progress and keeps working after its file loops", async () => {
  const h = harness();
  h.context.now = 1000;
  h.evaluate("Date.now = () => now");
  await playMusic(h);
  const audio = h.audios[0];
  audio.readyState = 3;
  audio.currentTime = 180;
  await audio.dispatch("timeupdate");
  await ring(h, { autoStopMins: 0 });
  audio.readyState = 3;
  h.context.now = 2000;
  audio.currentTime = 0.25;
  await audio.dispatch("timeupdate");
  assert.equal(h.evaluate("player.lastProgress"), 2000);
  h.context.now = 3000;
  audio.currentTime = 200;
  await audio.dispatch("timeupdate");
  h.context.now = 4000;
  audio.currentTime = 0;
  await audio.dispatch("timeupdate");
  assert.equal(h.evaluate("player.lastProgress"), 3000);
  h.context.now = 5000;
  audio.currentTime = 0.25;
  await audio.dispatch("timeupdate");
  assert.equal(h.evaluate("player.lastProgress"), 5000);
  assert.equal(h.calls.some(({ command }) => command === "backup_track"), false);
});

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

test("duplicate delivery of one occurrence keeps its fade and budget, while a new occurrence replaces it", async () => {
  const h = harness();
  h.context.now = 1000;
  h.evaluate("Date.now = () => now");
  await ring(h, { occurrenceId: "1", autoSnoozes: 1 });
  const first = h.evaluate("ringing");
  h.evaluate("autoSnoozed.set('wake', 1)");
  h.context.now = 61000;
  await h.fireTimer(h.evaluate("autoStopTimer"));
  const end = h.evaluate("autoStopTimer");
  h.context.now = 64000;
  await h.fireTimer(h.evaluate("player.fadeTimer"));
  const fadedVolume = h.audios[0].volume;
  const fade = h.evaluate("player.fadeTimer");
  h.evaluate("onAlarmFire(alarmPayload)");
  assert.equal(h.evaluate("ringing"), first);
  assert.equal(h.evaluate("givingUp"), true);
  assert.equal(h.evaluate("autoStopTimer"), end);
  assert.equal(h.evaluate("player.fadeTimer"), fade);
  assert.equal(h.audios[0].volume, fadedVolume);
  assert.equal(h.evaluate("autoSnoozed.get('wake')"), 1);

  await ring(h, { occurrenceId: "2", title: "New occurrence", path: "/alarm/new.mp3" });
  assert.notEqual(h.evaluate("ringing"), first);
  assert.equal(h.evaluate("player.source?.title"), "New occurrence");
  assert.equal(h.evaluate("givingUp"), false);
  h.context.alarmPayload.occurrenceId = "1";
  h.evaluate("onAlarmFire(alarmPayload)");
  assert.equal(h.evaluate("player.source?.title"), "New occurrence");
  await h.evaluate("dismissRing()");
  h.context.alarmPayload.occurrenceId = "2";
  h.evaluate("onAlarmFire(alarmPayload)");
  assert.equal(h.evaluate("ringing"), null);
});

for (const [newer, older] of [
  ["2", "1"],
  ["9007199254740993", "9007199254740992"],
]) {
  test(`an unseen older occurrence ${older} cannot replace a newer ring ${newer}`, async () => {
    const h = harness();
    await ring(h, { occurrenceId: newer, title: "New occurrence", path: "/alarm/new.mp3" });
    const current = h.evaluate("ringing");
    const timer = h.evaluate("autoStopTimer");
    h.context.alarmPayload = {
      ...h.context.alarmPayload, occurrenceId: older,
      title: "Old occurrence", path: "/alarm/old.mp3",
    };
    h.evaluate("onAlarmFire(alarmPayload)");
    assert.equal(h.evaluate("ringing"), current);
    assert.equal(h.evaluate("player.source?.title"), "New occurrence");
    assert.equal(h.evaluate("autoStopTimer"), timer);
  });
}

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

  test(`failed manual ${action === "dismissRing" ? "dismissal" : "snooze"} during give-up retries that action after restoring sound`, async () => {
    const command = action === "dismissRing" ? "dismiss_alarm" : "snooze_alarm";
    let attempts = 0;
    const h = harness({ invoke: (cmd) => cmd === command && ++attempts === 1
      ? Promise.reject(new Error("temporarily unavailable")) : undefined });
    h.context.now = 1000;
    h.evaluate("Date.now = () => now");
    await ring(h, { autoSnoozes: 1 });
    h.context.now = 61000;
    await h.fireTimer(h.evaluate("autoStopTimer"));
    const end = h.evaluate("autoStopTimer");
    h.context.now = 64000;
    await h.fireTimer(h.evaluate("player.fadeTimer"));
    assert.ok(h.audios[0].volume < 0.9);
    await h.evaluate(`${action}()`);
    await flush();
    assert.equal(h.evaluate("ringing?.alarmId"), "wake");
    assert.equal(h.evaluate("givingUp"), false);
    assert.equal(h.audios[0].volume, 0.9);
    assert.equal(h.evaluate("player.fadeTimer"), null);
    assert.equal(h.timers.get(h.evaluate("autoStopTimer"))?.ms, 30000);
    assert.equal(h.timers.has(end), false);
    assert.equal(h.timers.get(h.evaluate("ringWatchdog"))?.interval, true);
    assert.equal(attempts, 1);
    assert.equal(h.evaluate("autoSnoozed.get('wake') || 0"), 0);
    h.context.now = 94000;
    await h.fireTimer(h.evaluate("autoStopTimer"));
    h.context.now = 100000;
    await h.fireTimer(h.evaluate("player.fadeTimer"));
    await h.fireTimer(h.evaluate("autoStopTimer"));
    await flush();
    assert.equal(attempts, 2);
    assert.equal(h.evaluate("ringing"), null);
    assert.equal(h.evaluate("player.source"), null);
  });
}

for (const autoSnoozes of [0, 1]) {
  const action = autoSnoozes ? "snoozeRing" : "dismissRing";
  const command = autoSnoozes ? "snooze_alarm" : "dismiss_alarm";
  test(`failed automatic ${autoSnoozes ? "snooze" : "dismissal"} restores a fully faded ring before a delayed retry`, async () => {
    let attempts = 0;
    const h = harness({ invoke: (cmd) => cmd === command && ++attempts === 1
      ? Promise.reject(new Error("temporarily unavailable")) : undefined });
    h.context.now = 1000;
    h.evaluate("Date.now = () => now");
    await playRadio(h);
    await ring(h, { autoSnoozes });
    h.context.now = 61000;
    await h.fireTimer(h.evaluate("autoStopTimer"));
    h.context.now = 67000;
    await h.fireTimer(h.evaluate("player.fadeTimer"));
    assert.equal(h.audios[0].volume, 0);
    await h.fireTimer(h.evaluate("autoStopTimer"));
    await flush();

    assert.equal(attempts, 1);
    assert.equal(h.evaluate("ringing?.alarmId"), "wake");
    assert.equal(h.evaluate("givingUp"), false);
    assert.equal(h.audios[0].volume, 0.9);
    assert.equal(h.evaluate("autoSnoozed.get('wake') || 0"), 0);
    assert.equal(h.timers.get(h.evaluate("autoStopTimer"))?.ms, 30000);
    assert.equal(h.timers.get(h.evaluate("ringWatchdog"))?.interval, true);

    h.context.now = 97000;
    await h.fireTimer(h.evaluate("autoStopTimer"));
    h.context.now = 103000;
    await h.fireTimer(h.evaluate("player.fadeTimer"));
    await h.fireTimer(h.evaluate("autoStopTimer"));
    await flush();
    assert.equal(attempts, 2);
    assert.equal(h.evaluate("ringing"), null);
    assert.equal(h.evaluate("player.source?.stationId"), "listening");
    assert.equal(h.evaluate("autoSnoozed.get('wake') || 0"), autoSnoozes);
  });

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

test("manual dismissal redirects a failed pending auto-snooze to a delayed dismissal", async () => {
  const request = deferred();
  let attempts = 0;
  const h = harness({ invoke: (cmd) => cmd === "snooze_alarm" && ++attempts === 1
    ? request.promise : undefined });
  h.context.now = 1000;
  h.evaluate("Date.now = () => now");
  await playRadio(h);
  await ring(h, { autoSnoozes: 1 });
  h.context.now = 61000;
  await h.fireTimer(h.evaluate("autoStopTimer"));
  h.context.now = 67000;
  await h.fireTimer(h.evaluate("player.fadeTimer"));
  await h.fireTimer(h.evaluate("autoStopTimer"));
  assert.equal(h.audios[0].volume, 0);
  await h.evaluate("dismissRing()");
  request.reject(new Error("temporarily unavailable"));
  await flush();
  assert.equal(h.evaluate("ringing?.alarmId"), "wake");
  assert.equal(h.evaluate("givingUp"), false);
  assert.equal(h.audios[0].volume, 0.9);
  assert.equal(h.timers.get(h.evaluate("autoStopTimer"))?.ms, 30000);
  assert.equal(h.evaluate("autoSnoozed.get('wake') || 0"), 0);
  h.context.now = 97000;
  await h.fireTimer(h.evaluate("autoStopTimer"));
  h.context.now = 103000;
  await h.fireTimer(h.evaluate("player.fadeTimer"));
  await h.fireTimer(h.evaluate("autoStopTimer"));
  await flush();
  assert.equal(h.evaluate("ringing"), null);
  assert.equal(h.evaluate("player.source"), null);
  assert.equal(h.calls.filter(({ command }) => command === "snooze_alarm").length, 1);
  assert.equal(h.calls.filter(({ command }) => command === "dismiss_alarm").length, 1);
});

test("a volume change while automatic snooze is pending survives its failure", async () => {
  const request = deferred();
  const h = harness({ invoke: (cmd) => cmd === "snooze_alarm" ? request.promise : undefined });
  h.context.now = 1000;
  h.evaluate("Date.now = () => now");
  await ring(h, { autoSnoozes: 1 });
  h.context.now = 61000;
  await h.fireTimer(h.evaluate("autoStopTimer"));
  h.context.now = 67000;
  await h.fireTimer(h.evaluate("player.fadeTimer"));
  await h.fireTimer(h.evaluate("autoStopTimer"));
  h.evaluate("ringing.volume = player.target = audio.volume = 0.25");
  request.reject(new Error("temporarily unavailable"));
  await flush();
  assert.equal(h.audios[0].volume, 0.25);
  assert.equal(h.evaluate("player.target"), 0.25);
  assert.equal(h.evaluate("ringing.volume"), 0.25);
  assert.equal(h.timers.get(h.evaluate("autoStopTimer"))?.ms, 30000);
});

test("repeated automatic action failures leave an audible alarm for manual control", async () => {
  const h = harness({ invoke: (cmd) => cmd === "dismiss_alarm"
    ? Promise.reject(new Error("still unavailable")) : undefined });
  h.context.now = 1000;
  h.evaluate("Date.now = () => now");
  await ring(h);
  for (let attempt = 1; attempt <= 3; attempt++) {
    h.context.now += attempt === 1 ? 60000 : 30000;
    await h.fireTimer(h.evaluate("autoStopTimer"));
    h.context.now += 6000;
    await h.fireTimer(h.evaluate("player.fadeTimer"));
    assert.equal(h.audios[0].volume, 0);
    await h.fireTimer(h.evaluate("autoStopTimer"));
    await flush();
    assert.equal(h.audios[0].volume, 0.9);
  }
  assert.equal(h.calls.filter(({ command }) => command === "dismiss_alarm").length, 3);
  assert.equal(h.evaluate("autoStopTimer"), null);
  assert.equal(h.evaluate("givingUp"), false);
  assert.equal(h.evaluate("ringing?.alarmId"), "wake");
  assert.equal(h.timers.get(h.evaluate("ringWatchdog"))?.interval, true);
});

test("repeated failed manual dismissal keeps its intent and stops retrying after three failures", async () => {
  const h = harness({ invoke: (cmd) => cmd === "dismiss_alarm"
    ? Promise.reject(new Error("still unavailable")) : undefined });
  h.context.now = 1000;
  h.evaluate("Date.now = () => now");
  await ring(h, { autoSnoozes: 1 });
  h.context.now = 61000;
  await h.fireTimer(h.evaluate("autoStopTimer"));
  await h.evaluate("dismissRing()");
  for (let attempt = 2; attempt <= 3; attempt++) {
    h.context.now += 30000;
    await h.fireTimer(h.evaluate("autoStopTimer"));
    h.context.now += 6000;
    await h.fireTimer(h.evaluate("player.fadeTimer"));
    await h.fireTimer(h.evaluate("autoStopTimer"));
    await flush();
  }
  assert.equal(h.calls.filter(({ command }) => command === "dismiss_alarm").length, 3);
  assert.equal(h.calls.some(({ command }) => command === "snooze_alarm"), false);
  assert.equal(h.evaluate("autoStopTimer"), null);
  assert.equal(h.evaluate("ringing?.alarmId"), "wake");
  assert.equal(h.audios[0].volume, 0.9);
  assert.equal(h.evaluate("autoSnoozed.get('wake') || 0"), 0);
});

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
