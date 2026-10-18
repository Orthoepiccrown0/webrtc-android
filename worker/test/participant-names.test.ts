import test from "node:test";
import assert from "node:assert/strict";
import {
  emptyState,
  register,
  participantName,
  requestConnection,
  projectRequest,
  approve,
  reset,
  participantEvents,
} from "../src/coordinator.ts";
import { incomingCallMessage } from "../src/services.ts";

test("names are optional, trimmed, bounded and reject control characters", () => {
  for (const value of [null, undefined, "  "]) assert.equal(participantName(value), undefined);
  assert.equal(participantName("  River Otter  "), "River Otter");
  assert.equal(participantName("🦊".repeat(40)), "🦊".repeat(40));
  for (const value of [4, {}, "x".repeat(41), "name\n", "\tname", "name\u007f"])
    assert.throws(() => participantName(value));
});

test("existing codes stay callable and names are participant-only snapshots", () => {
  const state = emptyState();
  const target = register(state, "target", () => "ABC234");
  target.participantName = "Owl";
  register(state, "caller", () => "DEF234").participantName = "Fox";
  const { request } = requestConnection(state, "caller", target.code, "request-for-names", 100);
  assert.equal(projectRequest(state, request, "target").peerName, "Fox");
  assert.equal(projectRequest(state, request, "caller").peerName, "Owl");
  assert.throws(() => projectRequest(state, request, "outsider"));
  target.participantName = "Renamed";
  assert.equal(projectRequest(state, request, "caller").peerName, "Owl");
  const room = approve(state, "target", request.requestId, 101);
  assert.equal(projectRequest(state, request, "caller").peerName, "Owl");
  reset(state, "target", () => "GHJ234");
  assert.equal(state.sessions[room.roomId].status, "active");
  assert.equal(target.participantName, "Renamed");
  const events = participantEvents(state);
  assert.equal(events.outsider, undefined);
  assert.equal(events.target.requests[request.requestId].peerName, "Fox");
  assert.equal(
    incomingCallMessage("token", { ...request, status: "pending" }, 102)!.message.data.type,
    "directstream.incoming",
  );
});
