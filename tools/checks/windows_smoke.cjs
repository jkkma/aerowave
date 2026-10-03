/* Native WebView2 playback check with an isolated extracted portable profile. */
const { spawn } = require('node:child_process');
const { dirname } = require('node:path');
const assert = require('node:assert/strict');
const net = require('node:net');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function main() {
  const [exe, version] = process.argv.slice(2);
  const listener = net.createServer();
  await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  const child = spawn(exe, [], {
    cwd: dirname(exe), stdio: 'ignore',
    env: { ...process.env, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS:
      `--remote-debugging-port=${port} --autoplay-policy=no-user-gesture-required` },
  });
  let socket;
  try {
    let page;
    for (let attempt = 0; attempt < 60; attempt++) {
      if (child.exitCode !== null) throw new Error(`Native application exited: ${child.exitCode}`);
      try {
        const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
        page = pages.find(p => p.type === 'page' && /tauri\.localhost/.test(p.url));
        if (page) break;
      } catch {}
      await delay(500);
    }
    assert(page, 'Native WebView2 page did not appear');
    socket = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', reject, { once: true });
    });
    let id = 0;
    const pending = new Map();
    socket.addEventListener('message', event => {
      const reply = JSON.parse(event.data);
      if (pending.has(reply.id)) { pending.get(reply.id)(reply); pending.delete(reply.id); }
    });
    async function evaluate(expression) {
      const requestId = ++id;
      const response = new Promise(resolve => pending.set(requestId, resolve));
      socket.send(JSON.stringify({ id: requestId, method: 'Runtime.evaluate',
        params: { expression, awaitPromise: true, returnByValue: true } }));
      const reply = await Promise.race([response, delay(15000).then(() => { throw new Error('WebView2 evaluation timed out'); })]);
      if (reply.error || reply.result.exceptionDetails) throw new Error(JSON.stringify(reply));
      return reply.result.result.value;
    }
    let initialized = false;
    for (let attempt = 0; attempt < 40; attempt++) {
      initialized = await evaluate('document.querySelector("#status-msg")?.textContent === "Ready to listen" && !!document.querySelector("#app-content") && typeof play === "function" && configLoadError === null');
      if (initialized) break;
      await delay(250);
    }
    assert(initialized, 'Frontend did not initialize');
    const actualVersion = await evaluate('window.__TAURI__.app.getVersion()');
    assert.equal(actualVersion, version);
    const location = await evaluate('invoke("config_location")');
    assert(JSON.stringify(location).toLowerCase().includes('windows-smoke'), 'Smoke check must use its portable profile');
    await evaluate('play({kind:"station",url:"https://icecast.radiofrance.fr/fip-midfi.mp3",title:"Release playback check",stationId:"release-smoke"},{volume:0.15}); true');
    let first, last, advancing = false;
    for (let attempt = 0; attempt < 80; attempt++) {
      last = await evaluate('({readyState:audio.readyState,time:audio.currentTime,paused:audio.paused,error:audio.error?.code||null})');
      if (last.readyState >= 3 && !last.paused && !last.error) {
        if (!first) first = last;
        if (last.time > first.time + 1) { advancing = true; break; }
      }
      await delay(500);
    }
    assert(advancing, 'Native audio never reached canplay with an advancing playhead');
    await evaluate('stopPlayback(); true');
    console.log(JSON.stringify({ version: actualVersion, portable: true, readyState: last.readyState,
      playbackAdvancedSeconds: last.time - first.time, physicalSpeakerAudibility: 'not verified' }));
  } finally {
    socket?.close();
    child.kill();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
