import React, { useEffect, useState } from 'react';
import { useOutletContext, useSearchParams } from 'react-router-dom';
import { summary as fetchSummary, WorkspaceSummary } from '../api/workspace';
import BackToChatHeader from '../components/BackToChatHeader';
import WorkspaceFileTree from '../components/WorkspaceFileTree';
import WorkspaceEditor from '../components/WorkspaceEditor';
import type { ShellOutletContext } from '../components/EditTierGate';

const pathBar: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '120px minmax(0, 1fr) auto', alignItems: 'center', gap: 8,
  padding: '6px 16px', borderBottom: '1px solid #f1f5f9',
  background: '#f8fafc', flexShrink: 0,
  fontSize: '0.78rem', color: '#64748b',
};
const pathLabel: React.CSSProperties = {
  fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.08em',
};
const pathValue: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: '#334155', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  flex: 1, minWidth: 0,
};
const hint: React.CSSProperties = {
  padding: '5px 16px', borderBottom: '1px solid #f1f5f9',
  background: '#eef6ff', color: '#1e3a8a',
  fontSize: '0.74rem', flexShrink: 0,
};
const syncHint: React.CSSProperties = {
  padding: '5px 16px', borderBottom: '1px solid #f1f5f9',
  background: '#fffbeb', color: '#92400e',
  fontSize: '0.74rem', flexShrink: 0,
};
const stats: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8, padding: '8px 16px',
  borderBottom: '1px solid #f1f5f9', background: '#ffffff', flexShrink: 0,
  flexWrap: 'wrap',
};
const statItem: React.CSSProperties = {
  border: '1px solid #e2e8f0', borderRadius: 6, padding: '4px 8px',
  color: '#334155', fontSize: '0.76rem', background: '#fbfdff',
};

export default function WorkspacePage() {
  const ctx = useOutletContext<ShellOutletContext>();
  const agentId = ctx.activeAgentId;
  const [searchParams] = useSearchParams();
  const sessionKey = searchParams.get('session') ?? undefined;
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [summary, setSummary] = useState<WorkspaceSummary | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchSummary(agentId, sessionKey)
      .then(s => { if (!cancelled) setSummary(s); })
      .catch(() => { if (!cancelled) setSummary(null); });
    return () => { cancelled = true; };
  }, [agentId, refreshKey, sessionKey]);

  useEffect(() => {
    setSelected(searchParams.get('path'));
  }, [searchParams]);

  async function copyPath(value?: string | null) {
    if (!value) return;
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      // Clipboard is optional in restricted browser contexts.
    }
  }

  function pathRow(label: string, value?: string | null) {
    if (!value) return null;
    return (
      <div style={pathBar} title={value}>
        <span style={pathLabel}>{label}</span>
        <span style={pathValue}>{value}</span>
        <button
          type="button"
          onClick={() => copyPath(value)}
          style={{
            background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
            borderRadius: 6, padding: '3px 10px', cursor: 'pointer',
            fontSize: '0.75rem', fontWeight: 500,
          }}
          title={`复制${label}`}
        >
          复制
        </button>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="工作区" subtitle="浏览 agent 的工作目录" />
      {pathRow('运行时工作区', summary?.runtimeWorkspacePath ?? '/workspace')}
      {pathRow('Agent 定义目录', summary?.definitionWorkspacePath ?? summary?.workspacePath)}
      {pathRow('本地镜像', summary?.localMirrorPath)}
      {summary?.emptyNotSynced && (
        <div style={syncHint}>
          ⚠️ 工作区尚未同步 - 请确保已建立会话或刷新页面
        </div>
      )}
      {summary && (
        <div style={stats}>
          <span style={statItem}>技能 {summary.skillCount}</span>
          <span style={statItem}>子 Agent {summary.subagentCount}</span>
          <span style={statItem}>记忆 {summary.dailyMemoryCount}</span>
          <span style={statItem}>产物 {summary.artifactCount ?? 0}</span>
          <span style={statItem}>图表 {summary.chartArtifactCount ?? 0}</span>
          <span style={statItem}>报告 {summary.reportArtifactCount ?? 0}</span>
          <span style={statItem}>数据集 {summary.datasetArtifactCount ?? 0}</span>
          {sessionKey && <span style={statItem}>会话: {sessionKey}</span>}
        </div>
      )}
      <div style={hint}>
        Docker 容器可能被冷启动回收；长期可见文件以工作区和本地镜像为准。
      </div>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <WorkspaceFileTree
          agentId={agentId}
          selectedPath={selected}
          onSelect={p => setSelected(p || null)}
          refreshKey={refreshKey}
          onRefresh={() => setRefreshKey(k => k + 1)}
          sessionKey={sessionKey}
          emptyNotSynced={summary?.emptyNotSynced}
        />
        <WorkspaceEditor
          agentId={agentId}
          path={selected}
          refreshKey={refreshKey}
          sessionKey={sessionKey}
        />
      </div>
    </div>
  );
}
