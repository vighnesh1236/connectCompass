const jwt = require("jsonwebtoken");
const { WebSocketServer, WebSocket } = require("ws");
const pool = require("../config/database");

const connections = new Map();
const webSocketServer = new WebSocketServer({ noServer: true, maxPayload: 2048 });
let heartbeat;

function attachMessaging(server) {
  server.on("upgrade", (request, socket, head) => {
    let path;
    try {
      path = new URL(request.url, `http://${request.headers.host}`).pathname;
    } catch {
      socket.destroy();
      return;
    }
    if (path !== "/api/messages/live") {
      socket.destroy();
      return;
    }
    webSocketServer.handleUpgrade(request, socket, head, (client) => {
      webSocketServer.emit("connection", client, request);
    });
  });

  webSocketServer.on("connection", (client) => {
    let userId;
    let authPending = false;
    const authTimeout = setTimeout(() => client.close(4001, "Authentication required"), 10_000);

    client.on("error", (error) => {
      if (userId) console.error("Student message connection failed:", error);
    });

    client.on("message", async (raw) => {
      if (userId || authPending) return;
      authPending = true;
      try {
        const message = JSON.parse(raw.toString());
        if (message.type !== "authenticate" || typeof message.token !== "string") {
          client.close(4001, "Authentication required");
          return;
        }
        const payload = jwt.verify(message.token, process.env.JWT_SECRET);
        const { rows } = await pool.query("SELECT role FROM users WHERE id = $1", [payload.id]);
        if (client.readyState !== WebSocket.OPEN) return;
        if (payload.role !== "student" || rows[0]?.role !== "student") {
          client.close(4003, "Student account required");
          return;
        }
        userId = Number(payload.id);
        clearTimeout(authTimeout);
        const userConnections = connections.get(userId) || new Set();
        userConnections.add(client);
        connections.set(userId, userConnections);
        client.send(JSON.stringify({ type: "ready" }));
      } catch {
        client.close(4001, "Authentication failed");
      }
    });

    client.on("close", () => {
      clearTimeout(authTimeout);
      if (!userId) return;
      const userConnections = connections.get(userId);
      userConnections?.delete(client);
      if (userConnections?.size === 0) connections.delete(userId);
    });
  });

  heartbeat = setInterval(() => {
    for (const client of webSocketServer.clients) {
      if (client.readyState === WebSocket.OPEN) client.ping();
    }
  }, 25_000);
}

function closeMessaging() {
  clearInterval(heartbeat);
  for (const client of webSocketServer.clients) client.terminate();
  webSocketServer.close();
}

function notifyUser(userId, message) {
  const payload = JSON.stringify(message);
  for (const client of connections.get(userId) || []) {
    if (client.readyState !== WebSocket.OPEN) continue;
    client.send(payload, (error) => {
      if (error) console.error("Could not deliver a live message notification:", error);
    });
  }
}

module.exports = { attachMessaging, closeMessaging, notifyUser };
