import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  activeAgentIdFromSearch,
  chatHref,
  hasStoredActiveAgent,
  persistActiveAgentId,
  storedActiveAgentId,
} from '../api/activeAgent';
import { AgentDefinition, listAgents } from '../api/agents';
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
  const hasSavedAgentRef = useRef(hasStoredActiveAgent());
  const didChooseInitialAgentRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    setAgent(null);
    setAgentLoading(true);
    setAgentError(null);
    listAgents()
      .then(agents => {
        if (cancelled) return;
        const personalAgent = agents.find(item => item.scope === 'user');
        if (!didChooseInitialAgentRef.current && !hasSavedAgentRef.current && personalAgent) {
          didChooseInitialAgentRef.current = true;
          persistActiveAgentId(personalAgent.id);
          setActiveAgentIdState(personalAgent.id);
          navigate(chatHref(personalAgent.id), { replace: true });
          return;
        }
        didChooseInitialAgentRef.current = true;
        const current = agents.find(item => item.id === activeAgentId) ?? personalAgent;
        if (!current) throw new Error('当前账号没有可用的 Agent。');
        if (current.id !== activeAgentId) {
          persistActiveAgentId(current.id);
          setActiveAgentIdState(current.id);
          navigate(chatHref(current.id), { replace: true });
          return;
        }
        setAgent(current);
      })
      .catch(e => { if (!cancelled) setAgentError(e instanceof Error ? e.message : '加载 agent 失败'); })
      .finally(() => { if (!cancelled) setAgentLoading(false); });
    return () => { cancelled = true; };
  }, [activeAgentId, navigate]);

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
    <div
      style={{
        position: 'fixed',
        inset: 0,
        display: 'flex',
        background: '#f8fafc',
        color: '#0f172a',
        overflow: 'hidden',
      }}
    >
      <SessionsSidebar agentId={activeAgentId} refreshKey={refreshTick} />
      <div
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          minWidth: 0,
          minHeight: 0,
          overflow: 'auto',
        }}
      >
        <Outlet context={ctx} />
      </div>
    </div>
  );
}
