import React, { useState } from 'react';
import { ACTIVE_AGENT_ID } from '../../api/activeAgent';
import BackToChatHeader from '../../components/BackToChatHeader';
import SkillsWorkspacePanel from '../../components/SkillsWorkspacePanel';
import SkillsMarketplacesPanel from '../../components/SkillsMarketplacesPanel';

const helpStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: '#64748b',
  background: '#f8fafc',
  borderBottom: '1px solid #e2e8f0',
};

const modalOverlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.55)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 60,
};
const modalShellStyle: React.CSSProperties = {
  background: '#ffffff',
  borderRadius: 12,
  width: 'min(960px, 92vw)',
  height: 'min(720px, 88vh)',
  display: 'flex',
  flexDirection: 'column',
  boxShadow: '0 24px 80px rgba(15,23,42,0.3)',
  overflow: 'hidden',
};
const modalHeaderStyle: React.CSSProperties = {
  padding: '14px 20px',
  borderBottom: '1px solid #e2e8f0',
  display: 'flex',
  alignItems: 'center',
  gap: 12,
};
const closeButtonStyle: React.CSSProperties = {
  padding: '6px 14px',
  borderRadius: 8,
  border: '1px solid #cbd5e1',
  background: '#ffffff',
  color: '#475569',
  fontSize: '0.85rem',
  fontWeight: 600,
  cursor: 'pointer',
};

export default function SkillsPage() {
  const agentId = ACTIVE_AGENT_ID;
  const [refreshKey, setRefreshKey] = useState(0);
  const [browseOpen, setBrowseOpen] = useState(false);

  const bumpRefresh = () => setRefreshKey(k => k + 1);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="技能" subtitle="安装在此 agent 工作区中的技能" />
      <div style={helpStyle}>
        Skills installed into this agent's <code>workspace/skills/</code> from a bound marketplace
        (git / nacos / mysql / classpath / filesystem). Click <b>+ Install</b> to browse what is
        available.
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>
        <SkillsWorkspacePanel
          agentId={agentId}
          refreshKey={refreshKey}
          onChange={bumpRefresh}
          onRequestBrowse={() => setBrowseOpen(true)}
        />
      </div>
      {browseOpen && (
        <div style={modalOverlayStyle} onClick={() => setBrowseOpen(false)}>
          <div style={modalShellStyle} onClick={e => e.stopPropagation()}>
            <div style={modalHeaderStyle}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: '1rem', fontWeight: 600, color: '#0f172a' }}>
                  浏览 Marketplace
                </div>
                <div style={{ fontSize: '0.78rem', color: '#64748b', marginTop: 2 }}>
                  选择要安装到 <code>workspace/skills/</code> 的技能。
                </div>
              </div>
              <button onClick={() => setBrowseOpen(false)} style={closeButtonStyle}>
                关闭
              </button>
            </div>
            <div style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
              <SkillsMarketplacesPanel
                agentId={agentId}
                onInstalled={() => {
                  bumpRefresh();
                }}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
