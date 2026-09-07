/* =====================================================================
   The orb: one low-poly ball, turning slowly on a vertical axis.

   A flat-shaded icosahedron with a 64-pixel texture stretched over its
   triangles, rendered into a small buffer and scaled up with hard pixel
   edges. The facets are the point: a smooth sphere cannot show that it is
   turning, and there is nothing else on screen to give the rotation away.

   It turns a little faster while something plays, and hops when clicked.

   Three.js is vendored in src/vendor/ rather than fetched: the app has to
   work offline, and the content security policy only allows scripts from
   its own origin. If WebGL will not start, the CSS orb underneath stays
   visible and nothing else changes.
   ===================================================================== */

import * as THREE from "./vendor/three.module.min.js";

/**
 * The orb is rendered into a small buffer and scaled up with hard pixel
 * edges, the way a PS2 game looks running at native resolution on a modern
 * screen: chunky pixels and stairstepped edges, no antialiasing.
 */
export const RENDER_SIZE = 112;

/** Coarse enough that the rim is still faintly polygonal. */
const SEGMENTS_H = 24;
const SEGMENTS_V = 16;

const IDLE_SPIN = 0.26;   // radians per second
const PLAYING_SPIN = 0.7;

/** The shove a click gives it, tuned to be spent in about half a second. */
const KICK_SPEED = 6.5;
const KICK_DECAY = 8;
const KICK_MS = 500;

const state = {
  renderer: null,
  scene: null,
  camera: null,
  orb: null,
  canvas: null,
  host: null,
  last: 0,
  spin: IDLE_SPIN,
  kick: 0,
};

/**
 * The texture on the core's triangles: 64 pixels square, drawn once, and
 * filtered with nothing at all - so the texels stay square and enormous once
 * they are stretched over a facet. Crystal, of a sort.
 */
function coreTexture() {
  const size = 64;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext("2d");

  ctx.fillStyle = "#2fb4d8";
  ctx.fillRect(0, 0, size, size);

  // blocky crystal grain
  for (let i = 0; i < 190; i++) {
    const w = 1 + Math.floor(Math.random() * 5);
    const h = 1 + Math.floor(Math.random() * 5);
    const x = Math.floor(Math.random() * size);
    const y = Math.floor(Math.random() * size);
    const light = Math.random();
    ctx.fillStyle =
      light > 0.72 ? "#8fe9ff" : light > 0.45 ? "#4ccbe8" : "#1c8fb4";
    ctx.fillRect(x, y, w, h);
  }

  // a few bright chips and some deep veins
  for (let i = 0; i < 26; i++) {
    ctx.fillStyle = Math.random() > 0.45 ? "#dcfaff" : "#10617f";
    ctx.fillRect(
      Math.floor(Math.random() * size),
      Math.floor(Math.random() * size),
      1 + Math.floor(Math.random() * 2),
      1 + Math.floor(Math.random() * 2)
    );
  }

  const texture = new THREE.CanvasTexture(canvas);
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestFilter;
  texture.generateMipmaps = false;
  texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}


/**
 * Lit to match the CSS backdrop: bright sky above, deep water bounce from
 * below, and a key from the upper left.
 */
export function lightScene(scene) {
  scene.add(new THREE.HemisphereLight(0xd8f9ff, 0x0a5a74, 1.15));
  const key = new THREE.DirectionalLight(0xffffff, 1.55);
  key.position.set(-2.2, 2.6, 3);
  scene.add(key);
  const fill = new THREE.DirectionalLight(0x9fe4ff, 0.35);
  fill.position.set(3, -1.4, -1.2);
  scene.add(fill);
  const bounce = new THREE.DirectionalLight(0x64ffe2, 0.5);
  bounce.position.set(0.4, -2.6, 1.2);
  scene.add(bounce);
}

export function buildOrb() {
  const group = new THREE.Group();

  // A low-poly ball with a 64-pixel texture stretched over its triangles,
  // flat shaded so every facet reads on its own. Nothing behind it and
  // nothing over it - no glow, no shell, no halo.
  const orb = new THREE.Mesh(
    new THREE.IcosahedronGeometry(0.98, 1),
    new THREE.MeshPhongMaterial({
      map: coreTexture(),
      color: 0xffffff,
      emissive: 0x0d6a88,
      // Enough sheen to see the light travel across it as it turns, and
      // no more: a white specular on facets this size blows a whole
      // triangle out to flat white. Low shininess spreads it over several
      // facets at partial brightness instead of clipping one.
      specular: 0x7fd2ea,
      shininess: 22,
      flatShading: true,
    })
  );
  group.add(orb);
  group.userData.spinning = [orb];

  // Tipped just enough to see a little of the top while it turns; the spin
  // axis stays vertical so the motion reads as horizontal.
  group.rotation.x = -0.16;
  return group;
}

function frame(now) {
  const dt = Math.min(0.05, (now - state.last) / 1000) || 0;
  state.last = now;

  // Ease towards the target speed instead of jumping when playback starts.
  const target = document.body.classList.contains("playing") ? PLAYING_SPIN : IDLE_SPIN;
  state.spin += (target - state.spin) * Math.min(1, dt * 1.5);

  // A click gives it a shove that bleeds off exponentially.
  state.kick *= Math.exp(-KICK_DECAY * dt);
  if (Math.abs(state.kick) < 0.002) state.kick = 0;

  // Horizontal only: one axis, no tumble and no nod.
  const turn = (state.spin + state.kick) * dt;
  for (const mesh of state.orb.userData.spinning) mesh.rotation.y += turn;

  state.renderer.render(state.scene, state.camera);
}

/** Click it and it spins up for a moment - the side you hit decides which way. */
function wireClicks(host) {
  const ring = document.createElement("div");
  ring.className = "orb-kick";
  host.appendChild(ring);
  let flashTimer = null;

  host.addEventListener("pointerdown", (event) => {
    const rect = host.getBoundingClientRect();
    const dir = event.clientX < rect.left + rect.width / 2 ? -1 : 1;
    // Shoving it again while it is still spinning adds to the shove.
    state.kick = Math.max(-9, Math.min(9, state.kick + dir * KICK_SPEED));

    // Restart the ripple even if one is already running.
    ring.classList.remove("go");
    void ring.offsetWidth;
    ring.classList.add("go");

    host.classList.add("kick");
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => host.classList.remove("kick"), KICK_MS);
  });
}

function init() {
  const host = document.getElementById("orb");
  if (!host) return false;

  const canvas = document.createElement("canvas");
  canvas.id = "orb3d";
  host.appendChild(canvas);

  try {
    state.renderer = new THREE.WebGLRenderer({
      canvas,
      alpha: true,
      // No antialiasing on purpose - the jagged edges are the point.
      antialias: false,
      powerPreference: "low-power",
    });
  } catch (e) {
    console.warn("Aerowave: no WebGL, keeping the CSS orb -", e);
    canvas.remove();
    return false;
  }

  state.host = host;
  state.canvas = canvas;
  // A fixed low-resolution buffer; CSS stretches it up to the orb's size.
  state.renderer.setPixelRatio(1);
  state.renderer.setSize(RENDER_SIZE, RENDER_SIZE, false);
  state.renderer.setClearAlpha(0);

  state.scene = new THREE.Scene();
  state.camera = new THREE.PerspectiveCamera(30, 1, 0.1, 100);
  state.camera.position.z = 4.4;

  lightScene(state.scene);

  state.orb = buildOrb();
  state.scene.add(state.orb);

  wireClicks(host);
  host.classList.add("gl");
  state.last = performance.now();
  state.renderer.setAnimationLoop(frame);
  return true;
}

window.aerowaveOrb = { ok: init() };
