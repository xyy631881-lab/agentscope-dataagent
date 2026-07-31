import React, { useEffect, useState } from 'react';
import BackToChatHeader from '../components/BackToChatHeader';
import { listMyTraceRuns, TraceRun } from '../api/traces';

function duration(ms: number) {
  if (ms < 1_000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1_000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

function dateTime(value: number) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(value);
}

function stateStyle(status: string): React.CSSProperties {
  if (status === 'SUCCESS') return { ...styles.status, background: '#ecfdf5', color: '#047857', borderColor: '#a7f3d0' };
  if (status === 'RUNNING') return { ...styles.status, background: '#eff6ff', color: '#1d4ed8', borderColor: '#bfdbfe' };
  if (status === 'CANCELLED') return { ...styles.status, background: '#fffbeb', color: '#92400e', borderColor: '#fde68a' };
  return { ...styles.status, background: '#fef2f2', color: '#b91c1c', borderColor: '#fecaca' };
}

function RunRow({ run }: { run: TraceRun }) {
  const [open, setOpen] = useState(false);
  return (
    <article style={styles.run}>
      <button type="button" style={styles.runHead} onClick={() => setOpen(value => !value)} aria-expanded={open}>
        <span style={styles.disclosure}>{open ? '-' : '+'}</span>
        <span style={styles.runMain}>
          <span style={styles.runTitle}>{run.agentId}</span>
          <span style={styles.runMeta}>{dateTime(run.startedAtMs)} · {run.modelId || 'default model'} · {run.spans.length} spans</span>
        </span>
        <span style={styles.duration}>{duration(run.durationMs)}</span>
        <span style={stateStyle(run.status)}>{run.status}</span>
      </button>
      {run.errorMessage && <div style={styles.error}>{run.errorMessage}</div>}
      {open && (
        <div style={styles.detail}>
          <div style={styles.traceId}>trace {run.traceId}</div>
          {run.spans.length === 0 ? <div style={styles.empty}>OTel span 正在导出，稍后刷新查看。</div> : run.spans.map(span => (
            <div key={span.spanId} style={styles.span}>
              <span style={styles.spanDot} />
              <div style={styles.spanBody}>
                <div style={styles.spanHead}><strong>{span.operationName}</strong><span>{duration(span.durationMs)}</span></div>
                <div style={styles.spanMeta}>{span.spanKind} · {span.status}</div>
                {Object.keys(span.attributes).length > 0 && <pre style={styles.attributes}>{JSON.stringify(span.attributes, null, 2)}</pre>}
              </div>
            </div>
          ))}
        </div>
      )}
    </article>
  );
}

export default function TraceRunsPage() {
  const [runs, setRuns] = useState<TraceRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true); setError(null);
    try { setRuns(await listMyTraceRuns()); }
    catch (reason) { setError(reason instanceof Error ? reason.message : '无法加载运行记录'); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  return (
    <div style={styles.root}>
      <BackToChatHeader title="运行记录" subtitle="AgentScope OtelTracingMiddleware 生成的 Agent、模型和工具执行 span" />
      <div style={styles.content}>
        <div style={styles.toolbar}>
          <div><h1 style={styles.title}>运行记录</h1><p style={styles.subtitle}>聊天内保留面向阅读的执行轨迹；这里用于跨会话排查模型、工具和耗时。</p></div>
          <button type="button" style={styles.refresh} onClick={load} disabled={loading}>{loading ? '加载中' : '刷新'}</button>
        </div>
        {error && <div style={styles.error}>{error}</div>}
        {!loading && !error && runs.length === 0 && <div style={styles.empty}>还没有运行记录。发送一条消息后会自动生成。</div>}
        <div style={styles.list}>{runs.map(run => <RunRow key={run.traceId} run={run} />)}</div>
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 },
  content: { flex: 1, minHeight: 0, padding: '22px 28px', overflow: 'auto', maxWidth: 1080, width: '100%', boxSizing: 'border-box' },
  toolbar: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, marginBottom: 18 },
  title: { margin: 0, color: '#0f172a', fontSize: '1.25rem', letterSpacing: 0 },
  subtitle: { margin: '6px 0 0', color: '#64748b', fontSize: '0.86rem', lineHeight: 1.5 },
  refresh: { border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff', color: '#334155', padding: '7px 12px', cursor: 'pointer', fontSize: '0.82rem', fontWeight: 600, flexShrink: 0 },
  list: { display: 'grid', gap: 8 },
  run: { border: '1px solid #e2e8f0', borderRadius: 7, background: '#fff', overflow: 'hidden' },
  runHead: { display: 'grid', gridTemplateColumns: '20px minmax(0, 1fr) auto auto', gap: 12, alignItems: 'center', width: '100%', border: 0, background: '#fff', padding: '13px 14px', textAlign: 'left', cursor: 'pointer', color: '#0f172a' },
  disclosure: { color: '#64748b', fontFamily: 'monospace', fontSize: '1rem', textAlign: 'center' },
  runMain: { display: 'grid', gap: 3, minWidth: 0 },
  runTitle: { fontSize: '0.9rem', fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  runMeta: { color: '#64748b', fontSize: '0.76rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  duration: { color: '#334155', fontFamily: 'ui-monospace, monospace', fontSize: '0.8rem' },
  status: { border: '1px solid transparent', borderRadius: 999, padding: '3px 7px', fontSize: '0.68rem', fontWeight: 700 },
  detail: { borderTop: '1px solid #e2e8f0', background: '#f8fafc', padding: '12px 16px 16px 42px' },
  traceId: { color: '#64748b', fontFamily: 'ui-monospace, monospace', fontSize: '0.72rem', marginBottom: 12, overflowWrap: 'anywhere' },
  span: { display: 'flex', gap: 10, position: 'relative', paddingBottom: 12 },
  spanDot: { width: 8, height: 8, borderRadius: '50%', background: '#6366f1', marginTop: 5, flexShrink: 0 },
  spanBody: { flex: 1, minWidth: 0, borderLeft: '1px solid #cbd5e1', paddingLeft: 10 },
  spanHead: { display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12, color: '#1e293b', fontSize: '0.82rem' },
  spanMeta: { color: '#64748b', fontSize: '0.72rem', marginTop: 3 },
  attributes: { margin: '8px 0 0', padding: 8, overflowX: 'auto', background: '#fff', border: '1px solid #e2e8f0', borderRadius: 4, color: '#475569', fontSize: '0.72rem', lineHeight: 1.45 },
  error: { margin: '0 14px 12px', border: '1px solid #fecaca', borderRadius: 5, background: '#fef2f2', color: '#b91c1c', padding: '8px 10px', fontSize: '0.78rem', overflowWrap: 'anywhere' },
  empty: { border: '1px dashed #cbd5e1', borderRadius: 7, padding: 24, color: '#64748b', background: '#fff', fontSize: '0.86rem', textAlign: 'center' },
};
