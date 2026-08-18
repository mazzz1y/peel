"use strict";

// Unprivileged half of the Peel certificate store: relays the PEM list Kotlin
// pushes over native messaging into the privileged peelCerts experiment API,
// and acks so Kotlin knows trust is in place before the first page load.

const NATIVE_APP = "certStore";

let port = null;

function connect() {
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    setTimeout(connect, 1000);
    return;
  }
  port.onMessage.addListener(handleNativeMessage);
  port.onDisconnect.addListener(() => {
    port = null;
    setTimeout(connect, 1000);
  });
  try {
    port.postMessage({ type: "hello" });
  } catch (e) {
    // The port died between connect and hello; onDisconnect will retry.
  }
}

async function handleNativeMessage(msg) {
  if (!msg || typeof msg !== "object") {
    return;
  }
  if (msg.cmd !== "sync") {
    return;
  }

  const seq = typeof msg.seq === "number" ? msg.seq : -1;
  const pems = Array.isArray(msg.certs) ? msg.certs : [];

  let result = { installed: [], errors: [] };
  let failure = null;
  try {
    result = await browser.peelCerts.sync(pems);
  } catch (e) {
    failure = String(e);
  }

  if (seq < 0 || !port) {
    return;
  }
  try {
    port.postMessage({
      type: "sync-ack",
      seq,
      installed: result.installed || [],
      errors: result.errors || [],
      failure,
    });
  } catch (e) {
    // Kotlin will retry on reconnect.
  }
}

connect();
