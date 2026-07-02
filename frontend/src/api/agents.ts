import { getToken } from './auth';

export interface AgentShareGrant {
  granteeType: string;
  granteeId: string;
  tier: string;
  createdAt?: number;
  createdBy?: string;
}

export interface AgentDefinition {
  id: string;
  name: string;
  description?: string;
  sysPrompt?: string;
  model?: string;
  maxIters?: number;
  tools?: string[];
  toolsAllow?: string[];
  toolsDeny?: string[];
  identityName?: string;
  identityEmoji?: string;
  groupChatMentionPatterns?: string[];
  groupChatRequireMention?: boolean;
  skillsAllow?: string[];
  skillsDeny?: string[];
  scope: 'global' | 'user';
  ownerId?: string;
  createdAt: number;
  updatedAt: number;
  shares?: AgentShareGrant[];
  runAs?: string;
  forkOf?: string;
  workspacePath?: string;
  sandboxMode?: string;
  sandboxScope?: string;
  tierForCurrentUser?: 'CLONE' | 'RUN' | 'EDIT' | string;
}

export interface AgentDraft {
  name: string;
  description?: string;
  sysPrompt?: string;
  suggestedTools?: string[];
  suggestedSkills?: { name: string; content: string }[];
  suggestedSubagents?: { name: string; content: string }[];
}

export interface AgentCreateRequest {
  id?: string;
  name: string;
  description?: string;
  sysPrompt?: string;
  maxIters?: number;
  templateId?: string;
  aiDraft?: AgentDraft;
  workspacePath?: string;
  sandboxMode?: string;
  sandboxScope?: string;
}

function authHeaders() {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${getToken()}`,
  };
}

export async function listAgents(): Promise<AgentDefinition[]> {
  const res = await fetch('/api/agents', { headers: authHeaders() });
  if (!res.ok) throw new Error(`获取 agent 列表失败: ${res.status}`);
  return res.json();
}

export async function getAgent(id: string): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`加载 agent 失败: ${res.status}`);
  return res.json();
}

export async function createAgent(req: AgentCreateRequest): Promise<AgentDefinition> {
  const res = await fetch('/api/agents', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`创建 agent 失败: ${msg}`);
  }
  return res.json();
}

export async function updateAgent(
  id: string,
  req: AgentCreateRequest,
): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`更新 agent 失败: ${msg}`);
  }
  return res.json();
}

export async function deleteAgent(id: string): Promise<void> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) {
    throw new Error(`删除 agent 失败: ${res.status}`);
  }
}

export async function draftAgentWithAi(description: string): Promise<AgentDraft> {
  const res = await fetch('/api/agents/draft', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ description }),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `草稿 agent 失败: ${res.status}`);
  }
  return res.json();
}

export async function cloneAgent(sourceId: string, body: { id?: string; name?: string }): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(sourceId)}/clone`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`克隆 agent 失败: ${msg}`);
  }
  return res.json();
}

// 分享授权请求体（POST /api/agents/{id}/shares 的请求 body）
export interface ShareGrantRequest {
  granteeType: 'USER' | 'WORKSPACE';
  granteeId: string;  // USER 时为目标 userId；WORKSPACE 时传 '*'（后端会归一化）
  tier: 'CLONE' | 'RUN' | 'EDIT';
}

/**
 * 追加（或更新）一条分享授权。upsert 语义：同 (granteeType, granteeId) 已存在就更新 tier。
 * 只有 Agent 的 owner 能调用，后端会校验。
 */
export async function grantShare(agentId: string, req: ShareGrantRequest): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/shares`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`分享 agent 失败: ${msg}`);
  }
  return res.json();
}

/**
 * 撤销一条分享授权。精确匹配 (granteeType, granteeId)。
 * 参数走 query string（DELETE 带 body 不规范）。
 */
export async function revokeShare(
  agentId: string,
  granteeType: 'USER' | 'WORKSPACE',
  granteeId: string,
): Promise<AgentDefinition> {
  const qs = new URLSearchParams({ granteeType, granteeId });
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/shares?${qs}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(`撤销分享失败: ${msg}`);
  }
  return res.json();
}
