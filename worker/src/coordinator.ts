export const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const REQUEST_TTL = 5 * 60_000;
export const SESSION_TTL = 60 * 60_000;
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}
export function normalizeCode(value: unknown): string {
  if (typeof value !== "string") throw new ApiError(400, "Enter a six-character device code.");
  const code = value.replace(/[\s-]/g, "").toUpperCase();
  if (code.length !== 6 || [...code].some((c) => !ALPHABET.includes(c)))
    throw new ApiError(400, "Enter a six-character device code.");
  return code;
}
export function randomCode(): string {
  // Rejection sampling avoids modulo bias.
  const output: string[] = [];
  while (output.length < 6) {
    for (const byte of crypto.getRandomValues(new Uint8Array(16))) {
      if (byte < 256 - (256 % ALPHABET.length)) output.push(ALPHABET[byte % ALPHABET.length]);
      if (output.length === 6) break;
    }
  }
  return output.join("");
}
export interface Device {
  code: string;
  participantName?: string;
  requestsEnabled: boolean;
  token?: string;
  pending?: string;
  outgoing?: string;
  active?: string;
  attemptWindow?: number;
  attempts?: number;
}
export type CallMode = "Audio" | "Video";
export interface PairingRequest {
  senderName?: string;
  receiverName?: string;
  mode?: CallMode;
  requestId: string;
  senderUid: string;
  receiverUid: string;
  verificationValue: string;
  createdAt: number;
  expiresAt: number;
  status: "pending" | "approved" | "rejected" | "cancelled" | "expired";
  roomId?: string;
}
export interface Session {
  mode?: CallMode;
  roomId: string;
  hostUid: string;
  guestUid: string;
  createdAt: number;
  expiresAt: number;
  status: "active" | "ended";
  cleaned?: boolean;
}
export interface ParticipantEvents {
  incoming: ReturnType<typeof projectRequest> | null;
  requests: Record<string, ReturnType<typeof projectRequest>>;
  sessions: Record<string, { active: boolean; expiresAt: number }>;
}
export interface State {
  events?: Record<string, ParticipantEvents>;
  codes: Record<string, { senderUid: string; retired: boolean }>;
  devices: Record<string, Device>;
  requests: Record<string, PairingRequest>;
  sessions: Record<string, Session>;
}
export const emptyState = (): State => ({ codes: {}, devices: {}, requests: {}, sessions: {} });
export function expire(state: State, now: number): void {
  for (const request of Object.values(state.requests)) {
    if (request.status === "pending" && request.expiresAt <= now) {
      request.status = "expired";
      releaseRequest(state, request);
    }
  }
  for (const session of Object.values(state.sessions)) {
    if (session.status === "active" && session.expiresAt <= now) {
      session.status = "ended";
      for (const uid of [session.hostUid, session.guestUid])
        if (state.devices[uid]?.active === session.roomId) delete state.devices[uid].active;
    }
  }
}
export function register(state: State, uid: string, nextCode = randomCode): Device {
  if (state.devices[uid]) {
    state.devices[uid].requestsEnabled = true;
    return state.devices[uid];
  }
  let code: string;
  let attempts = 0;
  do {
    code = nextCode();
    if (++attempts > 64) throw new ApiError(503, "Try again shortly.");
  } while (state.codes[code]);
  state.codes[code] = { senderUid: uid, retired: false };
  return (state.devices[uid] = { code, requestsEnabled: true });
}
export function reset(state: State, uid: string, nextCode = randomCode): Device {
  const device = register(state, uid, nextCode);
  for (const id of [device.pending, device.outgoing]) {
    const request = id ? state.requests[id] : undefined;
    if (request?.status === "pending") {
      request.status = "cancelled";
      releaseRequest(state, request);
    }
  }
  const old = device.code;
  let code: string;
  let attempts = 0;
  do {
    code = nextCode();
    if (++attempts > 64) throw new ApiError(503, "Try again shortly.");
  } while (state.codes[code]);
  state.codes[old].retired = true;
  state.codes[code] = { senderUid: uid, retired: false };
  device.code = code;
  return device;
}
// Compatibility for older APKs: receiving requests is no longer configurable.
export function enable(state: State, uid: string, _enabled: boolean): Device {
  return register(state, uid);
}
export function requestConnection(
  state: State,
  uid: string,
  code: string,
  requestId: string,
  now: number,
  mode: CallMode = "Video",
): { request: PairingRequest; fresh: boolean } {
  const existing = state.requests[requestId];
  if (existing) {
    if (existing.receiverUid !== uid) throw new ApiError(403, "Request unavailable.");
    if ((existing.mode ?? "Video") !== mode) throw new ApiError(409, "Request mode is immutable.");
    if (state.codes[code]?.senderUid !== existing.senderUid)
      throw new ApiError(409, "Request recipient is immutable.");
    return { request: existing, fresh: false };
  }
  const mapping = state.codes[code];
  const sender = mapping && !mapping.retired ? state.devices[mapping.senderUid] : null;
  if (!sender) throw new ApiError(404, "Device unavailable.");
  if (mapping.senderUid === uid)
    throw new ApiError(400, "Use a different device to receive the stream.");
  const caller = state.devices[uid];
  if (caller?.pending || caller?.outgoing || caller?.active || sender.outgoing)
    throw new ApiError(409, "Device is busy.");
  if (
    Object.values(state.sessions).some(
      (s) =>
        s.status === "active" &&
        s.expiresAt > now &&
        [s.hostUid, s.guestUid].some((id) => id === uid || id === mapping.senderUid),
    ) ||
    sender.pending ||
    sender.active
  )
    throw new ApiError(409, "Device is busy.");
  // Strict per-target notification limit lives in the same atomic state as admission.
  if (!sender.attemptWindow || now - sender.attemptWindow >= 60_000) {
    sender.attemptWindow = now;
    sender.attempts = 0;
  }
  if ((sender.attempts ?? 0) >= 5) throw new ApiError(429, "Try again later.");
  sender.attempts = (sender.attempts ?? 0) + 1;
  register(state, uid);
  const verificationValue = String(
    crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000,
  ).padStart(6, "0");
  const request: PairingRequest = {
    mode,
    senderName: sender.participantName,
    receiverName: state.devices[uid].participantName,
    requestId,
    senderUid: mapping.senderUid,
    receiverUid: uid,
    verificationValue,
    createdAt: now,
    expiresAt: now + REQUEST_TTL,
    status: "pending",
  };
  state.requests[requestId] = request;
  sender.pending = requestId;
  state.devices[uid].outgoing = requestId;
  return { request, fresh: true };
}
export function getRequest(state: State, uid: string, id: string): PairingRequest {
  const request = state.requests[id];
  if (!request || (request.senderUid !== uid && request.receiverUid !== uid))
    throw new ApiError(404, "Request unavailable.");
  return request;
}
export function approve(state: State, uid: string, id: string, now: number): Session {
  const request = getRequest(state, uid, id);
  if (request.senderUid !== uid) throw new ApiError(403, "Only the sender can approve.");
  if (request.status === "approved" && request.roomId) {
    const room = state.sessions[request.roomId];
    if (!room || room.status !== "active" || room.expiresAt <= now)
      throw new ApiError(410, "Session has ended.");
    return room;
  }
  if (request.status !== "pending" || request.expiresAt <= now)
    throw new ApiError(410, "Request is no longer pending.");
  const device = state.devices[uid];
  if (
    device.pending !== id ||
    device.active ||
    Object.values(state.sessions).some(
      (s) =>
        s.status === "active" &&
        s.expiresAt > now &&
        [s.hostUid, s.guestUid].some(
          (participant) => participant === uid || participant === request.receiverUid,
        ),
    )
  )
    throw new ApiError(409, "Device unavailable.");
  const roomId = crypto.randomUUID();
  const room: Session = {
    roomId,
    hostUid: uid,
    guestUid: request.receiverUid,
    createdAt: now,
    expiresAt: now + SESSION_TTL,
    status: "active",
    mode: request.mode ?? "Video",
  };
  state.sessions[roomId] = room;
  request.status = "approved";
  request.roomId = roomId;
  releaseRequest(state, request);
  device.active = roomId;
  const guest = register(state, request.receiverUid);
  guest.active = roomId;
  if (guest.pending) {
    state.requests[guest.pending].status = "cancelled";
    delete guest.pending;
  }
  return room;
}
export function finishRequest(
  state: State,
  uid: string,
  id: string,
  action: "reject" | "cancel",
): PairingRequest {
  const request = getRequest(state, uid, id);
  if ((action === "reject" ? request.senderUid : request.receiverUid) !== uid)
    throw new ApiError(403, "Not authorized.");
  if (request.status === "pending") {
    request.status = action === "reject" ? "rejected" : "cancelled";
    releaseRequest(state, request);
  } else if (request.status === "approved")
    throw new ApiError(409, "Session was already approved. End the session instead.");
  return request;
}
export function getSession(state: State, uid: string, roomId: string, now: number): Session {
  const room = state.sessions[roomId];
  if (!room || (room.hostUid !== uid && room.guestUid !== uid))
    throw new ApiError(404, "Session unavailable.");
  if (room.status !== "active" || room.expiresAt <= now)
    throw new ApiError(410, "Session has ended.");
  return room;
}
export function endSession(state: State, uid: string, roomId: string): Session {
  const room = state.sessions[roomId];
  if (!room || (room.hostUid !== uid && room.guestUid !== uid))
    throw new ApiError(404, "Session unavailable.");
  room.status = "ended";
  for (const uid of [room.hostUid, room.guestUid])
    if (state.devices[uid]?.active === roomId) delete state.devices[uid].active;
  return room;
}
export function projectRequest(state: State, request: PairingRequest, uid: string) {
  getRequest(state, uid, request.requestId);
  const peerUid = request.senderUid === uid ? request.receiverUid : request.senderUid;
  return {
    mode: request.mode ?? "Video",
    requestId: request.requestId,
    verificationValue: request.verificationValue,
    peerCode: state.devices[peerUid]?.code ?? null,
    peerName: (request.senderUid === uid ? request.receiverName : request.senderName) ?? null,
    createdAt: request.createdAt,
    expiresAt: request.expiresAt,
    status: request.status,
    roomId: request.roomId ?? null,
    role: request.senderUid === uid ? "SENDER" : "RECEIVER",
  };
}

/** Participant-only projection shared by the in-memory reference and D1 event delivery. */
export function participantEvents(state: State): Record<string, ParticipantEvents> {
  const views: Record<string, ParticipantEvents> = {};
  const own = (uid: string) => (views[uid] ??= { incoming: null, requests: {}, sessions: {} });
  for (const [uid, device] of Object.entries(state.devices)) {
    const view = own(uid);
    const pending = device.pending ? state.requests[device.pending] : undefined;
    if (pending?.status === "pending") view.incoming = projectRequest(state, pending, uid);
  }
  for (const request of Object.values(state.requests)) {
    for (const uid of [request.senderUid, request.receiverUid])
      own(uid).requests[request.requestId] = projectRequest(state, request, uid);
  }
  for (const room of Object.values(state.sessions)) {
    for (const uid of [room.hostUid, room.guestUid])
      own(uid).sessions[room.roomId] = {
        active: room.status === "active",
        expiresAt: room.expiresAt,
      };
  }
  return views;
}

// Idempotent revocation does not allocate a replacement registration.
export function revoke(state: State, uid: string): void {
  const device = state.devices[uid];
  if (!device) return;
  if (state.codes[device.code]) state.codes[device.code].retired = true;
  for (const request of Object.values(state.requests)) {
    if (request.status === "pending" && [request.senderUid, request.receiverUid].includes(uid)) {
      request.status = "cancelled";
      releaseRequest(state, request);
    }
  }
  for (const session of Object.values(state.sessions)) {
    if (session.status === "active" && [session.hostUid, session.guestUid].includes(uid))
      endSession(state, uid, session.roomId);
  }
  delete state.devices[uid];
}

/** Validate only host-supplied display metadata; a name is not an authenticated user identity. */
export function participantName(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value !== "string" || /[\p{Cc}]/u.test(value))
    throw new ApiError(400, "Invalid participant name.");
  const name = value.trim();
  if ([...name].length > 40)
    throw new ApiError(400, "Participant names may contain up to 40 characters.");
  return name || undefined;
}

/** Both participants own a slot from submission until a terminal outcome. */
function releaseRequest(state: State, request: PairingRequest) {
  const sender = state.devices[request.senderUid];
  const caller = state.devices[request.receiverUid];
  if (sender?.pending === request.requestId) delete sender.pending;
  if (caller?.outgoing === request.requestId) delete caller.outgoing;
}
