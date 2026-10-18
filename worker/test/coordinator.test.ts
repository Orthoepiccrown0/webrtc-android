import test from "node:test";
import assert from "node:assert/strict";
import {
  emptyState,
  register,
  reset,
  enable,
  normalizeCode,
  requestConnection,
  approve,
  expire,
  finishRequest,
  getSession,
  endSession,
  projectRequest,
  SESSION_TTL,
} from "../src/coordinator.ts";
const now = 1_700_000_000_000;
function setup() {
  const state = emptyState();
  register(state, "sender", () => "ABC234");
  enable(state, "sender", true);
  return state;
}
test("codes normalize identically to manual and QR input", () => {
  assert.equal(normalizeCode(" aBc - 234 "), "ABC234");
  for (const value of ["ABC23O", "ABC23L", "ABC230", "ABC23I", "ABC23", null])
    assert.throws(() => normalizeCode(value));
});
test("registration persists and allocation retries collisions", () => {
  const s = setup();
  let attempts = 0;
  const other = register(s, "other", () => (++attempts === 1 ? "ABC234" : "DEF567"));
  assert.equal(other.code, "DEF567");
  assert.equal(other.requestsEnabled, true);
  assert.equal(register(s, "sender", () => "ZZZ999").code, "ABC234");
});
test("receiving is always enabled; code is not authorization", () => {
  const s = emptyState();
  register(s, "sender", () => "ABC234");
  s.devices.sender.requestsEnabled = false;
  assert.equal(requestConnection(s, "guest", "ABC234", "request-1", now).request.status, "pending");
  assert.equal(enable(s, "sender", false).requestsEnabled, true);
  assert.throws(() => getSession(s, "guest", "ABC234", now));
});
test("exactly one pending request and one approved receiver", () => {
  const s = setup();
  requestConnection(s, "guest", "ABC234", "request-1", now);
  assert.throws(() => requestConnection(s, "other", "ABC234", "request-2", now));
  assert.throws(() => approve(s, "guest", "request-1", now));
  const room = approve(s, "sender", "request-1", now);
  assert.equal(approve(s, "sender", "request-1", now).roomId, room.roomId);
  assert.throws(() => requestConnection(s, "other", "ABC234", "request-2", now));
  assert.throws(() => getSession(s, "other", room.roomId, now));
});
test("receiver request retry is idempotent and hides sender identity", () => {
  const s = setup();
  const first = requestConnection(s, "guest", "ABC234", "request-1", now);
  const retry = requestConnection(s, "guest", "ABC234", "request-1", now);
  assert.equal(retry.fresh, false);
  assert.equal(first.request.verificationValue, retry.request.verificationValue);
  assert.equal("senderUid" in projectRequest(s, first.request, "guest"), false);
  assert.throws(() => requestConnection(s, "intruder", "ABC234", "request-1", now));
});
test("expiry frees the slot and late approval fails", () => {
  const s = setup();
  requestConnection(s, "guest", "ABC234", "request-1", now);
  expire(s, now + 300_000);
  assert.equal(s.requests["request-1"].status, "expired");
  assert.equal(s.devices.sender.pending, undefined);
  assert.throws(() => approve(s, "sender", "request-1", now + 300_000));
  requestConnection(s, "other", "ABC234", "request-2", now + 300_000);
});
test("cancel and reject cannot be reversed by stale approval", () => {
  for (const action of ["cancel", "reject"] as const) {
    const s = setup();
    requestConnection(s, "guest", "ABC234", "request-1", now);
    finishRequest(s, action === "cancel" ? "guest" : "sender", "request-1", action);
    assert.throws(() => approve(s, "sender", "request-1", now));
    assert.equal(s.devices.sender.pending, undefined);
  }
});
test("reset retires old code, cancels pending request, preserves an approved stream", () => {
  const s = setup();
  requestConnection(s, "guest", "ABC234", "request-1", now);
  reset(s, "sender", () => "DEF567");
  assert.equal(s.requests["request-1"].status, "cancelled");
  assert.throws(() => requestConnection(s, "other", "ABC234", "request-2", now));
  requestConnection(s, "guest", "DEF567", "request-2", now);
  const room = approve(s, "sender", "request-2", now);
  reset(s, "sender", () => "GHJ678");
  assert.equal(getSession(s, "guest", room.roomId, now).status, "active");
});
test("legacy settings do not cancel pending requests or active sessions", () => {
  const s = setup();
  requestConnection(s, "guest", "ABC234", "request-1", now);
  assert.equal(enable(s, "sender", false).requestsEnabled, true);
  assert.equal(s.requests["request-1"].status, "pending");
  const room = approve(s, "sender", "request-1", now);
  enable(s, "sender", false);
  assert.equal(getSession(s, "guest", room.roomId, now).status, "active");
});
test("ending frees sender and repeat guest needs approval again", () => {
  const s = setup();
  requestConnection(s, "guest", "ABC234", "request-1", now);
  const room = approve(s, "sender", "request-1", now);
  endSession(s, "guest", room.roomId);
  assert.throws(() => getSession(s, "guest", room.roomId, now));
  assert.throws(() => approve(s, "sender", "request-1", now));
  assert.equal(requestConnection(s, "guest", "ABC234", "request-2", now).request.status, "pending");
});
test("session deadline revokes credentials and frees the slot", () => {
  const s = setup();
  requestConnection(s, "guest", "ABC234", "request-1", now);
  const room = approve(s, "sender", "request-1", now);
  expire(s, now + SESSION_TTL);
  assert.throws(() => getSession(s, "guest", room.roomId, now + SESSION_TTL));
  assert.equal(s.devices.sender.active, undefined);
});
test("target notification spam is capped even as callers change", () => {
  const s = setup();
  for (let i = 0; i < 5; i++) {
    requestConnection(s, `guest${i}`, "ABC234", `r${i}`, now);
    finishRequest(s, "sender", `r${i}`, "reject");
  }
  assert.throws(() => requestConnection(s, "guest6", "ABC234", "r6", now));
});

test("callbacks are participant-only and both clients stay busy until end", () => {
  const s = setup();
  register(s, "guest", () => "DEF567");
  const request = requestConnection(s, "guest", "ABC234", "callback", now).request;
  assert.equal(projectRequest(s, request, "sender").peerCode, "DEF567");
  assert.equal(projectRequest(s, request, "guest").peerCode, "ABC234");
  assert.throws(() => projectRequest(s, request, "outsider"));
  const room = approve(s, "sender", "callback", now);
  assert.equal(s.devices.guest.active, room.roomId);
  assert.throws(() => requestConnection(s, "third", "DEF567", "busy", now));
  endSession(s, "guest", room.roomId);
  assert.equal(s.devices.guest.active, undefined);
  assert.equal(requestConnection(s, "third", "DEF567", "next", now).request.status, "pending");
});
test("opposing requests cannot establish two overlapping calls", () => {
  const s = setup();
  register(s, "guest", () => "DEF567");
  requestConnection(s, "guest", "ABC234", "forward", now);
  assert.throws(() => requestConnection(s, "sender", "DEF567", "reverse", now));
  approve(s, "sender", "forward", now);
  assert.throws(() => approve(s, "guest", "reverse", now));
});

test("an idempotency key cannot be redirected to a different recipient", () => {
  const state = setup();
  register(state, "other", () => "DEF567");
  requestConnection(state, "guest", "ABC234", "immutable-request", now);
  assert.throws(
    () => requestConnection(state, "guest", "DEF567", "immutable-request", now),
    /recipient is immutable/,
  );
});
