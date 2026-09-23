//! Non-disruptive Windows power adapter check. It never sleeps or shuts down
//! the machine, and its wake request is canceled immediately after arming.

#[allow(dead_code)]
#[path = "../src/power.rs"]
mod power;

#[cfg(windows)]
mod modern_standby_probe {
    use std::ptr::{null, null_mut};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::mpsc;
    use std::time::{Duration, Instant};
    use windows_sys::Win32::Foundation::{GetLastError, HWND, LPARAM, LRESULT, WPARAM};
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetMessageW,
        PostMessageW, PostQuitMessage, RegisterClassW, UnregisterClassW, HWND_MESSAGE, MSG,
        SC_MONITORPOWER, WM_APP, WM_CLOSE, WM_DESTROY, WM_SYSCOMMAND, WNDCLASSW,
    };

    static DISPLAY_OFF_REQUESTS: AtomicUsize = AtomicUsize::new(0);
    static PROBES_RECEIVED: AtomicUsize = AtomicUsize::new(0);
    const PROBE_MESSAGE: u32 = WM_APP + 1;

    unsafe extern "system" fn fixture_window_proc(
        window: HWND,
        message: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        if message == WM_SYSCOMMAND && wparam & 0xfff0 == SC_MONITORPOWER as usize {
            // Never forward this request: DefWindowProc would power off the real display.
            if lparam == 2 {
                DISPLAY_OFF_REQUESTS.fetch_add(1, Ordering::SeqCst);
            }
            return 0;
        }
        match message {
            PROBE_MESSAGE => {
                PROBES_RECEIVED.fetch_add(1, Ordering::SeqCst);
                0
            }
            WM_CLOSE => {
                unsafe { DestroyWindow(window) };
                0
            }
            WM_DESTROY => {
                unsafe { PostQuitMessage(0) };
                0
            }
            _ => unsafe { DefWindowProcW(window, message, wparam, lparam) },
        }
    }

    struct MessageWindow {
        window: isize,
        start_pump: Option<mpsc::Sender<()>>,
        thread: Option<std::thread::JoinHandle<Result<(), String>>>,
    }

    impl MessageWindow {
        fn spawn(name: &str, pump_immediately: bool) -> Result<Self, String> {
            let class_name: Vec<u16> = format!("AerowavePowercheck{name}\0")
                .encode_utf16()
                .collect();
            let (ready_send, ready) = mpsc::sync_channel(1);
            let (start_pump, wait_to_pump) = mpsc::channel();
            let thread = std::thread::spawn(move || {
                let class = WNDCLASSW {
                    lpfnWndProc: Some(fixture_window_proc),
                    lpszClassName: class_name.as_ptr(),
                    ..WNDCLASSW::default()
                };
                if unsafe { RegisterClassW(&class) } == 0 {
                    let error = last_error("Could not register the powercheck window class");
                    let _ = ready_send.send(Err(error.clone()));
                    return Err(error);
                }
                let window = unsafe {
                    CreateWindowExW(
                        0,
                        class_name.as_ptr(),
                        null(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        HWND_MESSAGE,
                        null_mut(),
                        null_mut(),
                        null(),
                    )
                };
                if window.is_null() {
                    let error = last_error("Could not create the powercheck message window");
                    unsafe { UnregisterClassW(class_name.as_ptr(), null_mut()) };
                    let _ = ready_send.send(Err(error.clone()));
                    return Err(error);
                }
                if ready_send.send(Ok(window as isize)).is_err() {
                    unsafe { DestroyWindow(window) };
                    unsafe { UnregisterClassW(class_name.as_ptr(), null_mut()) };
                    return Ok(());
                }
                if !pump_immediately {
                    let _ = wait_to_pump.recv();
                }
                let mut message = MSG::default();
                let result = loop {
                    match unsafe { GetMessageW(&mut message, null_mut(), 0, 0) } {
                        -1 => break Err(last_error("The powercheck message loop failed")),
                        0 => break Ok(()),
                        _ => unsafe {
                            DispatchMessageW(&message);
                        },
                    }
                };
                unsafe { UnregisterClassW(class_name.as_ptr(), null_mut()) };
                result
            });
            let window = ready
                .recv_timeout(Duration::from_secs(5))
                .map_err(|error| format!("Powercheck window did not start: {error}"))??;
            Ok(Self {
                window,
                start_pump: (!pump_immediately).then_some(start_pump),
                thread: Some(thread),
            })
        }

        fn hwnd(&self) -> isize {
            self.window
        }

        fn start_pumping(&mut self) -> Result<(), String> {
            if let Some(start) = self.start_pump.take() {
                start.send(()).map_err(|error| error.to_string())?;
            }
            Ok(())
        }

        fn finish(mut self) -> Result<(), String> {
            self.start_pumping()?;
            if unsafe { PostMessageW(self.window as HWND, WM_CLOSE, 0, 0) } == 0 {
                return Err(last_error("Could not close the powercheck message window"));
            }
            self.thread
                .take()
                .unwrap()
                .join()
                .map_err(|_| "The powercheck window thread panicked.".to_string())?
        }
    }

    impl Drop for MessageWindow {
        fn drop(&mut self) {
            if let Some(thread) = self.thread.take() {
                if let Some(start) = self.start_pump.take() {
                    let _ = start.send(());
                }
                let _ = unsafe { PostMessageW(self.window as HWND, WM_CLOSE, 0, 0) };
                let _ = thread.join();
            }
        }
    }

    fn last_error(operation: &str) -> String {
        format!(
            "{operation}: {}",
            std::io::Error::from_raw_os_error(unsafe { GetLastError() } as i32)
        )
    }

    fn wait_for_probe() -> Result<(), String> {
        let deadline = Instant::now() + Duration::from_secs(2);
        while PROBES_RECEIVED.load(Ordering::SeqCst) == 0 {
            if Instant::now() >= deadline {
                return Err("The resumed powercheck window did not process its probe.".into());
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        Ok(())
    }

    pub fn verify() -> Result<(), String> {
        DISPLAY_OFF_REQUESTS.store(0, Ordering::SeqCst);
        let responsive = MessageWindow::spawn("Responsive", true)?;
        super::power::modern_standby(Some(responsive.hwnd()))?;
        if DISPLAY_OFF_REQUESTS.load(Ordering::SeqCst) != 1 {
            return Err(
                "The responsive target did not receive exactly one display-off request.".into(),
            );
        }
        responsive.finish()?;

        DISPLAY_OFF_REQUESTS.store(0, Ordering::SeqCst);
        PROBES_RECEIVED.store(0, Ordering::SeqCst);
        let mut stalled = MessageWindow::spawn("Stalled", false)?;
        let window = stalled.hwnd();
        let (result_send, result) = mpsc::sync_channel(1);
        let caller = std::thread::spawn(move || {
            let started = Instant::now();
            let outcome = super::power::modern_standby(Some(window));
            let _ = result_send.send((outcome, started.elapsed()));
        });
        let (outcome, elapsed) = result
            .recv_timeout(Duration::from_secs(5))
            .map_err(|_| "Modern Standby delivery exceeded its bounded timeout.".to_string())?;
        caller
            .join()
            .map_err(|_| "The Modern Standby caller thread panicked.".to_string())?;
        if outcome.is_ok() || elapsed > Duration::from_secs(4) {
            return Err(format!(
                "The stalled target was not rejected within the expected bound ({elapsed:?})."
            ));
        }
        stalled.start_pumping()?;
        if unsafe { PostMessageW(stalled.hwnd() as HWND, PROBE_MESSAGE, 0, 0) } == 0 {
            return Err(last_error("Could not probe the resumed message window"));
        }
        wait_for_probe()?;
        if DISPLAY_OFF_REQUESTS.load(Ordering::SeqCst) != 0 {
            return Err(
                "The timed-out display-off request was delivered after pumping resumed.".into(),
            );
        }
        stalled.finish()?;
        println!("Modern Standby display-off delivery accepted one responsive request and canceled a timed-out request without late delivery. The fixture swallowed every display-power message.");
        Ok(())
    }
}

#[cfg(windows)]
fn verify_execution_state(system: bool, display: bool) -> Result<(), String> {
    use windows_sys::Win32::System::Power::{
        SetThreadExecutionState, ES_CONTINUOUS, ES_DISPLAY_REQUIRED, ES_SYSTEM_REQUIRED,
    };
    // The API returns the previous request. Restore it immediately after
    // reading it, keeping the adapter's cached state consistent with Windows.
    let previous = unsafe { SetThreadExecutionState(ES_CONTINUOUS) };
    if previous == 0 || unsafe { SetThreadExecutionState(previous | ES_CONTINUOUS) } == 0 {
        return Err("Could not read and restore this thread's execution-state request.".into());
    }
    let expected =
        if system { ES_SYSTEM_REQUIRED } else { 0 } | if display { ES_DISPLAY_REQUIRED } else { 0 };
    let actual = previous & (ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);
    if actual != expected {
        return Err(format!(
            "Execution-state request mismatch: expected {expected:#x}, Windows reported {actual:#x}."
        ));
    }
    Ok(())
}

#[cfg(not(windows))]
fn verify_execution_state(_system: bool, _display: bool) -> Result<(), String> {
    Ok(())
}

fn simulate_cleared_execution_state() -> Result<(), String> {
    #[cfg(windows)]
    {
        use windows_sys::Win32::System::Power::{SetThreadExecutionState, ES_CONTINUOUS};
        if unsafe { SetThreadExecutionState(ES_CONTINUOUS) } == 0 {
            return Err("Could not clear the execution-state request for the resume check.".into());
        }
    }
    Ok(())
}

fn verify_pending_power_action(manager: &mut power::PowerManager) -> Result<(), String> {
    use aerowave_core::sleep::{AlarmWakeHold, SleepAction};
    use chrono::{TimeZone, Utc};
    use std::sync::mpsc;
    use std::time::{Duration, Instant};

    for expected in [Ok(()), Err("simulated sleep failure".to_string())] {
        // Dispatch releases the sleep timer's system request before the OS
        // worker starts, leaving a later alarm free to own a new request.
        let mut hold = AlarmWakeHold::default();
        hold.alarm_fired(true);
        let plan = hold.plan(0, Some(600_000), false, true, true);
        manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
        let saved_hold = std::mem::take(&mut hold);
        manager.keep_awake(false, false)?;
        verify_execution_state(false, false)?;
        let (entered, started) = mpsc::channel();
        let (release, wait) = mpsc::channel();
        let completion = expected.clone();
        let task = power::PendingAction::spawn(SleepAction::Sleep, move |action| {
            assert_eq!(action, SleepAction::Sleep);
            let _ = entered.send(());
            wait.recv_timeout(Duration::from_secs(5))
                .map_err(|error| error.to_string())?;
            completion
        })?;
        started
            .recv_timeout(Duration::from_secs(5))
            .map_err(|error| error.to_string())?;
        assert!(task.try_result().is_none());

        // Hold the OS-call substitute open while the clock reaches preparation
        // and ringing. These requests still belong to this scheduler thread.
        let alarm = Utc.with_ymd_and_hms(2026, 1, 1, 7, 30, 0).unwrap();
        let plan = hold.plan(
            alarm.timestamp_millis() - 45_000,
            Some(alarm.timestamp_millis()),
            false,
            true,
            true,
        );
        assert!(plan.keep_awake && plan.keep_display_awake);
        manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
        verify_execution_state(true, true)?;
        assert!(aerowave_core::schedule::due_now(7, 30, &[], &alarm));
        assert!(
            task.try_result().is_none(),
            "alarm work waited for the power action"
        );
        hold.alarm_fired(true);
        let plan = hold.plan(alarm.timestamp_millis(), None, true, false, true);
        manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
        verify_execution_state(true, true)?;

        release.send(()).map_err(|error| error.to_string())?;
        let deadline = Instant::now() + Duration::from_secs(5);
        let actual = loop {
            if let Some(result) = task.try_result() {
                break result;
            }
            if Instant::now() >= deadline {
                return Err("The power action did not report completion.".into());
            }
            std::thread::park_timeout(Duration::from_millis(10));
        };
        assert_eq!(actual, expected);
        hold.finish_power_action(
            saved_hold, SleepAction::Sleep, actual.is_ok(), true, true,
        );
        let plan = hold.plan(alarm.timestamp_millis(), None, true, false, true);
        manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
        // Completing the old action must leave the new cycle protected,
        // including its next snooze after this ring ends.
        verify_execution_state(true, true)?;
        let plan = hold.plan(
            alarm.timestamp_millis(),
            Some(alarm.timestamp_millis() + 600_000),
            false, true, true,
        );
        manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
        verify_execution_state(true, false)?;
        manager.keep_awake(false, false)?;
        verify_execution_state(false, false)?;
    }
    println!("A blocked power action leaves alarm preparation and due checks runnable; success and failure completions preserve a newer ring request. No sleep requested.");
    Ok(())
}

fn main() -> Result<(), String> {
    #[cfg(windows)]
    modern_standby_probe::verify()?;
    println!(
        "{}",
        serde_json::to_string_pretty(&power::capabilities()).unwrap()
    );
    let mut manager = power::PowerManager::new();
    verify_pending_power_action(&mut manager)?;
    manager.keep_awake(true, false)?;
    verify_execution_state(true, false)?;
    manager.keep_awake(true, true)?;
    verify_execution_state(true, true)?;
    manager.keep_awake(true, false)?;
    verify_execution_state(true, false)?;
    manager.keep_awake(false, false)?;
    verify_execution_state(false, false)?;
    // Model Windows dropping a request on resume or a power-source change.
    // The adapter must recover even though the desired flags did not change.
    for display in [false, true] {
        manager.keep_awake(true, display)?;
        simulate_cleared_execution_state()?;
        verify_execution_state(false, false)?;
        manager.keep_awake(true, display)?;
        verify_execution_state(true, display)?;
    }
    manager.keep_awake(false, false)?;
    verify_execution_state(false, false)?;
    use aerowave_core::sleep::AlarmWakeHold;
    let mut hold = AlarmWakeHold::default();
    for (now, next, ringing, snoozing, system, display) in [
        (0, Some(600_000), false, false, false, false),
        (555_000, Some(600_000), false, false, true, true),
        (600_000, None, true, false, true, true),
        (600_001, Some(1_200_000), false, true, true, false),
        (1_155_000, Some(1_200_000), false, true, true, true),
        (1_200_000, None, true, false, true, true),
        (1_200_001, Some(1_800_000), false, true, true, false),
        (1_755_000, Some(1_800_000), false, true, true, true),
        (1_800_000, None, true, false, true, true),
        (1_800_001, None, false, false, false, false),
    ] {
        if ringing {
            hold.alarm_fired(true);
        }
        let plan = hold.plan(now, next, ringing, snoozing, true);
        manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
        verify_execution_state(system, display)?;
    }
    let deadline = chrono::Utc::now().timestamp_millis() + 24 * 60 * 60 * 1_000;
    manager.sync_wake(Some(deadline))?;
    manager.sync_wake(Some(deadline))?;
    manager.sync_wake(None)?;
    manager.keep_awake(true, true)?;
    drop(manager);
    verify_execution_state(false, false)?;
    power::execute(aerowave_core::sleep::SleepAction::Stop, None)?;
    println!("System-only and alarm display requests verified, including display release while the system remains awake and cleanup on drop.");
    println!("Unchanged active requests restored after a simulated Windows execution-state reset; no actual suspend or power-source transition performed.");
    println!("The system remained awake across repeated snoozes; the display released between rings, and both requests released when the alarm cycle ended.");
    println!("Wake timer armed, unchanged deadline retained, and canceled. No sleep or shutdown requested.");
    Ok(())
}
