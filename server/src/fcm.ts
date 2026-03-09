export type Env = {
  FCM_PROJECT_ID: string;
  FCM_CLIENT_EMAIL: string;
  FCM_PRIVATE_KEY: string;
};

export type PushDataPayload = {
  type: "pr_update";
  repo: string;
  prNumber: string;
  action: string;
  deliveryId: string;
  sentAt: string;
  prTitle?: string;
  prUrl?: string;
};

const encoder = new TextEncoder();

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const cleaned = pem
    .replace(/\\n/g, "\n")
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s/g, "");
  const binaryDer = Uint8Array.from(atob(cleaned), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8",
    binaryDer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
}

function base64url(input: string | ArrayBuffer): string {
  const str =
    typeof input === "string"
      ? btoa(input)
      : btoa(String.fromCharCode(...new Uint8Array(input)));
  return str.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function generateServiceAccountJwt(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: env.FCM_CLIENT_EMAIL,
    sub: env.FCM_CLIENT_EMAIL,
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
  };
  const unsignedToken = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(payload))}`;
  const key = await importPrivateKey(env.FCM_PRIVATE_KEY);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    encoder.encode(unsignedToken)
  );
  return `${unsignedToken}.${base64url(signature)}`;
}

export let cachedAccessToken: { token: string; expiresAt: number } | null = null;

export function resetAccessTokenCache(): void {
  cachedAccessToken = null;
}

async function getAccessToken(env: Env): Promise<string> {
  const now = Date.now();
  if (cachedAccessToken && cachedAccessToken.expiresAt > now) {
    return cachedAccessToken.token;
  }

  const jwt = await generateServiceAccountJwt(env);
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`,
  });

  if (!res.ok) {
    const text = await res.text();
    throw new Error(`OAuth token exchange failed (${res.status}): ${text}`);
  }

  const data = (await res.json()) as { access_token: string; expires_in: number };
  cachedAccessToken = {
    token: data.access_token,
    expiresAt: now + (data.expires_in - 300) * 1000,
  };
  return data.access_token;
}

export type FcmSendResult = { success: true } | { success: false; reason: string; deleteToken: boolean };

function maskToken(token: string): string {
  if (token.length <= 10) {
    return `${token.slice(0, 2)}***${token.slice(-2)}`;
  }

  return `${token.slice(0, 6)}...${token.slice(-6)}`;
}

export async function sendFcmPush(
  env: Env,
  payload: PushDataPayload,
  token: string
): Promise<FcmSendResult> {
  const accessToken = await getAccessToken(env);
  const fcmUrl = `https://fcm.googleapis.com/v1/projects/${env.FCM_PROJECT_ID}/messages:send`;

  const res = await fetch(fcmUrl, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      message: {
        token,
        data: {
          type: payload.type,
          repo: payload.repo,
          prNumber: payload.prNumber,
          action: payload.action,
          deliveryId: payload.deliveryId,
          sentAt: payload.sentAt,
          ...(payload.prTitle ? { prTitle: payload.prTitle } : {}),
          ...(payload.prUrl ? { prUrl: payload.prUrl } : {}),
        },
      },
    }),
  });

  if (res.ok) {
    return { success: true };
  }

  const errorBody = (await res.json().catch(() => ({}))) as {
    error?: { code?: number; status?: string; message?: string };
  };
  const errorStatus = errorBody.error?.status ?? "";
  const errorMessage = errorBody.error?.message ?? `HTTP ${res.status}`;

  if (errorStatus === "UNREGISTERED" || errorStatus === "INVALID_ARGUMENT") {
    console.warn(`FCM token invalid (${errorStatus}), removing: ${maskToken(token)}`);
    return { success: false, reason: errorStatus, deleteToken: true };
  }

  if (res.status === 429) {
    console.warn(`FCM rate limited for token ${maskToken(token)}`);
    return { success: false, reason: "RATE_LIMITED", deleteToken: false };
  }

  console.error(`FCM send failed (${res.status}): ${errorMessage}`);
  return { success: false, reason: errorMessage, deleteToken: false };
}
