const BASE = '';

export interface LoginResponse {
  token: string;
  userId: string;
  username: string;
  roles: string[];
}

export interface MeResponse {
  userId: string;
  username: string;
  roles: string[];
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error('凭据无效');
  return res.json();
}

export async function me(): Promise<MeResponse> {
  const res = await fetch(`${BASE}/api/auth/me`, {
    headers: { Authorization: `Bearer ${localStorage.getItem('claw_token')}` },
  });
  if (!res.ok) throw new Error('未授权');
  return res.json();
}

export function getToken(): string | null {
  return localStorage.getItem('claw_token');
}

export function saveToken(token: string) {
  localStorage.setItem('claw_token', token);
}

export function clearToken() {
  localStorage.removeItem('claw_token');
}

/**
 * 解码 JWT 负载（base64url → JSON），不验证签名。
 * 如果没有 token 或解析失败则返回 null。
 */
function decodeTokenPayload(): Record<string, unknown> | null {
  const token = getToken();
  if (!token) return null;
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** 返回当前用户的 JWT 是否包含 'admin' 角色。 */
export function isAdmin(): boolean {
  const payload = decodeTokenPayload();
  if (!payload) return false;
  const roles = payload['roles'];
  if (Array.isArray(roles)) {
    return roles.some(r => r === 'admin' || r === 'ROLE_ADMIN');
  }
  return false;
}
