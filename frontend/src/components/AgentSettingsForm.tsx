import React, { useEffect, useState } from 'react';
import { confirmAction } from './InteractionHost';
import { AgentDefinition, updateAgent, deleteAgent } from '../api/agents';
import { useNavigate } from 'react-router-dom';

const S: Record<string, React.CSSProperties> = {
  page: { padding: '32px 36px', maxWidth: 820 },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '24px 28px', marginBottom: 20,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  cardLabel: {
    fontSize: '0.78rem', color: '#94a3b8', fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: '0.1em',
    marginBottom: 18, display: 'block',
  },
  fieldLabel: {
    display: 'block', fontSize: '0.88rem', fontWeight: 500,
    color: '#475569', marginBottom: 8,
  },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '11px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem',
  },
  textarea: {
    width: '100%', boxSizing: 'border-box', padding: '12px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem', lineHeight: 1.55,
    minHeight: 150, resize: 'vertical',
  },
  row: { marginBottom: 18 },
  saveBtn: {
    padding: '11px 24px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    border: 'none', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.95rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  dangerBtn: {
    padding: '11px 20px', background: '#ffffff', color: '#dc2626',
    border: '1px solid #fca5a5', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.92rem', fontWeight: 500,
  },
  banner: {
    padding: '14px 18px', borderRadius: 10, marginBottom: 20,
    background: '#eef2ff', color: '#3730a3', fontSize: '0.9rem',
    border: '1px solid #c7d2fe',
  },
  success: { color: '#059669', fontSize: '0.9rem', marginTop: 10 },
  error: { color: '#dc2626', fontSize: '0.9rem', marginTop: 10 },
  meta: {
    fontSize: '0.85rem', color: '#64748b', fontFamily: 'monospace',
  },
};

export default function AgentSettingsForm({ agent }: { agent: AgentDefinition }) {
  const navigate = useNavigate();
  const isGlobal = agent.scope === 'global';
  const canEdit = agent.tierForCurrentUser === 'EDIT';

  const [name, setName] = useState(agent.name);
  const [description, setDescription] = useState(agent.description ?? '');
  const [sysPrompt, setSysPrompt] = useState(agent.sysPrompt ?? '');
  const [maxIters, setMaxIters] = useState<string>(String(agent.maxIters ?? 12));
  const [saving, setSaving] = useState(false);
  const [ok, setOk] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    setName(agent.name);
    setDescription(agent.description ?? '');
    setSysPrompt(agent.sysPrompt ?? '');
    setMaxIters(String(agent.maxIters ?? 12));
  }, [agent.id]);

  async function handleSave() {
    setOk(false);
    setErr(null);
    setSaving(true);
    try {
      const iters = Number.parseInt(maxIters, 10);
      await updateAgent(agent.id, {
        name: name.trim() || agent.id,
        description: description.trim() || undefined,
        sysPrompt: sysPrompt || undefined,
        maxIters: Number.isFinite(iters) && iters > 0 ? iters : undefined,
      });
      setOk(true);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!(await confirmAction(`删除 Agent“${agent.name}”？将同时移除其工作区和会话。`))) return;
    try {
      await deleteAgent(agent.id);
      navigate('/agents', { replace: true });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed');
    }
  }

  return (
    <div style={S.page}>
      {isGlobal && (
        <div style={S.banner}>
          当前编辑的是全局 Agent。管理员保存后，会写入全局覆盖配置并在后续请求中对所有用户生效；
          下方的“学习偏好”仍只属于当前登录用户，不会改变全局行为。
        </div>
      )}

      <div style={S.card}>
        <span style={S.cardLabel}>身份</span>

        <div style={S.row}>
          <label style={S.fieldLabel}>Agent ID</label>
          <div style={S.meta}>{agent.id}</div>
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>名称</label>
          <input
            style={S.input}
            value={name}
            onChange={e => setName(e.target.value)}
            disabled={!canEdit}
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>描述</label>
          <input
            style={S.input}
            value={description}
            onChange={e => setDescription(e.target.value)}
            disabled={!canEdit}
            placeholder="显示在卡片和标签页上的简短摘要"
          />
        </div>
      </div>

      <div style={S.card}>
        <span style={S.cardLabel}>行为</span>

        <div style={S.row}>
          <label style={S.fieldLabel}>系统提示词</label>
          <textarea
            style={S.textarea}
            value={sysPrompt}
            onChange={e => setSysPrompt(e.target.value)}
            disabled={!canEdit}
            placeholder="高级指令。运行时工作区 AGENTS.md 仍然优先。"
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>最大迭代次数</label>
          <input
            style={{ ...S.input, width: 140 }}
            type="number"
            min={1}
            max={64}
            value={maxIters}
            onChange={e => setMaxIters(e.target.value)}
            disabled={!canEdit}
          />
        </div>
      </div>

      {canEdit && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
          <button style={S.saveBtn} onClick={handleSave} disabled={saving}>
            {saving ? '保存中…' : '保存更改'}
          </button>
          {!isGlobal && <button style={S.dangerBtn} onClick={handleDelete}>删除 agent</button>}
        </div>
      )}
      {ok && <p style={S.success}>已保存。</p>}
      {err && <p style={S.error}>{err}</p>}

      <div style={{ ...S.card, marginTop: 24 }}>
        <span style={S.cardLabel}>元数据</span>
        <div style={S.row}>
          <label style={S.fieldLabel}>作用域</label>
          <div style={S.meta}>{agent.scope}</div>
        </div>
        {agent.forkOf && (
          <div style={S.row}>
            <label style={S.fieldLabel}>Fork 自</label>
            <div style={S.meta}>{agent.forkOf}</div>
          </div>
        )}
        <div style={S.row}>
          <label style={S.fieldLabel}>创建时间</label>
          <div style={S.meta}>{new Date(agent.createdAt).toLocaleString()}</div>
        </div>
        <div style={S.row}>
          <label style={S.fieldLabel}>更新时间</label>
          <div style={S.meta}>{new Date(agent.updatedAt).toLocaleString()}</div>
        </div>
      </div>
    </div>
  );
}
