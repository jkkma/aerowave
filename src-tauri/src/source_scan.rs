//! Bounded filesystem scanning for alarm and folder commands.
//!
//! Windows cannot cancel a directory enumeration stalled in a filesystem
//! driver or on a disconnected share. A preferred-folder worker and a separate
//! backup-folder worker each have one queued job. A stalled preferred share
//! cannot consume the backup worker; callers have their own five-second limit.

use std::path::{Path, PathBuf};
use std::sync::{mpsc, Arc};
use std::time::Instant;

use tokio::sync::oneshot;

use crate::library::{self, RecentTracks};

pub struct Pick {
    pub path: PathBuf,
    pub total: usize,
    pub completed_at: Instant,
}

enum Job {
    Scan {
        path: PathBuf,
        reply: oneshot::Sender<Vec<PathBuf>>,
    },
    Pick {
        path: PathBuf,
        held: Option<PathBuf>,
        recent: Arc<RecentTracks>,
        authorize: Box<dyn FnOnce(&Path) -> Result<(), String> + Send>,
        reply: oneshot::Sender<Result<Option<Pick>, String>>,
    },
}

impl Job {
    fn abandoned(&self) -> bool {
        match self {
            Job::Scan { reply, .. } => reply.is_closed(),
            Job::Pick { reply, .. } => reply.is_closed(),
        }
    }
}

pub struct Scanner {
    preferred: mpsc::SyncSender<Job>,
    backup: mpsc::SyncSender<Job>,
}

impl Scanner {
    pub fn new() -> Self {
        Self::with_scan(library::scan)
    }

    /// The injected scanner lets the non-disruptive probe hold one lane open
    /// without depending on a real network filesystem or touching app data.
    pub fn with_scan(scan: impl Fn(&Path) -> Vec<PathBuf> + Send + Sync + 'static) -> Self {
        let scan = Arc::new(scan);
        fn worker(
            name: &str,
            scan: Arc<impl Fn(&Path) -> Vec<PathBuf> + Send + Sync + 'static>,
        ) -> mpsc::SyncSender<Job> {
            let (jobs, receiver) = mpsc::sync_channel::<Job>(1);
            std::thread::Builder::new()
                .name(format!("aerowave-folder-{name}"))
                .spawn(move || loop {
                    let job = match receiver.recv() {
                        Ok(job) => job,
                        Err(_) => break,
                    };
                    // A timed-out or dismissed request has no consumer. It may
                    // still finish in the OS, but cannot modify alarm state.
                    if job.abandoned() {
                        continue;
                    }
                    match job {
                        Job::Scan { path, reply } => {
                            let _ = reply.send(scan(&path));
                        }
                        Job::Pick {
                            path,
                            held,
                            recent,
                            authorize,
                            reply,
                        } => {
                            let files = scan(&path);
                            // Cancellation can arrive while the filesystem is
                            // inside scan(). Do not then enter scope's own
                            // canonicalization for an obsolete request.
                            if reply.is_closed() {
                                continue;
                            }
                            let track = held
                                .filter(|track| files.contains(track))
                                .or_else(|| library::choose_random(&files, &recent));
                            let result = track
                                .map(|track| {
                                    authorize(&track)?;
                                    Ok(Pick {
                                        path: track,
                                        total: files.len(),
                                        completed_at: Instant::now(),
                                    })
                                })
                                .transpose();
                            let _ = reply.send(result);
                        }
                    }
                })
                .expect("start folder scanner");
            jobs
        }
        Self {
            preferred: worker("preferred", scan.clone()),
            backup: worker("backup", scan),
        }
    }

    pub fn scan(&self, path: PathBuf) -> Result<oneshot::Receiver<Vec<PathBuf>>, String> {
        let (reply, receiver) = oneshot::channel();
        self.preferred
            .try_send(Job::Scan { path, reply })
            .map_err(|_| "folder scanner is busy".to_string())?;
        Ok(receiver)
    }

    pub fn scan_backup(&self, path: PathBuf) -> Result<oneshot::Receiver<Vec<PathBuf>>, String> {
        let (reply, receiver) = oneshot::channel();
        self.backup
            .try_send(Job::Scan { path, reply })
            .map_err(|_| "backup folder scanner is busy".to_string())?;
        Ok(receiver)
    }

    pub fn pick(
        &self,
        path: PathBuf,
        held: Option<PathBuf>,
        recent: Arc<RecentTracks>,
        backup: bool,
        authorize: impl FnOnce(&Path) -> Result<(), String> + Send + 'static,
    ) -> Result<oneshot::Receiver<Result<Option<Pick>, String>>, String> {
        let (reply, receiver) = oneshot::channel();
        let jobs = if backup {
            &self.backup
        } else {
            &self.preferred
        };
        jobs.try_send(Job::Pick {
            path,
            held,
            recent,
            authorize: Box::new(authorize),
            reply,
        })
        .map_err(|_| "folder scanner is busy".to_string())?;
        Ok(receiver)
    }
}
