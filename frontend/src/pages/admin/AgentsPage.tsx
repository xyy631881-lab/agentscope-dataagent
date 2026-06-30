import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import AdminPageLayout from '../../components/admin/AdminPageLayout';

interface RegisteredAgentView {
  id: string;
  name: string;
  description: string | null;
  maxIters: number | null;
}

function authH(): Record<string, string> {
  return { Authorization: `Bearer ${localStorage.getItem('claw_token') ?? ''}` };
}

async function listAgents(): Promise<RegisteredAgentView[]> {
  const res = await fetch('/api/admin/agents', { headers: authH() });
  if (!res.ok) throw new Error(`Failed to load agents: ${res.status}`);
  return res.json();
}

const S: Record<string, React.CSSProperties> = {
  err:        { color: '#dc2626', fontSize: '0.9rem', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 10, padding: '12px 16px', marginBottom: 18 },
  table:      { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.9rem', background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 12, overflow: 'hidden', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  th:         { textAlign: 'left' as const, padding: '12px 16px', background: '#f8fafc', color: '#64748b', borderBottom: '1px solid #e5e7eb', fontWeight: 600, fontSize: '0.78rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em' },
  td:         { padding: '14px 16px', borderBottom: '1px solid #f1f5f9', color: '#334155', verticalAlign: 'top' as const },
  mono:       { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.86rem' },
  detailLink: { color: '#4f46e5', textDecoration: 'none', borderBottom: '1px dashed #4f46e5', fontWeight: 500 },
  refreshBtn: { background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '7px 14px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500 },
  hint:       { fontSize: '0.9rem', color: '#64748b', marginBottom: 22, lineHeight: 1.7, background: '#f8fafc', padding: '14px 18px', border: '1px solid #e5e7eb', borderRadius: 10 },
};

export default function AdminAgentsPage() {
  const [agents,  setAgents]  = useState<RegisteredAgentView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);

  async function load() {
    setLoading(true); setError(null);
    try { setAgents(await listAgents()); }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, []);

  return (
    <>
      <AdminPageLayout>
        {error && <div style={S.err}>{error}</div>}

        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 18 }}>
          <h2 style={{ margin: 0, fontSize: '1.5rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' }}>
            已注册 Agents
          </h2>
          <button style={{ ...S.refreshBtn, marginLeft: 14 }} onClick={load} disabled={loading}>
            {loading ? '…' : '↺ 刷新'}
          </button>
          <span style={{ marginLeft: 'auto', fontSize: '0.88rem', color: '#64748b' }}>
            {agents.length} 个 agent
          </span>
        </div>

        <div style={S.hint}>
          全局 Agents 在 <code style={{ color: '#4f46e5', background: '#eef2ff', padding: '1px 6px', borderRadius: 4 }}>agentscope.json</code> 中声明，
          位于每个 <code style={{ color: '#4f46e5', background: '#eef2ff', padding: '1px 6px', borderRadius: 4, marginLeft: 4 }}> .agentscope/</code> 工作区下。此视图为只读 —
          在部署主机上编辑 JSON 并重启 claw 来添加或移除 agents。
        </div>

        <table style={S.table}>
          <thead>
            <tr>
              <th style={S.th}>ID</th>
              <th style={S.th}>Name</th>
              <th style={S.th}>Description</th>
              <th style={S.th}>Max Iters</th>
            </tr>
          </thead>
          <tbody>
            {agents.map(a => (
              <tr key={a.id}>
                <td style={{ ...S.td, ...S.mono }}>
                  <Link to={`/admin/agents/${encodeURIComponent(a.id)}`} style={S.detailLink}>{a.id}</Link>
                </td>
                <td style={{ ...S.td, fontWeight: 600, color: '#0f172a' }}>{a.name}</td>
                <td style={S.td}>{a.description ?? <span style={{ color: '#94a3b8' }}>—</span>}</td>
                <td style={{ ...S.td, ...S.mono, textAlign: 'right' as const }}>{a.maxIters ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {!loading && agents.length === 0 && (
          <p style={{ color: '#94a3b8', fontSize: '0.92rem', marginTop: 16 }}>未找到全局 agents。</p>
        )}
      </AdminPageLayout>
    </>
  );
}
