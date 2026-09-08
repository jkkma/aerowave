/* =====================================================================
   AEROWAVE — front end.

   The webview owns playback and the face. It never owns the clock: alarm
   times are decided in Rust and arrive here as `alarm-fire` events, because
   WebView2 throttles timers in hidden windows and an alarm you have to be
   watching is not an alarm.
   ===================================================================== */

const { invoke, convertFileSrc } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const appWindow = window.__TAURI__.window.getCurrentWindow();

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const pad2 = (n) => String(n).padStart(2, "0");
const DAY_LETTERS = ["M", "T", "W", "T", "F", "S", "S"];
const DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

let state = { stations: [], alarms: [], settings: {} };
let visibleStations = [];      // current filtered order, for prev/next
const BACKUP = "\u0000backup";  // marks a track that came from the backup folder
const shuffleFolder = () => state.settings.shuffleFolder || null;

// ---------------------------------------------------------------- clock ---

function fmtClock(d, withSeconds) {
  const h24 = state.settings.clock24h !== false;
  let h = d.getHours();
  let suffix = "";
  if (!h24) {
    suffix = h < 12 ? " AM" : " PM";
    h = h % 12 || 12;
  }
  const core = `${h24 ? pad2(h) : h}:${pad2(d.getMinutes())}`;
  return (withSeconds ? `${core}:${pad2(d.getSeconds())}` : core) + suffix;
}

function fmtAlarmTime(hour, minute) {
  return fmtClock(new Date(2000, 0, 1, hour, minute), false);
}

function fmtDuration(secs) {
  secs = Math.max(0, Math.round(secs));
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  if (h > 0) return `${h}h ${pad2(m)}m`;
  if (m > 0) return `${m}m ${pad2(secs % 60)}s`;
  return `${secs}s`;
}

function tickClock() {
  const now = new Date();
  $("#tb-clock").textContent = fmtClock(now, true);
  $("#tb-date").textContent = now
    .toLocaleDateString(undefined, { weekday: "short", day: "2-digit", month: "short" })
    .toUpperCase();
}

// -------------------------------------------------------------- signals ---

function setStatus(text, lamp) {
  $("#statusline").textContent = text;
  $("#lamp").className = "lamp" + (lamp ? " " + lamp : "");
}

let statusMsgTimer = null;
function say(msg, mood, sticky) {
  const el = $("#status-msg");
  el.textContent = msg.toUpperCase();
  el.style.color = mood === "bad" ? "var(--alert)" : mood === "good" ? "var(--alien)" : "";
  clearTimeout(statusMsgTimer);
  // Most of these are passing notices. A few - settings that would not load -
  // must not scroll past while the user is looking at another tab.
  if (sticky) return;
  statusMsgTimer = setTimeout(() => {
    el.textContent = "READY";
    el.style.color = "";
  }, 6000);
}

// -------------------------------------------------------------- player ---

const audio = new Audio();
audio.preload = "none";

/**
 * Bumped every time playback is torn down or restarted. Calling pause() (or
 * loading a new source) rejects any play() promise still in flight with an
 * AbortError - which is not a failure, it is this code superseding itself.
 * Without this guard one such rejection cascades: the alarm's fallback
 * aborts the stream, the abort is read as a failure, that starts another
 * fallback, which aborts the first fallback, and so on until it gives up.
 */
let playGeneration = 0;

/** Did something newer take over since this attempt started? */
function superseded(generation) {
  return generation !== playGeneration;
}

/** An abort is supersession, not a broken source. */
function isAbort(error) {
  if (!error) return false;
  if (error.name === "AbortError") return true;
  const message = String(error.message || error);
  return /interrupted by a call to pause|interrupted by a new load request/i.test(message);
}

const player = {
  source: null,       // { kind, url, path, title, subtitle, stationId }
  target: 0.8,        // volume we are heading for
  fadeTimer: null,
  metaTimer: null,
  retries: 0,
  retryTimer: null,
  backupAttempts: 0,  // backup tracks that have failed for one ringing alarm
  lastProgress: 0,    // when audio last actually arrived, for the ring watchdog
  resolved: null,     // stream URL after following a playlist, if different
  get playing() {
    return !!this.source;
  },
};

function markPlaying(on) {
  document.body.classList.toggle("playing", on);
}

function clearTimers() {
  clearInterval(player.fadeTimer);
  clearInterval(player.metaTimer);
  clearTimeout(player.retryTimer);
  player.fadeTimer = player.metaTimer = player.retryTimer = null;
}

function stopPlayback(quiet) {
  playGeneration += 1;
  // Load-bearing: without this an alarm firing while the radio is already
  // playing inherits a fresh timestamp and its watchdog never trips.
  player.lastProgress = 0;
  clearTimers();
  // Drop the source first: tearing down the element fires events that would
  // otherwise look like a stream failure and start a reconnect.
  player.source = null;
  audio.pause();
  audio.removeAttribute("src");
  audio.load();
  player.retries = 0;
  player.resolved = null;
  markPlaying(false);
  if (!quiet) {
    setStatus("STANDBY", "");
    $("#np-station").textContent = "NO CARRIER";
    $("#np-track").textContent = "Pick a station, choose a folder, or set an alarm.";
    $("#np-meta").textContent = "";
  }
}

/** Ramp the element volume up to `player.target` over `seconds`. */
function fadeTo(target, seconds) {
  clearInterval(player.fadeTimer);
  player.target = target;
  if (!seconds || seconds <= 0) {
    audio.volume = target;
    return;
  }
  const started = Date.now();
  const from = 0.02;
  audio.volume = from;
  player.fadeTimer = setInterval(() => {
    const t = Math.min(1, (Date.now() - started) / (seconds * 1000));
    // ease-in: quiet for longer, then arrive
    const v = from + (target - from) * t * t;
    audio.volume = Math.min(1, Math.max(0, v));
    if (t >= 1) {
      clearInterval(player.fadeTimer);
      player.fadeTimer = null;
    }
  }, 120);
}

/**
 * Will the player actually decode this? The Rust probe only proves the
 * server answers - plenty of stations serve a perfectly good HTTP response
 * that the media element then refuses. This is the check that counts.
 */
function canDecode(url, timeoutMs = 9000) {
  return new Promise((resolve) => {
    const probe = new Audio();
    probe.preload = "auto";
    probe.muted = true;
    let settled = false;
    const done = (result) => {
      if (settled) return;
      settled = true;
      probe.removeAttribute("src");
      probe.load();
      resolve(result);
    };
    probe.addEventListener("loadedmetadata", () => done({ ok: true }));
    probe.addEventListener("canplay", () => done({ ok: true }));
    probe.addEventListener("error", () => {
      const code = probe.error ? probe.error.code : 0;
      done({
        ok: false,
        reason: code === 4 ? "the player cannot decode this stream" : "media error " + code,
      });
    });
    setTimeout(() => done({ ok: false, reason: "no audio within " + Math.round(timeoutMs / 1000) + "s" }), timeoutMs);
    probe.src = url;
    probe.load();
  });
}

function showNowPlaying(title, sub, meta) {
  $("#np-station").textContent = title || "";
  $("#np-track").textContent = sub || "";
  if (meta !== undefined) $("#np-meta").textContent = meta || "";
}

/**
 * Start a source. `opts.fadeSecs` ramps the volume in, `opts.volume`
 * overrides the master volume (alarms have their own).
 */
async function play(source, opts = {}) {
  stopPlayback(true);
  const generation = playGeneration;
  player.source = source;
  player.lastProgress = Date.now();
  player.retries = 0;
  const volume = opts.volume !== undefined ? opts.volume : state.settings.volume ?? 0.8;

  showNowPlaying(source.title, source.subtitle || "", "");
  markPlaying(true);

  setStatus("CONNECTING", "busy");
  let url = source.url;

  if (source.kind === "station" && /\.(pls|m3u|m3u8|asx)(\?|$)/i.test(url)) {
    // A playlist file cannot be handed to <audio> - resolve it first.
    try {
      const info = await invoke("probe_stream", { url, wantTitle: false });
      url = info.url;
      player.resolved = info.url;
      if (info.warning) say(info.warning, "bad");
    } catch (e) {
      if (!superseded(generation)) failure(String(e));
      return;
    }
    if (superseded(generation)) return;
  }

  audio.src = url;
  audio.volume = opts.fadeSecs > 0 ? 0.02 : volume;
  player.target = volume;
  try {
    await audio.play();
  } catch (e) {
    // Autoplay refusals and decode errors land here - but so do aborts from
    // this code starting something else, which must not count as a failure.
    if (superseded(generation) || isAbort(e)) return;
    failure(String(e && e.message ? e.message : e));
    return;
  }
  if (superseded(generation)) return;
  if (opts.fadeSecs > 0) fadeTo(volume, opts.fadeSecs);

  if (source.kind === "station") startMetadata(source);
}

function startMetadata(source) {
  clearInterval(player.metaTimer);
  if (state.settings.showMetadata === false) return;
  const poll = async () => {
    if (player.source !== source) return;
    try {
      const info = await invoke("probe_stream", { url: source.url, wantTitle: true });
      if (player.source !== source) return;
      if (info.title) $("#np-track").textContent = info.title;
      const bits = [];
      if (info.bitrate) bits.push(info.bitrate + " kbps");
      if (info.genre) bits.push(info.genre);
      if (info.name && info.name !== source.title) bits.push(info.name);
      $("#np-meta").textContent = bits.join("  ·  ");
    } catch {
      /* metadata is a nicety; a failure here must not disturb playback */
    }
  };
  poll();
  player.metaTimer = setInterval(poll, 25000);
}

/**
 * Something went wrong mid-stream. Reconnect, or give up loudly.
 * `opts.fatal` means the source is unusable and retrying cannot help.
 */
function failure(detail, opts = {}) {
  const source = player.source;
  if (!source) return;

  if (ringing && ringing.alarmId) {
    // An alarm must make a noise. Reach for the backup folder and say why.
    if (source.folder === BACKUP) {
      // Even the backup track failed - try a couple more before giving up.
      if (player.backupAttempts++ < 3) {
        playBackupTrack("that backup track failed - trying another", { volume: ringing.volume });
      } else {
        goSilent("the backup folder will not play (" + detail + ")");
      }
      return;
    }
    say("alarm source failed - using the backup folder", "bad");
    $("#ring-note").textContent = "Source failed (" + detail + ") - playing the backup folder.";
    player.backupAttempts = 0;
    playBackupTrack("after " + detail, { volume: ringing.volume });
    return;
  }

  if (source.kind === "folder") {
    if (source.folder === BACKUP || !source.folder) {
      setStatus("TRACK FAILED", "error");
      say("could not play that file: " + detail, "bad");
      stopPlayback();
      return;
    }
    // A file from the shuffle folder would not play; move on to another one.
    setStatus("SKIPPING", "busy");
    playRandomFromFolder(source.folder, { volume: player.target, silentErrors: true });
    return;
  }

  player.retries += 1;
  if (opts.fatal || player.retries > 4) {
    // The station is not coming back. Fall through to the backup folder.
    say("stream unavailable - falling back to the backup folder", "bad");
    playBackupTrack(source.title + " is unavailable (" + detail + ")");
    return;
  }
  const wait = 1500 * player.retries;
  setStatus("RECONNECTING " + player.retries + "/4", "busy");
  clearTimeout(player.retryTimer);
  player.retryTimer = setTimeout(async () => {
    if (player.source !== source) return;
    // Second attempt onwards, try the playlist-resolved URL.
    if (player.retries >= 2 && !player.resolved) {
      try {
        const info = await invoke("probe_stream", { url: source.url, wantTitle: false });
        player.resolved = info.url;
      } catch { /* stay with the original */ }
    }
    const generation = playGeneration;
    audio.src = player.resolved || source.url;
    audio.play().catch((e) => {
      if (superseded(generation) || isAbort(e)) return;
      failure(String(e && e.message ? e.message : e));
    });
  }, wait);
}

audio.addEventListener("playing", () => {
  if (!player.source) return;
  player.retries = 0;
  setStatus(player.source.kind === "folder" ? "PLAYING FILE" : "ON AIR", "on");
});
// The only event that means audio is genuinely coming out, rather than that
// something was asked to start.
audio.addEventListener("timeupdate", () => {
  if (player.source) player.lastProgress = Date.now();
});
audio.addEventListener("waiting", () => player.source && setStatus("BUFFERING", "busy"));
audio.addEventListener("stalled", () => player.source && setStatus("STALLED", "busy"));
audio.addEventListener("error", () => {
  const err = audio.error;
  console.error(
    "aerowave media error:",
    "code=" + (err ? err.code : "?"),
    "net=" + audio.networkState,
    "src=" + (audio.currentSrc || "(empty)")
  );
  // MEDIA_ERR_SRC_NOT_SUPPORTED: the engine will not play this however many
  // times we ask. Skip the reconnects and go straight to the fallback.
  if (err && err.code === 4) {
    failure("this stream is not one the player can decode", { fatal: true });
    return;
  }
  failure(err ? "media error " + err.code : "unknown media error");
});
audio.addEventListener("ended", () => {
  if (!player.source) return;
  if (player.source.kind === "folder") {
    // Local file finished: keep going with another random track.
    if (player.source.folder === BACKUP) {
      playBackupTrack("next track from the backup folder", { volume: player.target });
    } else if (player.source.folder) {
      playRandomFromFolder(player.source.folder, { volume: player.target, silentErrors: true });
    } else {
      stopPlayback();
    }
  } else {
    failure("stream ended");
  }
});

// --------------------------------------------------------- radio actions ---

function stationById(id) {
  return state.stations.find((s) => s.id === id) || null;
}

function playStation(station, opts) {
  if (!station) return;
  play(
    {
      kind: "station",
      url: station.url,
      title: station.name,
      subtitle: state.settings.showMetadata === false ? station.url : "…",
      stationId: station.id,
    },
    opts
  );
  state.settings.lastStation = station.id;
  saveSettings();
  renderStations();
}

async function playRandomFromFolder(folder, opts = {}) {
  if (!folder) {
    say("choose a folder first", "bad");
    return;
  }
  try {
    const pick = await invoke("random_track", { path: folder });
    play(
      {
        kind: "folder",
        url: convertFileSrc(pick.path),
        title: pick.name.replace(/\.[^.]+$/, ""),
        subtitle: `${pick.total} track${pick.total === 1 ? "" : "s"} in the folder`,
        folder,
      },
      opts
    );
  } catch (e) {
    if (!opts.silentErrors) say(String(e), "bad");
    stopPlayback();
  }
}

function togglePlay() {
  if (player.playing) {
    stopPlayback();
    return;
  }
  const last = stationById(state.settings.lastStation) || state.stations[0];
  if (last) playStation(last);
  else say("no stations yet — add one", "bad");
}

function step(delta) {
  const list = visibleStations.length ? visibleStations : state.stations;
  if (!list.length) return;
  const here = list.findIndex((s) => s.id === (player.source && player.source.stationId));
  const next = list[(here + delta + list.length * 2) % list.length] || list[0];
  playStation(next);
}

// --------------------------------------------------------------- alarms ---

let ringing = null;
let ringWatchdog = null;
let autoStopTimer = null;

/**
 * The fallback for everything: a random track from the backup folder. Used
 * when an alarm source will not make a sound, and when a station gives up
 * during ordinary listening.
 */
async function playBackupTrack(reason, opts = {}) {
  // Give this attempt its own quiet window. The watchdog stays armed on
  // purpose, so it guards the backup track too.
  player.lastProgress = Date.now();
  try {
    const pick = await invoke("backup_track");
    const title = pick.name.replace(/\.[^.]+$/, "");
    play(
      {
        kind: "folder",
        url: convertFileSrc(pick.path),
        title,
        subtitle: reason || "from the backup folder",
        folder: BACKUP,
      },
      opts
    );
    // Say what is actually ringing, not what was supposed to.
    if (ringing) $("#ring-source").textContent = "BACKUP FOLDER · " + title.toUpperCase();
    return true;
  } catch (e) {
    if (ringing) {
      goSilent(String(e));
    } else {
      setStatus("OFF AIR", "error");
      showNowPlaying("NO CARRIER", reason || "", String(e));
      stopPlayback(true);
      markPlaying(false);
    }
    return false;
  }
}

/** Nothing at all can be played: keep the alarm on screen and say why. */
function goSilent(why) {
  clearInterval(ringWatchdog);
  ringWatchdog = null;
  stopPlayback(true);
  markPlaying(false);
  $("#ringcard").classList.add("silent");
  $("#ring-trigger").textContent = "ALARM - NO SOUND";
  $("#ring-source").textContent = "NOTHING TO PLAY";
  const advice = state.settings.backupFolder
    ? "Check the backup folder in SETUP."
    : "Set a backup folder in SETUP so this alarm can always ring.";
  $("#ring-note").textContent = (why ? why + ". " : "") + advice;
}

/**
 * Watch a ringing alarm by whether audio is actually arriving.
 *
 * `audio.paused` goes false the moment `play()` is called, so a one-shot
 * check on it is satisfied by any station that answers and then serves
 * silence - which is precisely the failure this guard exists for. A progress
 * timestamp catches that, and also a stream that starts and hangs mid-ring,
 * which fires neither `error` nor `ended`.
 */
function armRingWatchdog(quietMs, note, reason, volume) {
  clearInterval(ringWatchdog);
  ringWatchdog = setInterval(() => {
    if (!ringing) {
      clearInterval(ringWatchdog);
      ringWatchdog = null;
      return;
    }
    if (Date.now() - player.lastProgress <= quietMs) return;

    // One fallback per quiet window rather than one per tick.
    player.lastProgress = Date.now();
    if (player.backupAttempts++ >= 3) {
      goSilent("nothing has made a sound after four attempts");
      return;
    }
    $("#ring-note").textContent = note;
    playBackupTrack(reason, { volume });
  }, 1000);
}

function onAlarmFire(payload) {
  ringing = payload;
  // Otherwise a sleep timer set before bed calls stopPlayback() mid-ring and
  // leaves the overlay up over silence.
  setSleep(0);
  clearInterval(ringWatchdog);
  clearTimeout(autoStopTimer);
  player.backupAttempts = 0;
  $("#ringcard").classList.remove("silent");

  const overlay = $("#ringing");
  $("#ring-trigger").textContent =
    payload.trigger === "snooze" ? "SNOOZE OVER"
      : payload.trigger === "catchup" ? "MISSED ALARM"
      : payload.trigger === "test" ? "ALARM TEST"
      : "ALARM";
  $("#ring-time").textContent = fmtAlarmTime(payload.hour, payload.minute);
  $("#ring-label").textContent = payload.label || "Alarm";
  $("#ring-source").textContent =
    (payload.kind === "station" ? "STATION · " : payload.kind === "folder" ? "SHUFFLE · " : "") +
    (payload.title || "");
  $("#ring-note").textContent = payload.note || "";
  $("#ring-snooze-mins").textContent = payload.snoozeMins + " min";
  overlay.hidden = false;

  const opts = { volume: payload.volume, fadeSecs: payload.fadeSecs };
  if (payload.kind === "station") {
    play({ kind: "station", url: payload.url, title: payload.title, subtitle: payload.label, stationId: null }, opts);
    // If the stream has not made a sound within twelve seconds, stop
    // waiting for it and ring something that definitely works.
    armRingWatchdog(
      12000,
      "That stream did not start - playing the backup folder.",
      "stream did not start",
      payload.volume
    );
  } else if (payload.kind === "folder") {
    const alarm = state.alarms.find((a) => a.id === payload.alarmId);
    const own = alarm && alarm.source && alarm.source.kind === "folder" ? alarm.source.path : null;
    // A note means Rust could not use the alarm's own source and reached for
    // the backup folder; keep pulling from there for the rest of the ring.
    const folder = payload.note || !own ? BACKUP : own;
    play({ kind: "folder", url: convertFileSrc(payload.path), title: payload.title, subtitle: payload.label, folder }, opts);
    armRingWatchdog(
      8000,
      "That track would not play - playing the backup folder.",
      "that track would not play",
      payload.volume
    );
  } else {
    // Rust could not resolve any source at all.
    goSilent(payload.note || "no source available");
  }

  if (payload.autoStopMins > 0) {
    autoStopTimer = setTimeout(() => {
      say("alarm gave up after " + payload.autoStopMins + " minutes");
      dismissRing();
    }, payload.autoStopMins * 60000);
  }
}

function closeRingUi() {
  clearInterval(ringWatchdog);
  clearTimeout(autoStopTimer);
  ringWatchdog = autoStopTimer = null;
  $("#ringing").hidden = true;
  ringing = null;
}

async function dismissRing() {
  const id = ringing && ringing.alarmId;
  closeRingUi();
  stopPlayback();
  if (id) await invoke("dismiss_alarm", { alarmId: id });
  refreshNextAlarm();
}

async function snoozeRing() {
  if (!ringing) return;
  const { alarmId, snoozeMins } = ringing;
  closeRingUi();
  stopPlayback();
  await invoke("snooze_alarm", { alarmId, minutes: snoozeMins });
  say("snoozed for " + snoozeMins + " minutes", "good");
  refreshNextAlarm();
}

async function refreshNextAlarm() {
  let next = null;
  try {
    next = await invoke("next_alarm");
  } catch { /* nothing scheduled */ }
  const box = $("#next-alarm");
  const bar = $("#status-next");
  if (!next) {
    box.textContent = "NO ALARM SET";
    bar.textContent = "";
    return;
  }
  const when = new Date(next.atMs);
  const label = next.label ? next.label + " · " : "";
  const text = `${next.snoozed ? "SNOOZED" : "NEXT"} ${label}${fmtClock(when, false)} — IN ${fmtDuration(next.inSecs)}`;
  box.textContent = text;
  bar.textContent = text;
}

// ----------------------------------------------------------- rendering ---

function renderStations() {
  const q = $("#station-filter").value.trim().toLowerCase();
  const list = $("#station-list");
  visibleStations = state.stations.filter(
    (s) => !q || s.name.toLowerCase().includes(q) || (s.tag || "").toLowerCase().includes(q)
  );
  list.innerHTML = "";

  if (!visibleStations.length) {
    const li = document.createElement("li");
    li.className = "empty";
    li.textContent = state.stations.length ? "Nothing matches that filter." : "No stations yet.";
    list.append(li);
    return;
  }

  visibleStations.forEach((station, i) => {
    const li = document.createElement("li");
    li.className = "row";
    if (player.source && player.source.stationId === station.id) li.classList.add("on");
    li.dataset.id = station.id;

    const idx = document.createElement("span");
    idx.className = "idx";
    idx.textContent = pad2(i + 1);

    const name = document.createElement("span");
    name.className = "name";
    const b = document.createElement("b");
    b.textContent = station.name;
    const small = document.createElement("small");
    small.textContent = station.url;
    name.append(b, small);

    li.append(idx, name);

    if (station.tag) {
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = station.tag;
      li.append(tag);
    }

    const star = document.createElement("button");
    star.className = "icon star" + (station.favorite ? " on" : "");
    star.textContent = station.favorite ? "★" : "☆";
    star.title = "Favourite";
    star.addEventListener("click", (e) => {
      e.stopPropagation();
      station.favorite = !station.favorite;
      saveStations();
      renderStations();
    });

    const edit = document.createElement("button");
    edit.className = "icon";
    edit.textContent = "✎";
    edit.title = "Edit";
    edit.addEventListener("click", (e) => {
      e.stopPropagation();
      openStationEditor(station);
    });

    li.append(star, edit);
    li.addEventListener("click", () => playStation(station));
    list.append(li);
  });
}

function daysLabel(days) {
  if (!days || !days.length) return "ONCE";
  if (days.length === 7) return "EVERY DAY";
  const weekdays = [0, 1, 2, 3, 4];
  if (days.length === 5 && weekdays.every((d) => days.includes(d))) return "WEEKDAYS";
  if (days.length === 2 && days.includes(5) && days.includes(6)) return "WEEKENDS";
  return DAY_LETTERS.map((letter, i) =>
    days.includes(i) ? letter : `<i>${letter}</i>`
  ).join("");
}

function sourceLabel(source) {
  if (!source) return "";
  if (source.kind === "station") {
    const st = stationById(source.stationId);
    return st ? st.name : "missing station";
  }
  if (source.kind === "folder") {
    const parts = source.path.replace(/[\\/]+$/, "").split(/[\\/]/);
    return "shuffle · " + (parts[parts.length - 1] || source.path);
  }
  return "";
}

function renderAlarms() {
  const list = $("#alarm-list");
  list.innerHTML = "";
  const sorted = [...state.alarms].sort((a, b) => a.hour - b.hour || a.minute - b.minute);

  if (!sorted.length) {
    const li = document.createElement("li");
    li.className = "empty";
    li.textContent = "No alarms. Add one and it will ring even with the window hidden.";
    list.append(li);
    return;
  }

  sorted.forEach((alarm) => {
    const li = document.createElement("li");
    li.className = "row" + (alarm.enabled ? "" : " off");

    const when = document.createElement("span");
    when.className = "when";
    when.textContent = fmtAlarmTime(alarm.hour, alarm.minute);

    const name = document.createElement("span");
    name.className = "name";
    const b = document.createElement("b");
    b.textContent = alarm.label || "Alarm";
    const days = document.createElement("small");
    days.className = "days-mini";
    days.innerHTML = daysLabel(alarm.days);
    days.append("  ·  " + sourceLabel(alarm.source).toUpperCase());
    name.append(b, days);

    const sw = document.createElement("button");
    sw.className = "sw";
    sw.setAttribute("aria-pressed", String(!!alarm.enabled));
    sw.title = "Enable";
    sw.addEventListener("click", (e) => {
      e.stopPropagation();
      alarm.enabled = !alarm.enabled;
      saveAlarms();
      renderAlarms();
    });

    const edit = document.createElement("button");
    edit.className = "icon";
    edit.textContent = "✎";
    edit.title = "Edit";
    edit.addEventListener("click", (e) => {
      e.stopPropagation();
      openAlarmEditor(alarm);
    });

    li.append(when, name, sw, edit);
    li.addEventListener("click", () => openAlarmEditor(alarm));
    list.append(li);
  });
}

/** Show a chosen folder and how much it has in it. */
function showFolderCounts(info, selector) {
  $(selector).textContent = info.count
    ? `${info.path}  —  ${info.count} playable file${info.count === 1 ? "" : "s"}`
    : `${info.path}  —  nothing playable in here`;
  $(selector).title = info.path;
  say(
    info.count ? info.count + " tracks found" : "no playable audio in that folder",
    info.count ? "good" : "bad"
  );
}

/** Fill in the folder rows from what is stored, with a fresh file count. */
async function refreshFolderLabels() {
  // An unset shuffle folder costs nothing. An unset backup folder means every
  // fallback in the app ends in silence, so the two must not look alike.
  const rows = [
    { path: state.settings.shuffleFolder, selector: "#folder-path", critical: false },
    { path: state.settings.backupFolder, selector: "#backup-path", critical: true },
  ];
  for (const row of rows) {
    const el = $(row.selector);
    if (!row.path) {
      el.textContent = row.critical
        ? "No folder chosen — alarms have nothing to fall back on"
        : "No folder chosen";
      el.classList.toggle("warn", row.critical);
      continue;
    }
    try {
      const info = await invoke("folder_info", { path: row.path });
      el.textContent = info.count
        ? `${row.path}  —  ${info.count} playable file${info.count === 1 ? "" : "s"}`
        : `${row.path}  —  nothing playable in here`;
      el.classList.toggle("warn", row.critical && !info.count);
      el.title = row.path;
    } catch {
      el.textContent = row.path + "  —  unreadable";
      el.classList.toggle("warn", row.critical);
    }
  }
}

function renderSettings() {
  $$(".settings .row").forEach((row) => {
    const key = row.dataset.setting;
    row.querySelector(".sw").setAttribute("aria-pressed", String(!!state.settings[key]));
  });
  const vol = Math.round((state.settings.volume ?? 0.8) * 100);
  $("#volume").value = vol;
  $("#volval").textContent = vol;
  $("#volume").style.setProperty("--fill", vol + "%");
}

// ------------------------------------------------------------ persisting ---

const saveStations = () => invoke("save_stations", { stations: state.stations }).catch((e) => say(String(e), "bad"));
const saveAlarms = () =>
  invoke("save_alarms", { alarms: state.alarms })
    .then(refreshNextAlarm)
    .catch((e) => say(String(e), "bad"));

let settingsSaveTimer = null;
function saveSettings() {
  clearTimeout(settingsSaveTimer);
  settingsSaveTimer = setTimeout(() => {
    invoke("save_settings", { settings: state.settings }).catch((e) => {
      say(String(e), "bad");
      // start-with-Windows can fail on its own; reflect what actually stuck.
      loadState();
    });
  }, 250);
}

async function loadState() {
  state = await invoke("get_state");
  renderStations();
  renderAlarms();
  renderSettings();
  refreshNextAlarm();
  refreshFolderLabels();
}

// -------------------------------------------------------- station editor ---

let editingStation = null;

function openStationEditor(station) {
  editingStation = station || null;
  $("#st-name").value = station ? station.name : "";
  $("#st-url").value = station ? station.url : "";
  $("#st-tag").value = station ? station.tag || "" : "";
  $("#st-note").textContent = "";
  $("#st-note").className = "editor-note";
  $("#st-delete").classList.toggle("hidden", !station);
  $("#station-editor").classList.remove("hidden");
  $("#st-name").focus();
}

function closeStationEditor() {
  editingStation = null;
  $("#station-editor").classList.add("hidden");
}

// ---------------------------------------------------------- alarm editor ---

let editingAlarm = null;
let editorDays = [];
let editorKind = "station";
let editorFolder = null;

function newId() {
  return "a" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

function updateDayHint() {
  const hint = $("#al-dayhint");
  if (editorDays.length) {
    hint.textContent = editorDays
      .slice()
      .sort((a, b) => a - b)
      .map((d) => DAY_NAMES[d])
      .join(" ");
  } else {
    hint.textContent = `Once, at the next ${$("#al-hour").value}:${$("#al-minute").value}`;
  }
}

function setKind(kind) {
  editorKind = kind;
  $$("#al-kind .chip").forEach((c) => c.classList.toggle("on", c.dataset.kind === kind));
  $("#al-station").classList.toggle("hidden", kind !== "station");
  $("#al-folderpick").classList.toggle("hidden", kind !== "folder");
  const note = $("#al-sourcenote");
  note.className = "editor-note";
  note.textContent =
    kind === "folder"
      ? "One file is picked at random from the folder each time it rings."
      : "If the stream will not start within twelve seconds, the backup folder plays instead.";
  // Both kinds fall back to the backup folder, so both are silent without one.
  if (!state.settings.backupFolder) {
    note.className = "editor-note bad";
    note.textContent +=
      " No backup folder is set, so this alarm has nothing to fall back on - set one in SETUP.";
  }
}

function fillStationSelect(selected) {
  const sel = $("#al-station");
  sel.innerHTML = "";
  if (!state.stations.length) {
    const opt = document.createElement("option");
    opt.textContent = "No stations yet";
    opt.value = "";
    sel.append(opt);
    return;
  }
  state.stations.forEach((s) => {
    const opt = document.createElement("option");
    opt.value = s.id;
    opt.textContent = s.name;
    if (s.id === selected) opt.selected = true;
    sel.append(opt);
  });
}

/**
 * Point a select at a value, adding an option for it when the list does not
 * already offer it. A number hand-written into aerowave.json survives a trip
 * through the editor instead of being quietly reset to the nearest preset.
 */
function setSelectValue(select, value, describe) {
  const wanted = String(value);
  const known = Array.prototype.some.call(select.options, (o) => o.value === wanted);
  if (!known) {
    const option = document.createElement("option");
    option.value = wanted;
    option.textContent = describe(value);
    select.append(option);
  }
  select.value = wanted;
}

function openAlarmEditor(alarm) {
  editingAlarm = alarm || null;
  const now = new Date();
  const base = alarm || {
    hour: (now.getHours() + 1) % 24,
    minute: 0,
    days: [],
    label: "",
    source: { kind: "station", stationId: (state.stations[0] || {}).id },
    volume: 0.8,
    fadeSecs: 20,
    snoozeMins: 9,
    autoStopMins: 30,
  };

  $("#al-hour").value = pad2(base.hour);
  $("#al-minute").value = pad2(base.minute);
  $("#al-label").value = base.label || "";
  editorDays = [...(base.days || [])];
  $$("#al-days button").forEach((b) => b.classList.toggle("on", editorDays.includes(+b.dataset.day)));
  updateDayHint();

  const src = base.source || { kind: "station" };
  editorFolder = src.kind === "folder" ? src.path : null;
  $("#al-folderpath").textContent = editorFolder || "No folder chosen";
  fillStationSelect(src.kind === "station" ? src.stationId : (state.stations[0] || {}).id);
  setKind(src.kind);

  $("#al-volume").value = Math.round((base.volume ?? 0.8) * 100);
  $("#al-volval").textContent = $("#al-volume").value;
  $("#al-volume").style.setProperty("--fill", $("#al-volume").value + "%");
  setSelectValue($("#al-fade"), base.fadeSecs ?? 20, (v) => v + " s");
  setSelectValue($("#al-snooze"), base.snoozeMins ?? 9, (v) => v + " min");
  setSelectValue($("#al-autostop"), base.autoStopMins ?? 30, (v) =>
    v === 0 ? "never" : v + " min"
  );

  $("#al-delete").classList.toggle("hidden", !alarm);
  $("#alarm-editor").classList.remove("hidden");
}

function closeAlarmEditor() {
  editingAlarm = null;
  $("#alarm-editor").classList.add("hidden");
}

function readAlarmEditor() {
  const hour = Math.min(23, Math.max(0, parseInt($("#al-hour").value, 10) || 0));
  const minute = Math.min(59, Math.max(0, parseInt($("#al-minute").value, 10) || 0));
  let source;
  if (editorKind === "station") {
    const id = $("#al-station").value;
    if (!id) return { error: "add a station first, or point the alarm at a folder" };
    source = { kind: "station", stationId: id };
  } else if (editorKind === "folder") {
    if (!editorFolder) return { error: "choose a folder first" };
    source = { kind: "folder", path: editorFolder };
  } else {
    return { error: "pick a station or a folder" };
  }
  return {
    alarm: {
      id: editingAlarm ? editingAlarm.id : newId(),
      label: $("#al-label").value.trim(),
      hour,
      minute,
      days: [...editorDays].sort((a, b) => a - b),
      enabled: editingAlarm ? editingAlarm.enabled : true,
      source,
      volume: (+$("#al-volume").value || 80) / 100,
      fadeSecs: +$("#al-fade").value,
      snoozeMins: +$("#al-snooze").value,
      autoStopMins: +$("#al-autostop").value,
    },
  };
}

// --------------------------------------------------------- sleep timer ---

let sleepUntil = 0;

function setSleep(mins) {
  sleepUntil = mins > 0 ? Date.now() + mins * 60000 : 0;
  $$("#sleep-chips .chip").forEach((c) => c.classList.toggle("on", +c.dataset.mins === mins));
  $("#sleep-left").textContent = "";
  if (mins > 0) say("sleep timer set for " + mins + " minutes", "good");
}

setInterval(() => {
  if (!sleepUntil) return;
  const left = sleepUntil - Date.now();
  if (left <= 0) {
    sleepUntil = 0;
    $$("#sleep-chips .chip").forEach((c) => c.classList.toggle("on", c.dataset.mins === "0"));
    $("#sleep-left").textContent = "";
    // Covers the timer set *while* an alarm rings, which onAlarmFire cannot:
    // let it lapse, but never take the alarm's audio with it.
    if (ringing) return;
    stopPlayback();
    say("sleep timer — goodnight");
    return;
  }
  $("#sleep-left").textContent = fmtDuration(left / 1000) + " LEFT";
}, 1000);

// ---------------------------------------------------------------- wiring ---

function wire() {
  // window controls
  $("#btn-min").addEventListener("click", () => appWindow.minimize());
  $("#btn-max").addEventListener("click", () => appWindow.toggleMaximize());
  $("#btn-close").addEventListener("click", () => appWindow.close());
  $("#btn-quit").addEventListener("click", () => invoke("quit_app"));

  // transport
  $("#btn-play").addEventListener("click", togglePlay);
  $("#btn-prev").addEventListener("click", () => step(-1));
  $("#btn-next").addEventListener("click", () => step(1));
  $("#btn-shuffle").addEventListener("click", () => playRandomFromFolder(shuffleFolder()));

  $("#volume").addEventListener("input", (e) => {
    const v = +e.target.value;
    e.target.style.setProperty("--fill", v + "%");
    $("#volval").textContent = v;
    state.settings.volume = v / 100;
    if (!ringing) {
      clearInterval(player.fadeTimer);
      audio.volume = v / 100;
      player.target = v / 100;
    }
    saveSettings();
  });

  $$("#sleep-chips .chip").forEach((chip) =>
    chip.addEventListener("click", () => setSleep(+chip.dataset.mins))
  );

  // tabs
  $$(".tab").forEach((tab) =>
    tab.addEventListener("click", () => {
      $$(".tab").forEach((t) => t.classList.toggle("on", t === tab));
      $$(".pane").forEach((p) => p.classList.toggle("on", p.id === "pane-" + tab.dataset.pane));
      if (tab.dataset.pane === "alarms") refreshNextAlarm();
    })
  );

  // stations
  $("#station-filter").addEventListener("input", renderStations);
  $("#btn-add-station").addEventListener("click", () => openStationEditor(null));
  $("#st-cancel").addEventListener("click", closeStationEditor);

  $("#station-editor").addEventListener("submit", (e) => {
    e.preventDefault();
    const name = $("#st-name").value.trim();
    const url = $("#st-url").value.trim();
    if (!name || !url) return;
    if (!/^https?:\/\//i.test(url)) {
      const note = $("#st-note");
      note.textContent = "The URL must start with http:// or https://";
      note.className = "editor-note bad";
      return;
    }
    if (editingStation) {
      Object.assign(editingStation, { name, url, tag: $("#st-tag").value.trim() });
    } else {
      state.stations.push({
        id: newId(),
        name,
        url,
        tag: $("#st-tag").value.trim(),
        favorite: false,
      });
    }
    saveStations();
    renderStations();
    renderAlarms();
    closeStationEditor();
    say("station saved", "good");
  });

  $("#st-delete").addEventListener("click", () => {
    if (!editingStation) return;
    state.stations = state.stations.filter((s) => s.id !== editingStation.id);
    saveStations();
    renderStations();
    renderAlarms();
    closeStationEditor();
    say("station deleted");
  });

  $("#st-test").addEventListener("click", async () => {
    const url = $("#st-url").value.trim();
    const note = $("#st-note");
    if (!url) return;
    note.className = "editor-note";
    note.textContent = "Connecting…";
    try {
      const info = await invoke("probe_stream", { url, wantTitle: true });
      const bits = [info.contentType, info.bitrate ? info.bitrate + " kbps" : null, info.name, info.title]
        .filter(Boolean)
        .join("  ·  ");
      if (!$("#st-name").value.trim() && info.name) $("#st-name").value = info.name;

      if (info.warning) {
        note.className = "editor-note bad";
        note.textContent = "⚠ " + info.warning + " — " + (bits || info.url);
        return;
      }

      note.textContent = "Server answered — checking the player can decode it…";
      const playable = await canDecode(info.url);
      note.className = "editor-note " + (playable.ok ? "good" : "bad");
      note.textContent = playable.ok
        ? "✓ live and playable — " + (bits || info.url)
        : "✕ the server answers but nothing plays: " + playable.reason;
    } catch (e) {
      note.className = "editor-note bad";
      note.textContent = "✕ " + e;
    }
  });

  // shuffle folder on the radio tab
  $("#btn-pick-folder").addEventListener("click", async () => {
    const info = await invoke("pick_folder");
    if (!info) return;
    state.settings.shuffleFolder = info.path;
    saveSettings();
    showFolderCounts(info, "#folder-path");
  });
  $("#btn-play-folder").addEventListener("click", () => playRandomFromFolder(shuffleFolder()));

  $("#btn-pick-backup").addEventListener("click", async () => {
    const info = await invoke("pick_folder");
    if (!info) return;
    state.settings.backupFolder = info.path;
    saveSettings();
    showFolderCounts(info, "#backup-path");
  });

  // alarms
  $("#btn-add-alarm").addEventListener("click", () => openAlarmEditor(null));
  $("#al-cancel").addEventListener("click", closeAlarmEditor);

  $$("#al-days button").forEach((btn) =>
    btn.addEventListener("click", () => {
      const day = +btn.dataset.day;
      const i = editorDays.indexOf(day);
      if (i >= 0) editorDays.splice(i, 1);
      else editorDays.push(day);
      btn.classList.toggle("on", i < 0);
      updateDayHint();
    })
  );

  $$(".stepper").forEach((btn) =>
    btn.addEventListener("click", () => {
      const field = $("#al-" + btn.dataset.step);
      const max = btn.dataset.step === "hour" ? 24 : 60;
      const value = (parseInt(field.value, 10) || 0) + +btn.dataset.dir;
      field.value = pad2(((value % max) + max) % max);
      updateDayHint();
    })
  );

  ["al-hour", "al-minute"].forEach((id) => {
    const field = $("#" + id);
    field.addEventListener("input", updateDayHint);
    field.addEventListener("blur", () => {
      const max = id === "al-hour" ? 23 : 59;
      field.value = pad2(Math.min(max, Math.max(0, parseInt(field.value, 10) || 0)));
      updateDayHint();
    });
  });

  $$("#al-kind .chip").forEach((chip) => chip.addEventListener("click", () => setKind(chip.dataset.kind)));

  $("#al-folderbtn").addEventListener("click", async () => {
    const info = await invoke("pick_folder");
    if (!info) return;
    editorFolder = info.path;
    $("#al-folderpath").textContent = info.path;
    const note = $("#al-sourcenote");
    note.className = "editor-note" + (info.count ? "" : " bad");
    note.textContent = info.count
      ? `${info.count} playable file${info.count === 1 ? "" : "s"} — e.g. ${info.sample.slice(0, 2).join(", ")}`
      : "No playable audio in that folder — the backup folder would ring instead.";
  });

  $("#al-volume").addEventListener("input", (e) => {
    e.target.style.setProperty("--fill", e.target.value + "%");
    $("#al-volval").textContent = e.target.value;
  });

  $("#alarm-editor").addEventListener("submit", (e) => {
    e.preventDefault();
    const result = readAlarmEditor();
    if (result.error) {
      const note = $("#al-sourcenote");
      note.className = "editor-note bad";
      note.textContent = result.error;
      return;
    }
    const idx = state.alarms.findIndex((a) => a.id === result.alarm.id);
    if (idx >= 0) state.alarms[idx] = result.alarm;
    else state.alarms.push(result.alarm);
    saveAlarms();
    renderAlarms();
    closeAlarmEditor();
    say("alarm saved", "good");
  });

  $("#al-delete").addEventListener("click", () => {
    if (!editingAlarm) return;
    state.alarms = state.alarms.filter((a) => a.id !== editingAlarm.id);
    saveAlarms();
    renderAlarms();
    closeAlarmEditor();
    say("alarm deleted");
  });

  $("#al-test").addEventListener("click", async () => {
    const result = readAlarmEditor();
    if (result.error) {
      const note = $("#al-sourcenote");
      note.className = "editor-note bad";
      note.textContent = result.error;
      return;
    }
    // Save first so the backend can resolve the source exactly as it will
    // when the alarm really goes off.
    const idx = state.alarms.findIndex((a) => a.id === result.alarm.id);
    if (idx >= 0) state.alarms[idx] = result.alarm;
    else state.alarms.push(result.alarm);
    await saveAlarms();
    renderAlarms();
    if (!editingAlarm) editingAlarm = result.alarm;
    try {
      const payload = await invoke("test_alarm", { alarmId: result.alarm.id });
      onAlarmFire(payload);
    } catch (e) {
      say(String(e), "bad");
    }
  });

  // ringing overlay
  $("#ring-snooze").addEventListener("click", snoozeRing);
  $("#ring-dismiss").addEventListener("click", dismissRing);

  // settings
  $$(".settings .row").forEach((row) => {
    row.querySelector(".sw").addEventListener("click", (e) => {
      const sw = e.currentTarget;
      const next = sw.getAttribute("aria-pressed") !== "true";
      sw.setAttribute("aria-pressed", String(next));
      state.settings[row.dataset.setting] = next;
      if (row.dataset.setting === "clock24h") {
        tickClock();
        renderAlarms();
        refreshNextAlarm();
      }
      saveSettings();
    });
  });

  // keyboard
  document.addEventListener("keydown", (e) => {
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement.tagName);
    if (e.code === "Space" && !typing) {
      e.preventDefault();
      if (ringing) dismissRing();
      else togglePlay();
    }
    if (e.key === "Escape") {
      if (ringing) return; // an alarm should take a deliberate button press
      closeAlarmEditor();
      closeStationEditor();
    }
  });

}

/** Take the version from the bundle rather than hand-editing the titlebar. */
async function showBuildLabel() {
  try {
    const version = await window.__TAURI__.app.getVersion();
    $("#build-label").textContent = "AERO CONSOLE v" + version;
  } catch {
    /* the label reads fine without it */
  }
}

/** Portable copies keep their settings beside the exe; say which this is. */
async function showConfigLocation() {
  try {
    const where = await invoke("config_location");
    const line = $("#config-where");
    line.textContent = "";
    const label = document.createElement("b");
    label.textContent = where.portable ? "Portable copy. " : "Installed copy. ";
    line.append(label, "Settings: " + where.path);

    // A reset that passes for a first run is how every alarm quietly vanishes.
    if (where.loadError) {
      const problem = document.createElement("p");
      problem.className = "wherefrom warn";
      problem.textContent = where.loadError;
      line.after(problem);
      say("settings could not be loaded - see SETUP", "bad", true);
    }
  } catch {
    /* nothing worth saying if the backend will not tell us */
  }
}

// ----------------------------------------------------------------- boot ---

async function boot() {
  wire();
  tickClock();
  setInterval(tickClock, 1000);
  setInterval(refreshNextAlarm, 20000);

  await loadState();

  await listen("alarm-fire", (event) => onAlarmFire(event.payload));
  await listen("alarms-updated", async () => {
    state.alarms = (await invoke("get_state")).alarms;
    renderAlarms();
    refreshNextAlarm();
  });
  await listen("tray-stop", () => {
    if (ringing) dismissRing();
    else stopPlayback();
  });

  // The scheduler was already ticking while this page loaded. An alarm that
  // fired in that gap emitted to nobody and will not retry, and it left the
  // window pinned above everything - so ask, rather than trust the timing.
  try {
    const pending = await invoke("pending_alarm");
    if (pending) onAlarmFire(pending);
  } catch {
    /* nothing ringing, or the backend is not up yet */
  }

  showBuildLabel();
  showConfigLocation();
  say("aerowave online", "good");
}

boot().catch((e) => {
  document.body.innerHTML =
    '<pre style="padding:30px;color:#ff9c86;font:13px monospace">Aerowave failed to start:\n\n' +
    String(e) +
    "</pre>";
});
