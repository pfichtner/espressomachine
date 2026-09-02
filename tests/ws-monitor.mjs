#!/usr/bin/env node
// ws-monitor.mjs — Connect to virtualavr WebSocket, monitor a pin, and verify blink.
//
// Usage:
//   node tests/ws-monitor.mjs <ws-url> <pin> <min-toggle-count> [timeout-seconds]
//
// Exits 0 if the pin toggles at least <min-toggle-count> times within the timeout.
// Exits 1 on error or timeout.
//
// Example:
//   node tests/ws-monitor.mjs ws://localhost:8080 13 2 30

import { WebSocket } from 'ws';

const [,, wsUrl, pin, minTogglesArg, timeoutArg] = process.argv;

if (!wsUrl || !pin || !minTogglesArg) {
  process.stderr.write(
    'Usage: node ws-monitor.mjs <ws-url> <pin> <min-toggle-count> [timeout-seconds]\n'
  );
  process.exit(2);
}

const minToggles = parseInt(minTogglesArg, 10);
const timeoutSec = parseInt(timeoutArg || '30', 10);
const timeoutMs = timeoutSec * 1000;

let toggleCount = 0;
let lastState = null;
let settled = false;

function done(ok, msg) {
  if (settled) return;
  settled = true;
  if (ok) {
    process.stdout.write(`PASS: pin ${pin} toggled ${toggleCount} times (>= ${minToggles})\n`);
    ws.close();
    process.exit(0);
  } else {
    process.stderr.write(`FAIL: ${msg}\n`);
    ws.close();
    process.exit(1);
  }
}

const ws = new WebSocket(wsUrl);
let timer;

ws.on('error', (err) => {
  done(false, `WebSocket error: ${err.message}`);
});

ws.on('open', () => {
  // Enable digital pin reporting for the requested pin
  const msg = JSON.stringify({ type: 'pinMode', pin, mode: 'digital' });
  ws.send(msg);
  process.stderr.write(`Monitoring pin ${pin} via ${wsUrl} (timeout ${timeoutSec}s)\n`);

  timer = setTimeout(() => {
    done(false, `Timeout after ${timeoutSec}s: pin ${pin} toggled ${toggleCount} times, need ${minToggles}`);
  }, timeoutMs);
});

ws.on('message', (data) => {
  let parsed;
  try {
    parsed = JSON.parse(data.toString());
  } catch {
    return; // ignore non-JSON
  }

  if (parsed.type !== 'pinState' || parsed.pin !== pin) return;

  const state = !!parsed.state;
  if (lastState !== null && state !== lastState) {
    toggleCount++;
    process.stderr.write(`  toggle #${toggleCount}: ${lastState} -> ${state} (cpuTime=${parsed.cpuTime}s)\n`);
  }
  lastState = state;

  if (toggleCount >= minToggles) {
    clearTimeout(timer);
    done(true);
  }
});
