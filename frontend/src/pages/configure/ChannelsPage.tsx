import React from 'react';
import { useOutletContext } from 'react-router-dom';
import BackToChatHeader from '../../components/BackToChatHeader';
import ChannelBindingTable from '../../components/ChannelBindingTable';
import type { ShellOutletContext } from '../../components/EditTierGate';

export default function ChannelsPage() {
  const ctx = useOutletContext<ShellOutletContext>();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="通道" subtitle="绑定到此 agent 的外部通道" />
      <div style={{ flex: 1, minHeight: 0 }}>
        <ChannelBindingTable agentId={ctx.activeAgentId} />
      </div>
    </div>
  );
}
