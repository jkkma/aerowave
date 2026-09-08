/* =====================================================================
   The orb: one low-poly ball, turning slowly on a vertical axis.

   A smooth-shaded sphere with a 64-pixel texture wrapped around it, rendered
   into a small buffer and scaled up with hard pixel edges. It is faceted -
   subdivided far enough that the outline is a circle, but flat shaded, so
   each little triangle catches the light on its own as it turns.

   The shine on it is the lighting's, not a highlight painted over the top: a
   tight specular and a brightened rim, both worked out per pixel in the same
   low buffer as everything else, so they are as coarse as the ball is rather
   than sitting crisply above it. Both come off while it is wearing a station's
   picture, which wants to be read rather than shone on.

   The texture is read through the N64's three-point filter rather than the
   hardware's own, so the softness is in the texture while the buffer it all
   lands in stays hard-edged - which is roughly what that machine did.

   It turns a little faster while something plays, and clicking a side
   shoves it that way and leaves it turning that way.

   While a station with a picture is playing, that picture is what the facets
   wear - repainted at the same hard pixel edges, on white wherever the
   station's own artwork is transparent.

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

/**
 * How many times each of the icosahedron's twenty faces is divided.
 *
 * 2 is 320 triangles: big enough that the facets read as facets right across
 * the ball, and enough of them that the outline is still a circle rather than
 * something you can count the corners of. 3 is 1280, which at this size only
 * shows up where the highlight happens to land - a few bright plates on what
 * otherwise looks smooth, which reads as a mistake rather than as a facet.
 */
const FACETS = 2;

const IDLE_SPIN = 0.26;   // radians per second
const PLAYING_SPIN = 0.7;

/** The station picture is redrawn at this size before it goes on the ball. */
const LOGO_SIZE = 128;
/** What shows through a station picture wherever it is transparent. */
const LOGO_BACKING = "#ffffff";
/** How many times a picture is repeated around the ball, and top to bottom. */
const LOGO_REPEAT = [2, 1];
/** The glow inside the crystal, and the far dimmer one under a picture: a
 *  station's own colours are the point, and cyan light through them is not. */
const CORE_EMISSIVE = 0x0c5e78;
const LOGO_EMISSIVE = 0x0e1a20;
/** The glint off the bare crystal. Nothing at all once it is wearing a
 *  picture: a highlight travelling over a station's logo reads as glare on a
 *  screen rather than as shine on a ball, and it hides half the logo doing
 *  it. */
const CORE_SPECULAR = 0x63c2dd;

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
  /** Which way it settles back to turning: set by the side that was clicked. */
  dir: 1,
  /** The lit mesh, and the crystal texture to go back to when nothing plays. */
  core: null,
  crystal: null,
  /** The size of whatever the ball is wearing, for the filter to work from. */
  texSize: { value: new THREE.Vector2(1, 1) },
  /** How much of the rim shine to keep: all of it, or none while a station's
   *  picture is on. A uniform rather than a rebuild - it changes per station. */
  rim: { value: 1 },
};

/** The picture that was asked for last, so a slow one landing late is dropped. */
let logoToken = 0;

/**
 * The palette the crystal is drawn from: seven aqua steps, deep to bright.
 *
 * Few colours on purpose. An N64 texture was usually a colour-indexed bitmap
 * with a palette of sixteen or two hundred and fifty six, and what it could
 * not afford in colours it made up in dithering - so the count is the look.
 */
const CRYSTAL_RAMP = [
  "#14607a", "#1d86a6", "#28a5c8", "#33bcdf", "#5ed3ec", "#90e7f8", "#c8f5ff",
];

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

/** How far the marbling is dragged sideways, in texels. */
const WARP = 18;
/** How hard the field is pushed towards the ends of the ramp. Noise piles up
 *  around its middle, and a texture that only ever uses the middle of its own
 *  palette is the washed-out one - this spends the dark end too. */
const CONTRAST = 2.1;

/**
 * One octave of value noise, `cells` across, read at any point in between.
 *
 * The grid is addressed with a wrap, so the noise tiles: the texture goes all
 * the way round a sphere and meets itself, and a field that did not tile
 * would leave a join down one side of the ball.
 */
function noiseOctave(size, cells) {
  const grid = [];
  for (let i = 0; i < cells * cells; i++) grid.push(Math.random());
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
 * The crystal the ball wears when nothing is playing: 64 pixels square, and
 * built the way a texture on that machine was built.
 *
 * Three octaves of value noise, dragged sideways by a fourth so it marbles
 * rather than clouds, then flattened onto a seven-colour palette through an
 * ordered dither. Nothing is axis-aligned and nothing is a flat block - the
 * whole point is that it should look painted and then squeezed into a palette,
 * which is what those textures were, rather than assembled out of squares.
 *
 * Handed back as a canvas rather than a texture so the texture wrapper below
 * is the one place that decides how a canvas is filtered.
 */
function crystalCanvas() {
  const size = 64;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext("2d");

  const coarse = noiseOctave(size, 2);
  const middle = noiseOctave(size, 5);
  const fine = noiseOctave(size, 10);
  const dragX = noiseOctave(size, 4);
  const dragY = noiseOctave(size, 4);

  const ramp = CRYSTAL_RAMP.map((hex) => [
    parseInt(hex.slice(1, 3), 16),
    parseInt(hex.slice(3, 5), 16),
    parseInt(hex.slice(5, 7), 16),
  ]);
  const top = ramp.length - 1;

  const image = ctx.createImageData(size, size);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      // Warped before it is sampled: the same noise read along a wandering
      // line comes out in veins rather than in blobs.
      const wx = x + (dragX(x, y) - 0.5) * WARP;
      const wy = y + (dragY(x, y) - 0.5) * WARP;
      const field =
        coarse(wx, wy) * 0.6 + middle(wx, wy) * 0.28 + fine(wx, wy) * 0.12;

      const pushed = (field - 0.5) * CONTRAST + 0.5;
      const dither = (BAYER[y & 3][x & 3] + 0.5) / 16 - 0.5;
      const step = Math.min(top, Math.max(0, Math.round(pushed * top + dither)));

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
 * out. Both of those are already sitting in the fragment shader - `normal`
 * rather than the `vNormal` varying, because a flat-shaded material has no
 * such varying: it works the normal out from screen-space derivatives, and
 * only the local is there under both.
 */
const RIM_GLSL = `
float rim = 1.0 - abs( dot( normalize( normal ), normalize( vViewPosition ) ) );
outgoingLight += vec3( 0.40, 0.84, 1.0 ) * pow( rim, 3.2 ) * 0.5 * uRim;
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

  ctx.fillStyle = LOGO_BACKING;
  ctx.fillRect(0, 0, size, size);

  // Cover, not fit: a letterboxed logo would show bands of bare backing.
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
  // The crystal is glass and caught the light; a station's artwork is a
  // picture, and both halves of the shine come off for it.
  const bare = texture === state.crystal;
  material.specular.setHex(bare ? CORE_SPECULAR : 0x000000);
  state.rim.value = bare ? 1 : 0;
  material.needsUpdate = true;
  if (old && old !== state.crystal) old.dispose();
}


/**
 * Lit to match the CSS backdrop: bright sky above, deep water bounce from
 * below, and a key from the upper left.
 *
 * Kept deliberately dim. These four together used to sum well past full
 * brightness, so the whole lit side of the ball clipped to white and took the
 * texture with it - which is no good when the texture is the thing worth
 * looking at. A machine of that era barely had light to spare either: the
 * shading is in the palette, and this only says which side is which.
 */
export function lightScene(scene) {
  scene.add(new THREE.HemisphereLight(0xd8f9ff, 0x0a5a74, 1.0));
  const key = new THREE.DirectionalLight(0xffffff, 1.3);
  key.position.set(-2.2, 2.6, 3);
  scene.add(key);
  const fill = new THREE.DirectionalLight(0x9fe4ff, 0.3);
  fill.position.set(3, -1.4, -1.2);
  scene.add(fill);
  const bounce = new THREE.DirectionalLight(0x64ffe2, 0.42);
  bounce.position.set(0.4, -2.6, 1.2);
  scene.add(bounce);
}

export function buildOrb() {
  const group = new THREE.Group();

  state.crystal = pixelTexture(crystalCanvas());

  // A ball with a 64-pixel texture wrapped around it, flat shaded so every
  // facet reads on its own. Nothing behind it and nothing over it - no glow,
  // no shell, no halo.
  const orb = new THREE.Mesh(
    new THREE.IcosahedronGeometry(0.98, FACETS),
    new THREE.MeshPhongMaterial({
      map: state.crystal,
      color: 0xffffff,
      emissive: CORE_EMISSIVE,
      // Tight and tinted, not broad and white. The wide low-shine highlight
      // this had before spread into a bloom across half the ball and washed
      // the texture out; a small cool one sits on the surface and reads as
      // something the ball is made of rather than something stuck on it.
      specular: CORE_SPECULAR,
      shininess: 55,
      flatShading: true,
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
  const ring = document.createElement("div");
  ring.className = "orb-kick";
  host.appendChild(ring);
  let flashTimer = null;

  host.addEventListener("pointerdown", (event) => {
    const rect = host.getBoundingClientRect();
    const dir = event.clientX < rect.left + rect.width / 2 ? -1 : 1;
    // Shoving it again while it is still spinning adds to the shove.
    state.kick = Math.max(-9, Math.min(9, state.kick + dir * KICK_SPEED));
    // And it settles back to turning that way, rather than to whichever way
    // it happened to be turning before.
    state.dir = dir;

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

window.aerowaveOrb = { ok: init(), setImage };
