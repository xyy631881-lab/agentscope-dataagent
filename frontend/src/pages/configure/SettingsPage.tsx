import React from 'react';
import { useOutletContext } from 'react-router-dom';
import AgentSettingsForm from '../../components/AgentSettingsForm';
import BackToChatHeader from '../../components/BackToChatHeader';
import { ShellOutletContext } from '../../components/EditTierGate';

export default function SettingsPage() {
  const ctx = useOutletContext<ShellOutletContext>();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="设置" subtitle="Agent 配置和元数据" />
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        {ctx.agent
          ? <AgentSettingsForm agent={ctx.agent} />
          : <div style={{ padding: '24px 28px', color: '#64748b' }}>加载中…</div>
        }
      </div>
    </div>
  );
}
