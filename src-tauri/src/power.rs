//! Windows owns the actual power transitions and wake request. The scheduler
//! owns this adapter on one thread, since execution-state requests belong to
//! the thread that made them.

pub use aerowave_core::power::PowerCapabilities;
use aerowave_core::sleep::SleepAction;

#[cfg(windows)]
pub use platform::{capabilities, execute, PowerManager};

#[cfg(windows)]
mod platform {
    use super::{PowerCapabilities, SleepAction};
    use aerowave_core::power::{
        classify_capabilities, unix_ms_to_filetime, PowerFacts, SleepState, WakePolicy,
    };
    use std::mem::size_of;
    use std::ptr::{null, null_mut};
    use std::time::{Duration, Instant};
    use windows_sys::Win32::Foundation::{
        CloseHandle, GetLastError, LocalFree, SetLastError, ERROR_NOT_ALL_ASSIGNED,
        ERROR_NOT_SUPPORTED, ERROR_SUCCESS, HANDLE, LUID,
    };
    use windows_sys::Win32::Security::{
        AdjustTokenPrivileges, LookupPrivilegeValueW, LUID_AND_ATTRIBUTES, SE_PRIVILEGE_ENABLED,
        SE_SHUTDOWN_NAME, TOKEN_ADJUST_PRIVILEGES, TOKEN_PRIVILEGES, TOKEN_QUERY,
    };
    use windows_sys::Win32::System::Power::{
        GetPwrCapabilities, GetSystemPowerStatus, PowerGetActiveScheme, PowerReadACValueIndex,
        PowerReadDCValueIndex, PowerSystemHibernate, PowerSystemShutdown, PowerSystemSleeping1,
        PowerSystemSleeping2, PowerSystemSleeping3, SetSuspendState, SetThreadExecutionState,
        ES_CONTINUOUS, ES_SYSTEM_REQUIRED, SYSTEM_POWER_CAPABILITIES, SYSTEM_POWER_STATUS,
    };
    use windows_sys::Win32::System::Shutdown::{
        ExitWindowsEx, EWX_POWEROFF, SHTDN_REASON_FLAG_PLANNED, SHTDN_REASON_MAJOR_APPLICATION,
        SHTDN_REASON_MINOR_OTHER,
    };
    use windows_sys::Win32::System::SystemServices::{GUID_ALLOW_RTC_WAKE, GUID_SLEEP_SUBGROUP};
    use windows_sys::Win32::System::Threading::{
        CancelWaitableTimer, CreateWaitableTimerW, GetCurrentProcess, OpenProcessToken,
        SetWaitableTimer,
    };

    struct OwnedHandle(HANDLE);

    impl Drop for OwnedHandle {
        fn drop(&mut self) {
            unsafe { CloseHandle(self.0) };
        }
    }

    fn win_error(operation: &str, code: u32) -> String {
        format!(
            "{operation}: {}",
            std::io::Error::from_raw_os_error(code as i32)
        )
    }

    pub struct PowerManager {
        timer: Option<OwnedHandle>,
        armed_at_ms: Option<i64>,
        last_failure: Option<(i64, Instant, String)>,
        awake: bool,
        awake_request_failed: bool,
    }

    impl PowerManager {
        pub fn new() -> Self {
            Self {
                timer: None,
                armed_at_ms: None,
                last_failure: None,
                awake: false,
                awake_request_failed: false,
            }
        }

        pub fn sync_wake(&mut self, at_ms: Option<i64>) -> Result<(), String> {
            let Some(at_ms) = at_ms else {
                self.cancel();
                self.last_failure = None;
                return Ok(());
            };
            if Some(at_ms) == self.armed_at_ms {
                return Ok(());
            }
            // Hardware/policy failures rarely change within a scheduler tick.
            // Keep their diagnostic without asking Windows twice each second.
            if let Some((failed_at, attempted, error)) = &self.last_failure {
                if *failed_at == at_ms && attempted.elapsed() < Duration::from_secs(30) {
                    return Err(error.clone());
                }
            }
            // Dispose of the previous request before trying a new one. A failed
            // replacement must never leave a deleted alarm waking the machine.
            self.cancel();
            self.last_failure = None;
            let result = self.arm(at_ms);
            if let Err(error) = &result {
                self.last_failure = Some((at_ms, Instant::now(), error.clone()));
            }
            result
        }

        fn arm(&mut self, at_ms: i64) -> Result<(), String> {
            // FILETIME uses 100 ns ticks from 1601. A positive UTC deadline
            // continues through suspend; Windows pauses relative timers there.
            let due = unix_ms_to_filetime(at_ms)
                .ok_or_else(|| "Alarm time is outside the Windows wake timer range.".to_string())?;
            let handle = unsafe { CreateWaitableTimerW(null(), 1, null()) };
            if handle.is_null() {
                return Err(win_error("Could not create the alarm wake timer", unsafe {
                    GetLastError()
                }));
            }
            let timer = OwnedHandle(handle);
            // Windows can return success with ERROR_NOT_SUPPORTED when it
            // accepted the timer but cannot resume the computer for it.
            unsafe { SetLastError(ERROR_SUCCESS) };
            let result = unsafe { SetWaitableTimer(timer.0, &due, 0, None, null(), 1) };
            let code = unsafe { GetLastError() };
            if result == 0 || code == ERROR_NOT_SUPPORTED {
                unsafe { CancelWaitableTimer(timer.0) };
                return Err(win_error("Could not arm the alarm wake timer", code));
            }
            self.timer = Some(timer);
            self.armed_at_ms = Some(at_ms);
            Ok(())
        }

        fn cancel(&mut self) {
            if let Some(timer) = self.timer.take() {
                unsafe { CancelWaitableTimer(timer.0) };
                // Closing our sole, unnamed handle also disposes of the timer
                // if cancellation fails during shutdown or resume.
            }
            self.armed_at_ms = None;
        }

        pub fn keep_awake(&mut self, awake: bool) {
            if self.awake == awake {
                return;
            }
            let flags = ES_CONTINUOUS | if awake { ES_SYSTEM_REQUIRED } else { 0 };
            if unsafe { SetThreadExecutionState(flags) } != 0 {
                self.awake = awake;
                self.awake_request_failed = false;
            } else if !self.awake_request_failed {
                eprintln!("Windows refused the alarm's keep-awake request (requested: {awake}).");
                self.awake_request_failed = true;
            }
        }
    }

    impl Drop for PowerManager {
        fn drop(&mut self) {
            self.cancel();
            self.keep_awake(false);
        }
    }

    struct ShutdownPrivilege {
        token: OwnedHandle,
        previous: TOKEN_PRIVILEGES,
    }

    impl ShutdownPrivilege {
        fn enable() -> Result<Self, String> {
            let mut raw = null_mut();
            if unsafe {
                OpenProcessToken(
                    GetCurrentProcess(),
                    TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY,
                    &mut raw,
                )
            } == 0
            {
                return Err(win_error(
                    "Could not open the Windows power permission",
                    unsafe { GetLastError() },
                ));
            }
            let token = OwnedHandle(raw);
            let mut luid = LUID::default();
            if unsafe { LookupPrivilegeValueW(null(), SE_SHUTDOWN_NAME, &mut luid) } == 0 {
                return Err(win_error(
                    "Could not find the Windows power permission",
                    unsafe { GetLastError() },
                ));
            }
            let requested = TOKEN_PRIVILEGES {
                PrivilegeCount: 1,
                Privileges: [LUID_AND_ATTRIBUTES {
                    Luid: luid,
                    Attributes: SE_PRIVILEGE_ENABLED,
                }],
            };
            let mut guard = Self {
                token,
                previous: TOKEN_PRIVILEGES::default(),
            };
            let mut written = 0;
            unsafe { SetLastError(ERROR_SUCCESS) };
            let result = unsafe {
                AdjustTokenPrivileges(
                    guard.token.0,
                    0,
                    &requested,
                    size_of::<TOKEN_PRIVILEGES>() as u32,
                    &mut guard.previous,
                    &mut written,
                )
            };
            let code = unsafe { GetLastError() };
            if result == 0 || code == ERROR_NOT_ALL_ASSIGNED {
                return Err(win_error(
                    "Windows did not grant permission to sleep or shut down",
                    code,
                ));
            }
            Ok(guard)
        }
    }

    impl Drop for ShutdownPrivilege {
        fn drop(&mut self) {
            unsafe {
                AdjustTokenPrivileges(self.token.0, 0, &self.previous, 0, null_mut(), null_mut());
            }
        }
    }

    pub fn execute(action: SleepAction) -> Result<(), String> {
        if action == SleepAction::Stop {
            return Ok(());
        }
        let _permission = ShutdownPrivilege::enable()?;
        let result = unsafe {
            match action {
                SleepAction::Stop => true,
                // Wake events stay enabled so our alarm can resume the PC.
                SleepAction::Sleep => SetSuspendState(false, false, false),
                // Without FORCE or FORCEIFHUNG, Windows lets applications with
                // unsaved work block shutdown instead of discarding their data.
                SleepAction::Shutdown => {
                    ExitWindowsEx(
                        EWX_POWEROFF,
                        SHTDN_REASON_MAJOR_APPLICATION
                            | SHTDN_REASON_MINOR_OTHER
                            | SHTDN_REASON_FLAG_PLANNED,
                    ) != 0
                }
            }
        };
        if result {
            Ok(())
        } else {
            Err(win_error(
                "Windows could not complete the sleep timer action",
                unsafe { GetLastError() },
            ))
        }
    }

    fn wake_policy(on_battery: Option<bool>) -> Option<u32> {
        let on_battery = on_battery?;
        let mut scheme = null_mut();
        if unsafe { PowerGetActiveScheme(null_mut(), &mut scheme) } != ERROR_SUCCESS
            || scheme.is_null()
        {
            return None;
        }
        let mut value = 0;
        let result = unsafe {
            if on_battery {
                PowerReadDCValueIndex(
                    null_mut(),
                    scheme,
                    &GUID_SLEEP_SUBGROUP,
                    &GUID_ALLOW_RTC_WAKE,
                    &mut value,
                )
            } else {
                PowerReadACValueIndex(
                    null_mut(),
                    scheme,
                    &GUID_SLEEP_SUBGROUP,
                    &GUID_ALLOW_RTC_WAKE,
                    &mut value,
                )
            }
        };
        unsafe { LocalFree(scheme.cast()) };
        (result == ERROR_SUCCESS).then_some(value)
    }

    pub fn capabilities() -> PowerCapabilities {
        let mut status = SYSTEM_POWER_STATUS::default();
        let on_battery = if unsafe { GetSystemPowerStatus(&mut status) } != 0 {
            match status.ACLineStatus {
                0 => Some(true),
                1 => Some(false),
                _ => None,
            }
        } else {
            None
        };
        let mut caps = SYSTEM_POWER_CAPABILITIES::default();
        let known = unsafe { GetPwrCapabilities(&mut caps) };
        #[allow(non_upper_case_globals)]
        let rtc_wake = match caps.RtcWake {
            PowerSystemSleeping1 => Some(SleepState::Sleep1),
            PowerSystemSleeping2 => Some(SleepState::Sleep2),
            PowerSystemSleeping3 => Some(SleepState::Sleep3),
            PowerSystemHibernate => Some(SleepState::Hibernate),
            PowerSystemShutdown => Some(SleepState::PowerOff),
            _ => None,
        };
        let policy = match wake_policy(on_battery) {
            Some(0) => WakePolicy::Disabled,
            Some(1) => WakePolicy::Enabled,
            Some(2) => WakePolicy::ImportantOnly,
            _ => WakePolicy::Unknown,
        };
        classify_capabilities(PowerFacts {
            known,
            sleep_s1: caps.SystemS1,
            sleep_s2: caps.SystemS2,
            sleep_s3: caps.SystemS3,
            hibernate: caps.SystemS4,
            rtc_wake,
            modern_standby: caps.AoAc,
            wake_alarm_present: caps.WakeAlarmPresent,
            on_battery,
            policy,
        })
    }
}

#[cfg(not(windows))]
pub struct PowerManager;

#[cfg(not(windows))]
impl PowerManager {
    pub fn new() -> Self {
        Self
    }
    pub fn sync_wake(&mut self, at_ms: Option<i64>) -> Result<(), String> {
        if at_ms.is_some() {
            Err("Alarm wake is only supported on Windows.".into())
        } else {
            Ok(())
        }
    }
    pub fn keep_awake(&mut self, _awake: bool) {}
}

#[cfg(not(windows))]
pub fn execute(action: SleepAction) -> Result<(), String> {
    if action == SleepAction::Stop {
        Ok(())
    } else {
        Err("PC power actions are only supported on Windows.".into())
    }
}

#[cfg(not(windows))]
pub fn capabilities() -> PowerCapabilities {
    PowerCapabilities {
        sleep_supported: false,
        shutdown_supported: false,
        wake_supported: false,
        wake_allowed: Some(false),
        on_battery: None,
        message: "PC power actions and alarm wake are only supported on Windows.".into(),
    }
}
