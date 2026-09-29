// Compile the actual bridge model in the testable core crate. A separate
// copy of its shape could pass while the Tauri bridge still drops fields.
#[allow(dead_code)]
#[path = "../../plugins/tauri-plugin-android-audio/src/models.rs"]
mod android_audio_models;

use android_audio_models::AlarmState;
use serde_json::{json, Value};

fn native_state(permissions: Value) -> Value {
    json!({
        "initialized": true,
        "revision": 7,
        "alarms": [],
        "next": null,
        "ringing": null,
        "permissions": permissions,
        "error": null
    })
}

#[test]
fn permission_snapshot_survives_the_production_bridge_model() {
    let native = native_state(json!({
        "exact": "granted",
        "notifications": "granted",
        "alarmChannel": "high",
        "fullScreen": "notRequired",
        "batteryOptimized": true,
        "manufacturer": "Xiaomi",
        "brand": "POCO",
        "alarmScreenOverlay": "granted",
        "dnd": {
            "active": true,
            "access": "denied",
            "alarmBypass": false,
            "fullScreenSuppressed": true,
            "alarmsAllowed": true,
            "mediaAllowed": true
        }
    }));

    let bridged: AlarmState = serde_json::from_value(native.clone()).unwrap();
    let webview = serde_json::to_value(bridged).unwrap();
    assert_eq!(webview["permissions"], native["permissions"]);
}

#[test]
fn older_permission_snapshot_keeps_unavailable_checks_unknown() {
    let older = native_state(json!({
        "exact": "granted",
        "notifications": "granted",
        "fullScreen": "notRequired",
        "batteryOptimized": true
    }));

    let bridged: AlarmState = serde_json::from_value(older).unwrap();
    let webview = serde_json::to_value(bridged).unwrap();
    let permissions = &webview["permissions"];
    assert!(permissions["alarmChannel"].is_null());
    assert_eq!(permissions["manufacturer"], "");
    assert_eq!(permissions["brand"], "");
    assert_eq!(permissions["exact"], "granted");
    assert_eq!(permissions["notifications"], "granted");
    assert_eq!(permissions["alarmScreenOverlay"], "unknown");
    assert_eq!(permissions["dnd"]["access"], "unknown");
    for field in [
        "active", "alarmBypass", "fullScreenSuppressed", "alarmsAllowed", "mediaAllowed",
    ] {
        assert!(permissions["dnd"][field].is_null(), "{field}");
    }
}
