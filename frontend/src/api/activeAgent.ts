export const DEFAULT_AGENT_ID = 'data-agent';

const STORAGE_KEY = 'dataagent.activeAgentId';

export function storedActiveAgentId(): string {
  if (typeof window === 'undefined') return DEFAULT_AGENT_ID;
  try {
    return localStorage.getItem(STORAGE_KEY)?.trim() || DEFAULT_AGENT_ID;
  } catch {
    return DEFAULT_AGENT_ID;
  }
}

export function persistActiveAgentId(agentId: string): void {
  if (typeof window === 'undefined') return;
  try { localStorage.setItem(STORAGE_KEY, agentId.trim() || DEFAULT_AGENT_ID); } catch { /* optional */ }
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
