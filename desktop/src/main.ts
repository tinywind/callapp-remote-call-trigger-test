import "./styles.css";

type SavedFormState = {
  phoneIp: string;
  phonePort: string;
  callToken: string;
  phoneNumber: string;
};

const STORAGE_KEY = "remote-call-trigger-form";
const PHONE_NUMBER_PATTERN = /^[+0-9*#]{2,32}$/;

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
const callButton = getElement<HTMLButtonElement>("#call-button");
const statusOutput = getElement<HTMLOutputElement>("#status");

const setStatus = (message: string, tone: "idle" | "ok" | "error" = "idle") => {
  statusOutput.textContent = message;
  statusOutput.dataset.tone = tone;
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
    phoneNumber: phoneNumberInput.value.trim()
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
};

const normalizeHost = (value: string) =>
  value
    .trim()
    .replace(/^https?:\/\//i, "")
    .replace(/\/.*$/, "");

const buildEndpoint = () => {
  const host = normalizeHost(phoneIpInput.value);
  const port = Number.parseInt(phonePortInput.value.trim(), 10);

  if (!host) {
    throw new Error("Enter the Android phone IP.");
  }

  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("Enter a valid TCP port.");
  }

  return `http://${host}:${port}/call`;
};

const validatePhoneNumber = (number: string) => {
  if (!PHONE_NUMBER_PATTERN.test(number)) {
    throw new Error("Use digits, +, *, or # only. Length must be 2-32.");
  }
};

const triggerCall = async () => {
  const endpoint = buildEndpoint();
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

restoreState();

form.addEventListener("input", saveState);
form.addEventListener("submit", async (event) => {
  event.preventDefault();
  callButton.disabled = true;
  setStatus("Sending request...");

  try {
    await triggerCall();
    saveState();
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    setStatus(message, "error");
  } finally {
    callButton.disabled = false;
  }
});
