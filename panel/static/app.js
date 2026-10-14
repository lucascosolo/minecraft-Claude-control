"use strict";
const elements = Object.fromEntries(["start", "stop", "state", "status", "error", "output", "autoscroll", "command", "send", "command-form", "console-status"].map(id => [id, document.getElementById(id)]));
let csrf = "";
let serverState = "offline";
let actionBusy = false;
let commandBusy = false;
let previousLogs = [];
let history = [];
let stopped = false;

async function api(path, body) {
  const options = {credentials: "same-origin", cache: "no-store"};
  if (body !== undefined) {
    options.method = "POST";
    options.headers = {"Content-Type": "application/json", "X-CSRF-Token": csrf};
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  let data;
  try { data = await response.json(); }
  catch { throw new Error("The panel returned an unreadable response. Reload the page."); }
  if (!response.ok) throw new Error(data.error || "The server action failed. Try again.");
  return data;
}

function showError(message) {
  elements.error.textContent = message;
  elements.error.hidden = !message;
}

function controls() {
  elements.start.disabled = actionBusy || !["stopped", "not-created"].includes(serverState);
  elements.stop.disabled = actionBusy || !["running", "starting", "paused"].includes(serverState);
  elements.command.disabled = commandBusy || actionBusy || serverState !== "running";
  elements.send.disabled = commandBusy || actionBusy || serverState !== "running" || !elements.command.value.trim();
}

function renderStatus(data) {
  serverState = data.state;
  elements.state.dataset.state = serverState;
  elements.state.textContent = {running: "Running", starting: "Starting", stopping: "Stopping · saving", stopped: "Stopped", "not-created": "Not created", offline: "Offline", paused: "Paused"}[serverState] || "Unknown";
  if (!actionBusy) elements.status.textContent = data.message;
  controls();
}

function appendLines(lines) {
  if (!lines.length) return;
  history = history.concat(lines.slice(-2000)).slice(-2000);
  elements.output.textContent = history.join("\n");
  if (elements.autoscroll.checked) elements.output.scrollTop = elements.output.scrollHeight;
}

function renderLogs(output) {
  // Docker timestamps provide stable order across stdout and stderr. Keep the whole line
  // while finding the overlap so identical messages at different times stay visible.
  const lines = output.split("\n").filter(line => line.length > 0).sort((a, b) => {
    const first = a.match(/^\d{4}-\d\d-\d\dT\S+/);
    const second = b.match(/^\d{4}-\d\d-\d\dT\S+/);
    return first && second ? first[0].localeCompare(second[0]) : 0;
  });
  let overlap = Math.min(previousLogs.length, lines.length);
  while (overlap > 0 && !previousLogs.slice(-overlap).every((line, index) => line === lines[index])) overlap--;
  appendLines(lines.slice(overlap));
  previousLogs = lines;
}

async function poll() {
  try {
    const status = await api("/api/status");
    renderStatus(status);
    if (["running", "starting", "stopping", "stopped", "paused"].includes(status.state)) {
      try {
        const logs = await api("/api/logs");
        renderLogs(logs.output);
        elements["console-status"].textContent = "Output refreshes every second. Up to 2,000 recent lines stay in this tab.";
      } catch (error) {
        elements["console-status"].textContent = error.message;
      }
    } else {
      elements["console-status"].textContent = status.message;
    }
  } catch (error) {
    renderStatus({state: "offline", message: "Panel connection unavailable. Keep this page open while the panel is running."});
    elements["console-status"].textContent = error.message;
    // Refresh an expired local session automatically; mutations are never retried.
    try { csrf = (await api("/api/session")).csrf; } catch { /* Polling will recover. */ }
  }
  if (!stopped) window.setTimeout(poll, 1000);
}

async function action(name) {
  actionBusy = true;
  showError("");
  elements.status.textContent = name === "start" ? "Starting the container…" : "Stopping Minecraft and saving the world…";
  controls();
  try {
    const status = await api(`/api/${name}`, {});
    actionBusy = false;
    renderStatus(status);
  }
  catch (error) { showError(error.message); }
  finally { actionBusy = false; controls(); }
}

elements.start.addEventListener("click", () => action("start"));
elements.stop.addEventListener("click", () => action("stop"));
elements.command.addEventListener("input", controls);
elements.autoscroll.addEventListener("change", () => {
  if (elements.autoscroll.checked) elements.output.scrollTop = elements.output.scrollHeight;
});
elements["command-form"].addEventListener("submit", async event => {
  event.preventDefault();
  if (elements.send.disabled) return;
  const command = elements.command.value;
  if (new TextEncoder().encode(command).length > 1024) {
    showError("Command exceeds the 1024-byte limit.");
    return;
  }
  commandBusy = true;
  showError("");
  controls();
  try {
    const response = await api("/api/command", {command});
    appendLines(["— Command response —", ...(response.output || "Command completed without output.").split("\n")]);
    elements.command.value = "";
  } catch (error) { showError(error.message); }
  finally { commandBusy = false; controls(); elements.command.focus(); }
});
window.addEventListener("pagehide", () => { stopped = true; });
window.addEventListener("pageshow", event => { if (event.persisted) { stopped = false; poll(); } });
(async () => {
  try { csrf = (await api("/api/session")).csrf; }
  catch (error) { showError(error.message); }
  poll();
})();
