import React from 'react';
import { useOutletContext } from 'react-router-dom';
import ChatHeader from '../components/ChatHeader';
import ChatPanel from '../components/ChatPanel';
import { ShellOutletContext } from '../components/EditTierGate';

export default function ChatPage() {
  const ctx = useOutletContext<ShellOutletContext>();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <ChatHeader
        agentId={ctx.activeAgentId}
        agent={ctx.agent}
        onAgentChange={ctx.setActiveAgentId}
      />
      <div style={{ flex: 1, minHeight: 0 }}>
        <ChatPanel
          key={ctx.activeAgentId}
          agentId={ctx.activeAgentId}
          onSessionUpdate={ctx.bumpSidebar}
        />
      </div>
    </div>
  );
}
