import { readFile } from "node:fs/promises";
import { after, before, beforeEach, test } from "node:test";
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from "@firebase/rules-unit-testing";
import { ref, get, set, update } from "firebase/database";
let env;
const id = "room123";
const future = Date.now() + 3_600_000;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-webrtc",
    database: {
      host: "127.0.0.1",
      port: 9000,
      rules: await readFile(new URL("./database.rules.json", import.meta.url), "utf8"),
    },
  });
});
after(async () => {
  await env?.cleanup();
});
beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async (context) => {
    await set(ref(context.database()), {
      control: {
        codes: { ABC234: { senderUid: "host", retired: false } },
        devices: { host: { code: "ABC234", token: "private-token", requestsEnabled: true } },
        requests: { request: { senderUid: "host", receiverUid: "guest", status: "pending" } },
        events: {
          host: {
            incoming: {
              requestId: "request",
              status: "pending",
              role: "SENDER",
              peerName: "Caller Fox",
            },
            requests: { request: { status: "pending" } },
            sessions: { [id]: { active: true, expiresAt: future } },
          },
          guest: { requests: { request: { status: "pending", role: "RECEIVER" } } },
        },
        sessions: {
          [id]: { hostUid: "host", guestUid: "guest", expiresAt: future, status: "active" },
        },
      },
      rooms: {
        [id]: {
          metadata: {
            hostUid: "host",
            guestUid: "guest",
            expiresAt: future,
            status: "negotiating",
          },
        },
      },
    });
  });
});
const db = (uid) => env.authenticatedContext(uid).database();
test("anonymous clients and outsiders cannot read rooms", async () => {
  await assertFails(get(ref(env.unauthenticatedContext().database(), `rooms/${id}`)));
  await assertFails(get(ref(db("intruder"), `rooms/${id}`)));
});
test("directory, tokens, requests and room listing are private even to participants", async () => {
  for (const uid of ["host", "guest"]) {
    for (const path of [
      "control",
      "control/codes/ABC234",
      "control/devices/host/token",
      "control/requests/request",
      "rooms",
    ]) {
      await assertFails(get(ref(db(uid), path)));
    }
  }
});
test("both approved participants can read the single room", async () => {
  await assertSucceeds(get(ref(db("host"), `rooms/${id}`)));
  await assertSucceeds(get(ref(db("guest"), `rooms/${id}`)));
});
test("only host writes offer and only guest writes answer", async () => {
  const offer = { type: "offer", sdp: "v=0" };
  const answer = { type: "answer", sdp: "v=0" };
  await assertSucceeds(set(ref(db("host"), `rooms/${id}/signaling/offer`), offer));
  await assertSucceeds(set(ref(db("guest"), `rooms/${id}/signaling/answer`), answer));
  await assertFails(set(ref(db("guest"), `rooms/${id}/signaling/offer`), offer));
  await assertFails(set(ref(db("host"), `rooms/${id}/signaling/answer`), answer));
});
test("clients cannot create or overwrite approval and room membership", async () => {
  await assertFails(
    set(ref(db("guest"), "control/sessions/forged"), {
      hostUid: "host",
      guestUid: "guest",
      status: "active",
      expiresAt: future,
    }),
  );
  await assertFails(set(ref(db("guest"), `rooms/${id}/metadata/guestUid`), "intruder"));
  await assertFails(
    set(ref(db("host"), `rooms/${id}`), { metadata: { hostUid: "host", guestUid: "intruder" } }),
  );
});
test("candidate branches and presence belong to their respective participants", async () => {
  const candidate = { candidate: "candidate:1", sdpMLineIndex: 0, sdpMid: "0" };
  await assertSucceeds(set(ref(db("host"), `rooms/${id}/candidates/host/c1`), candidate));
  await assertSucceeds(set(ref(db("guest"), `rooms/${id}/candidates/guest/c2`), candidate));
  await assertFails(set(ref(db("guest"), `rooms/${id}/candidates/host/c3`), candidate));
  await assertSucceeds(set(ref(db("host"), `rooms/${id}/presence/host`), true));
  await assertFails(set(ref(db("guest"), `rooms/${id}/presence/host`), true));
});
test("malformed and oversized signaling is rejected", async () => {
  for (const data of [
    { type: "answer", sdp: "v=0" },
    { type: "offer", sdp: "" },
    { type: "offer", sdp: "x".repeat(65537) },
    { type: "offer", sdp: "v=0", secret: true },
  ]) {
    await assertFails(set(ref(db("host"), `rooms/${id}/signaling/offer`), data));
  }
  await assertFails(
    set(ref(db("host"), `rooms/${id}/candidates/host/c1`), { candidate: "x", sdpMLineIndex: -1 }),
  );
});
test("ended or expired authorization denies reads and signaling immediately", async () => {
  for (const change of [{ status: "ended" }, { expiresAt: 1 }]) {
    await env.withSecurityRulesDisabled(async (context) =>
      update(ref(context.database(), `control/sessions/${id}`), change),
    );
    await assertFails(get(ref(db("guest"), `rooms/${id}`)));
    await assertFails(
      set(ref(db("host"), `rooms/${id}/signaling/offer`), { type: "offer", sdp: "v=0" }),
    );
  }
});
test("pending request and known device code confer no signaling access", async () => {
  await env.withSecurityRulesDisabled(async (context) =>
    set(ref(context.database(), `control/sessions/${id}`), null),
  );
  await assertFails(get(ref(db("guest"), `rooms/${id}`)));
  await assertFails(
    set(ref(db("host"), `rooms/${id}/signaling/offer`), { type: "offer", sdp: "v=0" }),
  );
});

test("participants read only their own admission events and cannot enumerate or forge them", async () => {
  const own = await assertSucceeds(get(ref(db("host"), "control/events/host/incoming")));
  if (own.child("peerName").val() !== "Caller Fox")
    throw new Error("Missing authorized participant name");
  await assertFails(get(ref(db("intruder"), "control/events/host/incoming/peerName")));
  await assertSucceeds(get(ref(db("guest"), "control/events/guest/requests/request")));
  for (const uid of ["host", "guest", "intruder"]) {
    await assertFails(get(ref(db(uid), "control/events")));
    if (uid !== "host") await assertFails(get(ref(db(uid), "control/events/host")));
    await assertFails(
      set(ref(db(uid), `control/events/${uid}/requests/forged`), { status: "approved" }),
    );
    await assertFails(set(ref(db(uid), `control/events/${uid}/sessions/forged`), { active: true }));
  }
  await assertFails(get(ref(env.unauthenticatedContext().database(), "control/events/host")));
});

test("codec signaling belongs only to the approved participant's side", async () => {
  const message = { version: 1, payload: '{"kind":"CAPABILITIES"}' };
  const hostPath = `rooms/${id}/signaling/codecs/host/codec-transaction-1234_CAPABILITIES`;
  const guestPath = `rooms/${id}/signaling/codecs/guest/codec-transaction-1234_CAPABILITIES`;
  await assertSucceeds(set(ref(db("host"), hostPath), message));
  await assertSucceeds(set(ref(db("guest"), guestPath), message));
  await assertSucceeds(get(ref(db("guest"), hostPath)));
  await assertFails(set(ref(db("guest"), hostPath), message));
  await assertFails(set(ref(db("host"), guestPath), message));
  await assertFails(set(ref(db("intruder"), hostPath), message));
  await assertFails(get(ref(db("intruder"), hostPath)));
  await assertFails(get(ref(env.unauthenticatedContext().database(), hostPath)));
});

test("codec envelopes and description correlation are bounded", async () => {
  const path = `rooms/${id}/signaling/codecs/host/codec-transaction-1234_PREPARE`;
  for (const bad of [
    { version: 99, payload: "{}" },
    { version: 1, payload: "x".repeat(32769) },
    { version: 1, payload: "" },
    { version: 1, payload: "{}", extra: true },
    { payload: "{}" },
  ])
    await assertFails(set(ref(db("host"), path), bad));
  await assertSucceeds(
    set(ref(db("host"), `rooms/${id}/signaling/offer`), {
      type: "offer",
      sdp: "v=0",
      negotiationId: "codec-transaction-1234",
    }),
  );
  await assertFails(
    set(ref(db("host"), `rooms/${id}/signaling/offer`), {
      type: "offer",
      sdp: "v=0",
      negotiationId: "short",
    }),
  );
});

test("pending and ended sessions cannot exchange codec capabilities", async () => {
  const path = `rooms/${id}/signaling/codecs/host/codec-transaction-1234_CAPABILITIES`;
  for (const status of ["pending", "ended"]) {
    await env.withSecurityRulesDisabled(async (context) => {
      await set(ref(context.database(), `control/sessions/${id}/status`), status);
    });
    await assertFails(set(ref(db("host"), path), { version: 1, payload: "{}" }));
    await assertFails(get(ref(db("guest"), `rooms/${id}/signaling/codecs`)));
  }
});
