import { getToken } from './auth';

export interface TraceSpan {
  spanId: string;
  parentSpanId: string | null;
  operationName: string;
  spanKind: string;
  status: string;
  startedAtMs: number;
  durationMs: number;
  attributes: Record<string, unknown>;
}

export interface TraceRun {
  traceId: string;
  rootSpanId: string;
  agentId: string;
  sessionKey: string | null;
  modelId: string | null;
  status: 'RUNNING' | 'SUCCESS' | 'CANCELLED' | 'ERROR' | string;
  errorMessage: string | null;
  startedAtMs: number;
  durationMs: number;
  spans: TraceSpan[];
}

export async function listMyTraceRuns(limit = 40): Promise<TraceRun[]> {
  const token = getToken();
  const response = await fetch(`/api/traces/me?limit=${limit}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) throw new Error(await response.text().catch(() => `HTTP ${response.status}`));
  return response.json() as Promise<TraceRun[]>;
}
