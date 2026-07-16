import { getToken } from './auth';

export type ModelProvider = 'OPENAI_COMPATIBLE' | 'OLLAMA';

export interface TenantModelConfig {
  logicalModelId: string;
  provider: ModelProvider;
  modelName: string;
  baseUrl: string | null;
  endpointPath: string | null;
  enabled: boolean;
  apiKeyConfigured: boolean;
  inputMicrousdPerMillion: number;
  cachedInputMicrousdPerMillion: number;
  outputMicrousdPerMillion: number;
  updatedAtMs: number;
}

export interface TenantModelUpdate {
  provider: ModelProvider;
  modelName: string;
  baseUrl?: string;
  endpointPath?: string;
  apiKey?: string;
  enabled: boolean;
  inputMicrousdPerMillion?: number;
  cachedInputMicrousdPerMillion?: number;
  outputMicrousdPerMillion?: number;
}

function headers() { return { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' }; }

export async function listTenantModels(): Promise<TenantModelConfig[]> {
  const response = await fetch('/api/tenant/models', { headers: headers() });
  if (!response.ok) throw new Error(await response.text());
  return response.json() as Promise<TenantModelConfig[]>;
}

export async function saveTenantModel(id: string, value: TenantModelUpdate): Promise<TenantModelConfig> {
  const response = await fetch(`/api/tenant/models/${encodeURIComponent(id)}`, {
    method: 'PUT', headers: headers(), body: JSON.stringify(value),
  });
  if (!response.ok) throw new Error(await response.text());
  return response.json() as Promise<TenantModelConfig>;
}

export async function deleteTenantModel(id: string): Promise<void> {
  const response = await fetch(`/api/tenant/models/${encodeURIComponent(id)}`, { method: 'DELETE', headers: headers() });
  if (!response.ok && response.status !== 204) throw new Error(await response.text());
}
