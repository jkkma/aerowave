//! Decide whether a settings save should change the operating-system startup entry.

#[derive(Debug, PartialEq, Eq)]
pub enum AutostartAction {
    Enable,
    Disable,
    ReflectDisabled,
    Keep,
}

pub fn autostart_action(
    want: bool,
    explicit: Option<bool>,
    entry_matches: bool,
    disabled_externally: bool,
) -> AutostartAction {
    match explicit {
        Some(true) => AutostartAction::Enable,
        Some(false) => AutostartAction::Disable,
        None if !want => AutostartAction::Disable,
        None if disabled_externally => AutostartAction::ReflectDisabled,
        None if !entry_matches => AutostartAction::Enable,
        None => AutostartAction::Keep,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_volume_save_respects_the_external_disable() {
        assert_eq!(
            autostart_action(true, None, true, true),
            AutostartAction::ReflectDisabled
        );
        assert_eq!(
            autostart_action(true, None, false, true),
            AutostartAction::ReflectDisabled
        );
    }

    #[test]
    fn an_explicit_enable_can_reverse_the_external_disable() {
        assert_eq!(
            autostart_action(false, Some(true), true, true),
            AutostartAction::Enable
        );
        assert_eq!(
            autostart_action(true, Some(false), true, false),
            AutostartAction::Disable
        );
    }

    #[test]
    fn ordinary_saves_repair_a_moved_entry_without_rewriting_a_good_one() {
        assert_eq!(
            autostart_action(true, None, false, false),
            AutostartAction::Enable
        );
        assert_eq!(
            autostart_action(true, None, true, false),
            AutostartAction::Keep
        );
    }
}
