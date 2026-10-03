/* Read-only, on-demand checks. A successful inspection is never a wake test. */
window.AlarmReadiness = {
  create({ getContext, readNext, readPower, readAndroid, readConfig, readStorage, readFolder, localTime, formatWhen }) {
    const $ = id => document.querySelector("#readiness-" + id);
    const folderRequests = new Map();
    let generation = 0;
    let inspectedKey = null;
    let busy = false;
    const key = () => JSON.stringify(getContext());
    const describeError = error => String(error?.message || error);
    const bounded = (work, ms = 5000) => new Promise(resolve => {
      const timer = setTimeout(() => resolve({ error: "Check timed out. Try again." }), ms);
      Promise.resolve().then(work).then(value => {
        clearTimeout(timer);
        resolve({ value });
      }, error => {
        clearTimeout(timer);
        resolve({ error: describeError(error) });
      });
    });
    const folder = path => {
      if (!folderRequests.has(path)) {
        // A timeout cannot cancel a platform folder provider. Reuse unfinished
        // work and cap it so repeated clicks never build an unbounded queue.
        if (folderRequests.size >= 2) return Promise.resolve({ error: "Earlier folder checks are still running. Try again when they finish." });
        const work = Promise.resolve().then(() => readFolder(path));
        folderRequests.set(path, work);
        work.then(() => folderRequests.delete(path), () => folderRequests.delete(path));
      }
      const work = folderRequests.get(path);
      return bounded(() => work, 10000);
    };
    const row = (name, status, detail, level = "unknown") => {
      const item = document.createElement("li");
      item.className = "readiness-row " + level;
      const title = document.createElement("strong");
      title.textContent = name + " · " + status;
      const description = document.createElement("p");
      description.textContent = detail;
      item.append(title, description);
      $("list").append(item);
    };
    const buttons = on => {
      busy = on;
      $("check").disabled = on;
      $("check").textContent = on ? "Checking…" : "Check again";
      $("panel").setAttribute("aria-busy", String(on));
    };
    function invalidate() {
      if (inspectedKey === null || inspectedKey === key()) return;
      generation++;
      inspectedKey = null;
      buttons(false);
      $("list").replaceChildren();
      $("summary").textContent = "Setup changed. Check again for current results.";
    }
    function selectAlarms(alarms) {
      const selected = $("alarm").value || "";
      const options = [{ id: "", label: "Next scheduled alarm" }, ...alarms.map(alarm => ({
        id: alarm.id, label: `${alarm.label || "Alarm"} · ${String(alarm.hour).padStart(2, "0")}:${String(alarm.minute).padStart(2, "0")}${alarm.enabled ? "" : " · off"}`,
      }))];
      $("alarm").replaceChildren(...options.map(option => {
        const element = document.createElement("option");
        element.value = option.id;
        element.textContent = option.label;
        return element;
      }));
      $("alarm").value = options.some(option => option.id === selected) ? selected : "";
    }
    function androidRows(snapshot) {
      const permissions = snapshot?.permissions || {};
      const problems = [];
      const unknown = [];
      for (const [field, label] of [["exact", "Alarms & reminders"], ["notifications", "notifications"], ["fullScreen", "full-screen alarm access"]]) {
        if (permissions[field] === "denied") problems.push(label + " is off");
        else if (!["granted", "notRequired"].includes(permissions[field])) unknown.push(label);
      }
      if (["blocked", "quiet"].includes(permissions.alarmChannel)) problems.push("alarm alerts need high priority");
      else if (!["high", "notRequired"].includes(permissions.alarmChannel)) unknown.push("alarm alert category");
      if (permissions.batteryOptimized === true) problems.push("battery optimization is on");
      else if (permissions.batteryOptimized !== false) unknown.push("battery limits");
      const dnd = permissions.dnd || {};
      if (dnd.active === true) {
        if (dnd.mediaAllowed === false) problems.push("Do Not Disturb blocks media sound");
        else if (dnd.mediaAllowed !== true) unknown.push("Do Not Disturb media exceptions");
        if (dnd.fullScreenSuppressed === true) problems.push("Do Not Disturb may hide the alarm screen");
        else if (dnd.fullScreenSuppressed !== false) unknown.push("Do Not Disturb alarm screen rules");
        if (dnd.alarmsAllowed === false && dnd.alarmBypass === false) problems.push("Do Not Disturb blocks alarm alerts");
        else if (dnd.alarmsAllowed !== true && dnd.alarmBypass !== true) unknown.push("Do Not Disturb alarm exceptions");
        if (/xiaomi|redmi|poco/i.test(`${permissions.manufacturer} ${permissions.brand}`) && permissions.alarmScreenOverlay !== "granted") {
          problems.push("review Xiaomi/Redmi/POCO alarm screen access");
        }
      } else if (dnd.active !== false) unknown.push("Do Not Disturb");
      row("Android access", problems.length ? "Needs attention" : unknown.length ? "Partly unknown" : "Inspected",
        [...problems, ...(unknown.length ? ["Could not confirm: " + unknown.join(", ")] : []),
          "Review Permissions below for controls and phone-specific limits. Access does not verify audible screen-off wake."].join(". "),
        problems.length ? "attention" : "unknown");
      const audio = snapshot?.audio || {};
      const volumeKnown = Number.isFinite(audio.mediaVolume) && Number.isFinite(audio.mediaVolumeMax) && audio.mediaVolumeMax > 0;
      const silent = volumeKnown && audio.mediaVolume === 0 || audio.mediaMuted === true;
      row("Phone media volume", silent ? "Silent" : volumeKnown ? `${audio.mediaVolume} / ${audio.mediaVolumeMax}` : "Unknown",
        silent ? "Raise the phone’s media volume and check mute before relying on an alarm."
          : "This is Android’s media volume, separate from the alarm’s app volume. Loudness still needs a listening test." + (audio.mediaMuted == null ? " Mute state could not be checked." : ""),
        silent ? "attention" : "unknown");
      const outputs = Array.isArray(audio.outputs) ? audio.outputs : null;
      row("Audio outputs", outputs?.length ? "Available devices" : "Unknown",
        (outputs?.length ? outputs.map(output => `${output.kind || "Audio device"}${output.name ? ": " + output.name : ""}${output.bluetooth ? " (Bluetooth)" : ""}`).join(", ") + ". " : "Output devices could not be confirmed. ") +
          "Exact playback route is unknown. Connected devices are not proof of the route at alarm time; check Bluetooth and listen on the intended output.");
    }
    async function refresh() {
      if (busy) return;
      const context = JSON.parse(JSON.stringify(getContext()));
      const request = ++generation;
      inspectedKey = key();
      buttons(true);
      $("list").replaceChildren();
      $("summary").textContent = "Checking saved setup without playing sound…";
      const current = () => {
        if (request !== generation) return false;
        invalidate();
        return request === generation;
      };
      try {
        const [schedule, platform, config, storage] = await Promise.all([
          bounded(context.android ? readAndroid : readNext),
          context.android ? Promise.resolve({}) : bounded(readPower),
          bounded(readConfig),
          bounded(readStorage),
        ]);
        if (!current()) return;
        const native = context.android ? schedule.value : null;
        const next = context.android ? native?.next : schedule.value;
        const alarms = context.android ? native?.alarms || context.alarms : context.alarms;
        selectAlarms(alarms);
        const chosen = $("alarm").value;
        const alarm = alarms.find(item => item.id === (chosen || next?.alarmId));
        const when = next?.atMs != null ? await bounded(() => localTime(next.atMs)) : {};
        if (!current()) return;
        const scheduleError = schedule.error || native?.error || (context.android && !native ? "Native alarm state unavailable" : null);
        row("Next scheduled alarm", scheduleError ? "Unknown" : next ? next.snoozed ? "Snoozed" : "Scheduled" : "None",
          scheduleError || (next ? `${next.label || "Alarm"} · ${when.value ? formatWhen(when.value) : "local time unavailable"}. Delivery and sound are not yet verified.`
            : "No upcoming alarm was reported. Save and enable an alarm to schedule a wake-up."), scheduleError || !next ? "attention" : "unknown");
        const recoveryError = config.error || (config.value ? config.value.loadError : context.configError || "Settings storage unavailable") || storage.value?.error;
        const androidSetupError = context.android ? context.androidAlarmError || context.androidSourceError || scheduleError : null;
        const pendingStorage = storage.value?.writesBlocked || storage.value?.restartSafe === false || storage.value?.pendingCompletions > 0;
        const storageUnknown = storage.error || !storage.value;
        const saving = context.saving || pendingStorage;
        row("Saved settings", recoveryError ? "Recovery needed" : androidSetupError ? "Update needed" : saving ? "Save pending" : storageUnknown ? "Durability unknown" : "Readable",
          recoveryError ? `${recoveryError}. Restore a backup or repair settings before changing setup or trusting this check.`
            : androidSetupError ? `${androidSetupError}. Use Retry alarm setup in Permissions, then check again before relying on these sources.`
            : pendingStorage ? "Alarm changes or completions are not safely stored yet. Keep Aerowave open and use Retry in Settings storage before restarting."
            : context.saving ? "Wait for the current changes to save, then check again."
            : storageUnknown ? "Could not confirm durable alarm storage. Check Settings storage and try again."
            : "Settings storage reported no load error. This does not verify alarm delivery.", recoveryError || androidSetupError || saving || storageUnknown ? "attention" : "checked");
        if (context.android) androidRows(native);
        else {
          const power = platform.value;
          const wake = power?.wakeSupported === true && power?.wakeAllowed === true && !power.error && context.settings.wakeForAlarms !== false;
          row("PC wake", platform.error || !power ? "Unknown" : wake ? "Policy allows wake" : "Needs attention",
            [platform.error, context.settings.wakeForAlarms === false ? "Wake this PC for alarms is off." : null,
              power?.message, power?.error,
              "Keep Aerowave running. Sleep wake depends on hardware and power policy; Modern Standby is unverified. A shut-down PC cannot wake for alarms."].filter(Boolean).join(" "), wake ? "unknown" : "attention");
          row("System volume & output", "Check manually", "Check the OS mixer, mute and selected speakers or Bluetooth. Aerowave cannot read desktop system volume or confirm the eventual output.");
        }
        row("Screen-off audible wake", "Not verified", "Permission access and the foreground Test sound button do not verify a scheduled alarm with the screen off. Schedule a near-term alarm on this device and confirm both the screen and sound under your usual overnight conditions.");
        if (recoveryError || androidSetupError || saving) {
          row("Source & backup", "Not checked", androidSetupError
            ? "Retry alarm setup in Permissions to confirm saved station and backup changes, then check again."
            : "Recover settings or finish saving, then check again.", "attention");
        } else {
          if (!alarm) row("Selected source", "No alarm selected", "Choose a saved alarm to inspect its source.", "attention");
          else {
            row("Selected alarm", alarm.enabled ? "Enabled" : "Off", `${alarm.label || "Alarm"} · app volume ${Math.round((alarm.volume ?? 0) * 100)}%.${alarm.enabled ? "" : " This alarm is disabled."}${alarm.volume > 0 ? "" : " Increase its app volume."}`, alarm.enabled && alarm.volume > 0 ? "checked" : "attention");
          }
          const checks = [];
          const checkFolder = async (name, path) => {
            const result = await folder(path);
            if (!current()) return;
            row(name, result.error ? "Unavailable" : result.value?.count > 0 ? "Files found" : "Empty",
              result.error ? `${result.error} Choose the folder again if access was lost.`
                : result.value?.count > 0 ? `${result.value.count} supported audio files found. File discovery does not test decoding or audibility.`
                : "No supported audio files found. Choose another folder.", result.error || !result.value?.count ? "attention" : "checked");
          };
          if (alarm?.source?.kind === "folder") {
            if (alarm.source.path) checks.push(checkFolder("Selected source folder", alarm.source.path));
            else row("Selected source folder", "Missing", "Choose an alarm music folder.", "attention");
          } else if (alarm?.source?.kind === "station") {
            const station = context.stations.find(item => item.id === alarm.source.stationId);
            let valid = false;
            try { valid = ["http:", "https:"].includes(new URL(station?.url).protocol); } catch { /* missing or malformed */ }
            row("Selected station", valid ? "Saved link found" : "Missing or invalid", valid
              ? `${station.name || "Station"}. Network availability and decoding were not checked; a saved URL cannot verify alarm sound.`
              : "Select an existing station with an HTTP or HTTPS stream link.", valid ? "unknown" : "attention");
          } else if (alarm) row("Selected source", "Unknown", "Choose a station or music folder.", "attention");
          if (context.settings.backupFolder) checks.push(checkFolder("Backup folder", context.settings.backupFolder));
          else row("Backup folder", "Not selected", context.android
            ? "Choose local backup music. Android’s default alarm sound is the final fallback; its audibility still needs testing."
            : "Choose backup music in Settings. An offline emergency tone is the final fallback, but mute, volume or output-device problems can still prevent sound.", "attention");
          await Promise.all(checks);
        }
        if (current()) $("summary").textContent = "Check complete. Review the results below; screen-off audible wake remains unverified. Recheck after changing volume, outputs or system settings.";
      } catch (error) {
        if (current()) $("summary").textContent = "Could not complete readiness check: " + describeError(error);
      } finally {
        if (request === generation) buttons(false);
      }
    }
    function expire() {
      if (inspectedKey === null) return;
      generation++;
      inspectedKey = null;
      buttons(false);
      $("list").replaceChildren();
      $("summary").textContent = "Check again for current volume, outputs and alarm setup.";
    }
    return { refresh, invalidate, expire, selectionChanged() { generation++; inspectedKey = null; buttons(false); return refresh(); } };
  },
};
