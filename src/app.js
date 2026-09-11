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

let clockNow = null;
let clockRequest = 0;

function fmtClock(time, withSeconds) {
  const h24 = state.settings.clock24h !== false;
  let h = time.hour;
  let suffix = "";
  if (!h24) {
    suffix = h < 12 ? " AM" : " PM";
    h = h % 12 || 12;
  }
  const core = `${h24 ? pad2(h) : h}:${pad2(time.minute)}`;
  return (withSeconds ? `${core}:${pad2(time.second)}` : core) + suffix;
}

function fmtAlarmTime(hour, minute) {
  return fmtClock({ hour, minute }, false);
}

function fmtDuration(secs) {
  secs = Math.max(0, Math.round(secs));
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  if (h > 0) return `${h}h ${pad2(m)}m`;
  if (m > 0) return `${m}m ${pad2(secs % 60)}s`;
  return `${secs}s`;
}

async function tickClock() {
  const request = ++clockRequest;
  try {
    // The webview may carry older timezone rules than the OS scheduler.
    // Rust supplies wall time; the browser only supplies date language.
    const now = await invoke("local_time");
    if (request !== clockRequest) return;
    clockNow = now;
    $("#tb-clock").textContent = fmtClock(now, true);
    $("#tb-date").textContent = new Date(Date.UTC(now.year, now.month - 1, now.day))
      .toLocaleDateString(undefined, { timeZone: "UTC", weekday: "short", day: "2-digit", month: "short" })
      .toUpperCase();
  } catch {
    if (request !== clockRequest) return;
    clockNow = null;
    $("#tb-clock").textContent = "--:--:--";
    $("#tb-date").textContent = "Clock unavailable";
  }
}

// -------------------------------------------------------------- signals ---

function setStatus(text, lamp) {
  $("#statusline").textContent = text;
  $("#lamp").className = "lamp" + (lamp ? " " + lamp : "");
}

let statusMsgTimer = null;
function say(msg, mood, sticky) {
  const el = $("#status-msg");
  el.textContent = msg;
  el.style.color = mood === "bad" ? "var(--alert)" : mood === "good" ? "var(--alien)" : "";
  // Announce it too. This one is never cleared, so the reset to Ready is not
  // read out over whatever the user is doing.
  const log = $("#a11y-log");
  if (log) log.textContent = msg;
  clearTimeout(statusMsgTimer);
  // Most of these are passing notices. A few - settings that would not load -
  // must not scroll past while the user is looking at another tab.
  if (sticky) return;
  statusMsgTimer = setTimeout(() => {
    el.textContent = "Ready";
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
  resolved: null,     // the station's own stream URL, after any playlist hop
  probed: false,      // ...and whether a probe, rather than the station, chose it
  relayed: false,     // is <audio> playing through the local relay?
  streamTitle: false, // has the stream itself named the track? then stop asking
  triedDirect: false, // ...and have we already fallen back off it?
  hls: false,         // is hls.js driving the element instead?
  paused: false,      // held, with the source still on the element, so it can resume
  pendingPosition: null, // local position until its media URL has arrived
  get playing() {
    return !!this.source;
  },
};

function markPlaying(on) {
  document.body.classList.toggle("playing", on);
  if ("mediaSession" in navigator) navigator.mediaSession.playbackState = on ? "playing" : "none";
}

function clearTimers() {
  clearInterval(player.fadeTimer);
  clearInterval(player.metaTimer);
  clearTimeout(player.retryTimer);
  player.fadeTimer = player.metaTimer = player.retryTimer = null;
}

function stopPlayback(quiet) {
  playGeneration += 1;
  // pause/load/destroy can queue events until after the next source is set.
  // Keep those teardown events guarded through the next connection attempt.
  switchingSource = true;
  // Load-bearing: without this an alarm firing while the radio is already
  // playing inherits a fresh timestamp and its watchdog never trips.
  player.lastProgress = 0;
  clearTimers();
  // Drop the source first: tearing down the element fires events that would
  // otherwise look like a stream failure and start a reconnect.
  player.source = null;
  audio.pause();
  audio.loop = false;
  audio.removeAttribute("src");
  audio.load();
  player.retries = 0;
  player.paused = false;
  player.pendingPosition = null;
  player.resolved = null;
  player.probed = false;
  player.relayed = false;
  player.triedDirect = false;
  stopHls();
  markPlaying(false);
  if (!quiet) {
    setOrbArt(null);
    setStatus("Stopped", "");
    $("#np-station").textContent = "Ready to listen";
    $("#np-track").textContent = "Browse stations or play music from a folder.";
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
 * Ramp the element volume down to silence over `seconds`.
 *
 * The curve is the fade-in's mirrored: away quickly while it is still loud,
 * where a change in level is least noticeable, then a long quiet tail. The
 * caller decides what happens at the end - this only moves the knob, so a
 * fade that gets cancelled halfway cannot take the ending with it.
 */
function fadeOut(seconds) {
  clearInterval(player.fadeTimer);
  const from = audio.volume;
  const started = Date.now();
  player.fadeTimer = setInterval(() => {
    const t = Math.min(1, (Date.now() - started) / (seconds * 1000));
    const away = 1 - t;
    audio.volume = Math.min(1, Math.max(0, from * away * away));
    if (t < 1) return;
    clearInterval(player.fadeTimer);
    player.fadeTimer = null;
  }, 120);
}

/**
 * Will the player actually decode this? The Rust probe only proves the
 * server answers - plenty of stations serve a perfectly good HTTP response
 * that the media element then refuses. This is the check that counts.
 */
function canDecode(url, timeoutMs = 9000, { hls = false, signal } = {}) {
  return new Promise((resolve) => {
    const probe = new Audio();
    probe.preload = "auto";
    probe.muted = true;
    let settled = false;
    let decoder = null;
    let base = null;
    let timer = null;
    let relayed = false;
    const done = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
      if (decoder) {
        try { decoder.destroy(); } catch { /* an errored decoder may already be detached */ }
      }
      if (base) closeHlsSession(base);
      probe.pause();
      probe.removeAttribute("src");
      probe.load();
      resolve(result);
    };
    const abort = () => done({ ok: false, cancelled: true });
    if (signal?.aborted) { abort(); return; }
    signal?.addEventListener("abort", abort, { once: true });
    probe.addEventListener("canplay", () => done({ ok: true }));
    probe.addEventListener("loadeddata", () => {
      if (probe.readyState >= 3) done({ ok: true });
    });
    probe.addEventListener("error", () => {
      const code = probe.error ? probe.error.code : 0;
      if (!settled && code === 4 && relayed) {
        relayed = false;
        probe.src = url;
        probe.load();
        startProbe();
        return;
      }
      done({
        ok: false,
        reason: code === 4 ? "the player could not load or decode this stream" : "media error " + code,
      });
    });
    const startProbe = () => {
      if (settled) return;
      const attemptUrl = probe.src;
      if (probe.readyState >= 3) done({ ok: true });
      else probe.play().catch((e) => {
        if (probe.src === attemptUrl && !isAbort(e)) done({ ok: false, reason: String(e.message || e) });
      });
    };
    timer = setTimeout(() => done({ ok: false, reason: "no audio within " + Math.round(timeoutMs / 1000) + "s" }), timeoutMs);
    const connect = async () => {
      if (hls) {
        if (typeof Hls === "undefined" || !Hls.isSupported()) {
          done({ ok: false, reason: "this build cannot play HLS" });
          return;
        }
        const opened = await invoke("hls_session", { url });
        if (settled) { if (opened) closeHlsSession(opened); return; }
        base = opened;
        if (!base) { done({ ok: false, reason: "could not open an HLS session" }); return; }
        decoder = new Hls({ enableWorker: false, loader: relayLoader(Hls.DefaultConfig.loader, base) });
        decoder.on(Hls.Events.ERROR, (_event, data) => {
          if (data?.fatal) done({ ok: false, reason: "HLS " + (data.details || data.type || "error") });
        });
        decoder.loadSource(url);
        decoder.attachMedia(probe);
      } else {
        const playUrl = await playable({ kind: "station" }, url);
        if (settled) return;
        relayed = playUrl !== url;
        probe.src = playUrl;
        probe.load();
      }
      startProbe();
    };
    connect().catch((e) => done({ ok: false, reason: String(e.message || e) }));
  });
}

let stationTest = null;
function cancelStationTest() {
  stationTest?.abort();
  stationTest = null;
}

function showNowPlaying(title, sub, meta) {
  $("#np-station").textContent = title || "";
  $("#np-track").textContent = sub || "";
  if (meta !== undefined) $("#np-meta").textContent = meta || "";
  // The same two lines the system's media overlay puts on screen when a media
  // key is pressed. Without them it names the app and nothing else, which
  // looks like the key missed. The track line holds an ellipsis until the
  // stream names something, and an overlay reading "..." looks worse than one
  // reading the station, so a placeholder counts as no track at all.
  if ("mediaSession" in navigator && window.MediaMetadata) {
    const track = /^[\s.…]*$/.test(sub || "") ? "" : sub;
    navigator.mediaSession.metadata = title
      ? new MediaMetadata({ title: track || title, artist: track ? title : "" })
      : null;
  }
}

/*
 * HLS.
 *
 * WebView2 will not play an .m3u8 by itself - it reads the playlist, reports
 * metadata, and then stalls at readyState 1 for ever. hls.js does the work
 * instead: it fetches the playlists and segments over XHR, demuxes MPEG-TS or
 * ADTS, and feeds the result to the element through Media Source Extensions.
 *
 * Every one of those fetches goes through Rust, the same as ordinary audio,
 * and for the same reason - the request headers are ours to choose. But not
 * through the loopback relay: XHR is not a media load, so it needs CORS and an
 * origin the page is allowed to reach, and reaching a loopback port would have
 * meant opening the policy to `http://127.0.0.1:*` - every service on this
 * machine that happens to be bound to loopback. A Tauri custom protocol is one
 * static origin instead, and no other process on the machine can knock on it.
 * See src-tauri/src/hls.rs.
 */

let hlsPlayer = null;
let hlsBase = null;
/** Titles read out of the segments, waiting for playback to reach them. */
let hlsTitles = [];
/** What the demuxer says it is actually decoding, for the third line. */
let hlsCodec = null;

function b64url(text) {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * The loader hls.js uses for every fetch it makes.
 *
 * Two things it must get right. It copies the context rather than editing it,
 * because hls.js indexes in-flight loads by `context.url` and would lose track
 * of its own requests. And it puts the broadcaster's URL back into
 * `response.url` before handing the reply on, because hls.js resolves every
 * relative URI in a playlist against that - point it at us and the next
 * request would be for a segment relative to our own origin.
 */
function relayLoader(Base, base) {
  return class extends Base {
    load(context, config, callbacks) {
      const upstream = context.url;
      let url = base + "?u=" + b64url(upstream);
      // hls.js uses an exclusive end; HTTP byte ranges use an inclusive one.
      // Carry it in the URL because the custom protocol reads this parameter.
      if (Number.isSafeInteger(context.rangeStart) && context.rangeStart >= 0 &&
          Number.isSafeInteger(context.rangeEnd) && context.rangeEnd > context.rangeStart) {
        url += "&r=" + context.rangeStart + "-" + (context.rangeEnd - 1);
      }
      const proxied = Object.assign({}, context, { url });
      const wrapped = Object.assign({}, callbacks, {
        onSuccess: (response, stats, _ctx, networkDetails) => {
          let final = null;
          try {
            final = networkDetails && networkDetails.getResponseHeader
              ? networkDetails.getResponseHeader("X-Aerowave-Final")
              : null;
          } catch { /* not an XHR we can read headers off */ }
          response.url = final || upstream;
          callbacks.onSuccess(response, stats, context, networkDetails);
        },
        onError: (error, _ctx, networkDetails, stats) =>
          callbacks.onError(error, context, networkDetails, stats),
        onTimeout: (stats, _ctx, networkDetails) =>
          callbacks.onTimeout(stats, context, networkDetails),
      });
      if (callbacks.onProgress) {
        wrapped.onProgress = (stats, _ctx, data, networkDetails) =>
          callbacks.onProgress(stats, context, data, networkDetails);
      }
      super.load(proxied, config, wrapped);
    }
  };
}

/**
 * ID3v2 out of a segment. hls.js parses these internally but does not export
 * the parser, and its own has two faults worth avoiding: it decodes every text
 * frame as UTF-8 whatever the encoding byte says, and it reads ID3v2.3 frame
 * sizes as synchsafe when they are plain big-endian - one frame of 128 bytes
 * or more and the rest of the tag is misread.
 */
function id3Text(body) {
  if (!body.length) return "";
  const encoding = body[0];
  const label =
    encoding === 0 ? "iso-8859-1" : encoding === 1 ? "utf-16" : encoding === 2 ? "utf-16be" : "utf-8";
  const data = body.subarray(1);
  let text;
  try {
    text = new TextDecoder(label).decode(data);
  } catch {
    text = new TextDecoder().decode(data);
  }
  return text.replace(/\0+$/, "").trim();
}

function readId3(bytes) {
  const found = {};
  let at = 0;
  // One sample can hold several tags end to end - the RFC 8216 timestamp tag
  // and then the broadcaster's own.
  while (at + 10 <= bytes.length && bytes[at] === 0x49 && bytes[at + 1] === 0x44 && bytes[at + 2] === 0x33) {
    const major = bytes[at + 3];
    const size =
      (bytes[at + 6] << 21) | (bytes[at + 7] << 14) | (bytes[at + 8] << 7) | bytes[at + 9];
    const end = Math.min(at + 10 + size, bytes.length);
    let p = at + 10;
    if (bytes[at + 5] & 0x40) {
      // Extended header: skip whatever it says it is.
      const ext =
        major >= 4
          ? (bytes[p] << 21) | (bytes[p + 1] << 14) | (bytes[p + 2] << 7) | bytes[p + 3]
          : ((bytes[p] << 24) | (bytes[p + 1] << 16) | (bytes[p + 2] << 8) | bytes[p + 3]) >>> 0;
      p += major >= 4 ? ext : ext + 4;
    }
    while (p + 10 <= end && bytes[p] !== 0) {
      const id = String.fromCharCode(bytes[p], bytes[p + 1], bytes[p + 2], bytes[p + 3]);
      const frameSize =
        major >= 4
          ? (bytes[p + 4] << 21) | (bytes[p + 5] << 14) | (bytes[p + 6] << 7) | bytes[p + 7]
          : ((bytes[p + 4] << 24) | (bytes[p + 5] << 16) | (bytes[p + 6] << 8) | bytes[p + 7]) >>> 0;
      if (frameSize <= 0 || p + 10 + frameSize > end) break;
      if (id === "TIT2" || id === "TPE1") {
        found[id] = id3Text(bytes.subarray(p + 10, p + 10 + frameSize));
      }
      p += 10 + frameSize;
    }
    at = end;
  }
  return found;
}

/** Tear down an Hls instance and let Rust forget the session. */
function stopHls() {
  hlsTitles = [];
  hlsCodec = null;
  if (hlsPlayer) {
    switchingSource = true;
    const old = hlsPlayer;
    hlsPlayer = null;
    try {
      old.destroy();
    } catch { /* already gone */ }
  }
  if (hlsBase) {
    closeHlsSession(hlsBase);
    hlsBase = null;
  }
  player.hls = false;
}

function closeHlsSession(base) {
  const session = base.split("/").pop();
  invoke("hls_close", { session }).catch(() => {});
}

/**
 * Point hls.js at a playlist. Returns false when it could not start, having
 * already said why.
 */
async function startHls(source, url, generation) {
  switchingSource = true;
  clearInterval(player.metaTimer);
  player.metaTimer = null;
  if (typeof Hls === "undefined" || !Hls.isSupported()) {
    failure("this build cannot play HLS", { fatal: true });
    return false;
  }
  let base = null;
  try {
    base = await invoke("hls_session", { url });
  } catch { /* falls through to the null check */ }
  if (superseded(generation) || player.source !== source) {
    if (base) closeHlsSession(base);
    return false;
  }
  if (!base) {
    failure("could not open an HLS session", { fatal: true });
    return false;
  }
  hlsBase = base;

  const hls = new Hls({
    // The default worker is spawned from a blob: URL, which this app's policy
    // refuses - and it fails silently rather than throwing, which would look
    // like a station that simply never starts. Demuxing one audio stream on
    // the main thread costs nothing worth having.
    enableWorker: false,
    loader: relayLoader(Hls.DefaultConfig.loader, base),
  });
  hlsPlayer = hls;
  player.hls = true;

  hls.on(Hls.Events.ERROR, (_event, data) => {
    if (hlsPlayer !== hls || superseded(generation) || player.source !== source) return;
    // hls.js spends its own retry budget - two for a playlist, six for a
    // fragment - before it calls anything fatal, so by here it has already
    // tried. Non-fatal errors are its business, not ours.
    if (!data || !data.fatal) return;
    failure("HLS " + (data.details || data.type || "error"));
  });

  hls.on(Hls.Events.FRAG_PARSING_METADATA, (_event, data) => {
    if (hlsPlayer !== hls || !data || !data.samples) return;
    for (const sample of data.samples) {
      if (!sample || !sample.data) continue;
      const tags = readId3(sample.data);
      const title = [tags.TPE1, tags.TIT2].filter(Boolean).join(" — ");
      // These arrive when the segment is parsed, which is up to half a minute
      // before it is audible, so they queue against the playback clock rather
      // than going straight on screen.
      if (title) hlsTitles.push({ at: sample.pts, title });
    }
    hlsTitles.sort((a, b) => a.at - b.at);
  });

  // startMetadata() is skipped for HLS - the poll asks probe_stream, and an
  // .m3u8 resolves with no name, bitrate or genre to report. hls.js has been
  // told all of it by the playlist, so the third line comes from there instead.
  const showLevel = () => {
    if (hlsPlayer !== hls) return;
    const levels = hls.levels || [];
    const level = levels[hls.currentLevel >= 0 ? hls.currentLevel : 0];
    const bits = [];
    // A master playlist declares BANDWIDTH; a bare media playlist declares
    // nothing at all, which is most radio, so neither of these is a given.
    if (level && level.bitrate) bits.push(Math.round(level.bitrate / 1000) + " kbps");
    const codec = (level && level.audioCodec) || hlsCodec;
    if (codec) bits.push(codec);
    bits.push("HLS");
    $("#np-meta").textContent = bits.join("  ·  ");
  };
  hls.on(Hls.Events.MANIFEST_PARSED, showLevel);
  hls.on(Hls.Events.LEVEL_SWITCHED, showLevel);
  // The playlist may say nothing about the codec; the demuxer always knows.
  hls.on(Hls.Events.BUFFER_CODECS, (_event, data) => {
    if (hlsPlayer !== hls) return;
    if (data && data.audio && data.audio.codec) hlsCodec = data.audio.codec;
    showLevel();
  });

  hls.loadSource(url);
  hls.attachMedia(audio);
  return true;
}

/**
 * What to hand <audio>.
 *
 * Ordinary stations go through the loopback relay. A media element cannot
 * choose its request headers or strip ICY blocks. Rust presents Aerowave's
 * measured User-Agent and resolves playlists, redirects and Shoutcast v1 on
 * the way past. See src-tauri/src/relay.rs.
 *
 * WebKitGTK cannot reliably play files from the asset protocol, so Linux
 * serves files already granted by the picker through the same listener.
 * Other platforms keep the asset URL. Stations can still connect directly
 * when the relay is unavailable.
 */
async function playable(source, url) {
  if (source.kind === "folder" && source.path) {
    return await invoke("local_file_url", { path: source.path }) || url;
  }
  if (source.kind !== "station") return url;
  try {
    const relayed = await invoke("relay_url", { url });
    return relayed || url;
  } catch {
    return url;
  }
}

/**
 * Start a source. `opts.fadeSecs` ramps the volume in, `opts.volume`
 * overrides the master volume (alarms have their own). `opts.startTime`
 * resumes a local file at its interrupted position. `source.meta` is what
 * to show on the third line until the stream itself says otherwise - some
 * servers never will.
 */
async function play(source, opts = {}) {
  stopPlayback(true);
  const generation = playGeneration;
  player.source = source;
  player.lastProgress = Date.now();
  player.retries = 0;
  player.streamTitle = false;
  const volume = opts.volume !== undefined ? opts.volume : state.settings.volume ?? 0.8;
  // A second alarm can interrupt this connection before the media URL arrives.
  // Keep the intended volume and position available for its snapshot too.
  player.target = volume;
  player.pendingPosition = source.kind === "folder"
    ? (Number.isFinite(opts.startTime) && opts.startTime > 0 ? opts.startTime : 0)
    : null;

  showNowPlaying(source.title, source.subtitle || "", source.meta || "");
  setOrbArt(source);
  markPlaying(true);

  setStatus("Connecting", "busy");
  // A resumed station keeps the manifest reached through its playlist wrapper.
  // The original URL still identifies the station in BROWSE and the saved list.
  let url = source.hls && source.hlsUrl ? source.hlsUrl : source.url;

  let useHls = source.kind === "station" && !!source.hls;
  let initialInfo = null;
  if (source.kind === "station" && (!useHls || /\.(pls|m3u|asx)(\?|$)/i.test(url))) {
    // A /listen endpoint can serve or redirect to HLS just as a .m3u8 can.
    // Reuse this request's station headers for the first metadata update.
    try {
      const info = await invoke("probe_stream", { url, wantTitle: false });
      if (superseded(generation)) return;
      initialInfo = info;
      url = info.url;
      player.resolved = info.url;
      player.probed = true;
      useHls = !!info.hls;
      source.hls = useHls;
      source.hlsUrl = useHls ? info.url : null;
      if (info.warning) say(info.warning, "bad");
    } catch (e) {
      if (superseded(generation)) return;
      // A failed informational request need not prevent a relay from playing.
      // Explicit playlists still need resolution before any media load.
      if (/\.(pls|m3u|m3u8|asx)(\?|$)/i.test(url)) {
        failure(String(e));
        return;
      }
    }
  }

  // Remember the station's own URL. The metadata poll talks to the
  // broadcaster directly - it wants a title, not audio - so it must not be
  // pointed at the relay, which would only hand it back its own stream. It
  // is also what an `icy-title` event is matched against.
  if (source.kind === "station") player.resolved = url;
  if (useHls) {
    // hls.js sets the element's source itself, to a MediaSource blob.
    if (!(await startHls(source, url, generation))) return;
    if (superseded(generation)) return;
  } else {
    let playUrl;
    try {
      playUrl = await playable(source, url);
    } catch (e) {
      if (!superseded(generation) && !givingUp) failure(String(e));
      return;
    }
    if (superseded(generation) || givingUp) return;
    player.relayed = playUrl !== url;
    setAudioSource(playUrl);
  }
  // A ringing alarm plays its one file over and over rather than moving on to
  // another; anything else is heard once and then the `ended` handler decides.
  audio.loop = !!source.loop;
  if (player.pendingPosition > 0) {
    // Before metadata arrives this sets the element's default start position,
    // so an interrupted song does not play its opening again while seeking.
    audio.currentTime = player.pendingPosition;
  }
  player.pendingPosition = null;
  audio.volume = opts.fadeSecs > 0 ? 0.02 : volume;
  if (player.paused || givingUp) return;
  try {
    await audio.play();
  } catch (e) {
    // Autoplay refusals and decode errors land here - but so do aborts from
    // this code starting something else, which must not count as a failure.
    if (superseded(generation) || givingUp || isAbort(e)) return;
    failure(String(e && e.message ? e.message : e));
    return;
  }
  if (superseded(generation) || player.paused || givingUp) return;
  if (opts.fadeSecs > 0) fadeTo(volume, opts.fadeSecs);

  if (source.kind === "station" && !player.hls) startMetadata(source, initialInfo);
}

/*
 * The orb wears the artwork of whatever station is playing.
 *
 * A station kept from BROWSE arrives with its own. The ones this app ships
 * with, and anything typed in by hand, have none - so the directory is asked
 * once what the station looks like and the answer is written back onto it.
 *
 * Either way the picture itself is fetched in Rust and handed over as a data
 * URL: the content security policy allows the webview no remote images, and
 * even with one a broadcaster's logo host almost never sends the CORS headers
 * WebGL demands before it will upload a cross-origin image as a texture.
 */

/** Pictures already fetched, by the address they came from, oldest first. */
const orbArt = new Map();
/** How many to hold on to: a few hundred kilobytes each, so not many. */
const ORB_ART_CACHE = 8;
/** Stations already asked about, so one the directory has never heard of is
 *  not looked up again every time it is played. */
const orbAsked = new Set();
/** What the orb is showing, and which request owns it. */
let orbArtKey = "";
let orbArtRequest = 0;

async function setOrbArt(source) {
  const orb = window.aerowaveOrb;
  // No WebGL: the CSS orb underneath cannot wear anything.
  if (!orb || !orb.ok) return;
  // Only stations have artwork; a track off a folder puts the crystal back.
  const station = source && source.kind === "station" ? source : null;
  const key = station ? station.logo || station.url : "";
  if (key === orbArtKey) return;
  orbArtKey = key;
  const mine = ++orbArtRequest;

  if (!key) {
    orb.setImage(null);
    return;
  }
  if (orbArt.has(key)) {
    orb.setImage(orbArt.get(key));
    return;
  }
  // Back to the crystal while this one is on its way, rather than leaving the
  // last station's picture up over the new station's name.
  orb.setImage(null);

  let picture = null;
  let from = station.logo || "";
  try {
    if (from) {
      picture = await invoke("station_logo", { url: from });
    } else if (!orbAsked.has(key)) {
      orbAsked.add(key);
      const found = await invoke("station_art", { name: station.title, url: station.url });
      if (found) {
        from = found.url;
        picture = found.picture;
        // Written back so this costs one lookup ever, not one a play.
        const saved = station.stationId ? stationById(station.stationId) : null;
        if (saved) {
          saved.logo = from;
          saveStations();
        }
      }
    }
  } catch {
    /* a station with no usable picture simply keeps the crystal */
  }

  if (picture) {
    orbArt.set(key, picture);
    // Under the address it was found at as well: the station now carries that
    // one, so the next play looks itself up by it.
    if (from && from !== key) orbArt.set(from, picture);
    while (orbArt.size > ORB_ART_CACHE) orbArt.delete(orbArt.keys().next().value);
  }
  // A station switched away from while this was in flight owns nothing now.
  if (mine !== orbArtRequest) return;
  if (picture) orb.setImage(picture);
}

function startMetadata(source, initialInfo = null) {
  clearInterval(player.metaTimer);
  if (state.settings.showMetadata === false) return;
  // Plenty of stations send no ICY titles at all. Polling one of those opens a
  // connection a minute, for ever, to learn nothing - so give up after three.
  let titleless = 0;
  const generation = playGeneration;
  const poll = async (initial = null) => {
    if (player.source !== source) return;
    try {
      const info = initial || await invoke("probe_stream", {
        url: player.resolved || source.url,
        wantTitle: true,
        skipResolve: player.probed,
      });
      if (superseded(generation) || player.source !== source) return;
      if (info.title) {
        titleless = 0;
        // Unless the stream has already said, on the connection that is
        // actually playing. This poll was in flight before that arrived.
        if (!player.streamTitle) $("#np-track").textContent = info.title;
      } else if (!initial && ++titleless >= 3) {
        clearInterval(player.metaTimer);
        player.metaTimer = null;
        return;
      }
      const bits = [];
      if (info.bitrate) bits.push(info.bitrate + " kbps");
      if (info.genre) bits.push(info.genre);
      if (info.name && info.name !== source.title) bits.push(info.name);
      // Only when the server actually said something. A probe that comes back
      // empty must not rub out what the caller already knew.
      if (bits.length) $("#np-meta").textContent = bits.join("  ·  ");
    } catch {
      /* metadata is a nicety; a failure here must not disturb playback */
    }
  };
  poll(initialInfo);
  // The first poll is the one that matters: it fills in bitrate, genre and
  // the station's own name, none of which change. Titles come from the relay
  // now - off the connection that is playing, so they arrive with the song -
  // and the first one cancels this. The interval is what is left for a
  // station the relay is not carrying, and it is a whole connection to the
  // broadcaster each time, so once a minute.
  player.metaTimer = setInterval(poll, 60000);
}

/**
 * A title the relay read out of the stream that is playing.
 *
 * This comes off the same connection as the audio, so it arrives when the
 * song changes rather than whenever the poll next came round - which was up
 * to a minute later, and showed the previous track until it did.
 */
function onStreamTitle(payload) {
  if (!payload || !payload.title) return;
  if (state.settings.showMetadata === false) return;
  const source = player.source;
  if (!source || source.kind !== "station") return;
  // A relay connection outlives by a moment the station that opened it, so a
  // title from the one we are no longer listening to is not ours.
  const mine = String(player.resolved || source.url || "").trim();
  const from = String(payload.url || "").trim();
  if (mine && from && mine !== from) return;

  player.streamTitle = true;
  $("#np-track").textContent = payload.title;
  // The stream is saying this for itself now. The poll opened a whole
  // connection a minute to ask the same question, and has nothing left to
  // add: the third line it fills is bitrate and genre, which do not change.
  if (player.metaTimer) {
    clearInterval(player.metaTimer);
    player.metaTimer = null;
  }
}

/**
 * Something went wrong mid-stream. Reconnect, or give up loudly.
 * `opts.fatal` means the source is unusable and retrying cannot help.
 */
function failure(detail, opts = {}) {
  const source = player.source;
  if (!source) return;

  // Already receding: what failed was on its way to silence anyway, and a
  // fallback would only come in at full volume over the fade.
  if (givingUp) {
    endGiveUp();
    return;
  }

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
      setStatus("Could not play track", "error");
      say("could not play that file: " + detail, "bad");
      stopPlayback();
      return;
    }
    // A file from the shuffle folder would not play; move on to another one.
    setStatus("Skipping", "busy");
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
  setStatus("Reconnecting " + player.retries + "/4", "busy");
  clearTimeout(player.retryTimer);
  player.retryTimer = setTimeout(async () => {
    // Snapshot before the await, not after: taken afterwards this could only
    // ever equal itself, so it guarded nothing.
    const generation = playGeneration;
    if (player.source !== source) return;
    // Second attempt onwards, try the playlist-resolved URL.
    if (player.retries >= 2 && !player.probed) {
      try {
        const info = await invoke("probe_stream", { url: source.url, wantTitle: false });
        // A stop during the probe must not write a resolved URL back into a
        // session that has already been torn down.
        if (superseded(generation) || player.source !== source) return;
        player.resolved = info.url;
        player.probed = true;
        source.hls = !!info.hls;
        source.hlsUrl = info.hls ? info.url : null;
      } catch { /* stay with the original */ }
    }
    if (superseded(generation) || player.source !== source) return;
    const upstream = player.resolved || source.url;
    if (player.hls || source.hls) {
      // Start the HLS player over rather than assigning a source: the element
      // plays a MediaSource, and pointing it at the .m3u8 would give it a
      // playlist it cannot read.
      stopHls();
      if (!(await startHls(source, upstream, generation))) return;
    } else {
      const playUrl = player.triedDirect ? upstream : await playable(source, upstream);
      if (superseded(generation) || player.source !== source) return;
      player.relayed = playUrl !== upstream;
      setAudioSource(playUrl);
    }
    audio.play().catch((e) => {
      if (superseded(generation) || isAbort(e)) return;
      failure(String(e && e.message ? e.message : e));
    });
  }, wait);
}

/**
 * The relay could not serve this one. Try the station the old way, once.
 *
 * Relaying fixes more stations than it breaks, but it is one more thing
 * between the player and the broadcaster: if the loopback listener has gone,
 * or an ordinary stream works only with the media element's request, that
 * direct connection is worth trying. HLS must still go through hls.js.
 */
async function fallBackToDirect(source, generation) {
  player.triedDirect = true;
  player.relayed = false;
  if (!player.probed) {
    try {
      const info = await invoke("probe_stream", { url: source.url, wantTitle: false });
      if (superseded(generation) || player.source !== source) return;
      player.resolved = info.url;
      player.probed = true;
      source.hls = !!info.hls;
      source.hlsUrl = info.hls ? info.url : null;
    } catch { /* the direct request may still work */ }
  }
  if (superseded(generation) || player.source !== source) return;
  const upstream = player.resolved || source.url;
  if (source.hls) {
    if (!(await startHls(source, upstream, generation))) return;
  } else {
    setStatus("Trying another connection", "busy");
    setAudioSource(upstream);
  }
  audio.play().catch((e) => {
    if (superseded(generation) || isAbort(e)) return;
    failure(String(e && e.message ? e.message : e));
  });
}

/**
 * Set while the app is pointing the element at a new URL. Assigning `src` to
 * an element that was not paused makes it fire `pause` on the way past - the
 * reconnect path does exactly that - and that pause is the app's own doing,
 * not the audio being taken away. Cleared once sound is actually coming out
 * again, so a genuine outside pause after that is still caught.
 */
let switchingSource = false;
function setAudioSource(url) {
  switchingSource = true;
  audio.src = url;
}

audio.addEventListener("playing", () => {
  switchingSource = false;
  if (!player.source) return;
  player.retries = 0;
  setStatus(player.source.kind === "folder" ? "Playing your music" : "Live radio", "on");
});
// The only event that means audio is genuinely coming out, rather than that
// something was asked to start.
let lastMediaTime = 0;
audio.addEventListener("timeupdate", () => {
  if (!player.source) return;
  const now = audio.currentTime;
  // Under HLS the element is fed by hls.js, which writes currentTime itself to
  // step over gaps - and every write fires this. Only forward motion counts as
  // audio having arrived, or the alarm watchdog would be reassured by a stream
  // that had stopped.
  if (!player.hls || now > lastMediaTime) player.lastProgress = Date.now();
  lastMediaTime = now;

  if (!hlsTitles.length) return;
  let due = null;
  while (hlsTitles.length && hlsTitles[0].at <= now + 0.25) due = hlsTitles.shift().title;
  if (due) $("#np-track").textContent = due;
});
// Something outside the app paused the element - a media key this build did
// not claim, or the system taking the audio away. `stopPlayback` drops the
// source before it pauses, so it never lands here itself, and anything that
// does means the sound has gone: stop properly rather than leave the face
// saying ON AIR with the orb still spinning over silence.
audio.addEventListener("pause", () => {
  if (switchingSource || player.paused || !player.source) return;
  // A track reaching its end is not the sound going away. Ending pauses the
  // element on the way past - the spec sets `paused`, fires `pause`, and only
  // then fires `ended` - so tearing playback down here would drop the source
  // before the `ended` handler ran, and a shuffle folder would play exactly
  // one file. That the element already reports `ended` is what tells the two
  // apart; a genuine interruption arrives mid-track, with `ended` false.
  if (audio.ended) return;
  stopPlayback();
});
audio.addEventListener("waiting", () => player.source && setStatus("Buffering", "busy"));
audio.addEventListener("stalled", () => player.source && setStatus("Waiting for audio", "busy"));
audio.addEventListener("error", () => {
  const err = audio.error;
  console.error(
    "aerowave media error:",
    "code=" + (err ? err.code : "?"),
    "net=" + audio.networkState,
    "src=" + (audio.currentSrc || "(empty)")
  );
  // MEDIA_ERR_SRC_NOT_SUPPORTED. Not only "bad codec": the element reports
  // it for an error page and for a refused connection too, which is why a
  // relayed station that lands here is worth one attempt without the relay
  // before it is written off. Reconnecting on the same URL is not - that part
  // the engine really will refuse however many times we ask.
  if (err && err.code === 4) {
    const source = player.source;
    // Not while hls.js is driving: the element's source is a MediaSource blob,
    // and fallBackToDirect would hand it the .m3u8 that WebView2 cannot read
    // in the first place.
    if (source && source.kind === "station" && player.relayed && !player.triedDirect && !player.hls) {
      fallBackToDirect(source, playGeneration);
      return;
    }
    failure("the player could not load or decode this stream", { fatal: true });
    return;
  }
  failure(err ? "media error " + err.code : "unknown media error");
});
audio.addEventListener("ended", () => {
  if (!player.source) return;
  // Mid fade-out: the next track would only bring the alarm back up again.
  if (givingUp) {
    endGiveUp();
    return;
  }
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
      logo: station.logo,
      stationId: station.id,
      hls: !!station.hls,
    },
    opts
  );
  state.settings.lastStation = station.id;
  saveSettings();
  renderStations();
}

/**
 * Folder tracks already played, newest last, so prev can walk back through a
 * shuffle instead of rolling the dice again.
 */
let folderHistory = [];
let folderPickRequest = 0;

async function playRandomFromFolder(folder, opts = {}) {
  if (!folder) {
    say("choose a folder first", "bad");
    return;
  }
  const request = ++folderPickRequest;
  const generation = playGeneration;
  const stale = () => request !== folderPickRequest || superseded(generation) || givingUp;
  try {
    const pick = await invoke("random_track", { path: folder });
    if (stale()) return;
    // Remember what is being left so prev has somewhere to go back to. A
    // different folder is a different shuffle: what came before it is not
    // part of this one.
    const leaving = player.source;
    if (leaving && leaving.kind === "folder" && leaving.folder === folder) {
      folderHistory.push(leaving);
      // A shuffle can run all night; only the recent past is worth keeping.
      if (folderHistory.length > 50) folderHistory.shift();
    } else {
      folderHistory = [];
    }
    play(
      {
        kind: "folder",
        url: convertFileSrc(pick.path),
        path: pick.path,
        title: pick.name.replace(/\.[^.]+$/, ""),
        subtitle: `${pick.total} track${pick.total === 1 ? "" : "s"} in the folder`,
        folder,
      },
      opts
    );
  } catch (e) {
    if (stale()) return;
    if (!opts.silentErrors) say(String(e), "bad");
    stopPlayback();
  }
}

/**
 * Pause, as opposed to stop.
 *
 * The difference is not cosmetic: stopping takes the source off the element,
 * which ends the media session Chromium keeps, and a session that has ended
 * cannot be reached by the play key. Pausing leaves the source where it is,
 * so the session stays alive in a paused state and the next press of the key
 * comes back here. A pause key that could only ever stop is just a second
 * stop key.
 */
function pausePlayback() {
  if (!player.source || player.paused) return;
  // Not a ringing alarm. Its watchdog reads the same progress timestamp a
  // pause freezes, so a paused ring would be read as a stream that had died
  // and answered with the backup folder - and an alarm you can silence with a
  // stray keypress is not an alarm. Dismissing one stays a deliberate press.
  if (ringing) return;
  // Set before the element is touched: the `pause` listener below reads it to
  // tell this apart from the audio being taken away by something else.
  player.paused = true;
  audio.pause();
  markPlaying(false);
  if ("mediaSession" in navigator) navigator.mediaSession.playbackState = "paused";
  setStatus("Paused", "");
}

/**
 * A file carries on from where it stopped. A station does not: the seconds
 * spent paused are broadcast that has gone, and the buffer behind the pause
 * is that much further from live every second it sits there, so a station
 * rejoins the broadcast rather than resuming an old one.
 */
function resumePlayback() {
  const source = player.source;
  if (!source) return;
  player.paused = false;
  if (source.kind === "folder") {
    markPlaying(true);
    setStatus("Playing your music", "on");
    audio.play().catch((e) => {
      if (!isAbort(e)) failure(String(e && e.message ? e.message : e));
    });
    return;
  }
  play(source, { volume: player.target });
}

function togglePlay() {
  if (player.paused) {
    resumePlayback();
    return;
  }
  if (player.playing) {
    stopPlayback();
    return;
  }
  const last = stationById(state.settings.lastStation) || state.stations[0];
  if (last) playStation(last);
  else say("no stations yet — add one", "bad");
}

/**
 * The keyboard's media keys.
 *
 * Chromium gives them to whichever media session is active, which is this app
 * while the element is playing - but only to handlers that have been
 * registered. With none set, next and previous did nothing at all, and
 * play/pause and stop reached the <audio> element directly, behind the app's
 * back: the sound went and the app never learned, so the face kept saying ON
 * AIR over silence. These put the keys on the same functions the buttons use.
 *
 * The session only exists while something is playing, so a key pressed from
 * standby does not reach us - starting from cold is still the play button's
 * job, or a global shortcut, which would have to take the keys off every other
 * player on the machine to do it.
 */
function wireMediaKeys() {
  if (!("mediaSession" in navigator)) return;
  const on = (action, handler) => {
    try {
      navigator.mediaSession.setActionHandler(action, handler);
    } catch {
      // An action this build does not know is not worth failing the rest over.
    }
  };
  on("play", () => {
    if (player.paused) resumePlayback();
    else if (!player.playing) togglePlay();
  });
  on("pause", () => pausePlayback());
  on("stop", () => stopPlayback());
  on("nexttrack", () => step(1));
  on("previoustrack", () => step(-1));
}

/** Which tab is in front. */
const currentPane = () => {
  const tab = $(".tab.on");
  return tab ? tab.dataset.pane : "";
};

/**
 * Prev and next step through the list in front of you rather than always
 * through the saved stations: the browse results while that tab is open, the
 * shuffle folder while one of its tracks is playing, and the saved stations
 * otherwise. Stepping the saved list regardless would leave the browse
 * results with no way through them but the mouse, and would end a folder
 * shuffle the moment either button was pressed.
 */
function step(delta) {
  if (currentPane() === "browse" && browseResults.length) {
    stepBrowse(delta);
    return;
  }
  if (player.source && player.source.kind === "folder") {
    stepFolder(delta);
    return;
  }
  const list = visibleStations.length ? visibleStations : state.stations;
  if (!list.length) return;
  const here = list.findIndex((s) => s.id === (player.source && player.source.stationId));
  const next = list[(here + delta + list.length * 2) % list.length] || list[0];
  playStation(next);
}

/** Through the browse results, in the order they are shown. */
function stepBrowse(delta) {
  const here = browseResults.findIndex(
    (st) => player.source && sameStream(player.source.url, st.url)
  );
  // Nothing from this list is playing yet, so start at the end the button
  // points at rather than somewhere in the middle.
  const next =
    here < 0
      ? browseResults[delta > 0 ? 0 : browseResults.length - 1]
      : browseResults[(here + delta + browseResults.length * 2) % browseResults.length];
  previewBrowse(next);
}

/**
 * Through the shuffle folder. Next draws again, which is what the end of a
 * track already does; prev returns to the track actually played before this
 * one, since a "previous" that draws at random is not a previous at all.
 */
function stepFolder(delta) {
  if (delta < 0) {
    const back = folderHistory.pop();
    if (back) {
      play(back);
      return;
    }
  }
  playRandomFromFolder(player.source.folder);
}

// --------------------------------------------------------------- browse ---

/*
 * The radio-browser.info directory: a public catalogue of internet radio,
 * kept by the people who listen to it. The searching happens in Rust, which
 * picks one of the volunteer mirrors, says which app is calling, and hands
 * back a page already cleaned up. Nothing here is saved until ADD is
 * pressed - a row is a candidate, not a station.
 */

/** Rows per request. Two screenfuls: enough to browse, small enough to be quick. */
const BROWSE_PAGE = 40;
/** What the directory calls each chosen filter, and what to call it in a list. */
let browseTag = "";
let browseTagLabel = "";
let browseCountry = "";
let browseCountryLabel = "";
/** The directory's own name for a format, and which band of the bitrate
 *  dropdown to match - a round number, or "low" and "high" for the tails. */
let browseCodec = "";
let browseBitrate = "";
/** The directory's global lists, and the narrowed ones it works out for us. */
let browseCountries = null;
let browseTags = null;
const browseFacets = new Map();
let browseResults = [];
/** Where the next page starts, counted in what the directory offered. */
let browseOffset = 0;
let browseMore = false;
let browseBusy = false;
let browseLooked = false;
/**
 * Which search the list belongs to. Changing a filter while one is in flight
 * used to be dropped on the floor: the dropdowns showed the new filter, the
 * list showed the old results, and MORE then paged the new query from the old
 * query's offset. The newest request wins instead, and an older one that
 * lands late is thrown away.
 */
let browseRequest = 0;
/** The same, per dropdown, for the tallies that narrow one filter by the other. */
const browseNarrows = new Map();

/**
 * Same stream by any reasonable reading, so a station is not kept twice.
 * The scheme is dropped along with the case and a trailing slash: the
 * directory carries the same stream under http and https all the time, and
 * this has to read a URL the same way `stream_key` does in the backend, or
 * a row the search collapsed comes straight back on the next page.
 */
const sameStream = (a, b) => {
  const tidy = (u) =>
    (u || "").trim().toLowerCase().replace(/^https?:\/\//, "").replace(/\/+$/, "");
  return !!tidy(a) && tidy(a) === tidy(b);
};

const browseSaved = (url) => state.stations.some((s) => sameStream(s.url, url));

/**
 * How the result list reads: country first, then the station name.
 *
 * The directory pages in name order, so every page is a slice of one
 * alphabet. Sorting the whole list each time a page lands therefore keeps the
 * names in order inside each country instead of restarting the alphabet at
 * every MORE. Countries drop a leading "The" the way the country dropdown
 * does, so a fifth of the world does not file under T, and a station the
 * directory has no country for sorts last - a blank heading the list reads as
 * a bug rather than as a station nobody labelled.
 */
function byCountryThenName(a, b) {
  const country = (c) => {
    const lower = (c || "").trim().toLowerCase();
    return lower.startsWith("the ") ? lower.slice(4) : lower;
  };
  const one = country(a.country);
  const two = country(b.country);
  if (!one !== !two) return one ? -1 : 1;
  return one.localeCompare(two) || (a.name || "").localeCompare(b.name || "", undefined, { sensitivity: "base" });
}

/** What the chosen option calls itself, as opposed to what it is worth. */
const labelOf = (select) => {
  const picked = select.selectedOptions[0];
  return (picked && picked.dataset.label) || "";
};

function browseNote(text, mood) {
  const note = $("#browse-note");
  note.className = "editor-note" + (mood ? " " + mood : "");
  note.textContent = text;
}

/** Run a search. `more` adds the next page instead of starting over. */
async function browseSearch(more) {
  const mine = ++browseRequest;
  browseBusy = true;
  const name = $("#browse-query").value.trim();
  if (!more) {
    browseResults = [];
    browseOffset = 0;
    browseMore = false;
    $("#browse-list").scrollTop = 0;
  }
  browseNote(more ? "Fetching more…" : "Searching the directory…");
  renderBrowse();
  try {
    const page = await invoke("browse_stations", {
      query: {
        name,
        tag: browseTag,
        countryCode: browseCountry,
        codec: browseCodec,
        bitrate: browseBitrate,
        limit: BROWSE_PAGE,
        offset: browseOffset,
      },
    });
    // A newer search started while this one was out: that one owns the list,
    // the offset and the note now.
    if (mine !== browseRequest) return;
    // Step over what the directory offered, not over what survived the tidy:
    // an offset counted in survivors walks back over ground already covered.
    browseOffset += page.offered;
    // Mirrors are edited while they are being paged through, so the page
    // after this one can hand back something already on screen.
    const fresh = page.stations.filter(
      (st) => !browseResults.some((seen) => sameStream(seen.url, st.url))
    );
    browseResults = browseResults.concat(fresh).sort(byCountryThenName);
    // A short page is the end of the directory's answer. A full one that
    // added nothing new means the paging has stopped moving - which is what
    // the offset cap at the far end of the catalogue looks like from here.
    browseMore = page.offered >= BROWSE_PAGE && fresh.length > 0;
    browseNote(
      browseResults.length
        ? `${browseResults.length} from radio-browser.info — press a row to listen, + to keep it`
        : "Nothing in the directory matches that."
    );
  } catch (e) {
    if (mine !== browseRequest) return;
    browseMore = false;
    browseNote(String(e), "bad");
  } finally {
    if (mine === browseRequest) {
      browseBusy = false;
      renderBrowse();
    }
  }
}

const asCountry = (c) => [c.code, c.name, c.stations];
const asTag = (t) => [t.value, t.name, t.stations];

/**
 * Fetch the whole-directory lists. Once a run: neither the countries of the
 * world nor the directory's busiest genres change while the app is open, and
 * both are a convenience - searching still works if they will not load.
 */
async function loadBrowseFilters() {
  if (!browseCountries) {
    try {
      browseCountries = await invoke("browse_countries");
    } catch {
      say("could not load the country list", "bad");
    }
  }
  if (!browseTags) {
    try {
      browseTags = await invoke("browse_tags");
    } catch {
      say("could not load the genre list", "bad");
    }
  }
  refreshBrowseFilters();
}

/** What one filter leaves available to the other, worked out once and kept. */
function facetsFor(query) {
  const key = JSON.stringify(query);
  if (!browseFacets.has(key)) {
    // The request is kept, not the answer it settles on. Both dropdowns ask
    // the moment a format is picked, and with nothing else set they ask the
    // same question - which, cached only once it had returned, was a megabyte
    // fetched twice. A failure is dropped so the next try is a real one.
    browseFacets.set(
      key,
      invoke("browse_facets", { query }).catch((e) => {
        browseFacets.delete(key);
        throw e;
      })
    );
  }
  return browseFacets.get(key);
}

/**
 * A facet tally under everything else that is currently filtering.
 *
 * Only what is actually set goes in, so that the two dropdowns asking with
 * nothing but a format between them build the same question - and so share
 * the one tally rather than each fetching it.
 */
function facetQuery(base, omit) {
  const query = {};
  if (base.countryCode) query.countryCode = base.countryCode;
  if (base.tag) query.tag = base.tag;
  // `omit` leaves out the filter the answer is for. Counting formats under the
  // chosen format would only ever report the format already chosen.
  if (browseCodec && omit !== "codec") query.codec = browseCodec;
  if (browseBitrate && omit !== "bitrate") query.bitrate = browseBitrate;
  return query;
}

/**
 * Point each dropdown at what the others leave: the genres that a chosen
 * country actually has, the countries that carry a chosen genre, and both of
 * them counted under whatever format and bitrate are set. With nothing set
 * anywhere else, the directory's own list is the right list.
 *
 * The counts have to answer for the format and bitrate too. "Paraguay (68)"
 * beside a 320k filter is a promise the next search cannot keep - the country
 * has 68 stations and none of them are 320k - and a filter that lies about
 * what it will find is worse than one that offers no count at all.
 *
 * The tally is megabytes, so it happens only when a filter changes - not on
 * every search - and each answer is kept for the rest of the run.
 */
async function refreshBrowseFilters() {
  const jobs = [];
  // Format and bitrate narrow both lists, so neither global list is right
  // any more once one of them is set.
  const narrowed = !!browseCodec || !!browseBitrate;

  if (browseCountry || narrowed) {
    jobs.push(
      narrow("#browse-tag", facetQuery({ countryCode: browseCountry }), (f) => f.tags, asTag, "genre")
    );
  } else if (browseTags) {
    invalidateBrowseNarrow("#browse-tag");
    setBrowseOptions("#browse-tag", browseTags, asTag, browseTag, browseTagLabel);
  }

  if (browseTag || narrowed) {
    jobs.push(
      narrow("#browse-country", facetQuery({ tag: browseTag }), (f) => f.countries, asCountry, "country")
    );
  } else if (browseCountries) {
    invalidateBrowseNarrow("#browse-country");
    setBrowseOptions("#browse-country", browseCountries, asCountry, browseCountry, browseCountryLabel);
  }

  // The fixed lists are only worth counting once something else is narrowing
  // them. With nothing set the answer would be the whole directory, and a
  // tally is megabytes - the browse tab is meant to cost nothing until asked.
  const place = { countryCode: browseCountry, tag: browseTag };
  const anywhereElse = !!browseCountry || !!browseTag;
  if (anywhereElse || !!browseBitrate) {
    jobs.push(narrowFixed("#browse-codec", facetQuery(place, "codec"), (f) => f.codecs, "format"));
  } else {
    resetFixed("#browse-codec");
  }
  if (anywhereElse || !!browseCodec) {
    jobs.push(narrowFixed("#browse-bitrate", facetQuery(place, "bitrate"), (f) => f.bitrates, "bitrate"));
  } else {
    resetFixed("#browse-bitrate");
  }

  await Promise.all(jobs);
}

/** What that dropdown is filtering by right now, read fresh rather than kept. */
const filterNow = (selector) =>
  selector === "#browse-tag"
    ? { value: browseTag, label: browseTagLabel }
    : { value: browseCountry, label: browseCountryLabel };

/** Rebuild one dropdown from a facet tally, saying so while it is fetched. */
/**
 * Format and bitrate are fixed lists - five formats the player can open, and
 * nine bands of bitrate - so they are annotated rather
 * than rebuilt: each option keeps its place and gains a count, and one with
 * nothing behind it is disabled rather than removed. A short list that
 * reshuffles as you narrow is harder to use than one that greys out.
 */
function annotateFixed(selector, buckets, sampled) {
  const select = $(selector);
  const counts = new Map((buckets || []).map((b) => [b.key, b.stations]));
  Array.prototype.forEach.call(select.options, (option, index) => {
    // Index 0 is "Any format" / "Any bitrate", which is always available.
    if (index === 0) return;
    if (!option.dataset.label) option.dataset.label = option.textContent;
    const stations = counts.get(option.value) || 0;
    option.textContent = `${option.dataset.label} (${stations}${sampled && stations ? "+" : ""})`;
    // A filter still filtering stays selectable even at zero, or the dropdown
    // would refuse to offer what it is currently set to.
    option.disabled = stations === 0 && option.value !== select.value;
  });
}

/** Back to plain labels, for when nothing is narrowing these any more. */
function invalidateBrowseNarrow(selector) {
  browseNarrows.set(selector, (browseNarrows.get(selector) || 0) + 1);
  $(selector).disabled = false;
}

function resetFixed(selector) {
  invalidateBrowseNarrow(selector);
  Array.prototype.forEach.call($(selector).options, (option) => {
    if (option.dataset.label) option.textContent = option.dataset.label;
    option.disabled = false;
  });
}

async function narrowFixed(selector, query, pick, what) {
  const select = $(selector);
  const mine = (browseNarrows.get(selector) || 0) + 1;
  browseNarrows.set(selector, mine);
  try {
    const facets = await facetsFor(query);
    if (browseNarrows.get(selector) !== mine) return;
    annotateFixed(selector, pick(facets), facets.sampled);
  } catch {
    if (browseNarrows.get(selector) !== mine) return;
    // Leave the plain list rather than a half-annotated one.
    resetFixed(selector);
    say(`could not work out which ${what}s are available`, "bad");
  }
}

async function narrow(selector, query, pick, unpack, what) {
  const select = $(selector);
  // A cached tally resolves a microtask later than an uncached one, so two of
  // these can be in flight on one dropdown and finish out of order. Newest
  // wins, and the selection is read when the answer lands rather than when it
  // was asked for - otherwise a slow tally snaps the dropdown back to what was
  // chosen minutes ago, without firing a change event to say so.
  const mine = (browseNarrows.get(selector) || 0) + 1;
  browseNarrows.set(selector, mine);
  select.disabled = true;
  try {
    const facets = await facetsFor(query);
    if (browseNarrows.get(selector) !== mine) return;
    const chosen = filterNow(selector);
    setBrowseOptions(selector, pick(facets), unpack, chosen.value, chosen.label, facets.sampled);
  } catch {
    if (browseNarrows.get(selector) !== mine) return;
    // Keep whatever the list already had rather than emptying it.
    say(`could not work out which ${what}s are available`, "bad");
  } finally {
    if (browseNarrows.get(selector) === mine) select.disabled = false;
  }
}

/**
 * Rebuild a filter's options, keeping its "Any …" row and its selection. The
 * value is what the directory knows the thing as and the label is what a
 * person reads, which are not always the same string - so the label rides
 * along on the option for anything that saves it.
 */
function setBrowseOptions(selector, entries, unpack, value, label, sampled) {
  const select = $(selector);
  while (select.options.length > 1) select.remove(1);
  entries.forEach((entry) => {
    const [optionValue, optionLabel, stations] = unpack(entry);
    const option = document.createElement("option");
    option.value = optionValue;
    option.dataset.label = optionLabel;
    // A tally that hit its limit counted a slice of a big country, so what it
    // found is a floor. Say "312+" rather than passing it off as the total.
    option.textContent = `${optionLabel} (${stations}${sampled ? "+" : ""})`;
    select.append(option);
  });
  // A filter still filtering must still be shown, even when the other side
  // has narrowed it out of the list - otherwise the dropdown quietly claims
  // to be set to something it is not.
  const known = Array.prototype.some.call(select.options, (o) => o.value === value);
  if (value && !known) {
    const option = document.createElement("option");
    option.value = value;
    option.dataset.label = label || value;
    option.textContent = `${label || value} (0)`;
    select.append(option);
  }
  select.value = value;
}

/** Listen to a directory station without keeping it. */
function previewBrowse(st) {
  play({
    kind: "station",
    url: st.url,
    title: st.name,
    subtitle: [st.country, st.tags].filter(Boolean).join("  ·  ") || "radio-browser.info",
    // What the directory has on file, so the line says something from the
    // start. Plenty of Shoutcast servers answer `ICY 200 OK` rather than an
    // HTTP status line, and the metadata probe cannot read those at all.
    meta: [st.codec, st.bitrate ? st.bitrate + " kbps" : ""].filter(Boolean).join("  ·  "),
    logo: st.favicon,
    stationId: null,
    hls: !!st.hls,
  });
  // Nothing in the saved list is playing any more; both lists should say so.
  renderStations();
  renderBrowse();
}

function addBrowseStation(st) {
  if (browseSaved(st.url)) return;
  // The genre that was searched for beats the directory's own first tag: it
  // is what this station is to the person keeping it.
  const tag = browseTagLabel || st.tag;
  state.stations.push({
    id: newId(),
    name: st.name,
    url: st.url,
    tag,
    logo: st.favicon,
    favorite: false,
  });
  saveStations();
  renderStations();
  renderBrowse();
  say(st.name + " added to your stations", "good");
}

function renderBrowse() {
  const list = $("#browse-list");
  list.innerHTML = "";

  if (!browseResults.length) {
    const li = document.createElement("li");
    li.className = "empty";
    li.textContent = browseBusy ? "Searching…" : "Search for a station, or explore by country and genre.";
    list.append(li);
    return;
  }

  browseResults.forEach((st, i) => {
    const li = document.createElement("li");
    li.className = "row";
    if (player.source && sameStream(player.source.url, st.url)) li.classList.add("on");

    const idx = document.createElement("span");
    idx.className = "idx";
    idx.textContent = pad2(i + 1);

    const name = document.createElement("span");
    name.className = "name";
    const b = document.createElement("b");
    b.textContent = st.name;
    const small = document.createElement("small");
    small.textContent = [
      st.country,
      [st.codec, st.bitrate ? st.bitrate + "k" : ""].filter(Boolean).join(" "),
      st.tags,
    ]
      .filter(Boolean)
      .join("  ·  ");
    name.append(b, small);
    li.append(idx, name);

    // Still worth flagging, but as a fact rather than a warning: HLS plays,
    // it just takes the other player to do it.
    if (st.hls) {
      const flag = document.createElement("span");
      flag.className = "tag";
      flag.textContent = "HLS";
      flag.title = "Played through hls.js rather than by the webview itself.";
      li.append(flag);
    }

    const saved = browseSaved(st.url);
    const add = document.createElement("button");
    add.className = "icon" + (saved ? " done" : "");
    add.textContent = saved ? "✓" : "+";
    add.title = saved ? "Already in your stations" : "Add to your stations";
    add.setAttribute("aria-label", add.title);
    add.addEventListener("click", (e) => {
      e.stopPropagation();
      addBrowseStation(st);
    });
    li.append(add);

    // Same bargain as the station list: the row itself is the play control.
    li.tabIndex = 0;
    li.setAttribute("role", "button");
    li.setAttribute("aria-label", `Listen to ${st.name}`);
    li.addEventListener("click", () => previewBrowse(st));
    li.addEventListener("keydown", (e) => {
      if (e.target === li && (e.key === "Enter" || e.code === "Space")) {
        e.preventDefault();
        previewBrowse(st);
      }
    });
    list.append(li);
  });

  if (!browseMore) return;
  const tail = document.createElement("li");
  tail.className = "empty";
  const more = document.createElement("button");
  more.className = "gel";
  more.textContent = browseBusy ? "Loading…" : "More stations";
  more.disabled = browseBusy;
  more.addEventListener("click", () => browseSearch(true));
  tail.append(more);
  list.append(tail);
}

/**
 * Fill the pane the first time it is opened, and not before: the directory is
 * somebody else's server, and an app nobody browses should not be calling it.
 */
function browseFirstLook() {
  if (browseLooked) return;
  browseLooked = true;
  loadBrowseFilters();
  browseSearch(false);
}

// --------------------------------------------------------------- alarms ---

let ringing = null;
let interruptedPlayback = null;
let ringWatchdog = null;
let autoStopTimer = null;
/** Set from the moment the give-up timeout fires until the ring is over. */
let givingUp = false;
/**
 * Give-ups each alarm's current ring has turned into a snooze, by alarm id.
 * One tally per alarm rather than one for the app: a second alarm ringing in
 * the gap between a snooze and its return used to wipe the first one's
 * budget, and an alarm set to snooze once would do it again and again.
 */
const autoSnoozed = new Map();
/** How long an alarm takes to recede once it has given up. */
const GIVE_UP_FADE_SECS = 6;

/**
 * The fallback for everything: a random track from the backup folder. Used
 * when an alarm source will not make a sound, and when a station gives up
 * during ordinary listening.
 */
async function playBackupTrack(reason, opts = {}) {
  // Give this attempt its own quiet window. The watchdog stays armed on
  // purpose, so it guards the backup track too.
  player.lastProgress = Date.now();
  // Snapshotted before the await, not after: scanning the backup folder can
  // take seconds on a network share, and the ring can end - or start receding
  // - while it runs. Starting a track then would cancel the fade and bring the
  // alarm back at full volume after it had already given up.
  const generation = playGeneration;
  const stale = () => superseded(generation) || givingUp;
  try {
    const pick = await invoke("backup_track");
    if (stale()) return false;
    const title = pick.name.replace(/\.[^.]+$/, "");
    play(
      {
        kind: "folder",
        url: convertFileSrc(pick.path),
        path: pick.path,
        title,
        subtitle: reason || "from the backup folder",
        folder: BACKUP,
      },
      opts
    );
    // Say what is actually ringing, not what was supposed to.
    if (ringing) $("#ring-source").textContent = "Backup music · " + title;
    return true;
  } catch (e) {
    // Same again: a ring that has ended must not have its card rewritten.
    if (stale()) return false;
    if (ringing) {
      goSilent(String(e));
    } else {
      setStatus("Station unavailable", "error");
      showNowPlaying("Unable to connect", reason || "", String(e));
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
  $("#ring-trigger").textContent = "Alarm · no sound";
  $("#ring-source").textContent = "No audio available";
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
function armRingWatchdog(quietMs, note, reason) {
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
    // Read the volume now rather than capturing it when the ring started, so
    // turning it down mid-ring survives a fallback.
    playBackupTrack(reason, { volume: ringing.volume });
  }, 1000);
}

function onAlarmFire(payload) {
  // A replacement ring still interrupts the same listening session. Capture
  // it only once, before the alarm's source and volume replace the player's.
  if (!ringing) {
    interruptedPlayback = player.source ? {
      source: { ...player.source },
      position: player.source.kind === "folder" ? player.pendingPosition ?? audio.currentTime : 0,
      volume: player.target,
      paused: player.paused,
      history: [...folderHistory],
    } : null;
  }
  ringing = payload;
  // A native dialog occupies the top layer, above the alarm overlay. Close it
  // before focusing the alarm, and undo the old fade before its sound starts.
  closePowerCountdown(false);
  endSleepFade();
  setSleep(0);
  renderSleepTimer();
  clearInterval(ringWatchdog);
  clearTimeout(autoStopTimer);
  givingUp = false;
  player.backupAttempts = 0;
  // The budget belongs to the ring: a snooze carries its tally on, and any
  // other way of arriving - scheduled, caught up, tested - starts it over.
  if (payload.trigger !== "snooze") {
    autoSnoozed.delete(payload.alarmId);
  }
  $("#ringcard").classList.remove("silent");

  const overlay = $("#ringing");
  $("#ring-trigger").textContent =
    payload.trigger === "snooze" ? "Snooze ended"
      : payload.trigger === "catchup" ? "Missed alarm"
      : payload.trigger === "test" ? "Alarm test"
      : "Alarm";
  $("#ring-time").textContent = fmtAlarmTime(payload.hour, payload.minute);
  $("#ring-label").textContent = payload.label || "Alarm";
  $("#ring-source").textContent =
    (payload.kind === "station" ? "Station · " : payload.kind === "folder" ? "Your music · " : "") +
    (payload.title || "");
  $("#ring-note").textContent = payload.note || "";
  $("#ring-snooze-mins").textContent = payload.snoozeMins + " min";
  $("#ring-snooze").disabled = payload.trigger === "test";
  $("#ring-snooze").title = payload.trigger === "test" ? "Test alarms cannot be snoozed" : "";
  overlay.hidden = false;
  // After unhiding, not before: focus() on a hidden subtree does nothing, and
  // an alarm nobody can dismiss from the keyboard is not much of an alarm.
  $("#ring-dismiss").focus();

  const opts = { volume: payload.volume, fadeSecs: payload.fadeSecs };
  if (payload.kind === "station") {
    play({ kind: "station", url: payload.url, title: payload.title, subtitle: payload.label, stationId: null }, opts);
    // If the stream has not made a sound within twelve seconds, stop
    // waiting for it and ring something that definitely works.
    armRingWatchdog(
      12000,
      "That stream did not start - playing the backup folder.",
      "stream did not start"
    );
  } else if (payload.kind === "folder") {
    // payload.folder is set when the track came from the alarm's own folder.
    // Prefer it: a test ring is deliberately unsaved, so looking the alarm up
    // in stored state would miss and drop the ring to the backup folder.
    const alarm = state.alarms.find((a) => a.id === payload.alarmId);
    const own =
      payload.folder ||
      (alarm && alarm.source && alarm.source.kind === "folder" ? alarm.source.path : null);
    // A note means Rust could not use the alarm's own source and reached for
    // the backup folder; keep pulling from there for the rest of the ring.
    const folder = payload.note || !own ? BACKUP : own;
    // The alarm's own file repeats until somebody answers it. The backup
    // folder does not: it is already the sound of something having gone
    // wrong, and one track of it looping is a worse thing to wake up to than
    // the folder played through.
    play(
      {
        kind: "folder",
        url: convertFileSrc(payload.path),
        path: payload.path,
        title: payload.title,
        subtitle: payload.label,
        folder,
        loop: folder !== BACKUP,
      },
      opts
    );
    armRingWatchdog(
      8000,
      "That track would not play - playing the backup folder.",
      "that track would not play"
    );
  } else {
    // Rust could not resolve any source at all.
    goSilent(payload.note || "no source available");
  }

  if (payload.autoStopMins > 0) {
    autoStopTimer = setTimeout(giveUp, payload.autoStopMins * 60000);
  }
}

/**
 * The give-up timeout has run out. Let the sound recede rather than cutting
 * it dead mid-bar - the last thing a room hears from an alarm nobody
 * answered should not be a click - and then end the ring, or hand it to a snooze.
 */
function giveUp() {
  if (!ringing || givingUp) return;
  givingUp = true;
  // On the way out. A fallback track started now would come in at full
  // volume over the fade, and there is nothing left to rescue anyway.
  clearInterval(ringWatchdog);
  ringWatchdog = null;
  // Nothing is making a sound - the silent card, or a source that never
  // started - so there is nothing to let go of.
  if (!player.playing) {
    endGiveUp();
    return;
  }
  fadeOut(GIVE_UP_FADE_SECS);
  // The ending is its own timer rather than the fade's callback. Touching the
  // volume knob cancels a fade, and a ring that then never ended would be a
  // good deal worse than one that ends at the volume you just chose.
  autoStopTimer = setTimeout(endGiveUp, GIVE_UP_FADE_SECS * 1000);
}

/** Should this give-up come back later instead of being the end of it? */
function autoSnoozeDue() {
  if (!ringing) return false;
  if ((autoSnoozed.get(ringing.alarmId) || 0) >= (ringing.autoSnoozes || 0)) return false;
  // A test ring must not schedule a real one: nobody expects the alarm they
  // auditioned at teatime to go off again ten minutes later.
  return ringing.trigger !== "test";
}

/** The fade is over: end this ring and return to the interrupted playback. */
function endGiveUp() {
  if (!ringing) return;
  clearTimeout(autoStopTimer);
  clearInterval(player.fadeTimer);
  autoStopTimer = player.fadeTimer = null;
  const mins = ringing.autoStopMins;
  if (autoSnoozeDue()) {
    autoSnoozed.set(ringing.alarmId, (autoSnoozed.get(ringing.alarmId) || 0) + 1);
    snoozeRing("gave up after " + mins + " min", { resumePrevious: true });
    return;
  }
  say("alarm gave up after " + mins + " minutes");
  dismissRing({ resumePrevious: true });
}

function finishRing(resumePrevious) {
  const previous = resumePrevious ? interruptedPlayback : null;
  interruptedPlayback = null;
  clearInterval(ringWatchdog);
  clearTimeout(autoStopTimer);
  ringWatchdog = autoStopTimer = null;
  givingUp = false;
  $("#ringing").hidden = true;
  ringing = null;
  renderSleepTimer();
  if (previous) {
    folderHistory = previous.history;
    play(previous.source, { volume: previous.volume, startTime: previous.position });
    if (previous.paused) pausePlayback();
  } else {
    stopPlayback();
  }
}

const pendingRingActions = new WeakMap();

function beginRingAction(ring, resumePrevious) {
  const pending = pendingRingActions.get(ring);
  if (pending) {
    // A manual press still means stop when the automatic dismissal or snooze
    // has already reached Rust. Keep one request, but cancel its audio resume.
    if (!resumePrevious) pending.resumePrevious = false;
    return null;
  }
  const action = { resumePrevious };
  pendingRingActions.set(ring, action);
  return action;
}

async function dismissRing({ resumePrevious = false } = {}) {
  const ring = ringing;
  if (!ring) return;
  const action = beginRingAction(ring, resumePrevious);
  if (!action) return;
  try {
    if (ring.alarmId) {
      if (ring.trigger === "test") await invoke("dismiss_test_alarm", { alarmId: ring.alarmId });
      else await invoke("dismiss_alarm", { alarmId: ring.alarmId });
    }
    if (ringing !== ring) return;
    finishRing(action.resumePrevious);
    refreshNextAlarm();
    refreshPowerStatus();
  } catch (e) {
    if (ringing !== ring) return;
    $("#ring-note").textContent = "Could not dismiss the alarm: " + e + ". Try again.";
    say("could not dismiss the alarm: " + e, "bad");
  } finally {
    pendingRingActions.delete(ring);
  }
}

/** `why` is set when the alarm snoozed itself rather than being asked to. */
async function snoozeRing(why, { resumePrevious = false } = {}) {
  const ring = ringing;
  if (!ring || ring.trigger === "test") return;
  const action = beginRingAction(ring, resumePrevious);
  if (!action) return;
  const { alarmId, snoozeMins } = ring;
  try {
    await invoke("snooze_alarm", { alarmId, minutes: snoozeMins });
    // A later alarm can arrive while IPC is pending. Its card and audio belong
    // to that occurrence, even if both occurrences have the same alarm id.
    if (ringing !== ring) return;
    finishRing(action.resumePrevious);
    say((why ? why + " - " : "") + "snoozed for " + snoozeMins + " minutes", "good");
    refreshNextAlarm();
    refreshPowerStatus();
  } catch (e) {
    if (ringing !== ring) return;
    $("#ring-note").textContent = "Could not snooze the alarm: " + e + ". Try again or dismiss it.";
    say("could not snooze the alarm: " + e, "bad");
  } finally {
    pendingRingActions.delete(ring);
  }
}

let nextAlarmRequest = 0;

async function refreshNextAlarm() {
  const request = ++nextAlarmRequest;
  let next = null;
  try {
    next = await invoke("next_alarm");
  } catch { /* nothing scheduled */ }
  if (request !== nextAlarmRequest) return;
  const box = $("#next-alarm");
  const bar = $("#status-next");
  if (!next) {
    box.textContent = "No alarm set";
    bar.textContent = "";
    return;
  }
  let when = null;
  try {
    when = await invoke("local_time", { atMs: next.atMs });
  } catch { /* the countdown remains useful without a wall-time reading */ }
  if (request !== nextAlarmRequest) return;
  const label = next.label ? next.label + " · " : "";
  const text = `${next.snoozed ? "Snoozed" : "Next"} ${label}${when ? fmtClock(when, false) + " · " : ""}in ${fmtDuration(next.inSecs)}`;
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
    li.textContent = state.stations.length ? "Nothing matches that filter." : "Save stations from Browse, or add a stream URL.";
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
    // Reachable without a mouse: the row is the play control.
    li.tabIndex = 0;
    li.setAttribute("role", "button");
    li.setAttribute("aria-label", `Play ${station.name}`);
    if (player.source && player.source.stationId === station.id) {
      li.setAttribute("aria-current", "true");
    }
    li.addEventListener("click", () => playStation(station));
    li.addEventListener("keydown", (e) => {
      if (e.target === li && (e.key === "Enter" || e.code === "Space")) {
        e.preventDefault();
        playStation(station);
      }
    });
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
    li.textContent = "Wake up to a station or your own music. Add your first alarm.";
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
    sw.setAttribute("aria-label", `Enable the ${alarm.label || "alarm"} alarm at ${fmtAlarmTime(alarm.hour, alarm.minute)}`);
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
        ? "No backup folder chosen"
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
    const sw = row.querySelector(".sw");
    sw.setAttribute("aria-pressed", String(!!state.settings[key]));
    // The switches are empty buttons; without this they announce as "button".
    const label = row.querySelector("span");
    if (label) sw.setAttribute("aria-label", label.textContent.trim());
  });
  const vol = Math.round((state.settings.volume ?? 0.8) * 100);
  $("#volume").value = vol;
  $("#volval").textContent = vol;
  $("#volume").style.setProperty("--fill", vol + "%");
  $("#sleep-action").value = state.settings.sleepTimerAction || "stop";
  renderSleepTimer();
  renderPowerStatus();
}

// ------------------------------------------------------------ persisting ---

const saveStations = () => invoke("save_stations", { stations: state.stations }).catch((e) => say(String(e), "bad"));
const saveAlarms = () =>
  invoke("save_alarms", { alarms: state.alarms })
    .then(() => { refreshNextAlarm(); refreshPowerStatus(); })
    .catch((e) => say(String(e), "bad"));

let settingsSaveTimer = null;
let settingsAutostartIntent = null;
function saveSettings(explicitAutostart = null) {
  if (explicitAutostart !== null) settingsAutostartIntent = explicitAutostart;
  clearTimeout(settingsSaveTimer);
  settingsSaveTimer = setTimeout(() => {
    const explicitAutostart = settingsAutostartIntent;
    settingsAutostartIntent = null;
    if (explicitAutostart !== null) state.settings.startWithWindows = explicitAutostart;
    invoke("save_settings", { settings: state.settings, explicitAutostart }).then(refreshPowerStatus).catch((e) => {
      say(String(e), "bad");
      // start-with-Windows can fail on its own; reflect what actually stuck.
      loadState().catch((error) => say(String(error), "bad"));
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
  refreshPowerStatus();
}

// -------------------------------------------------------- station editor ---

let editingStation = null;

function openStationEditor(station) {
  cancelStationTest();
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
  cancelStationTest();
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

/**
 * An alarm keeps a 24-hour time whatever the clock setting says. On a
 * 12-hour clock the hour field holds 1-12 and the AM/PM pair carries the
 * rest of it, so these two functions are the only places that cross that
 * boundary - everything else works in the hour the alarm is stored with.
 */
const editorUses12h = () => state.settings.clock24h === false;
let editorPm = false;
let editorQuickMins = 0;
let editorTimeRequest = 0;
let editorPendingTime = 0;

function setEditorTime(hour, minute, quickMins = 0) {
  ++editorTimeRequest;
  // Only the RING IN chips pass a span, so every other way of moving the
  // time - typing, the steppers, AM/PM - puts the row back to nothing lit.
  editorQuickMins = quickMins;
  editorPm = hour >= 12;
  $("#al-hour").value = editorUses12h() ? String(hour % 12 || 12) : pad2(hour);
  $("#al-minute").value = pad2(minute);
  syncTimeUi();
}

function readEditorTime() {
  const typed = parseInt($("#al-hour").value, 10) || 0;
  const minute = Math.min(59, Math.max(0, parseInt($("#al-minute").value, 10) || 0));
  // A typed 0 on a 12-hour clock means midnight, which that clock calls 12.
  const hour = editorUses12h()
    ? (Math.min(12, Math.max(1, typed || 12)) % 12) + (editorPm ? 12 : 0)
    : Math.min(23, Math.max(0, typed));
  return { hour, minute };
}

/** Everything downstream of those two fields: which of AM and PM is lit,
 *  which quick-set chip matches, and the line saying when it next rings. */
function syncTimeUi() {
  const { hour, minute } = readEditorTime();
  $$("#al-meridiem .chip").forEach((btn) => {
    const lit = (btn.dataset.ampm === "pm") === (hour >= 12);
    btn.classList.toggle("on", lit);
    btn.setAttribute("aria-pressed", String(lit));
  });
  $$("#al-quick .chip").forEach((chip) =>
    chip.classList.toggle("on", +chip.dataset.mins === editorQuickMins)
  );
  updateDayHint();
}

/**
 * RING IN is a timer, not a clock: it moves the alarm to a span from now
 * rather than to a time of day. The span is rounded up to the next whole
 * minute because the scheduler fires on the minute, and a nap set for
 * fifteen minutes should not go off in fourteen and a bit.
 */
async function setEditorTimeIn(mins) {
  const request = ++editorTimeRequest;
  editorPendingTime = request;
  const atMs = Math.ceil((Date.now() + mins * 60000) / 60000) * 60000;
  let at;
  try {
    at = await invoke("local_time", { atMs });
  } catch (error) {
    if (editorPendingTime === request) editorPendingTime = 0;
    if (request === editorTimeRequest) say("Could not read the alarm time: " + error, "bad");
    return;
  }
  if (request !== editorTimeRequest) return;
  // A span from now can only happen once. Leaving days selected would give
  // an alarm that repeats at that time instead - not what the chip says.
  editorDays = [];
  $$("#al-days button").forEach((b) => b.classList.remove("on"));
  setEditorTime(at.hour, at.minute, mins);
}

/** AM and PM mean nothing on a 24-hour clock, so the pair comes and goes
 *  with that setting, and the time is rewritten in the reading it wants. */
function applyClockMode(hour, minute) {
  $("#al-meridiem").classList.toggle("hidden", !editorUses12h());
  setEditorTime(hour, minute);
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
    const { hour, minute } = readEditorTime();
    hint.textContent = `Once, at the next ${fmtAlarmTime(hour, minute)}`;
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
      ? "One file is picked at random from the folder and repeats until the alarm is answered — snoozes included, so it comes back with the same track."
      : "If the stream will not start within twelve seconds, the backup folder plays instead.";
  // Both kinds fall back to the backup folder, so both are silent without one.
  if (!state.settings.backupFolder) {
    note.className = "editor-note bad";
    note.textContent +=
      " Choose backup music in Settings in case this source is unavailable.";
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

/**
 * Auto-snooze hangs off the give-up timeout: an alarm that never gives up
 * never reaches it, so say so by greying it out rather than letting the
 * setting sit there looking as though it does something.
 */
function syncAutoSnooze() {
  const select = $("#al-autosnooze");
  select.disabled = +$("#al-autostop").value === 0;
  select.title = select.disabled
    ? "Only applies when the alarm gives up"
    : "When it gives up, snooze instead of stopping";
}

function openAlarmEditor(alarm) {
  if (!alarm && !clockNow) {
    say("Could not read the system clock. Try again.", "bad");
    tickClock();
    return;
  }
  editingAlarm = alarm || null;
  // A new alarm opens on the last one that was saved. Somebody who wakes to
  // the same station, fading in over the same twenty seconds, should not have
  // to say so again - but the time and the label are theirs to fill in, so
  // those two start empty however the last one was set.
  const last = state.settings.alarmDefaults || {};
  const base = alarm || {
    hour: (clockNow.hour + 1) % 24,
    minute: 0,
    days: last.days || [],
    label: "",
    source: last.source || { kind: "station", stationId: (state.stations[0] || {}).id },
    volume: last.volume ?? 0.8,
    fadeSecs: last.fadeSecs ?? 20,
    snoozeMins: last.snoozeMins ?? 10,
    autoStopMins: last.autoStopMins ?? 30,
    autoSnoozes: last.autoSnoozes ?? 0,
  };

  $("#al-label").value = base.label || "";
  editorDays = [...(base.days || [])];
  $$("#al-days button").forEach((b) => b.classList.toggle("on", editorDays.includes(+b.dataset.day)));
  applyClockMode(base.hour, base.minute);

  const src = base.source || { kind: "station" };
  editorFolder = src.kind === "folder" ? src.path : null;
  $("#al-folderpath").textContent = editorFolder || "No folder chosen";
  fillStationSelect(src.kind === "station" ? src.stationId : (state.stations[0] || {}).id);
  setKind(src.kind);

  $("#al-volume").value = Math.round((base.volume ?? 0.8) * 100);
  $("#al-volval").textContent = $("#al-volume").value;
  $("#al-volume").style.setProperty("--fill", $("#al-volume").value + "%");
  setSelectValue($("#al-fade"), base.fadeSecs ?? 20, (v) => v + " s");
  setSelectValue($("#al-snooze"), base.snoozeMins ?? 10, (v) => v + " min");
  setSelectValue($("#al-autostop"), base.autoStopMins ?? 30, (v) =>
    v === 0 ? "never" : v + " min"
  );
  setSelectValue($("#al-autosnooze"), base.autoSnoozes ?? 0, (v) =>
    v === 0 ? "off" : v + " times"
  );
  syncAutoSnooze();

  $("#al-delete").classList.toggle("hidden", !alarm);
  $("#alarm-editor").classList.remove("hidden");
}

/**
 * Keep how this alarm was set up, for the next one to start from. Editing an
 * older alarm counts as well: the last setup touched is the one still in
 * mind, whether or not it was the newest.
 */
function rememberAlarmSetup(alarm) {
  state.settings.alarmDefaults = {
    days: [...alarm.days],
    source: alarm.source,
    volume: alarm.volume,
    fadeSecs: alarm.fadeSecs,
    snoozeMins: alarm.snoozeMins,
    autoStopMins: alarm.autoStopMins,
    autoSnoozes: alarm.autoSnoozes,
  };
  saveSettings();
}

function closeAlarmEditor() {
  ++editorTimeRequest;
  editingAlarm = null;
  $("#alarm-editor").classList.add("hidden");
}

function readAlarmEditor() {
  if (editorPendingTime === editorTimeRequest && editorPendingTime !== 0) {
    return { error: "Wait for the alarm time to finish updating." };
  }
  const { hour, minute } = readEditorTime();
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
      autoSnoozes: +$("#al-autosnooze").value,
    },
  };
}

// --------------------------------------------------------- sleep timer ---

let sleepSnapshot = { revision: -1, timer: null, outcome: null, error: null };
let sleepFading = false;
let powerFocusBefore = null;
let sleepRequest = 0;
let powerStatus = null;
let powerWakeTime = null;
let powerStatusRequest = 0;
const sleepActionLabel = (action) => ({ stop: "Stop audio", sleep: "Sleep PC", shutdown: "Shut down PC" }[action] || "Stop audio");
/**
 * How long the sound takes to go. It is subtracted from the time left rather
 * than added to it: a thirty-minute timer should be silent at thirty minutes,
 * not just starting to think about it.
 */
const SLEEP_FADE_SECS = 20;

/** Put the volume back, if the timer had already started taking it away. */
function endSleepFade() {
  if (!sleepFading) return;
  sleepFading = false;
  clearInterval(player.fadeTimer);
  player.fadeTimer = null;
  if (player.playing) audio.volume = player.target;
}

function closePowerCountdown(restoreFocus = true) {
  const dialog = $("#power-countdown");
  if (!dialog.open) return;
  dialog.close();
  if (restoreFocus && !ringing && powerFocusBefore?.isConnected) powerFocusBefore.focus();
  powerFocusBefore = null;
}

function renderSleepTimer() {
  const timer = sleepSnapshot.timer;
  const pending = timer?.executeAtMs != null;
  $$("#sleep-chips .chip").forEach((chip) => {
    const on = +chip.dataset.mins === (timer?.minutes || 0);
    chip.classList.toggle("on", on);
    chip.setAttribute("aria-pressed", String(on));
    chip.disabled = !!ringing && +chip.dataset.mins > 0;
  });
  $("#sleep-hint").textContent = timer
    ? "This timer: " + sleepActionLabel(timer.action) + ". Choose minutes again to restart with the selected action."
    : "Choose what happens, then set the minutes.";
  if (!timer) {
    $("#sleep-left").textContent = "";
    closePowerCountdown();
    return;
  }
  if (pending) {
    const left = Math.max(0, Math.ceil((timer.executeAtMs - Date.now()) / 1000));
    $("#sleep-left").textContent = sleepActionLabel(timer.action) + " · " + left + "s";
    $("#power-title").textContent = timer.action === "shutdown" ? "This PC will shut down" : "This PC will sleep";
    $("#power-seconds").textContent = left + "s";
    const dialog = $("#power-countdown");
    if (!ringing && !dialog.open) {
      powerFocusBefore = document.activeElement;
      $("#power-error").textContent = "";
      $("#power-error").classList.add("hidden");
      dialog.showModal();
      $("#power-cancel").focus();
      say(sleepActionLabel(timer.action) + " in " + left + " seconds. Cancel or press Escape to keep this PC on.");
    }
    return;
  }
  closePowerCountdown();
  const left = timer.endsAtMs - Date.now();
  $("#sleep-left").textContent = left > 0 ? fmtDuration(left / 1000) + " left" : "Finishing…";
  // Fade out over whatever is actually left rather than a fixed twenty
  // seconds, so a tick that arrives late - the window was hidden, and
  // WebView2 throttles timers there - still lands on silence at zero.
  if (!sleepFading && !ringing && player.playing && left > 0 && left <= SLEEP_FADE_SECS * 1000) {
    sleepFading = true;
    fadeOut(Math.max(1, left / 1000));
  }
}

function applySleepSnapshot(snapshot) {
  // Events and command replies may cross in flight. Only Rust advances the
  // timer, and an older reply must never bring a cancelled power action back.
  if (snapshot.revision <= sleepSnapshot.revision) return;
  const previous = sleepSnapshot.timer;
  sleepSnapshot = snapshot;
  const timer = snapshot.timer;
  if (!timer || previous?.endsAtMs !== timer.endsAtMs || previous?.action !== timer.action) endSleepFade();
  if (!ringing && ((timer?.executeAtMs != null && previous?.executeAtMs == null) || snapshot.outcome === "finished")) {
    sleepFading = false;
    stopPlayback();
  }
  renderSleepTimer();
  if (snapshot.error) say(snapshot.error, "bad", true);
  else if (snapshot.outcome === "finished" && !ringing) say("sleep timer — goodnight");
}

async function setSleep(minutes) {
  if (minutes > 0 && ringing) {
    say("Dismiss or snooze the alarm before setting a sleep timer.");
    return;
  }
  const request = ++sleepRequest;
  const action = $("#sleep-action").value;
  try {
    const snapshot = minutes > 0
      ? await invoke("set_sleep_timer", { minutes, action })
      : await invoke("cancel_sleep_timer");
    applySleepSnapshot(snapshot);
    if (request === sleepRequest && snapshot.revision === sleepSnapshot.revision && !ringing) {
      say(minutes > 0 ? sleepActionLabel(action) + " in " + minutes + " minutes" : "sleep timer cancelled", "good");
    }
  } catch (error) {
    if (minutes === 0 && $("#power-countdown").open) {
      $("#power-error").textContent = "Could not cancel: " + String(error);
      $("#power-error").classList.remove("hidden");
    }
    say(String(error), "bad", true);
  }
}

function renderPowerStatus() {
  if (!powerStatus) return;
  $("#sleep-action option[value='sleep']").disabled = !powerStatus.sleepSupported;
  $("#sleep-action option[value='shutdown']").disabled = !powerStatus.shutdownSupported;
  const wake = $("[data-setting='wakeForAlarms'] .sw");
  wake.disabled = !powerStatus.wakeSupported;
  const details = [];
  if (state.settings.wakeForAlarms === false) details.push("Wake for alarms is off.");
  if (powerStatus.message) details.push(powerStatus.message);
  if (powerStatus.armedAtMs != null && powerWakeTime && state.settings.wakeForAlarms !== false) {
    const when = powerWakeTime;
    const day = new Date(Date.UTC(when.year, when.month - 1, when.day))
      .toLocaleDateString(undefined, { timeZone: "UTC", weekday: "short", month: "short", day: "numeric" });
    details.push("Wake timer armed for " + day + ", " + fmtClock(when, false) + ".");
  }
  if (powerStatus.error) details.push(powerStatus.error);
  const line = $("#power-status");
  line.textContent = details.join(" ") || "Wake status is unavailable.";
  line.classList.toggle("bad", !!powerStatus.error || (state.settings.wakeForAlarms !== false && (!powerStatus.wakeSupported || powerStatus.wakeAllowed === false)));
}

async function refreshPowerStatus() {
  const request = ++powerStatusRequest;
  try {
    const status = await invoke("power_status");
    if (request !== powerStatusRequest) return;
    // The wake deadline needs the same OS timezone rules as the alarm clock.
    const wakeTime = status?.armedAtMs != null ? await invoke("local_time", { atMs: status.armedAtMs }) : null;
    if (request !== powerStatusRequest) return;
    powerStatus = status;
    powerWakeTime = wakeTime;
    renderPowerStatus();
  } catch (error) {
    if (request !== powerStatusRequest) return;
    $("#power-status").textContent = "Could not check PC wake support: " + String(error);
    $("#power-status").classList.add("bad");
  }
}

setInterval(renderSleepTimer, 1000);

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

  $("#volume").addEventListener("input", (e) => {
    const v = +e.target.value;
    e.target.style.setProperty("--fill", v + "%");
    $("#volval").textContent = v;
    state.settings.volume = v / 100;
    // Apply it always: the slider used to persist a value it refused to act
    // on while an alarm rang, which is the one time you most want it. Cancels
    // any fade-in, which is the point - the user is overriding it.
    clearInterval(player.fadeTimer);
    audio.volume = v / 100;
    player.target = v / 100;
    // Carry it into the ring so a later fallback does not snap back.
    if (ringing) ringing.volume = v / 100;
    saveSettings();
  });

  $$("#sleep-chips .chip").forEach((chip) =>
    chip.addEventListener("click", () => setSleep(+chip.dataset.mins))
  );
  $("#sleep-action").addEventListener("change", () => {
    state.settings.sleepTimerAction = $("#sleep-action").value;
    saveSettings();
    renderSleepTimer();
  });
  $("#power-cancel").addEventListener("click", () => setSleep(0));
  $("#power-countdown").addEventListener("cancel", (event) => {
    event.preventDefault();
    setSleep(0);
  });
  $("#power-countdown").addEventListener("keydown", (event) => {
    if (event.key === "Tab") {
      event.preventDefault();
      $("#power-cancel").focus();
    }
  });

  // tabs
  $$(".tab").forEach((tab) =>
    tab.addEventListener("click", () => {
      $$(".tab").forEach((t) => {
        t.classList.toggle("on", t === tab);
        t.setAttribute("aria-selected", String(t === tab));
      });
      $$(".pane").forEach((p) => p.classList.toggle("on", p.id === "pane-" + tab.dataset.pane));
      if (tab.dataset.pane === "alarms") refreshNextAlarm();
      if (tab.dataset.pane === "browse") browseFirstLook();
      if (tab.dataset.pane === "settings") refreshPowerStatus();
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

  $("#st-url").addEventListener("input", () => {
    cancelStationTest();
    $("#st-note").textContent = "";
    $("#st-note").className = "editor-note";
  });
  $("#st-test").addEventListener("click", async () => {
    cancelStationTest();
    const url = $("#st-url").value.trim();
    const note = $("#st-note");
    if (!url) return;
    const test = new AbortController();
    stationTest = test;
    const stale = () => test.signal.aborted || $("#st-url").value.trim() !== url;
    note.className = "editor-note";
    note.textContent = "Connecting…";
    try {
      const info = await invoke("probe_stream", { url, wantTitle: true });
      if (stale()) return;
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
      const decoded = await canDecode(info.url, info.hls ? 15000 : 9000, {
        hls: !!info.hls,
        signal: test.signal,
      });
      if (stale()) return;
      note.className = "editor-note " + (decoded.ok ? "good" : "bad");
      note.textContent = decoded.ok
        ? "✓ live and playable" + (info.hls ? " (HLS)" : "") + " — " + (bits || info.url)
        : "✕ the server answers but nothing plays: " + decoded.reason;
    } catch (e) {
      if (stale()) return;
      note.className = "editor-note bad";
      note.textContent = "✕ " + e;
    } finally {
      if (stationTest === test) stationTest = null;
    }
  });

  // browse
  $("#btn-browse").addEventListener("click", () => browseSearch(false));
  $("#browse-query").addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    e.preventDefault();
    browseSearch(false);
  });
  $("#browse-country").addEventListener("change", (e) => {
    browseCountry = e.target.value;
    browseCountryLabel = labelOf(e.target);
    refreshBrowseFilters();
    browseSearch(false);
  });
  $("#browse-tag").addEventListener("change", (e) => {
    browseTag = e.target.value;
    browseTagLabel = labelOf(e.target);
    refreshBrowseFilters();
    browseSearch(false);
  });
  $("#browse-codec").addEventListener("change", (e) => {
    browseCodec = e.target.value;
    refreshBrowseFilters();
    browseSearch(false);
  });
  $("#browse-bitrate").addEventListener("change", (e) => {
    browseBitrate = e.target.value;
    refreshBrowseFilters();
    browseSearch(false);
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
      ++editorTimeRequest;
      const day = +btn.dataset.day;
      const i = editorDays.indexOf(day);
      if (i >= 0) editorDays.splice(i, 1);
      else editorDays.push(day);
      btn.classList.toggle("on", i < 0);
      editorQuickMins = 0; // a repeat is not a span from now either
      syncTimeUi();
    })
  );

  $$(".stepper").forEach((btn) =>
    btn.addEventListener("click", () => {
      const { hour, minute } = readEditorTime();
      const dir = +btn.dataset.dir;
      // The hour steps through all twenty-four even when only twelve are on
      // show, so 11 AM steps to 12 PM the way a clock does. The minute wraps
      // inside its own hour rather than carrying, as the spinner always has.
      if (btn.dataset.step === "hour") setEditorTime((hour + dir + 24) % 24, minute);
      else setEditorTime(hour, (minute + dir + 60) % 60);
    })
  );

  ["al-hour", "al-minute"].forEach((id) => {
    const field = $("#" + id);
    field.addEventListener("input", () => {
      ++editorTimeRequest;
      editorQuickMins = 0; // a typed time is no longer a span from now
      syncTimeUi();
    });
    field.addEventListener("blur", () => {
      const { hour, minute } = readEditorTime();
      setEditorTime(hour, minute);
    });
  });

  $$("#al-meridiem .chip").forEach((btn) =>
    btn.addEventListener("click", () => {
      const { hour, minute } = readEditorTime();
      setEditorTime((hour % 12) + (btn.dataset.ampm === "pm" ? 12 : 0), minute);
    })
  );

  $$("#al-quick .chip").forEach((chip) =>
    chip.addEventListener("click", () => setEditorTimeIn(+chip.dataset.mins))
  );

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

  $("#al-autostop").addEventListener("change", syncAutoSnooze);

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
    rememberAlarmSetup(result.alarm);
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
    // Deliberately not saved. TEST used to commit the edit so the backend
    // could look the alarm up by id, which armed the edited time the moment
    // the button was pressed and left CANCEL with nothing to undo. The alarm
    // goes over the wire instead, and the stored copy is untouched.
    try {
      const payload = await invoke("test_alarm", { alarm: result.alarm });
      onAlarmFire(payload);
    } catch (e) {
      say(String(e), "bad");
    }
  });

  // ringing overlay
  $("#ring-snooze").addEventListener("click", () => snoozeRing());
  $("#ring-dismiss").addEventListener("click", dismissRing);

  // settings
  $$(".settings .row").forEach((row) => {
    row.querySelector(".sw").addEventListener("click", (e) => {
      const sw = e.currentTarget;
      const next = sw.getAttribute("aria-pressed") !== "true";
      // The hour field holds 12- or 24-hour digits according to this very
      // setting, so take the time in the old reading before switching.
      const was = row.dataset.setting === "clock24h" ? readEditorTime() : null;
      sw.setAttribute("aria-pressed", String(next));
      state.settings[row.dataset.setting] = next;
      if (row.dataset.setting === "clock24h") {
        tickClock();
        renderAlarms();
        refreshNextAlarm();
        applyClockMode(was.hour, was.minute);
      }
      saveSettings(row.dataset.setting === "startWithWindows" ? next : null);
    });
  });

  // keyboard
  wireMediaKeys();

  document.addEventListener("keydown", (e) => {
    if (!ringing && $("#power-countdown").open) return;
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement.tagName);
    // Space belongs to whichever control has focus. The exception is a ringing
    // alarm: a field left focused overnight must not swallow the dismiss.
    const onControl = document.activeElement.closest("button, .row");
    if (e.code === "Space" && (ringing || (!typing && !onControl))) {
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
    $("#build-label").textContent = "Radio & alarms · " + version;
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
      say("Settings could not be loaded. See Settings for details.", "bad", true);
    }
  } catch {
    /* nothing worth saying if the backend will not tell us */
  }
}

// ----------------------------------------------------------------- boot ---

async function boot() {
  await tickClock();
  wire();
  setInterval(tickClock, 1000);
  setInterval(refreshNextAlarm, 20000);

  await loadState();

  await listen("alarm-fire", (event) => onAlarmFire(event.payload));
  // Windows can turn autostart off behind our back; the backend says when.
  await listen("settings-updated", () => loadState().catch((e) => say(String(e), "bad")));
  await listen("alarms-updated", async () => {
    try {
      state.alarms = (await invoke("get_state")).alarms;
      renderAlarms();
      refreshNextAlarm();
      refreshPowerStatus();
    } catch (e) {
      say(String(e), "bad");
    }
  });
  await listen("icy-title", (event) => onStreamTitle(event.payload));
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
  say("Ready to listen", "good");

  await listen("sleep-timer-updated", (event) => applySleepSnapshot(event.payload));
  try {
    applySleepSnapshot(await invoke("get_sleep_timer"));
  } catch (e) {
    say("Could not read the sleep timer: " + String(e), "bad", true);
  }
}

boot().catch((e) => {
  document.body.innerHTML =
    '<pre style="padding:30px;color:#ff9c86;font:13px monospace">Aerowave failed to start:\n\n' +
    String(e) +
    "</pre>";
});
