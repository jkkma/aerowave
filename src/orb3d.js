/* =====================================================================
   The orb: one smoothly lit ball, turning slowly on a vertical axis.

   A smooth-shaded sphere with a 64-pixel texture wrapped around it, rendered
   into a small buffer and scaled up with hard pixel edges. Its cyan shading
   makes the rotation visible; the light follows the round surface without
   exposing the triangles underneath.

   Diffuse lighting and a brightened rim give it volume without a bright
   specular spot. The rim is worked out in the same small buffer as the ball,
   so its edge stays coarse. Station pictures keep only a faint rim.

   The texture is read through the N64's three-point filter rather than the
   hardware's own, so the softness is in the texture while the buffer it all
   lands in stays hard-edged - which is roughly what that machine did.

   It turns a little faster while something plays, and clicking a side
   shoves it that way and leaves it turning that way.

   While a station with a picture is playing, that picture is what the surface
   wears - repainted at the same hard pixel edges, on white wherever the
   station's own artwork is transparent.

   Three.js is vendored in src/vendor/ rather than fetched: the app has to
   work offline, and the content security policy only allows scripts from
   its own origin. If WebGL will not start, the CSS orb underneath stays
   visible and nothing else changes.
   ===================================================================== */

import * as THREE from "./vendor/three.module.min.js";

/**
 * The orb is rendered into a small buffer and scaled up with hard pixel
 * edges: chunky pixels and stairstepped edges, no antialiasing.
 */
export const RENDER_SIZE = 112;

/**
 * Enough subdivisions for a round silhouette at the small render size.
 * Smooth normals hide the triangle boundaries; the texture carries motion.
 */
const SURFACE_DETAIL = 3;

const IDLE_SPIN = 0.26;   // radians per second
const PLAYING_SPIN = 0.7;
/** Which way it turns until something shoves it: -1 is to the left. */
const IDLE_DIR = -1;

/** The station picture is redrawn at this size before it goes on the ball. */
const LOGO_SIZE = 128;
/** What shows through a station picture wherever it is transparent. */
const LOGO_BACKING = "#ffffff";
/** How many times a picture is repeated around the ball, and top to bottom. */
const LOGO_REPEAT = [2, 1];
/** A clear blue crystal glow and a neutral lift beneath station art, so its
 *  colours remain readable on the shadow side without a cyan wash. */
const CORE_EMISSIVE = 0x054457;
const LOGO_EMISSIVE = 0x0e1a20;
/** The shove a click gives it, tuned to be spent in about half a second. */
const KICK_SPEED = 6.5;
const KICK_DECAY = 8;
const KICK_MS = 500;
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

const state = {
  renderer: null,
  scene: null,
  camera: null,
  orb: null,
  canvas: null,
  host: null,
  last: 0,
  // Signed, and started already going the idle way: easing in from the other
  // direction would spend the first second of every launch turning back.
  spin: IDLE_DIR * IDLE_SPIN,
  kick: 0,
  /** Which way it settles back to turning: set by the side that was clicked. */
  dir: IDLE_DIR,
  /** The lit mesh, and the crystal texture to go back to when nothing plays. */
  core: null,
  crystal: null,
  /** The size of whatever the ball is wearing, for the filter to work from. */
  texSize: { value: new THREE.Vector2(1, 1) },
  /** How much of the rim shine to keep: all of it, or a trace while a station's
   *  picture is on. A uniform rather than a rebuild - it changes per station. */
  rim: { value: 1 },
};

/** The picture that was asked for last, so a slow one landing late is dropped. */
let logoToken = 0;

/**
 * The palette the crystal is drawn from: seven cyan steps, clear to icy blue.
 *
 * Few colours on purpose. An N64 texture was usually a colour-indexed bitmap
 * with a palette of sixteen or two hundred and fifty six, and what it could
 * not afford in colours it made up in dithering - so the count is the look.
 */
const CRYSTAL_RAMP = [
  "#147fae", "#2aa5cc", "#50d3f1", "#6edff5", "#90eafa", "#b6f4fc", "#ddfcff",
];

// Choose the pattern once per launch. Returning from station art keeps this
// same crystal, while a fresh app run gets different shapes and shades.
const CRYSTAL_PATTERN = {
  seed: Math.floor(Math.random() * 4294967296),
  cells: 2 + Math.floor(Math.random() * 4),
  warp: 12 + Math.random() * 16,
  darkest: 0.04 + Math.random() * 0.06,
  lightest: 0.62 + Math.random() * 0.04,
};

/**
 * The 4x4 ordered dither the bands are broken up with, which is the other
 * half of the look: a hard edge between two palette steps reads as a contour
 * line, and a crosshatch between them reads as a gradient the palette could
 * not actually hold.
 */
const BAYER = [
  [0, 8, 2, 10],
  [12, 4, 14, 6],
  [3, 11, 1, 9],
  [15, 7, 13, 5],
];

/**
 * One octave of value noise, `cells` across, read at any point in between.
 *
 * The grid is addressed with a wrap, so the noise tiles: the texture goes all
 * the way round a sphere and meets itself, and a field that did not tile
 * would leave a join down one side of the ball.
 */
function noiseOctave(size, cells, seed) {
  const grid = [];
  // A seeded field stays still on the surface as the crystal turns.
  for (let i = 0; i < cells * cells; i++) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    grid.push(seed / 4294967296);
  }
  const at = (cx, cy) => {
    const x = ((cx % cells) + cells) % cells;
    const y = ((cy % cells) + cells) % cells;
    return grid[y * cells + x];
  };
  // Smoothstepped, or the cell corners show up as diamonds.
  const ease = (t) => t * t * (3 - 2 * t);
  return (px, py) => {
    const fx = (px / size) * cells;
    const fy = (py / size) * cells;
    const x0 = Math.floor(fx);
    const y0 = Math.floor(fy);
    const sx = ease(fx - x0);
    const sy = ease(fy - y0);
    const top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * sx;
    const bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * sx;
    return top + (bottom - top) * sy;
  };
}

/**
 * Soft cyan shading gives the crystal a recognizable shape in motion.
 * Keep the transitions broad: narrow pale veins read as white stripes, while
 * grain makes the diffuse shadow look stained.
 * Wrapped fields on both axes keep the 64-pixel skin seamless, and ordered
 * dithering joins its seven palette steps without adding a smooth gradient.
 */
function crystalCanvas() {
  const size = 64;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext("2d");

  const pattern = CRYSTAL_PATTERN;
  const patches = noiseOctave(size, pattern.cells, pattern.seed);
  const bendX = noiseOctave(size, 2, pattern.seed ^ 0x9e3779b9);
  const bendY = noiseOctave(size, 3, pattern.seed ^ 0x85ebca6b);
  const fields = new Float32Array(size * size);
  let darkest = Infinity;
  let lightest = -Infinity;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const field = patches(
        x + (bendX(x, y) - 0.5) * pattern.warp,
        y + (bendY(x, y) - 0.5) * pattern.warp
      );
      fields[y * size + x] = field;
      darkest = Math.min(darkest, field);
      lightest = Math.max(lightest, field);
    }
  }
  // Give even a quiet random field a full blue range, while keeping the pale
  // stripe colours out. Broad warped patches avoid a repeating band layout.
  const range = Math.max(0.000001, lightest - darkest);

  const ramp = CRYSTAL_RAMP.map((hex) => [
    parseInt(hex.slice(1, 3), 16),
    parseInt(hex.slice(3, 5), 16),
    parseInt(hex.slice(5, 7), 16),
  ]);
  const top = ramp.length - 1;

  const image = ctx.createImageData(size, size);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const shade = (fields[y * size + x] - darkest) / range;
      const field = pattern.darkest + shade * (pattern.lightest - pattern.darkest);
      const dither = (BAYER[y & 3][x & 3] + 0.5) / 16 - 0.5;
      const step = Math.min(top, Math.max(0, Math.round(field * top + dither * 0.65)));

      const at = (y * size + x) * 4;
      image.data[at] = ramp[step][0];
      image.data[at + 1] = ramp[step][1];
      image.data[at + 2] = ramp[step][2];
      image.data[at + 3] = 255;
    }
  }
  ctx.putImageData(image, 0, 0);
  return canvas;
}

/**
 * The Nintendo 64's texture filter, which is not quite bilinear.
 *
 * Bilinear reads the four texels around a sample and blends all four. The
 * RDP could only afford three, so it cut that square of texels along its
 * diagonal and interpolated across whichever triangle the sample landed in.
 * One fetch cheaper - and the reason N64 textures look the way they do: soft,
 * but with a faint crease running through every texel square rather than the
 * even wash bilinear gives.
 */
const THREE_POINT_GLSL = `
uniform vec2 uTexSize;
uniform float uRim;

vec4 texture3Point( sampler2D tex, vec2 uv ) {
  vec2 texel = 1.0 / uTexSize;
  vec2 st = uv * uTexSize - 0.5;
  vec2 corner = ( floor( st ) + 0.5 ) * texel;
  vec2 f = fract( st );

  vec4 c00 = texture2D( tex, corner );
  vec4 c10 = texture2D( tex, corner + vec2( texel.x, 0.0 ) );
  vec4 c01 = texture2D( tex, corner + vec2( 0.0, texel.y ) );
  vec4 c11 = texture2D( tex, corner + texel );

  // Which side of the diagonal the sample fell on decides which three.
  if ( f.x + f.y < 1.0 ) {
    return c00 + ( c10 - c00 ) * f.x + ( c01 - c00 ) * f.y;
  }
  return c11 + ( c01 - c11 ) * ( 1.0 - f.x ) + ( c10 - c11 ) * ( 1.0 - f.y );
}
`;

/**
 * The edge-on brightening that makes a glass ball read as glass.
 *
 * Light entering a transparent sphere leaves it most readily where you are
 * looking straight through the least of it, so the rim of a crystal ball is
 * always the brightest part of it - which is the half of "shine" a specular
 * highlight on its own cannot give you. Cheap here: the angle between the
 * normal and the eye, raised to a power, added to what the lighting worked
 * out. Both vectors are already available in the material's fragment shader.
 */
const RIM_GLSL = `
float rim = 1.0 - abs( dot( normalize( normal ), normalize( vViewPosition ) ) );
outgoingLight += vec3( 0.38, 0.86, 0.79 ) * pow( rim, 3.8 ) * 0.28 * uRim;
`;

/**
 * Patch a stock material: read the colour map through the three-point filter,
 * and brighten the rim on the way out.
 *
 * Only the two lines that do those jobs are replaced, so the lighting, the
 * fog and the tone mapping stay three.js's business. `uTexSize` is handed in
 * rather than worked out in the shader because the crystal and a station's
 * picture are not the same size, and the ball wears both.
 */
function patchOrbShader(material) {
  material.onBeforeCompile = (shader) => {
    shader.uniforms.uTexSize = state.texSize;
    shader.uniforms.uRim = state.rim;
    shader.fragmentShader =
      THREE_POINT_GLSL +
      shader.fragmentShader
        .replace(
          "#include <map_fragment>",
          `
#ifdef USE_MAP
  diffuseColor *= texture3Point( map, vMapUv );
#endif
`
        )
        .replace(
          "#include <opaque_fragment>",
          RIM_GLSL + "gl_FragColor = vec4( outgoingLight, diffuseColor.a );"
        );
  };
}

/**
 * A canvas, wrapped for the ball.
 *
 * Point sampling on purpose: the hardware must not blend anything of its own,
 * because the three-point filter above is doing all of it and would otherwise
 * be softening something already softened.
 */
function pixelTexture(canvas) {
  const texture = new THREE.CanvasTexture(canvas);
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestFilter;
  texture.generateMipmaps = false;
  texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

/**
 * A station's picture, redrawn as something this ball can wear.
 *
 * White goes down first. A great deal of station art is a transparent PNG of
 * a wordmark, and transparent texels on a material that does not read alpha
 * come out black - so without a backing the ball wears the logo on a hole.
 * White is also what these were drawn against: they are made to sit on a
 * page. Nothing is smoothed on the way in - the point is chunky texels.
 */
function logoCanvas(image) {
  const size = LOGO_SIZE;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext("2d");
  ctx.imageSmoothingEnabled = false;

  // Fill the whole texture first so transparent parts of the source stay
  // white after the artwork is cropped to cover the ball.
  ctx.fillStyle = LOGO_BACKING;
  ctx.fillRect(0, 0, size, size);

  const scale = Math.max(size / image.width, size / image.height);
  const w = image.width * scale;
  const h = image.height * scale;
  ctx.drawImage(image, (size - w) / 2, (size - h) / 2, w, h);
  return canvas;
}

/**
 * Put a station's picture on the ball, or take it off again.
 *
 * `src` must be something the page may load by itself - a data URL - because
 * the content security policy allows no remote images, and a cross-origin one
 * could not be uploaded as a texture even if it did.
 */
function setImage(src) {
  const mine = ++logoToken;
  if (!state.core) return;
  if (!src) {
    wear(state.crystal, CORE_EMISSIVE);
    return;
  }
  const image = new Image();
  // A picture that will not load leaves the crystal it already had.
  image.onerror = () => { if (mine === logoToken) wear(state.crystal, CORE_EMISSIVE); };
  image.onload = () => {
    if (mine !== logoToken) return;
    const texture = pixelTexture(logoCanvas(image));
    // Repeated around it rather than wrapped once: one copy stretched over a
    // whole sphere is a smear, and this way a whole copy faces you.
    texture.repeat.set(LOGO_REPEAT[0], LOGO_REPEAT[1]);
    wear(texture, LOGO_EMISSIVE);
  };
  image.src = src;
}

/** Tell the filter how big the texture it is reading actually is. */
function measure(texture) {
  const image = texture.image;
  state.texSize.value.set(image.width || 1, image.height || 1);
}

/** Swap the ball's skin, letting go of the last one unless it is the crystal. */
function wear(texture, emissive) {
  const material = state.core.material;
  const old = material.map;
  if (old === texture) return;
  material.map = texture;
  measure(texture);
  material.emissive.setHex(emissive);
  // Keep just enough edge light to describe the volume around the artwork.
  const bare = texture === state.crystal;
  state.rim.value = bare ? 1 : 0.14;
  material.needsUpdate = true;
  if (old && old !== state.crystal) old.dispose();
}


/**
 * Lit to match the CSS backdrop: bright sky above, deep water bounce from
 * below, and a key from the upper left.
 *
 * A restrained sky fill leaves the right-hand side dark enough to read.
 * One key and a mint bounce describe the volume without washing out the skin.
 */
export function lightScene(scene) {
  scene.add(new THREE.HemisphereLight(0xd8f9ff, 0x07384f, 0.65));
  const key = new THREE.DirectionalLight(0xeefff8, 1.65);
  key.position.set(-2.2, 2.6, 3);
  scene.add(key);
  const bounce = new THREE.DirectionalLight(0x64ffe2, 0.24);
  bounce.position.set(0.4, -2.6, 1.2);
  scene.add(bounce);
}

export function buildOrb() {
  const group = new THREE.Group();

  state.crystal = pixelTexture(crystalCanvas());

  // The texture and coarse render keep the retro character. Smooth lighting
  // supplies the volume, without a separate shell or halo around the ball.
  const orb = new THREE.Mesh(
    new THREE.IcosahedronGeometry(0.98, SURFACE_DETAIL),
    new THREE.MeshPhongMaterial({
      map: state.crystal,
      color: 0xffffff,
      emissive: CORE_EMISSIVE,
      // Diffuse shading carries the depth without a bright reflected spot.
      specular: 0x000000,
      flatShading: false,
    })
  );
  patchOrbShader(orb.material);
  measure(state.crystal);
  group.add(orb);
  group.userData.spinning = [orb];
  state.core = orb;

  // Tipped just enough to see a little of the top while it turns; the spin
  // axis stays vertical so the motion reads as horizontal.
  group.rotation.x = -0.16;
  return group;
}

function frame(now) {
  const dt = Math.min(0.05, (now - state.last) / 1000) || 0;
  state.last = now;
  if (reducedMotion.matches) {
    state.kick = 0;
    state.renderer.render(state.scene, state.camera);
    return;
  }

  // Ease towards the target speed instead of jumping when playback starts.
  // Signed: a shove to the left leaves it turning left rather than snapping
  // back the other way the moment the shove is spent.
  const speed = document.body.classList.contains("playing") ? PLAYING_SPIN : IDLE_SPIN;
  const target = state.dir * speed;
  state.spin += (target - state.spin) * Math.min(1, dt * 1.5);

  // A click gives it a shove that bleeds off exponentially.
  state.kick *= Math.exp(-KICK_DECAY * dt);
  if (Math.abs(state.kick) < 0.002) state.kick = 0;

  // Horizontal only: one axis, no tumble and no nod.
  const turn = (state.spin + state.kick) * dt;
  for (const mesh of state.orb.userData.spinning) mesh.rotation.y += turn;

  state.renderer.render(state.scene, state.camera);
}

/** Click it and it spins up - and keeps turning the way you shoved it. */
function wireClicks(host) {
  let flashTimer = null;

  host.addEventListener("pointerdown", (event) => {
    if (reducedMotion.matches) return;
    const rect = host.getBoundingClientRect();
    const dir = event.clientX < rect.left + rect.width / 2 ? -1 : 1;
    // Shoving it again while it is still spinning adds to the shove.
    state.kick = Math.max(-9, Math.min(9, state.kick + dir * KICK_SPEED));
    // And it settles back to turning that way, rather than to whichever way
    // it happened to be turning before.
    state.dir = dir;

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

window.aerowaveOrb = { ok: init(), setImage };
