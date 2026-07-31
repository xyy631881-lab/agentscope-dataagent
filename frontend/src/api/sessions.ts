import { getToken } from './auth';

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function readJson<T>(res: Response, failureMessage: string): Promise<T> {
  if (!res.ok) throw new Error(failureMessage);

  const contentType = res.headers.get('content-type') ?? '';
  if (!contentType.includes('application/json')) {
    throw new Error(`${failureMessage}：服务返回了网页而非 JSON，请确认后端已重启并与前端版本一致。`);
  }
  return res.json() as Promise<T>;
}

// ── Agent 范围的会话收件箱/轮次 ──────────────────

export interface InboxEntry {
  sessionKey: string;
  sessionId: string;
  agentId: string;
  /**
   * 用作 URL `?session=` 和聊天请求 body 中 `sessionKey` 的会话 ID。
   * 对于旧版单会话条目为 null；调用者应回退使用 `sessionKey`
   *（后端在会话管理端点上同样接受此值）。
   */
  conversationId: string | null;
  label: string | null;
  lastActivityMs: number;
  lastMessage: string | null;
  unread: boolean;
}

export interface InboxOptions {
  limit?: number;
  unreadOnly?: boolean;
}

export interface HistorySettings {
  maxSessions: number;
}

export interface CreatedSession {
  sessionKey: string;
  exists: boolean;
}

export interface TurnEntry {
  id: string;
  parentId: string | null;
  role: 'USER' | 'ASSISTANT' | 'TOOL' | string;
  content: string | null;
  timestampMs: number;
  toolName: string | null;
  toolInput: string | null;
  toolResult: string | null;
}

export interface ResetResult {
  sessionKey: string;
  reset: boolean;
}

export interface ReadStateResult {
  sessionKey: string;
  readAtMs: number;
  unread: boolean;
}

export async function inbox(agentId: string, opts: InboxOptions = {}): Promise<InboxEntry[]> {
  const params = new URLSearchParams();
  if (opts.limit != null) params.set('limit', String(opts.limit));
  if (opts.unreadOnly) params.set('unreadOnly', 'true');
  const qs = params.toString();
  const url = `/api/agents/${encodeURIComponent(agentId)}/sessions/inbox${qs ? `?${qs}` : ''}`;
  const res = await fetch(url, { headers: authHeaders() });
  return readJson<InboxEntry[]>(res, '加载会话列表失败');
}

export async function createSession(agentId: string): Promise<CreatedSession> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/session`, {
    method: 'POST', headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Unable to create a new conversation');
  return res.json();
}

export async function getHistorySettings(agentId: string): Promise<HistorySettings> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/sessions/settings`, {
    headers: authHeaders(),
  });
  return readJson<HistorySettings>(res, '加载会话保留设置失败');
}

export async function updateHistorySettings(
  agentId: string, maxSessions: number,
): Promise<HistorySettings> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/sessions/settings`, {
    method: 'PUT',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ maxSessions }),
  });
  return readJson<HistorySettings>(res, '保存会话保留设置失败');
}

export async function turns(agentId: string, sessionKey: string): Promise<TurnEntry[]> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw new Error('获取会话轮次失败');
  return res.json();
}

export async function resetSession(agentId: string, sessionKey: string): Promise<ResetResult> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}/reset`,
    { method: 'POST', headers: authHeaders() },
  );
  if (!res.ok) throw new Error('重置会话失败');
  return res.json();
}

export async function markRead(agentId: string, sessionKey: string): Promise<ReadStateResult> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}/read`,
    { method: 'PATCH', headers: authHeaders() },
  );
  if (!res.ok) throw new Error('标记会话已读失败');
  return res.json();
}

export async function deleteSession(agentId: string, sessionKey: string): Promise<void> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) throw new Error('删除会话失败');
}

// ── 旧版管理端会话列表（用于 admin/AgentSidebar） ───

export interface SessionView {
  sessionKey: string;
  agentId: string;
  sessionId: string;
  label: string | null;
  kind: string;
  lastActivityMs: number;
  createdAtMs: number;
  userId: string | null;
}

export async function listSessions(limit = 50): Promise<SessionView[]> {
  const res = await fetch(`/api/sessions?limit=${limit}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('获取会话列表失败');
  return res.json();
}
