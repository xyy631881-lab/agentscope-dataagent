import React, { useEffect, useState } from 'react';
import AdminPageLayout from '../components/AdminPageLayout';
import { getToken } from '../api/auth';

interface UsageSummary {
  totalTurns: number;
  todayTurns: number;
  inputTokens: number;
  outputTokens: number;
  cachedPromptTokens: number;
  totalTokens: number;
  totalCostMicrousd: number;
  avgDurationMs: number;
}

interface BucketCount { epochMs: number; label: string; count: number; }
interface ModelUsage {
  modelId: string;
  turns: number;
  inputTokens: number;
  outputTokens: number;
  cachedPromptTokens: number;
  totalTokens: number;
  costMicrousd: number;
  avgDurationMs: number;
}

function headers() {
  return { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' };
}

async function apiFetch<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: headers() });
  if (!response.ok) throw new Error(await response.text().catch(() => `HTTP ${response.status}`));
  return response.json() as Promise<T>;
}

function formatTokens(value: number) {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return value.toLocaleString();
}

function formatUsd(microUsd: number) {
  return `$${(microUsd / 1_000_000).toFixed(microUsd > 0 && microUsd < 10_000 ? 4 : 2)}`;
}

function formatDuration(durationMs: number) {
  if (durationMs < 1_000) return `${durationMs}ms`;
  if (durationMs < 60_000) return `${(durationMs / 1_000).toFixed(1)}s`;
  return `${(durationMs / 60_000).toFixed(1)}m`;
}

function Trend({ data }: { data: BucketCount[] }) {
  if (!data.length) return <div style={styles.empty}>暂无请求数据</div>;
  const max = Math.max(...data.map(item => item.count), 1);
  return (
    <div style={styles.trend} aria-label="请求量趋势">
      {data.map((item, index) => (
        <div key={item.epochMs} style={styles.trendItem} title={`${item.label}: ${item.count} 次请求`}>
          <div style={{ ...styles.bar, height: `${Math.max(3, item.count / max * 100)}%`, opacity: item.count ? 1 : 0.16 }} />
          {(index === 0 || index === data.length - 1 || index % Math.ceil(data.length / 6) === 0) && (
            <span style={styles.axisLabel}>{item.label}</span>
          )}
        </div>
      ))}
    </div>
  );
}

export default function UsagePage() {
  const [summary, setSummary] = useState<UsageSummary | null>(null);
  const [hourly, setHourly] = useState<BucketCount[]>([]);
  const [models, setModels] = useState<ModelUsage[]>([]);
  const [hours, setHours] = useState(24);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [nextSummary, nextHourly, nextModels] = await Promise.all([
        apiFetch<UsageSummary>('/api/usage/me/summary'),
        apiFetch<BucketCount[]>(`/api/usage/me/hourly?hours=${hours}`),
        apiFetch<ModelUsage[]>('/api/usage/me/models?days=30'),
      ]);
      setSummary(nextSummary);
      setHourly(nextHourly);
      setModels(nextModels);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '无法加载用量数据');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, [hours]);

  const cards = summary ? [
    ['总 Token', formatTokens(summary.totalTokens), `输入 ${formatTokens(summary.inputTokens)} / 输出 ${formatTokens(summary.outputTokens)}`],
    ['缓存 Prompt Token', formatTokens(summary.cachedPromptTokens), summary.inputTokens ? `占输入 ${(summary.cachedPromptTokens / summary.inputTokens * 100).toFixed(1)}%` : '暂无缓存命中'],
    ['已计成本', formatUsd(summary.totalCostMicrousd), '未配置单价的模型按 $0 计'],
    ['平均耗时', formatDuration(summary.avgDurationMs), `今日 ${summary.todayTurns} 次请求`],
  ] : [];

  return (
    <AdminPageLayout>
      <div style={styles.root}>
        <div style={styles.headingRow}>
          <div>
            <h1 style={styles.title}>用量</h1>
            <p style={styles.subtitle}>请求级用量已持久化，包含模型调用和 Prompt 缓存。</p>
          </div>
          <button type="button" style={styles.refresh} onClick={load} disabled={loading}>
            {loading ? '加载中' : '刷新'}
          </button>
        </div>

        {error && <div style={styles.error}>{error}</div>}

        <section style={styles.cardGrid}>
          {cards.map(([label, value, hint]) => (
            <div key={label} style={styles.metricCard}>
              <span style={styles.metricLabel}>{label}</span>
              <strong style={styles.metricValue}>{value}</strong>
              <span style={styles.metricHint}>{hint}</span>
            </div>
          ))}
        </section>

        <section style={styles.panel}>
          <div style={styles.panelHeader}>
            <div>
              <h2 style={styles.panelTitle}>请求趋势</h2>
              <p style={styles.panelHint}>按请求数统计，Token 详情见下方模型分布。</p>
            </div>
            <div style={styles.segmented}>
              {[12, 24, 48, 72].map(value => (
                <button key={value} type="button" onClick={() => setHours(value)} style={{ ...styles.segment, ...(hours === value ? styles.segmentActive : {}) }}>
                  {value}h
                </button>
              ))}
            </div>
          </div>
          <Trend data={hourly} />
        </section>

        <section style={styles.panel}>
          <div style={styles.panelHeader}>
            <div>
              <h2 style={styles.panelTitle}>模型分布</h2>
              <p style={styles.panelHint}>最近 30 天，按实际执行模型聚合。</p>
            </div>
          </div>
          {models.length === 0 ? <div style={styles.empty}>还没有完成的模型调用。</div> : (
            <div style={styles.tableWrap}>
              <table style={styles.table}>
                <thead><tr><th>模型</th><th>请求</th><th>输入</th><th>缓存</th><th>输出</th><th>总 Token</th><th>均耗时</th><th>成本</th></tr></thead>
                <tbody>{models.map(model => (
                  <tr key={model.modelId}>
                    <td><code style={styles.modelCode}>{model.modelId}</code></td>
                    <td>{model.turns}</td><td>{formatTokens(model.inputTokens)}</td><td>{formatTokens(model.cachedPromptTokens)}</td>
                    <td>{formatTokens(model.outputTokens)}</td><td><strong>{formatTokens(model.totalTokens)}</strong></td>
                    <td>{formatDuration(model.avgDurationMs)}</td><td>{formatUsd(model.costMicrousd)}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
        </section>
      </div>
    </AdminPageLayout>
  );
}

const styles: Record<string, React.CSSProperties> = {
  root: { maxWidth: 1180 },
  headingRow: { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16, marginBottom: 24 },
  title: { margin: 0, color: '#0f172a', fontSize: '1.45rem', lineHeight: 1.2, letterSpacing: 0 },
  subtitle: { margin: '7px 0 0', color: '#64748b', fontSize: '0.9rem' },
  refresh: { border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff', color: '#334155', padding: '7px 13px', cursor: 'pointer', fontSize: '0.84rem', fontWeight: 600, flexShrink: 0 },
  error: { marginBottom: 16, border: '1px solid #fecaca', borderRadius: 6, background: '#fef2f2', color: '#b91c1c', padding: '10px 12px', fontSize: '0.86rem' },
  cardGrid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(205px, 1fr))', gap: 12, marginBottom: 18 },
  metricCard: { display: 'grid', gap: 7, minHeight: 118, padding: '16px', border: '1px solid #e2e8f0', borderRadius: 7, background: '#fff' },
  metricLabel: { color: '#64748b', fontSize: '0.75rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: 0 },
  metricValue: { color: '#172554', fontSize: '1.55rem', lineHeight: 1.05 },
  metricHint: { color: '#64748b', fontSize: '0.78rem', lineHeight: 1.35 },
  panel: { border: '1px solid #e2e8f0', borderRadius: 7, background: '#fff', padding: '18px', marginBottom: 16 },
  panelHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, marginBottom: 16 },
  panelTitle: { margin: 0, color: '#0f172a', fontSize: '1rem', letterSpacing: 0 },
  panelHint: { margin: '5px 0 0', color: '#64748b', fontSize: '0.8rem' },
  segmented: { display: 'flex', border: '1px solid #cbd5e1', borderRadius: 6, overflow: 'hidden', flexShrink: 0 },
  segment: { border: 0, borderRight: '1px solid #cbd5e1', padding: '5px 9px', background: '#fff', color: '#475569', cursor: 'pointer', fontSize: '0.78rem' },
  segmentActive: { background: '#e0e7ff', color: '#3730a3', fontWeight: 700 },
  trend: { height: 150, display: 'flex', alignItems: 'flex-end', gap: 3, borderBottom: '1px solid #cbd5e1', padding: '0 3px 21px' },
  trendItem: { flex: 1, minWidth: 3, height: '100%', display: 'flex', alignItems: 'flex-end', justifyContent: 'center', position: 'relative' },
  bar: { width: '100%', maxWidth: 16, background: '#4f46e5', borderRadius: '3px 3px 0 0' },
  axisLabel: { position: 'absolute', bottom: -18, color: '#94a3b8', fontSize: '0.65rem', whiteSpace: 'nowrap' },
  tableWrap: { overflowX: 'auto' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.83rem', color: '#334155', minWidth: 720 },
  modelCode: { color: '#312e81', background: '#eef2ff', borderRadius: 3, padding: '2px 5px', fontSize: '0.78rem' },
  empty: { color: '#94a3b8', fontSize: '0.86rem', padding: '18px 0' },
};
