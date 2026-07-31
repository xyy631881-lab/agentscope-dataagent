export const DEFAULT_AGENT_ID = 'data-agent';

const STORAGE_KEY = 'dataagent.activeAgentId';

function userStorageKey(): string {
  try {
    const token = localStorage.getItem('claw_token');
    const rawPayload = token?.split('.')[1];
    const normalizedPayload = rawPayload
      ? rawPayload.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(rawPayload.length / 4) * 4, '=')
      : null;
    const payload = normalizedPayload ? JSON.parse(atob(normalizedPayload)) as Record<string, unknown> : null;
    const userId = payload?.userId ?? payload?.sub;
    if (typeof userId === 'string' && userId) return `${STORAGE_KEY}:${userId}`;
  } catch {
    // Fall back to a per-browser anonymous key while authentication is unavailable.
  }
  return `${STORAGE_KEY}:anonymous`;
}

export function hasStoredActiveAgent(): boolean {
  if (typeof window === 'undefined') return false;
  try { return Boolean(localStorage.getItem(userStorageKey())?.trim()); } catch { return false; }
}

export function storedActiveAgentId(): string {
  if (typeof window === 'undefined') return DEFAULT_AGENT_ID;
  try {
    return localStorage.getItem(userStorageKey())?.trim() || DEFAULT_AGENT_ID;
  } catch {
    return DEFAULT_AGENT_ID;
  }
}

export function persistActiveAgentId(agentId: string): void {
  if (typeof window === 'undefined') return;
  try { localStorage.setItem(userStorageKey(), agentId.trim() || DEFAULT_AGENT_ID); } catch { /* optional */ }
}

export function activeAgentIdFromSearch(search: string): string | null {
  const value = new URLSearchParams(search).get('agent');
  return value?.trim() || null;
}

export function chatHref(agentId: string, sessionKey?: string | null): string {
  const params = new URLSearchParams({ agent: agentId || DEFAULT_AGENT_ID });
  if (sessionKey) params.set('session', sessionKey);
  return `/chat?${params.toString()}`;
}

export function hrefForAgent(path: string, agentId: string, sessionKey?: string | null): string {
  const params = new URLSearchParams({ agent: agentId || DEFAULT_AGENT_ID });
  if (sessionKey) params.set('session', sessionKey);
  return `${path}?${params.toString()}`;
}
