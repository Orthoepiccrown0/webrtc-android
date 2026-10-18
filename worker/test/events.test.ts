import test from "node:test";
import assert from "node:assert/strict";
import {
  emptyState,
  register,
  requestConnection,
  participantEvents,
  approve,
  reset,
  endSession,
  expire,
  finishRequest,
} from "../src/coordinator.ts";
const now = 1_700_000_000_000;
function pending() {
  const s = emptyState();
  register(s, "host", () => "ABC234");
  register(s, "outsider", () => "DEF567");
  requestConnection(s, "guest", "ABC234", "request-one", now);
  s.devices.host.token = "never-publish";
  return s;
}
test("event views expose only participant projections, with no directory or tokens", () => {
  const s = pending();
  const views = participantEvents(s);
  assert.equal(views.host.incoming?.requestId, "request-one");
  assert.equal(views.host.requests["request-one"].role, "SENDER");
  assert.equal(views.guest.requests["request-one"].role, "RECEIVER");
  assert.equal(views.guest.incoming, null);
  assert.deepEqual(views.outsider.requests, {});
  assert.deepEqual(views.outsider.sessions, {});
  assert.equal(views.host.requests["request-one"].roomId, null);
  assert.equal(JSON.stringify(views).includes("never-publish"), false);
  for (const view of Object.values(views))
    for (const request of Object.values(view.requests)) {
      assert.equal("senderUid" in request, false);
      assert.equal("receiverUid" in request, false);
    }
});
test("approval and termination project to both participants with a single room", () => {
  const s = pending();
  const room = approve(s, "host", "request-one", now);
  let views = participantEvents(s);
  assert.equal(views.host.incoming, null);
  for (const uid of ["host", "guest"]) {
    assert.equal(views[uid].requests["request-one"].status, "approved");
    assert.equal(views[uid].requests["request-one"].roomId, room.roomId);
    assert.equal(views[uid].sessions[room.roomId].active, true);
  }
  assert.deepEqual(views.outsider.sessions, {});
  endSession(s, "host", room.roomId);
  views = participantEvents(s);
  assert.equal(views.host.sessions[room.roomId].active, false);
  assert.equal(views.guest.sessions[room.roomId].active, false);
});
test("reset, cancellation, rejection and expiry clear the inbox and retain terminal request events", () => {
  for (const action of ["reset", "cancel", "reject", "expire"] as const) {
    const s = pending();
    if (action === "reset") reset(s, "host", () => "GHJ678");
    else if (action === "expire") expire(s, now + 300_001);
    else finishRequest(s, action === "cancel" ? "guest" : "host", "request-one", action);
    const views = participantEvents(s);
    assert.equal(views.host.incoming, null);
    assert.notEqual(views.host.requests["request-one"].status, "pending");
    assert.equal(
      views.host.requests["request-one"].status,
      views.guest.requests["request-one"].status,
    );
  }
});
