import React, { FormEvent, useEffect, useState } from 'react';
import BackToChatHeader from '../components/BackToChatHeader';
import { deleteTenantModel, listTenantModels, ModelProvider, saveTenantModel, TenantModelConfig } from '../api/tenantModels';

interface FormState {
  logicalModelId: string;
  provider: ModelProvider;
  modelName: string;
  baseUrl: string;
  endpointPath: string;
  apiKey: string;
  enabled: boolean;
  inputPrice: string;
  cachedInputPrice: string;
  outputPrice: string;
}

const blank: FormState = {
  logicalModelId: 'longcat', provider: 'OPENAI_COMPATIBLE', modelName: 'LongCat-2.0', baseUrl: '', endpointPath: '', apiKey: '', enabled: true,
  inputPrice: '0', cachedInputPrice: '0', outputPrice: '0',
};

function fromConfig(config: TenantModelConfig): FormState {
  return {
    logicalModelId: config.logicalModelId, provider: config.provider, modelName: config.modelName, baseUrl: config.baseUrl || '', endpointPath: config.endpointPath || '', apiKey: '', enabled: config.enabled,
    inputPrice: String(config.inputMicrousdPerMillion), cachedInputPrice: String(config.cachedInputMicrousdPerMillion), outputPrice: String(config.outputMicrousdPerMillion),
  };
}

export default function TenantModelsPage() {
  const [items, setItems] = useState<TenantModelConfig[]>([]);
  const [form, setForm] = useState<FormState>(blank);
  const [editing, setEditing] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    try { setItems(await listTenantModels()); }
    catch (reason) { setMessage(reason instanceof Error ? reason.message : '无法加载模型连接'); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  function update<K extends keyof FormState>(key: K, value: FormState[K]) { setForm(current => ({ ...current, [key]: value })); }
  function edit(config: TenantModelConfig) { setEditing(config.logicalModelId); setForm(fromConfig(config)); setMessage(null); }
  function reset() { setEditing(null); setForm(blank); setMessage(null); }

  async function submit(event: FormEvent) {
    event.preventDefault(); setSaving(true); setMessage(null);
    try {
      await saveTenantModel(form.logicalModelId, {
        provider: form.provider, modelName: form.modelName, baseUrl: form.baseUrl || undefined, endpointPath: form.endpointPath || undefined,
        apiKey: form.apiKey || undefined, enabled: form.enabled,
        inputMicrousdPerMillion: Number(form.inputPrice) || 0,
        cachedInputMicrousdPerMillion: Number(form.cachedInputPrice) || 0,
        outputMicrousdPerMillion: Number(form.outputPrice) || 0,
      });
      await load(); reset(); setMessage('模型连接已保存。新的个人 Agent 会在下次创建或重建时使用该连接。');
    } catch (reason) { setMessage(reason instanceof Error ? reason.message : '保存失败'); }
    finally { setSaving(false); }
  }

  async function remove(id: string) {
    if (!window.confirm(`删除 ${id} 的租户模型配置？`)) return;
    try { await deleteTenantModel(id); await load(); if (editing === id) reset(); }
    catch (reason) { setMessage(reason instanceof Error ? reason.message : '删除失败'); }
  }

  return (
    <div style={styles.root}>
      <BackToChatHeader title="模型连接" subtitle="当前用户即当前租户；凭据加密存储且只可写入，不会回显。" />
      <div style={styles.content}>
        <div style={styles.heading}><div><h1 style={styles.title}>模型连接</h1><p style={styles.subtitle}>通过 AgentScope ModelCreationContext 为个人 Agent 提供租户级凭据与 Base URL。</p></div><button type="button" style={styles.secondary} onClick={reset}>新建连接</button></div>
        {message && <div style={styles.message}>{message}</div>}
        <div style={styles.grid}>
          <section style={styles.panel}>
            <h2 style={styles.panelTitle}>已配置连接</h2>
            {loading ? <div style={styles.muted}>加载中</div> : items.length === 0 ? <div style={styles.muted}>还没有租户模型配置。未配置时继续使用系统的 local / longcat 默认模型。</div> : items.map(item => (
              <div key={item.logicalModelId} style={styles.item}>
                <div style={{ minWidth: 0 }}><strong style={styles.itemTitle}>{item.logicalModelId}</strong><div style={styles.itemMeta}>{item.modelName} · {item.provider}</div><div style={styles.itemMeta}>{item.apiKeyConfigured ? 'API key 已配置' : '无需 API key'} · {item.enabled ? '启用' : '停用'}</div></div>
                <div style={styles.actions}><button type="button" style={styles.textButton} onClick={() => edit(item)}>编辑</button><button type="button" style={{ ...styles.textButton, color: '#b91c1c' }} onClick={() => remove(item.logicalModelId)}>删除</button></div>
              </div>
            ))}
          </section>
          <section style={styles.panel}>
            <h2 style={styles.panelTitle}>{editing ? `编辑 ${editing}` : '新增模型连接'}</h2>
            <form onSubmit={submit} style={styles.form}>
              <label>逻辑模型 ID<input required value={form.logicalModelId} disabled={Boolean(editing)} onChange={e => update('logicalModelId', e.target.value)} /></label>
              <label>提供商<select value={form.provider} onChange={e => update('provider', e.target.value as ModelProvider)}><option value="OPENAI_COMPATIBLE">OpenAI 兼容</option><option value="OLLAMA">Ollama</option></select></label>
              <label>模型名<input required value={form.modelName} onChange={e => update('modelName', e.target.value)} /></label>
              <label>Base URL<input value={form.baseUrl} placeholder="https://provider.example/openai" onChange={e => update('baseUrl', e.target.value)} /></label>
              <label>Endpoint Path<input value={form.endpointPath} placeholder="可选，例如 /v1/chat/completions" onChange={e => update('endpointPath', e.target.value)} /></label>
              {form.provider === 'OPENAI_COMPATIBLE' && <label>API Key<input type="password" value={form.apiKey} placeholder={editing ? '留空则保持已配置的密钥' : '必填'} onChange={e => update('apiKey', e.target.value)} /></label>}
              <label style={styles.checkbox}><input type="checkbox" checked={form.enabled} onChange={e => update('enabled', e.target.checked)} />启用此连接</label>
              <div style={styles.priceGrid}><label>输入单价<input type="number" min="0" value={form.inputPrice} onChange={e => update('inputPrice', e.target.value)} /></label><label>缓存输入单价<input type="number" min="0" value={form.cachedInputPrice} onChange={e => update('cachedInputPrice', e.target.value)} /></label><label>输出单价<input type="number" min="0" value={form.outputPrice} onChange={e => update('outputPrice', e.target.value)} /></label></div>
              <p style={styles.priceHint}>单价单位：micro USD / 1M tokens。填 0 表示不计成本。</p>
              <div style={styles.actions}><button type="submit" style={styles.primary} disabled={saving}>{saving ? '保存中' : '保存'}</button>{editing && <button type="button" style={styles.secondary} onClick={reset}>取消</button>}</div>
            </form>
          </section>
        </div>
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }, content: { padding: '22px 28px', overflow: 'auto' },
  heading: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, maxWidth: 1100, marginBottom: 18 }, title: { margin: 0, fontSize: '1.25rem', color: '#0f172a', letterSpacing: 0 }, subtitle: { margin: '6px 0 0', color: '#64748b', fontSize: '0.86rem', lineHeight: 1.5 },
  message: { maxWidth: 1100, marginBottom: 14, border: '1px solid #bfdbfe', borderRadius: 6, padding: '9px 11px', background: '#eff6ff', color: '#1e40af', fontSize: '0.82rem' },
  grid: { maxWidth: 1100, display: 'grid', gridTemplateColumns: 'minmax(280px, .8fr) minmax(360px, 1.2fr)', gap: 16, alignItems: 'start' }, panel: { border: '1px solid #e2e8f0', borderRadius: 7, background: '#fff', padding: 16 }, panelTitle: { margin: '0 0 13px', color: '#0f172a', fontSize: '0.96rem' },
  item: { display: 'flex', justifyContent: 'space-between', gap: 12, padding: '12px 0', borderTop: '1px solid #f1f5f9' }, itemTitle: { color: '#312e81', fontSize: '0.86rem' }, itemMeta: { color: '#64748b', fontSize: '0.75rem', marginTop: 3, overflowWrap: 'anywhere' }, muted: { color: '#94a3b8', fontSize: '0.84rem', lineHeight: 1.5 },
  form: { display: 'grid', gap: 11 }, priceGrid: { display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8 }, priceHint: { margin: '-3px 0 2px', fontSize: '0.72rem', color: '#64748b' },
  checkbox: { display: 'flex', alignItems: 'center', gap: 7 }, actions: { display: 'flex', alignItems: 'center', gap: 8 }, primary: { border: '1px solid #4338ca', borderRadius: 6, padding: '7px 13px', background: '#4f46e5', color: '#fff', cursor: 'pointer', fontSize: '0.82rem', fontWeight: 600 }, secondary: { border: '1px solid #cbd5e1', borderRadius: 6, padding: '7px 12px', background: '#fff', color: '#334155', cursor: 'pointer', fontSize: '0.82rem', fontWeight: 600 }, textButton: { border: 0, background: 'transparent', color: '#3730a3', cursor: 'pointer', padding: 2, fontSize: '0.78rem' },
};
