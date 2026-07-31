import { getToken } from './auth';

export interface SqlPatternPreference {
  sqlPreview: string;
  sqlText: string;
  useCount: number;
}

export interface ChartPreference {
  chartType: string;
  useCount: number;
  percentage: number;
}

export interface TablePreference {
  tableName: string;
  useCount: number;
}

export interface QueryStylePreference {
  label: string;
  useCount: number;
  percentage: number;
}

export interface AgentPreferenceSummary {
  sqlPatterns: SqlPatternPreference[];
  chartPreferences: ChartPreference[];
  tablePreferences: TablePreference[];
  queryStyles: QueryStylePreference[];
  sqlPatternCount: number;
}

export interface SqlPatternPage {
  items: SqlPatternPreference[];
  page: number;
  size: number;
  total: number;
}

function headers(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/preferences`;
}

export async function getAgentPreferences(agentId: string): Promise<AgentPreferenceSummary> {
  const res = await fetch(base(agentId), { headers: headers() });
  if (!res.ok) throw new Error((await res.text()) || `Failed to load preferences (${res.status})`);
  return res.json();
}

export async function getSqlPatternPage(agentId: string, page: number, size = 20): Promise<SqlPatternPage> {
  const res = await fetch(`${base(agentId)}/sql-patterns?page=${page}&size=${size}`, { headers: headers() });
  if (!res.ok) throw new Error((await res.text()) || `加载 SQL 偏好失败 (${res.status})`);
  return res.json();
}

export async function clearAgentPreferences(agentId: string): Promise<void> {
  const res = await fetch(base(agentId), { method: 'DELETE', headers: headers() });
  if (!res.ok && res.status !== 204) {
    throw new Error((await res.text()) || `Failed to clear preferences (${res.status})`);
  }
}
