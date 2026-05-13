import "./styles.css";

type SavedFormState = {
  phoneIp: string;
  phonePort: string;
  callToken: string;
  phoneNumber: string;
  smsMessage: string;
};

const STORAGE_KEY = "remote-call-trigger-form";
const PHONE_NUMBER_PATTERN = /^[+0-9*#]{2,32}$/;
const DEMO_HANG_UP_DELAY_MS = 3000;

const getElement = <T extends HTMLElement>(selector: string): T => {
  const element = document.querySelector<T>(selector);

  if (!element) {
    throw new Error(`Missing element: ${selector}`);
  }

  return element;
};

const form = getElement<HTMLFormElement>("#call-form");
const phoneIpInput = getElement<HTMLInputElement>("#phone-ip");
const phonePortInput = getElement<HTMLInputElement>("#phone-port");
const callTokenInput = getElement<HTMLInputElement>("#call-token");
const phoneNumberInput = getElement<HTMLInputElement>("#phone-number");
const smsMessageInput = getElement<HTMLTextAreaElement>("#sms-message");
const demoButton = getElement<HTMLButtonElement>("#demo-button");
const callButton = getElement<HTMLButtonElement>("#call-button");
const hangUpButton = getElement<HTMLButtonElement>("#hangup-button");
const smsButton = getElement<HTMLButtonElement>("#sms-button");
const smsBackupButton = getElement<HTMLButtonElement>("#sms-backup-button");
const statusOutput = getElement<HTMLOutputElement>("#status");
let demoCountdownId: number | null = null;
let demoTimeoutId: number | null = null;

const setStatus = (message: string, tone: "idle" | "ok" | "error" = "idle") => {
  statusOutput.textContent = message;
  statusOutput.dataset.tone = tone;
};

const setBusy = (busy: boolean) => {
  demoButton.disabled = busy;
  callButton.disabled = busy;
  hangUpButton.disabled = busy;
  smsButton.disabled = busy;
  smsBackupButton.disabled = busy;
};

const clearDemoCountdown = () => {
  if (demoCountdownId !== null) {
    window.clearInterval(demoCountdownId);
    demoCountdownId = null;
  }

  if (demoTimeoutId !== null) {
    window.clearTimeout(demoTimeoutId);
    demoTimeoutId = null;
  }
};

const readSavedState = (): SavedFormState | null => {
  const raw = window.localStorage.getItem(STORAGE_KEY);

  if (!raw) {
    return null;
  }

  try {
    return JSON.parse(raw) as SavedFormState;
  } catch {
    return null;
  }
};

const saveState = () => {
  const state: SavedFormState = {
    phoneIp: phoneIpInput.value.trim(),
    phonePort: phonePortInput.value.trim(),
    callToken: callTokenInput.value,
    phoneNumber: phoneNumberInput.value.trim(),
    smsMessage: smsMessageInput.value
  };

  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
};

const restoreState = () => {
  const state = readSavedState();

  if (!state) {
    return;
  }

  phoneIpInput.value = state.phoneIp;
  phonePortInput.value = state.phonePort;
  callTokenInput.value = state.callToken;
  phoneNumberInput.value = state.phoneNumber;
  smsMessageInput.value = state.smsMessage || "";
};

const normalizeHost = (value: string) =>
  value
    .trim()
    .replace(/^https?:\/\//i, "")
    .replace(/\/.*$/, "");

const buildEndpoint = (path: "/call" | "/hangup" | "/sms/send" | "/sms/backup") => {
  const host = normalizeHost(phoneIpInput.value);
  const port = Number.parseInt(phonePortInput.value.trim(), 10);

  if (!host) {
    throw new Error("Enter the Android phone IP.");
  }

  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("Enter a valid TCP port.");
  }

  return `http://${host}:${port}${path}`;
};

const validatePhoneNumber = (number: string) => {
  if (!PHONE_NUMBER_PATTERN.test(number)) {
    throw new Error("Use digits, +, *, or # only. Length must be 2-32.");
  }
};

const triggerCall = async () => {
  const endpoint = buildEndpoint("/call");
  const number = phoneNumberInput.value.trim();
  const token = callTokenInput.value;

  validatePhoneNumber(number);

  if (!token) {
    throw new Error("Enter the shared token.");
  }

  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), 8000);

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Call-Token": token
      },
      body: JSON.stringify({ number }),
      signal: controller.signal
    });

    const text = await response.text();

    if (!response.ok) {
      throw new Error(text || `HTTP ${response.status}`);
    }

    setStatus(`Accepted by ${endpoint}`, "ok");
  } finally {
    window.clearTimeout(timeoutId);
  }
};

const waitWithCountdown = (durationMs: number) =>
  new Promise<void>((resolve) => {
    const startMs = window.performance.now();
    const update = () => {
      const elapsedMs = window.performance.now() - startMs;
      const remainingMs = Math.max(0, durationMs - elapsedMs);
      setStatus(`Call accepted. Hanging up in ${(remainingMs / 1000).toFixed(1)}s...`);
    };

    update();
    demoCountdownId = window.setInterval(update, 100);
    demoTimeoutId = window.setTimeout(() => {
      setStatus("Call accepted. Hanging up in 0.0s...");
      clearDemoCountdown();
      resolve();
    }, durationMs);
  });

const triggerHangUp = async () => {
  const endpoint = buildEndpoint("/hangup");
  const token = callTokenInput.value;

  if (!token) {
    throw new Error("Enter the shared token.");
  }

  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), 8000);

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "X-Call-Token": token
      },
      signal: controller.signal
    });

    const text = await response.text();

    if (!response.ok) {
      throw new Error(text || `HTTP ${response.status}`);
    }

    setStatus(`Hang up accepted by ${endpoint}`, "ok");
  } finally {
    window.clearTimeout(timeoutId);
  }
};

const triggerSms = async () => {
  const endpoint = buildEndpoint("/sms/send");
  const number = phoneNumberInput.value.trim();
  const message = smsMessageInput.value;
  const token = callTokenInput.value;

  validatePhoneNumber(number);

  if (!token) {
    throw new Error("Enter the shared token.");
  }

  if (!message.trim()) {
    throw new Error("Enter an SMS message.");
  }

  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), 8000);

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Call-Token": token
      },
      body: JSON.stringify({ number, message }),
      signal: controller.signal
    });

    const text = await response.text();

    if (!response.ok) {
      throw new Error(text || `HTTP ${response.status}`);
    }

    setStatus(`SMS accepted by ${endpoint}`, "ok");
  } finally {
    window.clearTimeout(timeoutId);
  }
};

const backupSms = async () => {
  const endpoint = buildEndpoint("/sms/backup");
  const token = callTokenInput.value;

  if (!token) {
    throw new Error("Enter the shared token.");
  }

  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), 30000);

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "X-Call-Token": token
      },
      signal: controller.signal
    });

    const text = await response.text();

    if (!response.ok) {
      throw new Error(text || `HTTP ${response.status}`);
    }

    const backup = JSON.parse(text) as { count?: number };
    const blob = new Blob([JSON.stringify(backup, null, 2)], { type: "application/json" });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    const timestamp = new Date().toISOString().replace(/[:.]/g, "-");

    link.href = url;
    link.download = `sms-backup-${timestamp}.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
    setStatus(`Downloaded SMS backup with ${backup.count ?? 0} message(s).`, "ok");
  } finally {
    window.clearTimeout(timeoutId);
  }
};

const runDemo = async () => {
  setBusy(true);
  clearDemoCountdown();
  setStatus("Demo call request sending...");

  try {
    await triggerCall();
    saveState();
    await waitWithCountdown(DEMO_HANG_UP_DELAY_MS);
    setStatus("Sending automatic hang up request...");
    await triggerHangUp();
    setStatus("Demo complete. Call ended after 3 seconds.", "ok");
  } catch (error) {
    clearDemoCountdown();
    const message = error instanceof Error ? error.message : "Unknown error";
    setStatus(message, "error");
  } finally {
    clearDemoCountdown();
    setBusy(false);
  }
};

restoreState();

form.addEventListener("input", saveState);
form.addEventListener("submit", async (event) => {
  event.preventDefault();
  setBusy(true);
  setStatus("Sending request...");

  try {
    await triggerCall();
    saveState();
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    setStatus(message, "error");
  } finally {
    setBusy(false);
  }
});

demoButton.addEventListener("click", runDemo);

hangUpButton.addEventListener("click", async () => {
  setBusy(true);
  setStatus("Sending hang up request...");

  try {
    await triggerHangUp();
    saveState();
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    setStatus(message, "error");
  } finally {
    setBusy(false);
  }
});

smsButton.addEventListener("click", async () => {
  setBusy(true);
  setStatus("Sending SMS request...");

  try {
    await triggerSms();
    saveState();
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    setStatus(message, "error");
  } finally {
    setBusy(false);
  }
});

smsBackupButton.addEventListener("click", async () => {
  setBusy(true);
  setStatus("Downloading SMS backup...");

  try {
    await backupSms();
    saveState();
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    setStatus(message, "error");
  } finally {
    setBusy(false);
  }
});
