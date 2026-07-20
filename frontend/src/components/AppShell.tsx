import React, { useCallback, useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  activeAgentIdFromSearch,
  chatHref,
  persistActiveAgentId,
  storedActiveAgentId,
} from '../api/activeAgent';
import { AgentDefinition, getAgent } from '../api/agents';
import SessionsSidebar from './SessionsSidebar';
import { ShellOutletContext } from './EditTierGate';

export default function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const [activeAgentId, setActiveAgentIdState] = useState(
    () => activeAgentIdFromSearch(location.search) ?? storedActiveAgentId(),
  );
  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [agentLoading, setAgentLoading] = useState(true);
  const [agentError, setAgentError] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setAgent(null);
    setAgentLoading(true);
    setAgentError(null);
    getAgent(activeAgentId)
      .then(a => { if (!cancelled) setAgent(a); })
      .catch(e => { if (!cancelled) setAgentError(e instanceof Error ? e.message : '加载 agent 失败'); })
      .finally(() => { if (!cancelled) setAgentLoading(false); });
    return () => { cancelled = true; };
  }, [activeAgentId]);

  useEffect(() => {
    const requested = activeAgentIdFromSearch(location.search);
    if (requested && requested !== activeAgentId) {
      persistActiveAgentId(requested);
      setActiveAgentIdState(requested);
    }
  }, [activeAgentId, location.search]);

  const setActiveAgentId = useCallback((agentId: string) => {
    const next = agentId.trim();
    if (!next || next === activeAgentId) return;
    persistActiveAgentId(next);
    navigate(chatHref(next), { replace: false });
  }, [activeAgentId, navigate]);

  const bumpSidebar = useCallback(() => setRefreshTick(t => t + 1), []);

  const ctx: ShellOutletContext = {
    activeAgentId,
    setActiveAgentId,
    agent,
    agentLoading,
    agentError,
    bumpSidebar,
  };

  return (
    <div style={{ display: 'flex', height: '100vh', background: '#f8fafc', color: '#0f172a', overflow: 'hidden' }}>
      <SessionsSidebar agentId={activeAgentId} refreshKey={refreshTick} />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>
        <Outlet context={ctx} />
      </div>
    </div>
  );
}
