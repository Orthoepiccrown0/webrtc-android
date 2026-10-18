import {
  ApiError,
  register,
  revoke,
  reset,
  enable,
  normalizeCode,
  participantName,
  requestConnection,
  getRequest,
  approve,
  finishRequest,
  getSession,
  endSession,
  projectRequest,
} from "./coordinator.ts";
import {
  authenticate,
  transact,
  materializeRoom,
  turnCredentials,
  cleanup,
  flushAuthorizations,
} from "./services.ts";
import { CoordinationStore } from "./store.ts";
import { budgetDatabase } from "./query-budget.ts";
import type { Env } from "./services.ts";
const json = (body: unknown, status = 200) =>
  Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
export default {
  scheduled(_event: ScheduledController, env: Env, context: ExecutionContext) {
    context.waitUntil(cleanup(env));
  },
  async fetch(request: Request, configuration: Env, context: ExecutionContext): Promise<Response> {
    const env = {
      ...configuration,
      COORDINATION_DB: configuration.COORDINATION_DB
        ? budgetDatabase(configuration.COORDINATION_DB, configuration.D1_QUERY_BUDGET).db
        : undefined,
    } as Env;
    try {
      if (env.MAINTENANCE_MODE === "true")
        throw new ApiError(503, "Service maintenance. Retry shortly.");
      if (!env.COORDINATION_DB) throw new ApiError(503, "Coordination storage is not configured.");
      if (request.method !== "POST") throw new ApiError(405, "Use POST.");
      const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
      if (!(await env.IP_LIMITER.limit({ key: ip })).success)
        throw new ApiError(429, "Try again later.");
      const uid = await authenticate(request, env);
      if (!(await env.UID_LIMITER.limit({ key: uid })).success)
        throw new ApiError(429, "Try again later.");
      const text = await request.text();
      if (text.length > 8192) throw new ApiError(413, "Request too large.");
      let body: Record<string, unknown>;
      try {
        body = JSON.parse(text || "{}");
      } catch {
        throw new ApiError(400, "Invalid JSON.");
      }
      if (!body || typeof body !== "object" || Array.isArray(body))
        throw new ApiError(400, "Invalid request.");
      const id = (key: string) => {
        const value = body[key];
        if (typeof value !== "string" || !/^[a-zA-Z0-9_-]{16,100}$/.test(value))
          throw new ApiError(400, "Invalid identifier.");
        return value;
      };
      const path = new URL(request.url).pathname;
      if (!path.startsWith("/v1/")) throw new ApiError(404, "Unknown operation.");
      if (!(await new CoordinationStore(env.COORDINATION_DB).ready()))
        throw new ApiError(503, "Coordination migration is incomplete.");
      if (path === "/v1/device/revoke") {
        await transact(env, { uid }, (state) => revoke(state, uid));
        await flushAuthorizations(env, uid);
        return json({ ok: true });
      }
      if (path === "/v1/device/register" || path === "/v1/device/reset") {
        const name = participantName(body.participantName);
        const device = await transact(env, { uid }, (state) => {
          const device = path.endsWith("reset") ? reset(state, uid) : register(state, uid);
          // Old clients omit metadata; omission must not erase a previously assigned name.
          if (Object.hasOwn(body, "participantName")) {
            if (name) device.participantName = name;
            else delete device.participantName;
          }
          return device;
        });
        return json({
          code: device.code,
          participantName: device.participantName ?? null,
          codecProtocolVersion: 1,
          supportedCallModes: ["Audio", "Video"],
          requestsEnabled: device.requestsEnabled,
          activeRoomId: device.active ?? null,
          eventProtocolVersion: 1,
          relayAvailable: env.TURN_ENABLED === "true",
        });
      }
      if (path === "/v1/device/settings") {
        if (typeof body.enabled !== "boolean") throw new ApiError(400, "enabled must be boolean.");
        const device = await transact(env, { uid }, (state) =>
          enable(state, uid, body.enabled as boolean),
        );
        return json({
          code: device.code,
          participantName: device.participantName ?? null,
          codecProtocolVersion: 1,
          supportedCallModes: ["Audio", "Video"],
          requestsEnabled: device.requestsEnabled,
          activeRoomId: device.active ?? null,
          eventProtocolVersion: 1,
          relayAvailable: env.TURN_ENABLED === "true",
        });
      }
      if (path === "/v1/device/push-token") {
        if (typeof body.token !== "string" || body.token.length < 20 || body.token.length > 4096)
          throw new ApiError(400, "Invalid push registration.");
        await transact(env, { uid }, (state) => {
          register(state, uid).token = body.token as string;
        });
        return json({ ok: true });
      }
      if (path === "/v1/requests/create") {
        const code = normalizeCode(body.code);
        const requestId = id("requestId");
        const value = await transact(env, { uid, code, requestId }, (state, now) => {
          const mode = body.mode ?? "Video";
          if (mode !== "Audio" && mode !== "Video") throw new ApiError(400, "Invalid call mode.");
          const result = requestConnection(state, uid, code, requestId, now, mode);
          return {
            ...result,
            projected: projectRequest(state, result.request, uid),
            token: state.devices[result.request.senderUid].token,
          };
        });
        return json(value.projected);
      }
      if (path === "/v1/requests/incoming") {
        const value = await transact(env, { uid }, (state) => {
          const device = state.devices[uid];
          return device?.pending
            ? projectRequest(state, state.requests[device.pending], uid)
            : null;
        });
        return json({ request: value });
      }
      if (path === "/v1/requests/status") {
        const requestId = id("requestId");
        const result = await transact(env, { uid, requestId }, (state) => {
          const value = getRequest(state, uid, requestId);
          return {
            request: projectRequest(state, value, uid),
            room: value.roomId ? state.sessions[value.roomId] : null,
          };
        });
        if (result.room?.status === "active") await materializeRoom(env, result.room);
        return json(result.request);
      }
      if (path === "/v1/requests/approve") {
        const requestId = id("requestId");
        const room = await transact(env, { uid, requestId }, (state, now) =>
          approve(state, uid, requestId, now),
        );
        await materializeRoom(env, room);
        return json({ roomId: room.roomId });
      }
      if (path === "/v1/requests/reject" || path === "/v1/requests/cancel") {
        const requestId = id("requestId");
        const value = await transact(env, { uid, requestId }, (state) =>
          projectRequest(
            state,
            finishRequest(state, uid, requestId, path.endsWith("reject") ? "reject" : "cancel"),
            uid,
          ),
        );
        return json(value);
      }
      if (path === "/v1/sessions/status") {
        const roomId = id("roomId");
        const room = await transact(env, { uid, roomId }, (state, now) =>
          getSession(state, uid, roomId, now),
        );
        return json({ active: true, expiresAt: room.expiresAt });
      }
      if (path === "/v1/sessions/end") {
        const roomId = id("roomId");
        const room = await transact(env, { uid, roomId }, (state) =>
          endSession(state, uid, roomId),
        );
        // Authorization projection is retried durably; success is acknowledged only after mirroring.
        await materializeRoom(env, room);
        return json({ ok: true });
      }
      if (path === "/v1/turn-credentials") {
        const roomId = id("roomId");
        const room = await transact(env, { uid, roomId }, (state, now) =>
          getSession(state, uid, roomId, now),
        );
        return json(await turnCredentials(env, room));
      }
      throw new ApiError(404, "Unknown operation.");
    } catch (error) {
      if (error instanceof ApiError) return json({ error: error.message }, error.status);
      console.error("Pairing operation failed");
      return json({ error: "Service unavailable. Retry shortly." }, 503);
    }
  },
} satisfies ExportedHandler<Env>;
