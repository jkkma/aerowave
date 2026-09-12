const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

class Element {
  constructor(tag = "div") {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.dataset = {};
    this.style = { setProperty() {} };
    this.attributes = new Map();
    this.handlers = new Map();
    this.className = "";
    this.textContent = "";
    this.value = "";
    this.disabled = false;
    this.classList = {
      contains: (name) => this.className.split(/\s+/).includes(name),
      add: (...names) => names.forEach((name) => this.classList.toggle(name, true)),
      remove: (...names) => names.forEach((name) => this.classList.toggle(name, false)),
      toggle: (name, force) => {
        const classes = new Set(this.className.split(/\s+/).filter(Boolean));
        const on = force ?? !classes.has(name);
        if (on) classes.add(name); else classes.delete(name);
        this.className = [...classes].join(" ");
        return on;
      },
    };
  }
  set innerHTML(value) { this.children = []; this._innerHTML = value; }
  get innerHTML() { return this._innerHTML || ""; }
  get options() { return this.children; }
  get selectedOptions() { return this.children.filter((child) => child.value === this.value); }
  append(...items) {
    for (const item of items) {
      if (typeof item !== "object") continue;
      item.parentElement = this;
      this.children.push(item);
    }
  }
  appendChild(item) { this.append(item); return item; }
  remove(index) { this.children.splice(index, 1); }
  replaceChildren(...items) { this.children = []; this.append(...items); }
  setAttribute(key, value) { this.attributes.set(key, String(value)); }
  getAttribute(key) { return this.attributes.get(key) ?? null; }
  removeAttribute(key) { this.attributes.delete(key); if (key === "src") this.src = ""; }
  addEventListener(type, handler) {
    if (!this.handlers.has(type)) this.handlers.set(type, []);
    this.handlers.get(type).push(handler);
  }
  removeEventListener(type, handler) {
    this.handlers.set(type, (this.handlers.get(type) || []).filter((fn) => fn !== handler));
  }
  async dispatch(type, options = {}) {
    const event = {
      type, target: this, currentTarget: this, defaultPrevented: false, stopped: false,
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this.stopped = true; },
      ...options,
    };
    const pending = [];
    for (let node = this; node; node = event.stopped ? null : node.parentElement) {
      event.currentTarget = node;
      for (const handler of node.handlers.get(type) || []) pending.push(handler(event));
    }
    await Promise.all(pending);
    return event;
  }
  matches(selector) {
    if (selector.startsWith(".")) return selector.slice(1).split(".").every((c) => this.classList.contains(c));
    return this.tagName.toLowerCase() === selector.toLowerCase();
  }
  querySelectorAll(selector) {
    const choices = selector.split(",").map((s) => s.trim());
    return this.children.flatMap((child) => [
      ...(choices.some((s) => child.matches(s)) ? [child] : []), ...child.querySelectorAll(selector),
    ]);
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  closest(selector) {
    for (let node = this; node; node = node.parentElement) {
      if (selector.split(",").some((s) => node.matches(s.trim()))) return node;
    }
    return null;
  }
  focus() { if (this.ownerDocument) this.ownerDocument.activeElement = this; }
  showModal() { this.open = true; }
  close() { this.open = false; }
  select() {}
  scrollIntoView() {}
}

function createHarness(options = {}) {
  const elements = new Map();
  const queries = new Map();
  const calls = [];
  const audios = [];
  const hlsInstances = [];
  const timers = new Map();
  const listeners = new Map();
  let sleepSnapshot = { revision: 0, timer: null, outcome: null, error: null };
  let timerId = 0;
  const el = (selector) => {
    if (!elements.has(selector)) {
      const element = new Element();
      element.ownerDocument = document;
      element.isConnected = true;
      elements.set(selector, element);
    }
    return elements.get(selector);
  };
  const document = new Element("document");
  document.body = new Element("body");
  document.activeElement = document.body;
  document.createElement = (tag) => Object.assign(new Element(tag), { ownerDocument: document });
  document.querySelector = (selector) => selector.startsWith("#") ? el(selector) : (queries.get(selector)?.[0] || null);
  document.querySelectorAll = (selector) => queries.get(selector) || [];
  queries.set("[data-setting='wakeForAlarms'] .sw", [el("#wake-switch")]);
  class Audio extends Element {
    constructor() {
      super("audio");
      this.src = "";
      this.paused = true;
      this.readyState = 0;
      this.currentTime = 0;
      this.volume = 1;
      this.error = null;
      this.ended = false;
      audios.push(this);
    }
    load() { options.onLoad?.(this); }
    play() { this.paused = false; return Promise.resolve(options.onPlay?.(this)); }
    pause() { this.paused = true; options.onPause?.(this); }
  }
  class Loader {
    load(context, config, callbacks) { this.request = { context, config, callbacks }; }
  }
  class Hls {
    static isSupported() { return true; }
    static DefaultConfig = { loader: Loader };
    static Events = Object.fromEntries(["ERROR", "FRAG_PARSING_METADATA", "MANIFEST_PARSED", "LEVEL_SWITCHED", "BUFFER_CODECS"].map((key) => [key, key]));
    constructor(config) { this.config = config; this.handlers = new Map(); hlsInstances.push(this); }
    on(event, handler) { this.handlers.set(event, handler); }
    emit(event, data) { this.handlers.get(event)?.(event, data); }
    loadSource(url) { this.url = url; }
    attachMedia(media) { this.media = media; options.onAttach?.(this); }
    destroy() { this.destroyed = true; options.onDestroy?.(this); }
  }
  const invoke = async (command, args) => {
    calls.push({ command, args });
    if (options.invoke) {
      const result = options.invoke(command, args);
      if (result !== undefined) return result;
    }
    if (command === "relay_url") return "http://127.0.0.1/relay/" + encodeURIComponent(args.url);
    if (command === "probe_stream") return { url: args.url, hls: false };
    if (command === "hls_session") return "http://hls.localhost/session";
    if (command === "get_sleep_timer") return sleepSnapshot;
    if (command === "cancel_sleep_timer" || command === "set_sleep_timer") {
      sleepSnapshot = {
        revision: sleepSnapshot.revision + 1,
        timer: command === "set_sleep_timer" ? { ...args, endsAtMs: Date.now() + args.minutes * 60000, executeAtMs: null } : null,
        outcome: command === "cancel_sleep_timer" ? "cancelled" : null,
        error: null,
      };
      return sleepSnapshot;
    }
    if (command === "power_status") return {
      sleepSupported: true, shutdownSupported: true, wakeSupported: true,
      wakeAllowed: true, onBattery: false, message: "Wake timers allowed.", armedAtMs: null, error: null,
    };
    return null;
  };
  const setTimer = (fn, ms, interval) => {
    const id = ++timerId;
    timers.set(id, { fn, ms, interval });
    return id;
  };
  const context = vm.createContext({
    document, Audio, Hls, URL, TextEncoder, TextDecoder, AbortController, console,
    btoa: (str) => Buffer.from(str, "binary").toString("base64"),
    navigator: options.navigator || {},
    window: { __TAURI__: {
      core: { invoke, convertFileSrc: (file) => "asset://" + file },
      event: { listen: async (name, handler) => {
        if (!listeners.has(name)) listeners.set(name, []);
        listeners.get(name).push(handler);
      } },
      window: { getCurrentWindow: () => ({}) },
    } },
    setTimeout: (fn, ms) => setTimer(fn, ms, false), clearTimeout: (id) => timers.delete(id),
    setInterval: (fn, ms) => setTimer(fn, ms, true), clearInterval: (id) => timers.delete(id),
  });
  const source = fs.readFileSync(path.join(__dirname, "../../src/app.js"), "utf8");
  const bootAt = source.lastIndexOf("\nboot().catch(");
  if (bootAt < 0) throw new Error("Could not locate application boot entry point");
  vm.runInContext(source.slice(0, bootAt), context, { filename: "app.js" });
  const evaluate = (code) => vm.runInContext(code, context);
  return {
    context, evaluate, el, elements, queries, document, calls, audios, hlsInstances, timers, listeners, Element,
    async emit(name, payload) {
      await Promise.all((listeners.get(name) || []).map(handler => handler({ payload })));
    },
    async fireTimer(id) {
      const timer = timers.get(id);
      if (!timer) throw new Error("No timer " + id);
      if (!timer.interval) timers.delete(id);
      await timer.fn();
    },
  };
}

const flush = () => new Promise((resolve) => setImmediate(resolve));
module.exports = { createHarness, deferred, flush, Element };
