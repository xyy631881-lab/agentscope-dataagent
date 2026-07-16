import { getToken } from './auth';

export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'dir';
  size?: number;
  children?: FileNode[];
}

export interface WorkspaceSummary {
  agentId: string;
  workspacePath: string;
  runtimeWorkspacePath?: string;
  definitionWorkspacePath?: string;
  exists: boolean;
  agentsMdExists: boolean;
  memoryMdExists: boolean;
  skillCount: number;
  subagentCount: number;
  dailyMemoryCount: number;
  artifactCount?: number;
  chartArtifactCount?: number;
  reportArtifactCount?: number;
  datasetArtifactCount?: number;
  localMirrorPath?: string | null;
  sandboxAccessible?: boolean;
  emptyNotSynced?: boolean;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/workspace`;
}

function apiUrl(path: string): URL {
  return new URL(path, window.location.origin);
}

function appendSession(agentId: string, sessionKey?: string): string {
  const url = base(agentId);
  if (sessionKey) {
    return `${url}?session=${encodeURIComponent(sessionKey)}`;
  }
  return url;
}

async function unwrap<T>(res: Response): Promise<T> {
  const contentType = res.headers.get('content-type') ?? '';
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `HTTP ${res.status}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  if (!contentType.includes('application/json')) {
    const txt = await res.text().catch(() => '');
    if (txt.trimStart().startsWith('<!doctype') || txt.trimStart().startsWith('<html')) {
      throw new Error('工作区 API 没有命中后端，当前前端服务把 /api 请求返回成了页面 HTML。请通过 Spring Boot 后端端口访问，或确认 Vite dev/preview 已代理到 http://localhost:8080。');
    }
    throw new Error(txt || `Expected JSON response, got ${contentType || 'unknown content type'}`);
  }
  return res.json() as Promise<T>;
}

export async function summary(agentId: string, sessionKey?: string): Promise<WorkspaceSummary> {
  const res = await fetch(appendSession(agentId, sessionKey), { headers: authHeaders() });
  return unwrap<WorkspaceSummary>(res);
}

export async function tree(agentId: string, recursive = true, sessionKey?: string): Promise<FileNode[]> {
  const url = apiUrl(`${base(agentId)}/files`);
  url.searchParams.set('recursive', String(recursive));
  if (sessionKey) {
    url.searchParams.set('session', sessionKey);
  }
  const res = await fetch(url.toString(), { headers: authHeaders() });
  return unwrap<FileNode[]>(res);
}

export async function readFile(agentId: string, path: string, sessionKey?: string): Promise<string> {
  const url = apiUrl(`${base(agentId)}/file`);
  url.searchParams.set('path', path);
  if (sessionKey) {
    url.searchParams.set('session', sessionKey);
  }
  const res = await fetch(url.toString(), { headers: authHeaders() });
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `HTTP ${res.status}`);
  }
  return res.text();
}

export async function writeFile(agentId: string, path: string, content: string, sessionKey?: string): Promise<FileNode> {
  const url = apiUrl(`${base(agentId)}/file`);
  url.searchParams.set('path', path);
  if (sessionKey) {
    url.searchParams.set('session', sessionKey);
  }
  const res = await fetch(url.toString(), {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ content }),
  });
  return unwrap<FileNode>(res);
}

export async function createFile(agentId: string, path: string, sessionKey?: string): Promise<FileNode> {
  const url = apiUrl(`${base(agentId)}/file`);
  url.searchParams.set('path', path);
  url.searchParams.set('type', 'file');
  if (sessionKey) {
    url.searchParams.set('session', sessionKey);
  }
  const res = await fetch(url.toString(), {
    method: 'POST',
    headers: authHeaders(),
  });
  return unwrap<FileNode>(res);
}

export async function createDir(agentId: string, path: string, sessionKey?: string): Promise<FileNode> {
  const url = apiUrl(`${base(agentId)}/file`);
  url.searchParams.set('path', path);
  url.searchParams.set('type', 'dir');
  if (sessionKey) {
    url.searchParams.set('session', sessionKey);
  }
  const res = await fetch(url.toString(), {
    method: 'POST',
    headers: authHeaders(),
  });
  return unwrap<FileNode>(res);
}

export async function moveNode(agentId: string, from: string, to: string, sessionKey?: string): Promise<FileNode> {
  const url = apiUrl(`${base(agentId)}/file/move`);
  if (sessionKey) {
    url.searchParams.set('session', sessionKey);
  }
  const res = await fetch(url.toString(), {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ from, to }),
  });
  return unwrap<FileNode>(res);
}

export async function deleteNode(agentId: string, path: string, sessionKey?: string): Promise<void> {
  const url = apiUrl(`${base(agentId)}/file`);
  url.searchParams.set('path', path);
  if (sessionKey) {
    url.searchParams.set('session', sessionKey);
  }
  const res = await fetch(url.toString(), {
    method: 'DELETE',
    headers: authHeaders(),
  });
  await unwrap<void>(res);
}

export async function uploadFile(agentId: string, file: File, dir = 'knowledge', sessionKey?: string): Promise<FileNode> {
  const url = apiUrl(`${base(agentId)}/upload`);
  url.searchParams.set('path', dir);
  if (sessionKey) {
    url.searchParams.set('session', sessionKey);
  }
  const form = new FormData();
  form.append('file', file, file.name);
  const res = await fetch(url.toString(), {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  return unwrap<FileNode>(res);
}
