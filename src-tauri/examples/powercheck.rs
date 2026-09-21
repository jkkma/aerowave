//! Non-disruptive Windows power adapter check. It never sleeps or shuts down
//! the machine, and its wake request is canceled immediately after arming.

#[allow(dead_code)]
#[path = "../src/power.rs"]
mod power;

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
    use aerowave_core::sleep::{wake_plan, SleepAction};
    use chrono::{TimeZone, Utc};
    use std::sync::mpsc;
    use std::time::{Duration, Instant};

    for expected in [Ok(()), Err("simulated sleep failure".to_string())] {
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
        let plan = wake_plan(
            alarm.timestamp_millis() - 45_000,
            Some(alarm.timestamp_millis()),
            false,
        );
        assert!(plan.keep_awake && plan.keep_display_awake);
        manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
        verify_execution_state(true, true)?;
        assert!(aerowave_core::schedule::due_now(7, 30, &[], &alarm));
        assert!(
            task.try_result().is_none(),
            "alarm work waited for the power action"
        );

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
        manager.keep_awake(false, false)?;
    }
    println!("A blocked power action leaves alarm preparation and due checks runnable; both success and failure completions delivered. No sleep requested.");
    Ok(())
}

fn main() -> Result<(), String> {
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
    let mut hold = aerowave_core::sleep::AlarmWakeHold::default();
    hold.alarm_fired(true);
    for (next, ringing, display) in [
        (None, true, true),
        (Some(600_000), false, false),
        (None, false, false),
    ] {
        let plan = hold.plan(0, next, ringing, true);
        manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
        verify_execution_state(true, display)?;
    }
    let before_power_action = std::mem::take(&mut hold);
    let plan = hold.plan(0, None, false, true);
    manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
    verify_execution_state(false, false)?;
    // A refused power action restores the hold without an actual suspension.
    hold.finish_power_action(
        before_power_action,
        aerowave_core::sleep::SleepAction::Sleep,
        false,
    );
    let plan = hold.plan(0, None, false, true);
    manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
    verify_execution_state(true, false)?;
    let plan = hold.plan(0, None, false, false);
    manager.keep_awake(plan.keep_awake, plan.keep_display_awake)?;
    verify_execution_state(false, false)?;
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
    println!("Persistent alarm hold verified through snooze and dismissal, including release for explicit power actions and restoration after failure.");
    println!("Wake timer armed, unchanged deadline retained, and canceled. No sleep or shutdown requested.");
    Ok(())
}
