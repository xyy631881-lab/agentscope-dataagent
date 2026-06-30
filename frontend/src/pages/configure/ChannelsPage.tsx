import React from 'react';
import { ACTIVE_AGENT_ID } from '../../api/activeAgent';
import BackToChatHeader from '../../components/BackToChatHeader';
import ChannelBindingTable from '../../components/ChannelBindingTable';

export default function ChannelsPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="通道" subtitle="绑定到此 agent 的外部通道" />
      <div style={{ flex: 1, minHeight: 0 }}>
        <ChannelBindingTable agentId={ACTIVE_AGENT_ID} />
      </div>
    </div>
  );
}
