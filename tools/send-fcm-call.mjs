#!/usr/bin/env node
import { createSign } from "node:crypto";
import { readFile } from "node:fs/promises";

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const PHONE_NUMBER_PATTERN = /^[+0-9*#]{2,32}$/;

const readOption = (name) => {
  const valueFromEnv = process.env[name.replaceAll("-", "_").toUpperCase()];

  if (valueFromEnv) {
    return valueFromEnv;
  }

  const index = process.argv.indexOf(`--${name}`);

  if (index === -1) {
    return "";
  }

  return process.argv[index + 1] ?? "";
};

const requireOption = (name) => {
  const value = readOption(name);

  if (!value) {
    throw new Error(`Missing --${name}.`);
  }

  return value;
};

const base64Url = (value) =>
  Buffer.from(value)
    .toString("base64")
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");

const createServiceAccountJwt = (serviceAccount) => {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const payload = base64Url(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: FCM_SCOPE,
    aud: TOKEN_URL,
    iat: now,
    exp: now + 3600
  }));
  const signingInput = `${header}.${payload}`;
  const signature = createSign("RSA-SHA256")
    .update(signingInput)
    .sign(serviceAccount.private_key);

  return `${signingInput}.${base64Url(signature)}`;
};

const fetchAccessToken = async (serviceAccount) => {
  const jwt = createServiceAccountJwt(serviceAccount);
  const body = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion: jwt
  });
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body
  });
  const text = await response.text();

  if (!response.ok) {
    throw new Error(`OAuth token request failed: ${text}`);
  }

  return JSON.parse(text).access_token;
};

const sendFcmCall = async ({ accessToken, projectId, deviceToken, callToken, number, port }) => {
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      message: {
        token: deviceToken,
        android: {
          priority: "HIGH"
        },
        data: {
          action: "call",
          token: callToken,
          number,
          port
        }
      }
    })
  });
  const text = await response.text();

  if (!response.ok) {
    throw new Error(`FCM send failed: ${text}`);
  }

  return JSON.parse(text);
};

const main = async () => {
  const serviceAccountPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;

  if (!serviceAccountPath) {
    throw new Error("Set GOOGLE_APPLICATION_CREDENTIALS to a Firebase service account JSON file.");
  }

  const serviceAccount = JSON.parse(await readFile(serviceAccountPath, "utf8"));
  const projectId = readOption("project-id") || serviceAccount.project_id;
  const deviceToken = requireOption("device-token");
  const callToken = readOption("call-token") || "dev-token";
  const number = requireOption("number");
  const port = readOption("port") || "8088";

  if (!projectId) {
    throw new Error("Missing --project-id and service account project_id.");
  }

  if (!PHONE_NUMBER_PATTERN.test(number)) {
    throw new Error("Use digits, +, *, or # only. Length must be 2-32.");
  }

  const accessToken = await fetchAccessToken(serviceAccount);
  const result = await sendFcmCall({
    accessToken,
    projectId,
    deviceToken,
    callToken,
    number,
    port
  });

  console.log(`FCM message accepted: ${result.name}`);
};

main().catch((error) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
