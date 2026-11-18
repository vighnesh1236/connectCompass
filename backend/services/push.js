const { sign } = require("node:crypto");

let cachedAccessToken;
let cachedTokenExpiresAt = 0;

function serviceAccount() {
  const raw = process.env.FCM_SERVICE_ACCOUNT_JSON;
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (error) {
    throw new Error("FCM_SERVICE_ACCOUNT_JSON must contain valid service-account JSON", { cause: error });
  }
}

function isPushConfigured() {
  const account = serviceAccount();
  return Boolean(
    account?.client_email &&
    account.private_key &&
    (process.env.FCM_PROJECT_ID || account.project_id)
  );
}

function base64Url(value) {
  return Buffer.from(value).toString("base64url");
}

async function accessToken(account) {
  if (cachedAccessToken && cachedTokenExpiresAt > Date.now() + 60_000) {
    return cachedAccessToken;
  }

  const issuedAt = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: account.token_uri || "https://oauth2.googleapis.com/token",
    iat: issuedAt,
    exp: issuedAt + 3600,
  }));
  const unsignedAssertion = `${header}.${claims}`;
  const assertion = `${unsignedAssertion}.${sign("RSA-SHA256", Buffer.from(unsignedAssertion), account.private_key).toString("base64url")}`;
  const response = await fetch(account.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const result = await response.json();
  if (!response.ok || !result.access_token) {
    throw new Error(`Google token exchange failed (${response.status}): ${result.error || "missing access token"}`);
  }
  cachedAccessToken = result.access_token;
  cachedTokenExpiresAt = Date.now() + Number(result.expires_in || 3600) * 1000;
  return cachedAccessToken;
}

async function sendPush(token, { title, body, conversationId }) {
  const account = serviceAccount();
  const projectId = process.env.FCM_PROJECT_ID || account?.project_id;
  if (!account?.client_email || !account.private_key || !projectId) {
    console.warn("Push notification not sent: configure FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_JSON.");
    return null;
  }

  const bearer = await accessToken(account);
  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${bearer}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          data: {
            title: String(title).slice(0, 120),
            body: String(body).slice(0, 1000),
            conversationId: String(conversationId),
          },
          android: { priority: "high" },
        },
      }),
    }
  );
  if (!response.ok) {
    const result = await response.json().catch(() => ({}));
    if (result.error?.details?.some((detail) => detail.errorCode === "UNREGISTERED")) {
      return false;
    }
    throw new Error(`Firebase push request failed (${response.status}): ${result.error?.message || "unknown error"}`);
  }
  return true;
}

module.exports = { isPushConfigured, sendPush };
