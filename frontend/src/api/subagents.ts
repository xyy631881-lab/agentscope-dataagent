import { getToken } from './auth';

export interface SubagentInfo {
  name: string;
  description: string;
  model?: string;
  maxIters?: number;
  tools?: string[];
  workspaceMode: 'isolated' | 'shared';
  workspacePath?: string;
  hasInlineBody: boolean;
  sourceAgentId?: string;
}

export interface SubagentUpsertRequest {
  description: string;
  model?: string;
  maxIters?: number;
  tools?: string[];
  workspaceMode?: string;
  workspacePath?: string;
  inlineBody?: string;
  sourceAgentId?: string;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/workspace/subagents`;
}

export async function listSubagents(agentId: string): Promise<SubagentInfo[]> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12_000);
  try {
    const res = await fetch(base(agentId), {
      headers: authHeaders(),
      signal: controller.signal,
    });
    if (!res.ok) {
      const detail = (await res.text().catch(() => '')).trim();
      throw new Error(`加载子 Agent 失败 (${res.status})${detail ? `：${detail}` : ''}`);
    }
    return res.json();
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error('加载子 Agent 超时（12 秒）。请检查后端、Docker 和运行时日志。');
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

export async function upsertSubagent(
  agentId: string,
  name: string,
  req: SubagentUpsertRequest,
): Promise<SubagentInfo> {
  const res = await fetch(`${base(agentId)}/${encodeURIComponent(name)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`Failed to save subagent: ${msg}`);
  }
  return res.json();
}

export async function createFromAgent(
  agentId: string,
  sourceAgentId: string,
  name?: string,
): Promise<SubagentInfo> {
  const res = await fetch(`${base(agentId)}/from-agent`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ sourceAgentId, name }),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`Failed to create subagent from agent: ${msg}`);
  }
  return res.json();
}

export async function deleteSubagent(agentId: string, name: string): Promise<void> {
  const res = await fetch(`${base(agentId)}/${encodeURIComponent(name)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw new Error('Failed to delete subagent');
}
