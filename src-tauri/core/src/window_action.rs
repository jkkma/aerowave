//! Coordinate a native close request with pending settings in the webview.

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Action {
    Close,
    Quit,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Request {
    pub id: u64,
    pub action: Action,
    acknowledged: bool,
}

#[derive(Default)]
pub struct Requests {
    sequence: u64,
    pending: Option<Request>,
}

impl Requests {
    pub fn begin(&mut self, action: Action) -> Option<Request> {
        if let Some(pending) = self.pending {
            // Repeated clicks must not extend the fallback deadline. An
            // explicit Quit can still replace an ordinary close-to-tray.
            if pending.action == Action::Quit || pending.action == action {
                return None;
            }
        }
        self.sequence = self.sequence.checked_add(1)?;
        let request = Request {
            id: self.sequence,
            action,
            acknowledged: false,
        };
        self.pending = Some(request);
        Some(request)
    }

    pub fn acknowledge(&mut self, id: u64) -> bool {
        match self.pending.as_mut() {
            Some(request) if request.id == id => {
                request.acknowledged = true;
                true
            }
            _ => false,
        }
    }

    pub fn complete(&mut self, id: u64, saved: bool) -> Option<Action> {
        let request = self.pending?;
        if request.id != id || !request.acknowledged {
            return None;
        }
        self.pending = None;
        saved.then_some(request.action)
    }

    pub fn fallback(&mut self, id: u64) -> Option<Action> {
        let request = self.pending?;
        if request.id != id || request.acknowledged {
            return None;
        }
        self.pending = None;
        Some(request.action)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_live_page_must_finish_saving_before_closing() {
        let mut requests = Requests::default();
        let close = requests.begin(Action::Close).unwrap();
        assert_eq!(requests.complete(close.id, true), None);
        assert!(requests.acknowledge(close.id));
        assert_eq!(requests.fallback(close.id), None);
        assert_eq!(requests.complete(close.id, true), Some(Action::Close));
        assert_eq!(requests.complete(close.id, true), None);
    }

    #[test]
    fn save_failure_cancels_close_and_allows_retry() {
        let mut requests = Requests::default();
        let quit = requests.begin(Action::Quit).unwrap();
        requests.acknowledge(quit.id);
        assert_eq!(requests.complete(quit.id, false), None);
        assert_eq!(requests.fallback(quit.id), None);
        let retry = requests.begin(Action::Quit).unwrap();
        assert_ne!(quit.id, retry.id);
        assert_eq!(requests.fallback(quit.id), None);
        assert_eq!(requests.fallback(retry.id), Some(Action::Quit));
    }

    #[test]
    fn an_unresponsive_page_does_not_trap_the_app() {
        let mut requests = Requests::default();
        let quit = requests.begin(Action::Quit).unwrap();
        assert!(requests.begin(Action::Quit).is_none());
        assert!(requests.begin(Action::Close).is_none());
        assert_eq!(requests.fallback(quit.id), Some(Action::Quit));
        assert!(!requests.acknowledge(quit.id));
    }

    #[test]
    fn quit_supersedes_close_and_ignores_old_replies() {
        let mut requests = Requests::default();
        let close = requests.begin(Action::Close).unwrap();
        requests.acknowledge(close.id);
        let quit = requests.begin(Action::Quit).unwrap();
        assert_ne!(close.id, quit.id);
        assert!(!requests.acknowledge(close.id));
        assert_eq!(requests.complete(close.id, true), None);
        assert_eq!(requests.fallback(close.id), None);
        requests.acknowledge(quit.id);
        assert_eq!(requests.complete(quit.id, true), Some(Action::Quit));
    }
}
