//! A local, decoder-free last resort after an alarm's media sources fail.

pub const SAMPLE_RATE: u32 = 44_100;

/// The scheduler validates a start while holding its occurrence lock. Audio
/// callbacks use this independent gate, including while an output opens slowly.
#[derive(Default)]
pub struct ToneGate(std::sync::atomic::AtomicU64);

impl ToneGate {
    pub fn start(&self, occurrence: u64) {
        self.0.store(occurrence, std::sync::atomic::Ordering::Release);
    }

    pub fn is_current(&self, occurrence: u64) -> bool {
        occurrence != 0 && self.0.load(std::sync::atomic::Ordering::Acquire) == occurrence
    }

    pub fn stop(&self, occurrence: u64) {
        let _ = self.0.compare_exchange(occurrence, 0,
            std::sync::atomic::Ordering::AcqRel, std::sync::atomic::Ordering::Acquire);
    }

    pub fn clear(&self) { self.0.store(0, std::sync::atomic::Ordering::Release); }

    pub fn sample(&self, occurrence: u64, index: u64) -> Option<f32> {
        self.is_current(occurrence).then(|| sample(index))
    }
}

pub fn volume(value: f64) -> f32 {
    if value.is_finite() { value.clamp(0.0, 1.0) as f32 } else { 0.8 }
}

/// Two distinct pulses, then a pause. Short envelopes avoid clicks at the
/// pulse boundaries; the pattern needs no file, network, or codec.
pub fn sample(index: u64) -> f32 {
    let time = (index % u64::from(SAMPLE_RATE * 2)) as f64 / f64::from(SAMPLE_RATE);
    let (at, frequency) = if time < 0.45 {
        (time, 660.0)
    } else if (0.65..1.1).contains(&time) {
        (time - 0.65, 880.0)
    } else {
        return 0.0;
    };
    let envelope = (at / 0.01).min((0.45 - at) / 0.01).clamp(0.0, 1.0);
    (0.5 * envelope * (at * frequency * std::f64::consts::TAU).sin()) as f32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delayed_audio_callback_cannot_restart_a_dismissed_occurrence() {
        let gate = ToneGate::default();
        assert_eq!(gate.sample(0, 100), None);
        gate.start(7);
        assert!(gate.sample(7, 100).is_some());
        gate.stop(7);
        assert_eq!(gate.sample(7, 101), None);
    }

    #[test]
    fn stale_stop_cannot_silence_the_new_occurrence() {
        let gate = ToneGate::default();
        gate.start(7);
        gate.start(8);
        gate.stop(7);
        assert_eq!(gate.sample(7, 101), None);
        assert!(gate.sample(8, 101).is_some());
        gate.clear();
        assert_eq!(gate.sample(8, 102), None);
    }

    #[test]
    fn tone_is_finite_bounded_periodic_and_contains_both_pulses() {
        let samples: Vec<_> = (0..u64::from(SAMPLE_RATE * 2)).map(sample).collect();
        assert!(samples.iter().all(|s| s.is_finite() && s.abs() <= 0.5));
        assert!(samples[..SAMPLE_RATE as usize / 3].iter().any(|s| s.abs() > 0.45));
        assert!(samples[SAMPLE_RATE as usize * 7 / 10..SAMPLE_RATE as usize].iter().any(|s| s.abs() > 0.45));
        assert!(samples[SAMPLE_RATE as usize * 12 / 10..].iter().all(|s| *s == 0.0));
        assert_eq!(sample(123), sample(123 + u64::from(SAMPLE_RATE * 2)));
        assert_eq!(sample(0), 0.0);
    }

    #[test]
    fn volume_preserves_mute_and_bounds_invalid_settings() {
        assert_eq!(volume(0.0), 0.0);
        assert_eq!(volume(0.4), 0.4);
        assert_eq!(volume(-1.0), 0.0);
        assert_eq!(volume(2.0), 1.0);
        assert_eq!(volume(f64::NAN), 0.8);
        assert_eq!(volume(f64::INFINITY), 0.8);
    }
}
