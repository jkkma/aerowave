//! Non-disruptive Windows power adapter check. It never sleeps or shuts down
//! the machine, and its wake request is canceled immediately after arming.

#[allow(dead_code)]
#[path = "../src/power.rs"]
mod power;

fn main() -> Result<(), String> {
    println!(
        "{}",
        serde_json::to_string_pretty(&power::capabilities()).unwrap()
    );
    let mut manager = power::PowerManager::new();
    manager.keep_awake(true);
    manager.keep_awake(false);
    let deadline = chrono::Utc::now().timestamp_millis() + 24 * 60 * 60 * 1_000;
    manager.sync_wake(Some(deadline))?;
    manager.sync_wake(Some(deadline))?;
    manager.sync_wake(None)?;
    power::execute(aerowave_core::sleep::SleepAction::Stop)?;
    println!("Wake timer armed, unchanged deadline retained, and canceled. No power transition requested.");
    Ok(())
}
