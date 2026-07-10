import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AgentDefinition } from '../api/agents';
import { listModels, updateAgentConfig } from '../api/admin';
import type { ModelOption } from '../api/admin';

const CONFIG_BUTTONS: { key: string; label: string; icon: string }[] = [
  { key: 'skills',    label: '技能',    icon: '🛠' },
  { key: 'subagents', label: '子 Agent', icon: '🧩' },
  { key: 'channels',  label: '通道',  icon: '📡' },
  { key: 'tools',     label: '工具',     icon: '🧰' },
  { key: 'shares',    label: '分享',     icon: '👥' },
  { key: 'settings',  label: '设置',  icon: '⚙' },
];

export interface ChatHeaderProps {
  agent: AgentDefinition | null;
}

export default function ChatHeader({ agent }: ChatHeaderProps) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionKey = searchParams.get('session');

  // ── 模型选择 ──
  const [modelOptions, setModelOptions] = useState<ModelOption[]>([]);
  const [selectedModel, setSelectedModel] = useState<string>('');
  const [changing, setChanging] = useState(false);

  useEffect(() => {
    if (!agent) return;
    listModels()
      .then(opts => {
        setModelOptions(opts);
        if (agent.model) setSelectedModel(agent.model);
      })
      .catch(() => { /* 静默 */ });
  }, [agent]);

  async function onModelChange(id: string) {
    if (!agent) return;
    setSelectedModel(id);
    setChanging(true);
    try { await updateAgentConfig(agent.id, { name: agent.id, model: id || undefined }); }
    catch { /* ignore */ }
    finally { setChanging(false); }
  }

  const canEdit = agent?.tierForCurrentUser === 'EDIT';
  const name = agent?.name ?? 'data-agent';
  const emoji = agent?.identityEmoji ?? '📊';

  return (
    <div style={S.root}>
      <div style={S.left}>
        <span style={S.emoji}>{emoji}</span>
        <div style={S.identity}>
          <span style={S.name}>{name}</span>
          {agent?.description && <span style={S.desc}>{agent.description}</span>}
        </div>
        {sessionKey && (
          <span style={S.sessionTag} title={sessionKey}>
            session: {sessionKey.slice(0, 12)}{sessionKey.length > 12 ? '…' : ''}
          </span>
        )}
      </div>

      <div style={S.right}>
        {/* 模型选择器（紧凑内嵌） */}
        {modelOptions.length > 0 && (
          <div style={S.modelWrap}>
            <span style={S.modelLabel}>模型</span>
            <select
              value={selectedModel}
              onChange={e => onModelChange(e.target.value)}
              disabled={changing}
              title="切换当前会话使用的 AI 模型"
              style={S.modelSelect}
            >
              <option value="">默认</option>
              {modelOptions.map(opt => (
                <option key={opt.id} value={opt.id}>{opt.label}</option>
              ))}
            </select>
          </div>
        )}
        {canEdit && CONFIG_BUTTONS.map(b => (
          <button
            key={b.key}
            onClick={() => navigate(`/configure/${b.key}`)}
            style={S.btn}
            onMouseEnter={e => { e.currentTarget.style.background = '#eef2ff'; e.currentTarget.style.color = '#3730a3'; }}
            onMouseLeave={e => { e.currentTarget.style.background = '#ffffff'; e.currentTarget.style.color = '#475569'; }}
            title={`配置 ${b.label}`}
          >
            <span>{b.icon}</span> {b.label}
          </button>
        ))}
        <button
          onClick={() => navigate('/workspace')}
          style={S.btn}
          onMouseEnter={e => { e.currentTarget.style.background = '#eef2ff'; e.currentTarget.style.color = '#3730a3'; }}
          onMouseLeave={e => { e.currentTarget.style.background = '#ffffff'; e.currentTarget.style.color = '#475569'; }}
          title="浏览工作区文件"
        >
          <span>📁</span> 工作区
        </button>
      </div>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: 'flex', alignItems: 'center', gap: 16,
    padding: '14px 28px', borderBottom: '1px solid #e2e8f0',
    background: '#ffffff', flexShrink: 0,
  },
  left: { display: 'flex', alignItems: 'center', gap: 12, minWidth: 0, flex: 1 },
  emoji: {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    width: 36, height: 36, borderRadius: 10,
    background: 'linear-gradient(135deg,#eef2ff 0%,#e0e7ff 100%)',
    border: '1px solid #c7d2fe',
    fontSize: '1.2rem', flexShrink: 0,
  },
  identity: { display: 'flex', flexDirection: 'column', minWidth: 0 },
  name: { fontSize: '1rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' },
  desc: {
    fontSize: '0.78rem', color: '#64748b',
    maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  sessionTag: {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.74rem',
    background: '#f1f5f9', color: '#475569', padding: '3px 8px', borderRadius: 6,
    marginLeft: 8, flexShrink: 0,
  },
  right: { display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 },
  btn: {
    display: 'inline-flex', alignItems: 'center', gap: 6,
    background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
    padding: '7px 12px', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.84rem', fontWeight: 500,
    transition: 'background 0.12s ease, color 0.12s ease',
  },
  // 模型选择器（顶栏紧凑内嵌）
  modelWrap: {
    display: 'flex', alignItems: 'center', gap: 6,
    marginRight: 6,
    padding: '4px 10px 4px 6px',
    background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 8,
  },
  modelLabel: { fontSize: '0.76rem', color: '#64748b', fontWeight: 500, whiteSpace: 'nowrap' },
  modelSelect: {
    border: 'none', background: 'transparent',
    color: '#3730a3', fontSize: '0.82rem', fontWeight: 600,
    cursor: 'pointer', outline: 'none',
    maxWidth: 200,
  },
};
