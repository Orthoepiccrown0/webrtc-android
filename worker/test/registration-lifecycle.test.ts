import test from "node:test";
import assert from "node:assert/strict";
import {
  emptyState,
  register,
  requestConnection,
  approve,
  projectRequest,
  revoke,
} from "../src/coordinator.ts";

test("audio mode is authorized, immutable and carried into the approved room", () => {
  const state = emptyState();
  const caller = register(state, "caller");
  const target = register(state, "target");
  const request = requestConnection(
    state,
    "caller",
    target.code,
    "audio-request",
    1000,
    "Audio",
  ).request;
  assert.equal(projectRequest(state, request, "target").mode, "Audio");
  assert.throws(() =>
    requestConnection(state, "caller", target.code, "audio-request", 1001, "Video"),
  );
  const room = approve(state, "target", "audio-request", 1002);
  assert.equal(room.mode, "Audio");
  assert.equal(state.devices.caller.active, room.roomId);
  assert.equal(state.devices.target.active, room.roomId);
  revoke(state, "caller");
  assert.equal(room.status, "ended");
  assert.equal(state.codes[caller.code].retired, true);
  assert.equal(state.devices.target.active, undefined);
  revoke(state, "caller");
  assert.notEqual(register(state, "caller").code, caller.code);
});

test("revocation cancels pending incoming and outgoing requests without allocating", () => {
  const state = emptyState();
  const target = register(state, "target");
  const request = requestConnection(state, "caller", target.code, "pending-request", 1000).request;
  revoke(state, "caller");
  assert.equal(request.status, "cancelled");
  assert.equal(state.devices.target.pending, undefined);
  assert.equal(state.devices.caller, undefined);
});
