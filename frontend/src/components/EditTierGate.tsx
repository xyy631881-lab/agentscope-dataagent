import React from 'react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { AgentDefinition } from '../api/agents';
import { chatHrefPreservingSession } from '../utils/session';

export interface ShellOutletContext {
  agent: AgentDefinition | null;
  agentLoading: boolean;
  agentError: string | null;
  bumpSidebar: () => void;
}

/**
 * 路由守卫：仅当当前用户在活跃 data agent 上拥有 EDIT 等级时才渲染子组件。
 * 加载 agent 时显示占位符；等级不足时重定向回 /chat。
 */
export default function EditTierGate({ children }: { children: React.ReactElement }) {
  const ctx = useOutletContext<ShellOutletContext>();
  if (ctx.agentLoading) {
    return <div style={{ padding: 32, color: '#64748b' }}>加载中…</div>;
  }
  if (ctx.agentError) {
    return <div style={{ padding: 32, color: '#dc2626' }}>{ctx.agentError}</div>;
  }
  if (ctx.agent?.tierForCurrentUser !== 'EDIT') {
    return <Navigate to={chatHrefPreservingSession()} replace />;
  }
  return children;
}
