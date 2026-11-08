// Based on Flowseal/tg-ws-proxy Cloudflare Worker transport.
// Copyright (c) 2026 Flowseal — see NOTICE.md for the MIT license.
import { connect } from "cloudflare:sockets";

function toBytes(data) {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (typeof data === "string") return new TextEncoder().encode(data);
  if (data && typeof data.arrayBuffer === "function") {
    return data.arrayBuffer().then((buffer) => new Uint8Array(buffer));
  }
  return new Uint8Array();
}

const ALLOWED_DESTINATIONS = new Set(
  [1, 2, 3, 4, 5].flatMap((dc) => [
    `kws${dc}.web.telegram.org`,
    `kws${dc}-1.web.telegram.org`,
  ])
);

export default {
  async fetch(request) {
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("Expected websocket", { status: 426 });
    }

    const url = new URL(request.url);
    if (url.pathname !== "/apiws") {
      return new Response("Not found", { status: 404 });
    }

    const destination = (url.searchParams.get("dst") || "").toLowerCase();
    if (!ALLOWED_DESTINATIONS.has(destination)) {
      return new Response("Destination not allowed", { status: 403 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    let socket;
    try {
      socket = connect({ hostname: destination, port: 443 });
    } catch {
      server.close(1011, "upstream connect failed");
      return new Response("Upstream connection failed", { status: 502 });
    }

    const reader = socket.readable.getReader();
    const writer = socket.writable.getWriter();

    server.addEventListener("message", async (event) => {
      try {
        await writer.write(await toBytes(event.data));
      } catch {
        try { server.close(1011, "tcp write failed"); } catch {}
      }
    });

    server.addEventListener("close", async () => {
      try { await writer.close(); } catch {}
      try { socket.close(); } catch {}
    });

    (async () => {
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          if (value) server.send(value);
        }
      } catch {
        // The WebSocket close below terminates the client-side relay.
      } finally {
        try { server.close(); } catch {}
        try { reader.releaseLock(); } catch {}
        try { socket.close(); } catch {}
      }
    })();

    return new Response(null, { status: 101, webSocket: client });
  },
};
