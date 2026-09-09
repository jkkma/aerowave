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
  // Announce it too. This one is never cleared, so the reset to READY is not
  // read out over whatever the user is doing.
  const log = $("#a11y-log");
  if (log) log.textContent = msg;
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
  resolved: null,     // the station's own stream URL, after any playlist hop
  probed: false,      // ...and whether a probe, rather than the station, chose it
  relayed: false,     // is <audio> playing through the local relay?
  streamTitle: false, // has the stream itself named the track? then stop asking
  triedDirect: false, // ...and have we already fallen back off it?
  hls: false,         // is hls.js driving the element instead?
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
  audio.loop = false;
  audio.removeAttribute("src");
  audio.load();
  player.retries = 0;
  player.resolved = null;
  player.probed = false;
  player.relayed = false;
  player.triedDirect = false;
  stopHls();
  markPlaying(false);
  if (!quiet) {
    setOrbArt(null);
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
function relayLoader(Base) {
  return class extends Base {
    load(context, config, callbacks) {
      const upstream = context.url;
      const proxied = Object.assign({}, context, {
        url: hlsBase + "?u=" + b64url(upstream),
      });
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
    try {
      hlsPlayer.destroy();
    } catch { /* already gone */ }
    hlsPlayer = null;
  }
  if (hlsBase) {
    const session = hlsBase.split("/").pop();
    hlsBase = null;
    invoke("hls_close", { session }).catch(() => {});
  }
  player.hls = false;
}

/**
 * Point hls.js at a playlist. Returns false when it could not start, having
 * already said why.
 */
async function startHls(source, url, generation) {
  if (typeof Hls === "undefined" || !Hls.isSupported()) {
    failure("this build cannot play HLS", { fatal: true });
    return false;
  }
  let base = null;
  try {
    base = await invoke("hls_session", { url });
  } catch { /* falls through to the null check */ }
  if (superseded(generation) || player.source !== source) return false;
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
    loader: relayLoader(Hls.DefaultConfig.loader),
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
 * Every station goes through the loopback relay. A media element cannot choose
 * its own request headers, and enough broadcasters decide whether to answer on
 * the strength of them that going direct is the thing that fails - SomaFM
 * answers 403 to the webview's User-Agent and 200 to an ordinary browser's.
 * Rust can say whatever gets served, and resolves playlists, redirects and
 * Shoutcast v1 on the way past. See src-tauri/src/relay.rs.
 *
 * Files are not relayed: they are already playable and there is nothing to
 * negotiate. Nor is anything relayed when the listener would not bind, in
 * which case this hands back the URL untouched and playback is what it was
 * before the relay existed.
 */
async function playable(source, url) {
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
 * overrides the master volume (alarms have their own). `source.meta` is what
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

  showNowPlaying(source.title, source.subtitle || "", source.meta || "");
  setOrbArt(source);
  markPlaying(true);

  setStatus("CONNECTING", "busy");
  let url = source.url;

  let useHls = false;
  if (source.kind === "station" && /\.(pls|m3u|m3u8|asx)(\?|$)/i.test(url)) {
    // A playlist file cannot be handed to <audio> - resolve it first, and
    // find out which of the two players it wants on the way past.
    try {
      const info = await invoke("probe_stream", { url, wantTitle: false });
      url = info.url;
      player.resolved = info.url;
      player.probed = true;
      useHls = !!info.hls;
      if (info.warning) say(info.warning, "bad");
    } catch (e) {
      if (!superseded(generation)) failure(String(e));
      return;
    }
    if (superseded(generation)) return;
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
    const playUrl = await playable(source, url);
    if (superseded(generation)) return;
    player.relayed = playUrl !== url;
    audio.src = playUrl;
  }
  // A ringing alarm plays its one file over and over rather than moving on to
  // another; anything else is heard once and then the `ended` handler decides.
  audio.loop = !!source.loop;
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

  if (source.kind === "station" && !player.hls) startMetadata(source);
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

function startMetadata(source) {
  clearInterval(player.metaTimer);
  if (state.settings.showMetadata === false) return;
  // Plenty of stations send no ICY titles at all. Polling one of those opens a
  // connection a minute, for ever, to learn nothing - so give up after three.
  let titleless = 0;
  const poll = async () => {
    if (player.source !== source) return;
    try {
      const info = await invoke("probe_stream", {
        url: player.resolved || source.url,
        wantTitle: true,
        skipResolve: player.probed,
      });
      if (player.source !== source) return;
      if (info.title) {
        titleless = 0;
        // Unless the stream has already said, on the connection that is
        // actually playing. This poll was in flight before that arrived.
        if (!player.streamTitle) $("#np-track").textContent = info.title;
      } else if (++titleless >= 3) {
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
  poll();
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
      } catch { /* stay with the original */ }
    }
    const upstream = player.resolved || source.url;
    if (player.hls) {
      // Start the HLS player over rather than assigning a source: the element
      // plays a MediaSource, and pointing it at the .m3u8 would give it a
      // playlist it cannot read.
      stopHls();
      if (!(await startHls(source, upstream, generation))) return;
    } else {
      const playUrl = player.triedDirect ? upstream : await playable(source, upstream);
      if (superseded(generation) || player.source !== source) return;
      player.relayed = playUrl !== upstream;
      audio.src = playUrl;
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
 * or the relay cannot make sense of a source the media element could have
 * handled by itself (an HLS playlist, which must not be relayed), then going
 * direct is strictly better than going nowhere.
 */
function fallBackToDirect(source, generation) {
  player.triedDirect = true;
  player.relayed = false;
  const upstream = player.resolved || source.url;
  setStatus("RETRYING DIRECT", "busy");
  audio.src = upstream;
  audio.play().catch((e) => {
    if (superseded(generation) || isAbort(e)) return;
    failure(String(e && e.message ? e.message : e));
  });
}

audio.addEventListener("playing", () => {
  if (!player.source) return;
  player.retries = 0;
  setStatus(player.source.kind === "folder" ? "PLAYING FILE" : "ON AIR", "on");
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
    failure("this stream is not one the player can decode", { fatal: true });
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

async function playRandomFromFolder(folder, opts = {}) {
  if (!folder) {
    say("choose a folder first", "bad");
    return;
  }
  try {
    const pick = await invoke("random_track", { path: folder });
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
/** The directory's own name for a format, and the bitrate to match exactly. */
let browseCodec = "";
let browseBitrate = 0;
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

/** Same stream by any reasonable reading, so a station is not kept twice. */
const sameStream = (a, b) => {
  const tidy = (u) => (u || "").trim().replace(/\/+$/, "").toLowerCase();
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
  const narrowed = !!browseCodec || browseBitrate > 0;

  if (browseCountry || narrowed) {
    jobs.push(
      narrow("#browse-tag", facetQuery({ countryCode: browseCountry }), (f) => f.tags, asTag, "genre")
    );
  } else if (browseTags) {
    setBrowseOptions("#browse-tag", browseTags, asTag, browseTag, browseTagLabel);
  }

  if (browseTag || narrowed) {
    jobs.push(
      narrow("#browse-country", facetQuery({ tag: browseTag }), (f) => f.countries, asCountry, "country")
    );
  } else if (browseCountries) {
    setBrowseOptions("#browse-country", browseCountries, asCountry, browseCountry, browseCountryLabel);
  }

  // The fixed lists are only worth counting once something else is narrowing
  // them. With nothing set the answer would be the whole directory, and a
  // tally is megabytes - the browse tab is meant to cost nothing until asked.
  const place = { countryCode: browseCountry, tag: browseTag };
  const anywhereElse = !!browseCountry || !!browseTag;
  if (anywhereElse || browseBitrate > 0) {
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
 * Format and bitrate are fixed lists - five formats and six bitrates, chosen
 * because they are what the player can open - so they are annotated rather
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
function resetFixed(selector) {
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
  });
  if (st.hls) say("HLS — this one plays through hls.js");
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
    li.textContent = browseBusy ? "Searching…" : "Search the directory, or pick a tag.";
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
      if (e.key === "Enter" || e.code === "Space") {
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
  more.textContent = browseBusy ? "LOADING…" : "MORE";
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
    // Same again: a ring that has ended must not have its card rewritten.
    if (stale()) return false;
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
  ringing = payload;
  // Otherwise a sleep timer set before bed calls stopPlayback() mid-ring and
  // leaves the overlay up over silence.
  setSleep(0);
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
 * answered should not be a click - and then stop, or hand it to a snooze.
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

/** The fade is over, or there was nothing to fade: snooze, or stop. */
function endGiveUp() {
  if (!ringing) return;
  clearTimeout(autoStopTimer);
  clearInterval(player.fadeTimer);
  autoStopTimer = player.fadeTimer = null;
  const mins = ringing.autoStopMins;
  if (autoSnoozeDue()) {
    autoSnoozed.set(ringing.alarmId, (autoSnoozed.get(ringing.alarmId) || 0) + 1);
    snoozeRing("gave up after " + mins + " min");
    return;
  }
  say("alarm gave up after " + mins + " minutes");
  dismissRing();
}

function closeRingUi() {
  clearInterval(ringWatchdog);
  clearTimeout(autoStopTimer);
  ringWatchdog = autoStopTimer = null;
  givingUp = false;
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

/** `why` is set when the alarm snoozed itself rather than being asked to. */
async function snoozeRing(why) {
  if (!ringing) return;
  const { alarmId, snoozeMins } = ringing;
  closeRingUi();
  stopPlayback();
  await invoke("snooze_alarm", { alarmId, minutes: snoozeMins });
  say((why ? why + " - " : "") + "snoozed for " + snoozeMins + " minutes", "good");
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
    // Reachable without a mouse: the row is the play control.
    li.tabIndex = 0;
    li.setAttribute("role", "button");
    li.setAttribute("aria-label", `Play ${station.name}`);
    if (player.source && player.source.stationId === station.id) {
      li.setAttribute("aria-current", "true");
    }
    li.addEventListener("click", () => playStation(station));
    li.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.code === "Space") {
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
      ? "One file is picked at random from the folder each time it rings, and repeats until the alarm is answered."
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
  editingAlarm = alarm || null;
  const now = new Date();
  // A new alarm opens on the last one that was saved. Somebody who wakes to
  // the same station, fading in over the same twenty seconds, should not have
  // to say so again - but the time and the label are theirs to fill in, so
  // those two start empty however the last one was set.
  const last = state.settings.alarmDefaults || {};
  const base = alarm || {
    hour: (now.getHours() + 1) % 24,
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
      autoSnoozes: +$("#al-autosnooze").value,
    },
  };
}

// --------------------------------------------------------- sleep timer ---

let sleepUntil = 0;
let sleepFading = false;
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

function setSleep(mins) {
  sleepUntil = mins > 0 ? Date.now() + mins * 60000 : 0;
  // Changing the timer - or turning it off - undoes a fade in progress.
  endSleepFade();
  $$("#sleep-chips .chip").forEach((c) => c.classList.toggle("on", +c.dataset.mins === mins));
  $("#sleep-left").textContent = "";
  if (mins > 0) say("sleep timer set for " + mins + " minutes", "good");
}

setInterval(() => {
  if (!sleepUntil) return;
  const left = sleepUntil - Date.now();
  if (left <= 0) {
    sleepUntil = 0;
    sleepFading = false;
    $$("#sleep-chips .chip").forEach((c) => c.classList.toggle("on", c.dataset.mins === "0"));
    $("#sleep-left").textContent = "";
    // Covers the timer set *while* an alarm rings, which onAlarmFire cannot:
    // let it lapse, but never take the alarm's audio with it.
    if (ringing) return;
    stopPlayback();
    say("sleep timer — goodnight");
    return;
  }
  // Fade out over whatever is actually left rather than a fixed twenty
  // seconds, so a tick that arrives late - the window was hidden, and
  // WebView2 throttles timers there - still lands on silence at zero.
  if (!sleepFading && !ringing && player.playing && left <= SLEEP_FADE_SECS * 1000) {
    sleepFading = true;
    fadeOut(Math.max(1, left / 1000));
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

      // An .m3u8 will never satisfy the decode check - the media element is
      // not what plays it. Saying so beats reporting a working station as
      // broken because the wrong player was asked.
      if (info.hls) {
        note.className = "editor-note good";
        note.textContent = "✓ HLS — plays through hls.js — " + (bits || info.url);
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
    browseBitrate = +e.target.value || 0;
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
  // Windows can turn autostart off behind our back; the backend says when.
  await listen("settings-updated", () => loadState());
  await listen("alarms-updated", async () => {
    state.alarms = (await invoke("get_state")).alarms;
    renderAlarms();
    refreshNextAlarm();
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
  say("aerowave online", "good");
}

boot().catch((e) => {
  document.body.innerHTML =
    '<pre style="padding:30px;color:#ff9c86;font:13px monospace">Aerowave failed to start:\n\n' +
    String(e) +
    "</pre>";
});
