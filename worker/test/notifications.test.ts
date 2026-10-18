import test from "node:test";
import assert from "node:assert/strict";
import { incomingCallMessage } from "../src/services.ts";
import type { PairingRequest } from "../src/coordinator.ts";
const request: PairingRequest = {
  requestId: "request-for-test-only",
  senderUid: "sender",
  receiverUid: "caller",
  verificationValue: "123456",
  createdAt: 1_000,
  expiresAt: 301_000,
  status: "pending",
};
test("incoming calls use data-only high priority delivery bounded by request expiry", () => {
  const payload = incomingCallMessage("fake-token", request, 2_000)!;
  assert.equal(payload.message.android.priority, "HIGH");
  assert.equal(payload.message.android.ttl, "299s");
  assert.equal("notification" in payload.message, false);
  assert.deepEqual(payload.message.data, {
    requestId: request.requestId,
    expiresAt: "301000",
    type: "directstream.incoming",
    protocolVersion: "1",
    mode: "Video",
  });
});
test("terminal and expired requests do not produce incoming alerts", () => {
  assert.equal(incomingCallMessage("fake-token", request, request.expiresAt), null);
  for (const status of ["cancelled", "rejected", "expired", "approved"] as const)
    assert.equal(incomingCallMessage("fake-token", { ...request, status }, 2_000), null);
});
