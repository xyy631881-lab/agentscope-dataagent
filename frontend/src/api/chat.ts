import { getToken } from './auth';

export interface ConfirmDecision {
  toolCallId: string;
  approved: boolean;
}

export interface PendingToolCall {
  id: string;
  name: string;
  input?: unknown;
}

export interface ChatRequest {
  message: string;
  sessionKey?: string;
  confirmResults?: ConfirmDecision[];
  requestId?: string;
}

export interface ChatEvent {
  type: 'token' | 'tool_call' | 'tool_result' | 'done' | 'error' | 'confirm' | string;
  data?: string;
  toolName?: string;
  toolCallId?: string;
  toolInput?: string;
  toolResult?: string;
  error?: string;
  sessionKey?: string;
  replyId?: string;
  toolCalls?: PendingToolCall[];
}

export interface CurrentSession {
  sessionKey: string | null;
  exists: boolean;
}

export interface PendingConfirmationStatus {
  replyId: string;
  toolCalls: PendingToolCall[];
}

/** Runtime state survives a browser navigation; it is not tied to one SSE connection. */
export interface ChatRunStatus {
  sessionKey: string;
  running: boolean;
  requestId?: string | null;
  pendingConfirmation?: PendingConfirmationStatus | null;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function currentSession(
  agentId: string,
  sessionKey?: string,
): Promise<CurrentSession> {
  const qs = sessionKey ? `?sessionKey=${encodeURIComponent(sessionKey)}` : '';
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/session${qs}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(`解析当前会话失败: ${res.status}`);
  return res.json();
}

export async function chatRunStatus(agentId: string, sessionKey: string): Promise<ChatRunStatus> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/chat/status?sessionKey=${encodeURIComponent(sessionKey)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw new Error(`读取会话运行状态失败: ${res.status}`);
  return res.json();
}

export async function* stream(
  agentId: string,
  req: ChatRequest,
  signal?: AbortSignal,
): AsyncGenerator<ChatEvent> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok || !res.body) throw new Error(`聊天流连接失败: ${res.status}`);

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const evt = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const lines = evt.split('\n');
      let data = '';
      for (const ln of lines) if (ln.startsWith('data:')) data += ln.slice(5).trim();
      if (!data) continue;
      try {
        yield JSON.parse(data) as ChatEvent;
      } catch {
        yield { type: 'token', data } as ChatEvent;
      }
    }
  }
}

export async function cancelStream(agentId: string, requestId: string): Promise<void> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ requestId }),
  });
  if (!res.ok && res.status !== 204) {
    throw new Error(`Unable to cancel the current request: ${res.status}`);
  }
}
