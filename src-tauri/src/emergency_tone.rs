//! Native emergency audio is independent of the WebView, relay, and decoders.
//! The output stream stays on its own thread because some platform streams
//! cannot be moved between threads. Cancellation also gates every sample, so
//! a slow device open cannot resurrect a dismissed occurrence.

use std::sync::{mpsc, Arc};
use aerowave_core::emergency_tone::ToneGate;
use std::time::{Duration, Instant};
use tokio::sync::oneshot;

enum Command {
    Start(u64, f32, oneshot::Sender<Result<(), String>>),
    Stop(u64),
    Volume(u64, f32),
    Fade(u64, Duration),
}

pub struct EmergencyTone {
    active: Arc<ToneGate>,
    commands: mpsc::Sender<Command>,
}

impl EmergencyTone {
    pub fn new() -> Self {
        let active = Arc::new(ToneGate::default());
        let (commands, receiver) = mpsc::channel();
        let owned = active.clone();
        let _ = std::thread::Builder::new().name("alarm-emergency-audio".into())
            .spawn(move || run(receiver, owned));
        Self { active, commands }
    }

    /// Call while holding the scheduler lock after validating the occurrence.
    /// Await the reply only after releasing that lock.
    pub fn start(&self, occurrence: u64, volume: f64) -> Result<oneshot::Receiver<Result<(), String>>, String> {
        let (send, receive) = oneshot::channel();
        self.active.start(occurrence);
        self.commands.send(Command::Start(occurrence, aerowave_core::emergency_tone::volume(volume), send))
            .map_err(|_| "The native emergency audio worker is unavailable".to_string())?;
        Ok(receive)
    }

    pub fn stop(&self, occurrence: u64) {
        self.active.stop(occurrence);
        let _ = self.commands.send(Command::Stop(occurrence));
    }

    pub fn set_volume(&self, occurrence: u64, volume: f64) -> Result<(), String> {
        self.commands.send(Command::Volume(occurrence, aerowave_core::emergency_tone::volume(volume)))
            .map_err(|_| "The native emergency audio worker is unavailable".to_string())
    }

    pub fn fade(&self, occurrence: u64, milliseconds: u64) -> Result<(), String> {
        self.commands.send(Command::Fade(occurrence, Duration::from_millis(milliseconds.min(6_000))))
            .map_err(|_| "The native emergency audio worker is unavailable".to_string())
    }
}

impl Drop for EmergencyTone {
    fn drop(&mut self) { self.active.clear(); }
}

#[cfg(desktop)]
struct ToneSource {
    active: Arc<ToneGate>,
    occurrence: u64,
    index: u64,
}

#[cfg(desktop)]
impl Iterator for ToneSource {
    type Item = f32;
    fn next(&mut self) -> Option<f32> {
        let sample = self.active.sample(self.occurrence, self.index)?;
        self.index = self.index.wrapping_add(1);
        Some(sample)
    }
}

#[cfg(desktop)]
impl rodio::Source for ToneSource {
    fn current_frame_len(&self) -> Option<usize> { None }
    fn channels(&self) -> u16 { 1 }
    fn sample_rate(&self) -> u32 { aerowave_core::emergency_tone::SAMPLE_RATE }
    fn total_duration(&self) -> Option<Duration> { None }
}

#[cfg(desktop)]
fn run(receiver: mpsc::Receiver<Command>, active: Arc<ToneGate>) {
    use rodio::{OutputStream, Sink};
    let mut playing: Option<(u64, OutputStream, Sink)> = None;
    let mut fading: Option<(u64, Instant, Duration, f32)> = None;
    loop {
        match receiver.recv_timeout(Duration::from_millis(40)) {
            Ok(Command::Start(occurrence, volume, reply)) => {
                if !active.is_current(occurrence) {
                    let _ = reply.send(Err("That alarm is no longer ringing".into()));
                    continue;
                }
                if let Some((id, _, sink)) = &playing {
                    if *id == occurrence && !sink.empty() {
                        let _ = reply.send(Ok(()));
                        continue;
                    }
                }
                playing = None;
                fading = None;
                let opened = OutputStream::try_default()
                    .map_err(|error| format!("Could not open an audio output: {error}"))
                    .and_then(|(stream, handle)| Sink::try_new(&handle)
                        .map(|sink| (stream, sink))
                        .map_err(|error| format!("Could not start emergency audio: {error}")));
                let result = match opened {
                    Ok((stream, sink)) if active.is_current(occurrence) => {
                        sink.set_volume(volume);
                        sink.append(ToneSource { active: active.clone(), occurrence, index: 0 });
                        playing = Some((occurrence, stream, sink));
                        Ok(())
                    }
                    Ok(_) => Err("That alarm is no longer ringing".into()),
                    Err(error) => Err(error),
                };
                let _ = reply.send(result);
            }
            Ok(Command::Stop(occurrence)) => {
                if playing.as_ref().is_some_and(|(id, _, _)| *id == occurrence) {
                    playing = None;
                    fading = None;
                }
            }
            Ok(Command::Volume(occurrence, volume)) => {
                if let Some((id, _, sink)) = &playing {
                    if *id == occurrence && active.is_current(occurrence) {
                        fading = None;
                        sink.set_volume(volume);
                    }
                }
            }
            Ok(Command::Fade(occurrence, duration)) => {
                if let Some((id, _, sink)) = &playing {
                    if *id == occurrence {
                        fading = Some((occurrence, Instant::now(), duration, sink.volume()));
                    }
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
        if let (Some((id, _, sink)), Some((occurrence, start, duration, from))) = (&playing, fading) {
            if *id == occurrence {
                let remaining = if duration.is_zero() { 0.0 } else {
                    (1.0 - start.elapsed().as_secs_f32() / duration.as_secs_f32()).max(0.0)
                };
                sink.set_volume(from * remaining * remaining);
            }
        }
    }
}

#[cfg(not(desktop))]
fn run(receiver: mpsc::Receiver<Command>, _active: Arc<ToneGate>) {
    for command in receiver {
        if let Command::Start(_, _, reply) = command {
            let _ = reply.send(Err("Android owns its emergency alarm audio".into()));
        }
    }
}
