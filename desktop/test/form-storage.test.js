import {beforeEach, expect, it, vi} from "vitest";
import html from "../index.html?raw";

const key = "remote-call-trigger-form";
const legacy = {phoneIp: "192.168.1.2", phonePort: "8088",
  callToken: "fixture-private-token", phoneNumber: "+821011112222", smsMessage: "fixture private message"};

beforeEach(() => {
  vi.resetModules();
  localStorage.clear();
  document.documentElement.innerHTML = html;
  vi.stubGlobal("fetch", vi.fn(() => {throw new Error("A storage test must not contact the phone");}));
});

it("removes legacy private fields and restores only receiver connection settings", async () => {
  localStorage.setItem(key, JSON.stringify(legacy));
  await import("../src/main.ts");
  expect(JSON.parse(localStorage.getItem(key))).toEqual({phoneIp: legacy.phoneIp, phonePort: legacy.phonePort});
  expect(document.querySelector("#phone-ip").value).toBe(legacy.phoneIp);
  expect(document.querySelector("#phone-port").value).toBe(legacy.phonePort);
  for (const selector of ["#call-token", "#phone-number", "#sms-message"]) {
    expect(document.querySelector(selector).value).toBe("");
  }
  expect(document.querySelector("#call-token").type).toBe("password");
  expect(fetch).not.toHaveBeenCalled();
});

it("keeps entered private values in the form while saving only connection settings", async () => {
  await import("../src/main.ts");
  for (const [selector, value] of [["#phone-ip", legacy.phoneIp], ["#phone-port", legacy.phonePort],
    ["#call-token", legacy.callToken], ["#phone-number", legacy.phoneNumber], ["#sms-message", legacy.smsMessage]]) {
    const input = document.querySelector(selector);
    input.value = value;
    input.dispatchEvent(new Event("input", {bubbles: true}));
  }
  expect(JSON.parse(localStorage.getItem(key))).toEqual({phoneIp: legacy.phoneIp, phonePort: legacy.phonePort});
  expect(document.querySelector("#call-token").value).toBe(legacy.callToken);
  expect(document.querySelector("#phone-number").value).toBe(legacy.phoneNumber);
  expect(document.querySelector("#sms-message").value).toBe(legacy.smsMessage);
  vi.resetModules();
  document.documentElement.innerHTML = html;
  await import("../src/main.ts");
  expect(document.querySelector("#phone-ip").value).toBe(legacy.phoneIp);
  expect(document.querySelector("#phone-port").value).toBe(legacy.phonePort);
  for (const selector of ["#call-token", "#phone-number", "#sms-message"]) {
    expect(document.querySelector(selector).value).toBe("");
  }
  expect(document.querySelector("#status").textContent).toBe("Idle");
  for (const button of document.querySelectorAll("button")) expect(button.disabled).toBe(false);
  expect(fetch).not.toHaveBeenCalled();
});
