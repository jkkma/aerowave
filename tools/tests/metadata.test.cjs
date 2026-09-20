const test = require("node:test");
const assert = require("node:assert/strict");
const { createHarness, deferred, flush } = require("./frontend-harness.cjs");

const synchsafe = n => [(n >>> 21) & 127, (n >>> 14) & 127, (n >>> 7) & 127, n & 127];
const bigEndian = n => [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255];
const escape = bytes => Uint8Array.from([...bytes].flatMap((b, i) =>
  b === 255 && (bytes[i + 1] === 0 || bytes[i + 1] >= 224) ? [255, 0] : [b]));
function frame(id, body, version = 4, flags = 0) {
  return Buffer.from([...Buffer.from(id), ...(version === 4 ? synchsafe(body.length) : bigEndian(body.length)), 0, flags, ...body]);
}
function tag(frames, version = 4, flags = 0) {
  const body = Buffer.concat(frames.map(b => Buffer.from(b)));
  return Uint8Array.from([...Buffer.from("ID3"), version, 0, flags, ...synchsafe(body.length), ...body]);
}
const textFrame = (title, version = 4) => frame("TIT2", [3, ...Buffer.from(title)], version);
function parse(bytes) {
  const h = createHarness();
  h.context.bytes = bytes;
  return JSON.parse(JSON.stringify(h.evaluate("readId3(bytes)")));
}

for (const [name, body, expected] of [
  ["UTF-8", [3, ...Buffer.from("Björk — Jóga")], "Björk — Jóga"],
  ["Latin-1", [0, 66, 106, 246, 114, 107], "Björk"],
  ["UTF-16LE BOM", [1, 255, 254, 65, 0, 66, 0], "AB"],
  ["UTF-16BE BOM", [1, 254, 255, 0, 65, 0, 66], "AB"],
  ["UTF-16BE", [2, 0, 65, 0, 66], "AB"],
]) {
  test(`ID3 titles honor ${name}`, () => assert.equal(parse(tag([frame("TIT2", body)])).TIT2, expected));
}

test("ID3v2.3 big-endian frame lengths preserve following artist frames", () => {
  assert.deepEqual(parse(tag([textFrame("T".repeat(160), 3), frame("TPE1", [3, 65], 3)], 3)),
    { TIT2: "T".repeat(160), TPE1: "A" });
});

test("ID3v2.3 tag escaping is removed before walking frame sizes", () => {
  const frames = Buffer.concat([frame("TIT2", [1, 255, 254, 65, 0], 3), frame("TPE1", [0, 66], 3)]);
  assert.deepEqual(parse(tag([escape(frames)], 3, 128)), { TIT2: "A", TPE1: "B" });
});

test("ID3v2.4 handles frame escaping, grouping and a data-length indicator", () => {
  const body = [1, 255, 254, 65, 0, 66, 0];
  const payload = escape(Uint8Array.from([17, ...synchsafe(body.length), ...body]));
  assert.deepEqual(parse(tag([frame("TIT2", payload, 4, 0x43)])), { TIT2: "AB" });
});

test("ID3 skips compressed/encrypted frames instead of displaying their bytes", () => {
  for (const [version, flags] of [[3, 0x80], [3, 0x40], [4, 8], [4, 4]]) {
    assert.deepEqual(parse(tag([frame("TIT2", [3, 65], version, flags), frame("TPE1", [3, 66], version)], version)), { TPE1: "B" });
  }
});

test("ID3 rejects truncated tags, invalid synchsafe sizes and oversized extended headers", () => {
  const complete = tag([textFrame("Title")]);
  assert.deepEqual(parse(complete.subarray(0, complete.length - 1)), {});
  complete[6] = 255;
  assert.deepEqual(parse(complete), {});
  assert.deepEqual(parse(tag([Buffer.from(synchsafe(999)), textFrame("Title")], 4, 0x40)), {});
});

test("ID3 supports v2.2 text frames and consecutive tags", () => {
  const v22 = tag([Buffer.from([84, 84, 50, 0, 0, 3, 0, 65, 66])], 2);
  const artist = tag([frame("TPE1", [3, 67])]);
  assert.deepEqual(parse(Uint8Array.from([...v22, ...artist])), { TIT2: "AB", TPE1: "C" });
});

function mediaHarness(options = {}) {
  const h = createHarness({ navigator: { mediaSession: {} }, ...options });
  h.context.window.MediaMetadata = class { constructor(value) { Object.assign(this, value); } };
  return h;
}

test("relay song changes and explicit clears update both page and system media metadata", async () => {
  const h = mediaHarness();
  await h.evaluate("play({kind:'station',url:'https://radio.test/live',title:'Station',subtitle:'…'})");
  h.evaluate("onStreamTitle({url:'https://radio.test/live',title:'Artist - Song'})");
  assert.equal(h.el("#np-track").textContent, "Artist - Song");
  assert.equal(h.context.navigator.mediaSession.metadata.title, "Artist - Song");
  h.evaluate("onStreamTitle({url:'https://radio.test/live',title:''})");
  assert.equal(h.el("#np-track").textContent, "");
  assert.equal(h.context.navigator.mediaSession.metadata.title, "Station");
  h.evaluate("onStreamTitle({url:'https://radio.test/old',title:'Stale'})");
  assert.equal(h.context.navigator.mediaSession.metadata.title, "Station");
  h.evaluate("stopPlayback(false)");
  assert.equal(h.context.navigator.mediaSession.metadata, null);
});

test("HLS titles follow playback time, honor the preference, and clear a previous title", async () => {
  const h = mediaHarness();
  await h.evaluate("play({kind:'station',url:'https://radio.test/live.m3u8',title:'HLS',subtitle:'…',hls:true})");
  h.hlsInstances[0].emit("FRAG_PARSING_METADATA", { samples: [{ pts: 10, data: tag([textFrame("Song")]) }] });
  await h.audios[0].dispatch("timeupdate");
  assert.equal(h.el("#np-track").textContent, "…");
  h.audios[0].currentTime = 10;
  h.evaluate("state.settings.showMetadata = false; refreshMetadataPreference()");
  await h.audios[0].dispatch("timeupdate");
  assert.equal(h.el("#np-track").textContent, "https://radio.test/live.m3u8");
  assert.equal(h.context.navigator.mediaSession.metadata.title, "HLS");
  h.evaluate("state.settings.showMetadata = true; refreshMetadataPreference()");
  assert.equal(h.el("#np-track").textContent, "Song");
  h.hlsInstances[0].emit("FRAG_PARSING_METADATA", { samples: [{ pts: 11, data: tag([textFrame("")]) }] });
  h.audios[0].currentTime = 11;
  await h.audios[0].dispatch("timeupdate");
  assert.equal(h.el("#np-track").textContent, "");
  assert.equal(h.context.navigator.mediaSession.metadata.title, "HLS");
});

test("late metadata polling cannot replace a relay title or a newer station", async () => {
  const probe = deferred();
  const h = mediaHarness({ invoke: (command, args) => command === "probe_stream" && args.wantTitle ? probe.promise : undefined });
  await h.evaluate("play({kind:'station',url:'https://radio.test/live',title:'Station'})");
  const poll = h.fireTimer(h.evaluate("player.metaTimer"));
  h.evaluate("onStreamTitle({url:'https://radio.test/live',title:'Current'})");
  probe.resolve({ title: "Older" });
  await poll;
  assert.equal(h.el("#np-track").textContent, "Current");
});

test("a relay title received during buffering prevents redundant periodic polling", async () => {
  const h = mediaHarness();
  await h.evaluate("play({kind:'station',url:'https://radio.test/live',title:'Station'})");
  h.evaluate("onStreamTitle({url:'https://radio.test/live',title:'Current'}); startMetadata(player.source, {bitrate:'128'})");
  assert.equal(h.evaluate("player.metaTimer"), null);
  assert.equal(h.el("#np-track").textContent, "Current");
  assert.equal(h.el("#np-meta").textContent, "128 kbps");
});

const png = "data:image/png;base64,aW1hZ2U=";
function artHarness(invoke, options = {}) {
  const h = mediaHarness({ invoke, ...options });
  h.context.Image = class {
    naturalWidth = 640;
    naturalHeight = 320;
    async decode() { if (this.src.includes("Y29ycnVwdA==")) throw new Error("Corrupt image"); }
  };
  const create = h.document.createElement;
  h.document.createElement = tag => tag === "canvas" ? {
    getContext: () => ({ drawImage() {} }), toDataURL: () => png,
  } : create(tag);
  h.evaluate("state.stations=[{id:'saved',name:'Station',url:'https://radio.test/live',logo:'https://art.test/dead.png'}]; player.source={kind:'station',stationId:'saved',title:'Station',url:'https://radio.test/live',logo:'https://art.test/dead.png'}; showNowPlaying('Station','', ''); player.resolved='https://radio.test/resolved'");
  return h;
}

test("dead saved artwork falls back using stream identity and saves only decoded artwork", async () => {
  const h = artHarness((command) => {
    if (command === "station_logo") throw new Error("HTTP 404");
    if (command === "station_art") return { url: "https://art.test/good.png", picture: png };
  });
  await h.evaluate("setOrbArt(player.source)");
  const args = h.calls.find(c => c.command === "station_art").args;
  assert.equal(args.url, "https://radio.test/live");
  assert.equal(args.resolvedUrl, "https://radio.test/resolved");
  assert.deepEqual([...args.excludedUrls], ["https://art.test/dead.png"]);
  assert.equal(h.evaluate("state.stations[0].logo"), "https://art.test/good.png");
  assert.equal(h.context.navigator.mediaSession.metadata.artwork[0].src, png);
});

test("a corrupt candidate is excluded before trying the next matching logo", async () => {
  let attempt = 0;
  const h = artHarness(command => {
    if (command === "station_logo") return "data:image/png;base64,Y29ycnVwdA==";
    if (command === "station_art") return ++attempt === 1
      ? { url: "https://art.test/corrupt.png", picture: "data:image/png;base64,Y29ycnVwdA==" }
      : { url: "https://art.test/good.png", picture: png };
  });
  await h.evaluate("setOrbArt(player.source)");
  assert.equal(h.evaluate("state.stations[0].logo"), "https://art.test/good.png");
  assert.equal(attempt, 2);
  const last = h.calls.filter(c => c.command === "station_art").at(-1);
  assert.deepEqual([...last.args.excludedUrls], ["https://art.test/dead.png", "https://art.test/corrupt.png"]);
});

test("the current shuffle track supplies orb and system media artwork", async () => {
  const h = artHarness((command, args) => {
    if (command === "random_track") return { path: "/music/song.flac", name: "Song.flac", total: 3 };
    if (command === "track_artwork") return args.path === "/music/song.flac" ? png : null;
  });
  h.context.window.aerowaveOrb = { ok: true, setImage: picture => { h.lastArt = picture; } };
  await h.evaluate("playRandomFromFolder('/music')");
  await flush();
  await flush();
  assert.equal(h.calls.find(c => c.command === "track_artwork").args.path, "/music/song.flac");
  assert.equal(h.evaluate("player.source.path"), "/music/song.flac");
  assert.equal(h.evaluate("player.artwork"), png);
  assert.equal(h.lastArt, png);
  assert.equal(h.context.navigator.mediaSession.metadata.artwork[0].src, png);
});

test("track artwork extraction does not hold up local playback", async () => {
  const artwork = deferred();
  const h = artHarness(command => command === "track_artwork" ? artwork.promise : undefined);
  let finished = false;
  const playback = h.evaluate("play({kind:'folder',path:'/music/slow.flac',url:'asset://slow.flac',title:'Slow'})")
    .then(() => { finished = true; });
  await flush();
  await flush();
  assert.equal(finished, true);
  assert.equal(h.audios[0].paused, false);
  assert.equal(h.calls.some(c => c.command === "track_artwork"), true);
  artwork.resolve(null);
  await playback;
});

for (const replacement of ["track", "stop", "station"]) {
  test(`late track artwork cannot replace a newer ${replacement}`, async () => {
    const oldArtwork = deferred();
    const h = artHarness((command, args) => {
      if (command === "track_artwork" && args.path === "/music/old.flac") return oldArtwork.promise;
      if (command === "track_artwork") return null;
    });
    h.evaluate("player.source={kind:'folder',path:'/music/old.flac',title:'Old'}");
    const pending = h.evaluate("setOrbArt(player.source)");
    if (replacement === "track") {
      await h.evaluate("player.source={kind:'folder',path:'/music/new.flac',title:'New'}; setOrbArt(player.source)");
    } else if (replacement === "stop") {
      h.evaluate("stopPlayback(true)");
    } else {
      h.evaluate("player.source={kind:'station',url:'https://radio.test/new',title:'New'}; setOrbArt(player.source); showOrbArt(player.source,'station-cover')");
    }
    oldArtwork.resolve(png);
    await pending;
    await flush();
    assert.equal(h.evaluate("player.artwork"), replacement === "station" ? "station-cover" : null);
  });
}

test("a track switch during image decoding discards the decoded result", async () => {
  const decode = deferred();
  let decodeStarted = false;
  const h = artHarness((command, args) => command === "track_artwork" && args.path === "/music/old.flac" ? png : null);
  h.context.Image = class {
    naturalWidth = 640;
    naturalHeight = 320;
    decode() { decodeStarted = true; return decode.promise; }
  };
  h.evaluate("player.source={kind:'folder',path:'/music/old.flac',title:'Old'}");
  const pending = h.evaluate("setOrbArt(player.source)");
  await flush();
  assert.equal(decodeStarted, true);
  await h.evaluate("player.source={kind:'folder',path:'/music/new.flac',title:'New'}; setOrbArt(player.source)");
  decode.resolve();
  await pending;
  assert.equal(h.evaluate("player.artwork"), null);
  assert.equal(h.evaluate("trackArt.size"), 0);
});

for (const [name, embedded] of [["missing", null], ["corrupt", "data:image/png;base64,Y29ycnVwdA=="]]) {
  test(`${name} embedded artwork clears the previous cover and media metadata`, async () => {
    const h = artHarness(command => command === "track_artwork" ? embedded : undefined);
    h.context.window.aerowaveOrb = { ok: true, setImage: picture => { h.lastArt = picture; } };
    h.evaluate(`showOrbArt(player.source, ${JSON.stringify(png)}); player.source={kind:'folder',path:'/music/plain.mp3',title:'Plain'}`);
    await h.evaluate("setOrbArt(player.source)");
    assert.equal(h.evaluate("player.artwork"), null);
    assert.equal(h.lastArt, null);
    assert.equal(h.context.navigator.mediaSession.metadata.artwork.length, 0);
  });
}

test("track art accepts the backend limit without raising the station logo limit", async () => {
  const large = "data:image/png;base64," + "A".repeat(710000);
  const h = artHarness(command => command === "track_artwork" ? large : undefined);
  h.context.large = large;
  await assert.rejects(h.evaluate("decodedOrbArt(large)"), /Invalid station artwork/);
  h.evaluate("player.source={kind:'folder',path:'/music/cover.flac',title:'Cover'}");
  await h.evaluate("setOrbArt(player.source)");
  assert.equal(h.evaluate("player.artwork"), png);
});

test("decoded track artwork cache stays bounded", async () => {
  const h = artHarness(command => command === "track_artwork" ? png : undefined);
  for (let i = 0; i < 9; i++) {
    h.context.trackIndex = i;
    await h.evaluate("player.source={kind:'folder',path:`/music/${trackIndex}.flac`,title:String(trackIndex)}; setOrbArt(player.source)");
  }
  assert.equal(h.evaluate("trackArt.size"), 8);
  assert.equal(h.evaluate("trackArt.has(JSON.stringify(['folder','/music/0.flac']))"), false);
});

test("Android content URI tracks do not call the desktop artwork extractor", async () => {
  const h = artHarness(command => {
    if (command === "track_artwork") throw new Error("desktop command must not run");
  }, { navigator: { userAgent: "Android" } });
  h.evaluate(`showOrbArt(player.source, ${JSON.stringify(png)}); player.source={kind:'folder',path:'content://provider/song',title:'Song'}`);
  await h.evaluate("setOrbArt(player.source)");
  assert.equal(h.calls.some(c => c.command === "track_artwork"), false);
  assert.equal(h.evaluate("player.artwork"), null);
});

test("temporary art failure retries while playing and can recover", async () => {
  let attempt = 0;
  const h = artHarness(command => {
    if (command === "station_art") {
      if (++attempt === 1) throw new Error("temporary outage");
      return { url: "https://art.test/good.png", picture: png };
    }
  });
  h.evaluate("player.source.logo=''; state.stations[0].logo=''");
  await h.evaluate("setOrbArt(player.source)");
  const retry = [...h.timers].find(([, timer]) => timer.ms === 15000)[0];
  await h.fireTimer(retry);
  await flush();
  assert.equal(attempt, 2);
  assert.equal(h.evaluate("player.artwork"), png);
});

test("permanent saved-logo failure is cached while temporary failure is retried", async () => {
  for (const retryable of [false, true]) {
    const h = artHarness(command => {
      if (command === "station_logo") throw { message: "HTTP failure", retryable };
      if (command === "station_art") return null;
    });
    await h.evaluate("setOrbArt(player.source)");
    assert.equal(h.evaluate("orbArtMisses.size"), retryable ? 0 : 1);
    assert.equal([...h.timers.values()].some(timer => timer.ms === 15000), retryable);
  }
});

test("a quiet stop clears artwork and cancels pending artwork retries", async () => {
  const h = artHarness(command => {
    if (command === "station_logo") throw { message: "Unavailable", retryable: true };
    if (command === "station_art") return null;
  });
  h.context.window.aerowaveOrb = { ok: true, setImage: picture => { h.lastArt = picture; } };
  await h.evaluate("setOrbArt(player.source)");
  const retry = h.evaluate("orbArtTimer");
  h.evaluate(`showOrbArt(player.source, ${JSON.stringify(png)}); stopPlayback(true)`);
  assert.equal(h.lastArt, null);
  assert.equal(h.evaluate("player.artwork"), null);
  assert.equal(h.evaluate("orbArtTimer"), null);
  assert.equal(h.timers.has(retry), false);
});

test("Android restoration reuses the saved logo and updates native artwork", async () => {
  const h = artHarness(command => command === "station_logo" ? png : undefined,
    { navigator: { userAgent: "Android" } });
  h.evaluate("restoreAndroidSource({status:'playing',generation:4,stationId:'saved',sourceUrl:'https://radio.test/resolved',title:'Station'})");
  await flush();
  assert.equal(h.evaluate("player.source.logo"), "https://art.test/dead.png");
  assert.equal(h.calls.filter(c => c.command === "station_logo").length, 1);
  assert.equal(h.calls.filter(c => c.command === "station_art").length, 0);
  const call = h.calls.find(c => c.command.endsWith("|update_artwork"));
  assert.equal(call.args.payload.generation, 4);
  assert.equal(call.args.payload.sourceUrl, "https://radio.test/resolved");
  assert.equal(call.args.payload.artworkDataUrl, png);
});

test("a late artwork response cannot overwrite a station edit or a newer source", async () => {
  const art = deferred();
  const h = artHarness(command => command === "station_logo" ? art.promise : undefined);
  const pending = h.evaluate("setOrbArt(player.source)");
  h.evaluate("state.stations[0].url='https://radio.test/new'; state.stations[0].logo=''; player.source={kind:'station',url:'https://radio.test/new',title:'New'}; setOrbArt(null)");
  art.resolve(png);
  await pending;
  assert.equal(h.evaluate("state.stations[0].logo"), "");
  assert.equal(h.evaluate("player.artwork"), null);
});

test("changing a saved stream clears its derived logo and HLS hint", async () => {
  const h = artHarness();
  h.evaluate("wire(); editingStation=state.stations[0]; editingStation.hls=true");
  h.el("#st-name").value = "New station";
  h.el("#st-url").value = "https://radio.test/new";
  await h.el("#station-editor").dispatch("submit");
  assert.equal(h.evaluate("state.stations[0].logo"), "");
  assert.equal(h.evaluate("state.stations[0].hls"), false);
});

test("Android native null titles clear old display text and preference changes are session-scoped", async () => {
  const h = createHarness({ navigator: { userAgent: "Android" } });
  h.evaluate("restoreAndroidSource({status:'playing',generation:4,sourceUrl:'https://radio.test/live',title:'Station',trackTitle:'Old'})");
  h.evaluate("applyAndroidPlaybackState({status:'playing',generation:4,trackTitle:null})");
  assert.equal(h.el("#np-track").textContent, "");
  h.evaluate("state.settings.showMetadata=false; refreshMetadataPreference()");
  const call = h.calls.find(c => c.command.endsWith("|set_metadata_enabled"));
  assert.equal(call.args.payload.generation, 4);
  assert.equal(call.args.payload.enabled, false);
});
