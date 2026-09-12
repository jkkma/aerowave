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

fn main() -> Result<(), String> {
    println!(
        "{}",
        serde_json::to_string_pretty(&power::capabilities()).unwrap()
    );
    let mut manager = power::PowerManager::new();
    manager.keep_awake(true, false)?;
    verify_execution_state(true, false)?;
    manager.keep_awake(true, true)?;
    verify_execution_state(true, true)?;
    manager.keep_awake(true, false)?;
    verify_execution_state(true, false)?;
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
    power::execute(aerowave_core::sleep::SleepAction::Stop)?;
    println!("System-only and alarm display requests verified, including display release while the system remains awake and cleanup on drop.");
    println!("Persistent alarm hold verified through snooze and dismissal, including release for explicit power actions and restoration after failure.");
    println!("Wake timer armed, unchanged deadline retained, and canceled. No sleep or shutdown requested.");
    Ok(())
}
