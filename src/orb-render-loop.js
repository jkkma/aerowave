/** Advance the spin by elapsed wall time, even when frames arrive unevenly. */
export function advanceOrbMotion(state, target, dt, spinResponse, kickDecay) {
  const spin = state.spin;
  const kick = state.kick;
  const spinSurvival = Math.exp(-spinResponse * dt);
  const kickSurvival = Math.exp(-kickDecay * dt);

  state.spin = target + (spin - target) * spinSurvival;
  state.kick = kick * kickSurvival;
  if (Math.abs(state.kick) < 0.002) state.kick = 0;

  // Integrating both decays keeps a shove and speed change consistent whether
  // the browser delivers many short frames or a few late ones.
  return target * dt
    + (spin - target) * (1 - spinSurvival) / spinResponse
    + kick * (1 - kickSurvival) / kickDecay;
}

/** Run WebGL only while the orb can move on screen. */
export function createOrbRenderLoop({ renderer, draw, step, visible, reducedMotion, now, pause }) {
  let running = false;
  let last = null;

  function frame(timestamp) {
    if (!visible() || reducedMotion()) {
      sync();
      return;
    }

    // A late frame should keep the rotation on time. Bound a genuine long
    // main-thread stall so its first recovered frame cannot jump across the ball.
    const dt = last === null ? 0 : Math.min(0.25, Math.max(0, (timestamp - last) / 1000));
    last = timestamp;
    step(dt);
    draw();
  }

  function sync() {
    if (visible() && !reducedMotion()) {
      if (running) return;
      last = now();
      running = true;
      renderer.setAnimationLoop(frame);
      return;
    }

    if (running) {
      running = false;
      renderer.setAnimationLoop(null);
    }
    last = null;
    pause();
    if (visible()) draw();
  }

  function redraw() {
    if (!running && visible()) draw();
  }

  return { sync, redraw };
}
