import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AgentDefinition, listAgents } from '../api/agents';
import { hrefForAgent } from '../api/activeAgent';
import { listModels, updateAgentConfig } from '../api/admin';
import type { ModelOption } from '../api/admin';

const CONFIG_BUTTONS: { key: string; label: string; icon: string }[] = [
  { key: 'skills', label: '技能', icon: '🧩' },
  { key: 'subagents', label: '子 Agent', icon: '🧠' },
  { key: 'channels', label: '通道', icon: '📡' },
  { key: 'tools', label: '工具', icon: '🛠' },
  { key: 'shares', label: '分享', icon: '👥' },
  { key: 'settings', label: '设置', icon: '⚙' },
];

const READ_ONLY_BUTTONS = new Set(['skills', 'subagents', 'tools']);

export interface ChatHeaderProps {
  agentId: string;
  agent: AgentDefinition | null;
  onAgentChange: (agentId: string) => void;
}

export default function ChatHeader({ agentId, agent, onAgentChange }: ChatHeaderProps) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionKey = searchParams.get('session');

  const [modelOptions, setModelOptions] = useState<ModelOption[]>([]);
  const [agentOptions, setAgentOptions] = useState<AgentDefinition[]>([]);
  const [selectedModel, setSelectedModel] = useState<string>('');
  const [changing, setChanging] = useState(false);
  const [modelError, setModelError] = useState<string | null>(null);

  useEffect(() => {
    listAgents().then(setAgentOptions).catch(() => setAgentOptions([]));
  }, [agentId]);

  useEffect(() => {
    if (!agent) return;
    listModels()
      .then(opts => {
        setModelOptions(opts);
        if (agent.model) setSelectedModel(agent.model);
      })
      .catch(() => undefined);
  }, [agent]);

  async function onModelChange(id: string) {
    if (!agent) return;
    const previous = selectedModel;
    setSelectedModel(id);
    setModelError(null);
    setChanging(true);
    try {
      await updateAgentConfig(agent.id, { name: agent.name, model: id || undefined });
    } catch (error: unknown) {
      setSelectedModel(previous);
      setModelError(error instanceof Error ? error.message : 'Model change failed');
    } finally {
      setChanging(false);
    }
  }

  const canEdit = agent?.tierForCurrentUser === 'EDIT';
  const name = agent?.name ?? 'data-agent';
  const emoji = agent?.identityEmoji ?? '📊';
  const scopeLabel = agent?.scope === 'global' ? '团队模板' : '私有 Agent';
  const workspaceHref = hrefForAgent('/workspace', agentId, sessionKey);

  return (
    <div style={S.root}>
      <div style={S.left}>
        <span style={S.emoji}>{emoji}</span>
        <div style={S.identity}>
          <span style={S.name}>{name}</span>
          {agent?.description && <span style={S.desc}>{agent.description}</span>}
          {agent && <span style={agent.scope === 'global' ? S.globalScope : S.userScope}>{scopeLabel}</span>}
        </div>
        {sessionKey && (
          <span style={S.sessionTag} title={sessionKey}>
            session: {sessionKey.slice(0, 12)}{sessionKey.length > 12 ? '…' : ''}
          </span>
        )}
      </div>

      <div style={S.right}>
        {agentOptions.length > 0 && (
          <div style={S.modelWrap}>
            <span style={S.modelLabel}>Agent</span>
            <select
              value={agentId}
              onChange={event => onAgentChange(event.target.value)}
              title="切换当前 Agent"
              style={S.agentSelect}
            >
              {agentOptions.map(option => (
                <option key={option.id} value={option.id}>
                  {option.name} ({option.scope === 'global' ? '团队' : '私有'} · {option.id})
                </option>
              ))}
            </select>
          </div>
        )}
        {modelOptions.length > 0 && (
          <div style={S.modelWrap}>
            <span style={S.modelLabel}>模型</span>
            <select
              value={selectedModel}
              onChange={e => onModelChange(e.target.value)}
              disabled={changing}
              title="切换当前 Agent 使用的模型"
              style={S.modelSelect}
            >
              <option value="">默认</option>
              {modelOptions.map(opt => (
                <option key={opt.id} value={opt.id} disabled={!opt.available}>
                  {opt.label}
                </option>
              ))}
            </select>
            {modelError && <span style={S.modelError} title={modelError}>!</span>}
          </div>
        )}
        {!canEdit && agent && (
          <span style={S.readOnlyTag} title="当前账号可使用此团队模板，但不能修改其配置">
            只读
          </span>
        )}
        {CONFIG_BUTTONS.filter(button => canEdit || READ_ONLY_BUTTONS.has(button.key)).map(b => {
          const readOnly = !canEdit;
          return (
          <button
            type="button"
            key={b.key}
            onClick={() => {
              if (readOnly) return;
              navigate(hrefForAgent(`/configure/${b.key}`, agentId, sessionKey));
            }}
            style={{ ...S.btn, ...(readOnly ? S.btnDisabled : {}) }}
            onMouseEnter={e => {
              if (!readOnly) {
                e.currentTarget.style.background = '#eef2ff';
                e.currentTarget.style.color = '#3730a3';
              }
            }}
            onMouseLeave={e => {
              if (!readOnly) {
                e.currentTarget.style.background = '#ffffff';
                e.currentTarget.style.color = '#475569';
              }
            }}
            title={readOnly ? `团队模板的${b.label}由管理员维护` : `配置${b.label}`}
            aria-disabled={readOnly}
          >
            <span>{b.icon}</span> {b.label}
          </button>
          );
        })}
        <button
          type="button"
          onClick={() => navigate(workspaceHref)}
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
  globalScope: {
    width: 'fit-content', marginTop: 3, padding: '1px 5px', borderRadius: 4,
    background: '#dbeafe', color: '#1d4ed8', fontSize: '0.68rem', fontWeight: 600,
  },
  userScope: {
    width: 'fit-content', marginTop: 3, padding: '1px 5px', borderRadius: 4,
    background: '#dcfce7', color: '#15803d', fontSize: '0.68rem', fontWeight: 600,
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
  btnDisabled: {
    background: '#f8fafc', borderColor: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed',
  },
  readOnlyTag: {
    padding: '3px 7px', borderRadius: 5, background: '#f1f5f9', color: '#64748b',
    fontSize: '0.7rem', fontWeight: 600, whiteSpace: 'nowrap',
  },
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
  agentSelect: {
    border: 'none', background: 'transparent', color: '#0f172a',
    fontSize: '0.82rem', fontWeight: 600, cursor: 'pointer', outline: 'none', maxWidth: 220,
  },
  modelError: {
    color: '#b91c1c', fontWeight: 700, fontSize: '0.78rem', cursor: 'help',
  },
};
