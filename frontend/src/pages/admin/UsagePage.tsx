import React, { useEffect, useState } from 'react';
import AdminPageLayout from '../../components/AdminPageLayout';
import { getToken } from '../../api/auth';

interface UsageSummary {
  totalTurns: number;
  todayTurns: number;
  inputTokens: number;
  outputTokens: number;
  cachedPromptTokens: number;
  totalTokens: number;
  totalCostMicrousd: number;
  avgDurationMs: number;
  uniqueUsers?: number;
}

interface BucketCount {
  epochMs: number;
  label: string;
  count: number;
}

interface GroupCount {
  key: string;
  count: number;
}

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

function authHeaders() {
  return { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' };
}

async function apiFetch<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: authHeaders(), cache: 'no-store' });
  if (!res.ok) throw new Error(`请求失败（状态码 ${res.status}）`);
  return res.json() as Promise<T>;
}

export type UsageScope = 'personal' | 'platform';

const getSummary = (scope: UsageScope) => apiFetch<UsageSummary>(
  scope === 'platform' ? '/api/admin/usage/summary' : '/api/usage/me/summary',
);
const getHourly = (scope: UsageScope, hours: number) => apiFetch<BucketCount[]>(
  scope === 'platform' ? `/api/admin/usage/hourly?hours=${hours}` : `/api/usage/me/hourly?hours=${hours}`,
);
const getDaily = (scope: UsageScope, days: number) => apiFetch<BucketCount[]>(
  scope === 'platform' ? `/api/admin/usage/daily?days=${days}` : `/api/usage/me/daily?days=${days}`,
);
const getTopUsers = (days: number) => apiFetch<GroupCount[]>(`/api/admin/usage/top-users?days=${days}&n=10`);
const getTopAgents = (days: number) => apiFetch<GroupCount[]>(`/api/admin/usage/top-agents?days=${days}&n=10`);
const getModels = (scope: UsageScope, days: number) => apiFetch<ModelUsage[]>(
  scope === 'platform' ? `/api/admin/usage/models?days=${days}` : `/api/usage/me/models?days=${days}`,
);

const fmtTokens = (value: number) => value >= 1_000_000 ? `${(value / 1_000_000).toFixed(2)}M` : value >= 1_000 ? `${(value / 1_000).toFixed(1)}K` : value.toLocaleString();
const fmtCost = (microusd: number) => `$${(microusd / 1_000_000).toFixed(microusd > 0 && microusd < 10_000 ? 4 : 2)}`;

function TopList({ title, items, color }: { title: string; items: GroupCount[]; color: string }) {
  if (!items.length) return <div style={{ color: '#94a3b8', fontSize: '0.88rem' }}>暂无数据</div>;
  const max = Math.max(...items.map(i => i.count), 1);
  return (
    <div>
      <div style={{ fontSize: '0.92rem', fontWeight: 600, color: '#0f172a', marginBottom: 14 }}>{title}</div>
      {items.map(item => (
        <div key={item.key} style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
          <div style={{ width: 150, fontSize: '0.86rem', color: '#334155', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flexShrink: 0 }} title={item.key}>
            {item.key}
          </div>
          <div style={{ flex: 1, background: '#f1f5f9', borderRadius: 4, height: 10 }}>
            <div style={{ width: `${(item.count / max) * 100}%`, background: color, height: 10, borderRadius: 4, minWidth: 4 }} />
          </div>
          <div style={{ width: 40, fontSize: '0.88rem', color: '#4f46e5', textAlign: 'right', flexShrink: 0, fontWeight: 600 }}>{item.count}</div>
        </div>
      ))}
    </div>
  );
}

function ModelTable({ items }: { items: ModelUsage[] }) {
  if (!items.length) return <div style={{ color: '#94a3b8', fontSize: '0.88rem' }}>暂无模型调用。</div>;
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.82rem', minWidth: 680 }}>
        <thead><tr>{['模型', '请求', '输入', '缓存', '输出', '总 Token', '平均耗时', '成本'].map(header => <th key={header} style={{ textAlign: 'left', padding: '8px 10px', color: '#64748b', borderBottom: '1px solid #e2e8f0', fontSize: '0.72rem' }}>{header}</th>)}</tr></thead>
        <tbody>{items.map(item => <tr key={item.modelId}>
          <td style={{ padding: '10px', borderBottom: '1px solid #f1f5f9' }}><code style={{ color: '#3730a3' }}>{item.modelId}</code></td>
          <td style={{ padding: '10px', borderBottom: '1px solid #f1f5f9' }}>{item.turns}</td><td style={{ padding: '10px', borderBottom: '1px solid #f1f5f9' }}>{fmtTokens(item.inputTokens)}</td><td style={{ padding: '10px', borderBottom: '1px solid #f1f5f9' }}>{fmtTokens(item.cachedPromptTokens)}</td>
          <td style={{ padding: '10px', borderBottom: '1px solid #f1f5f9' }}>{fmtTokens(item.outputTokens)}</td><td style={{ padding: '10px', borderBottom: '1px solid #f1f5f9' }}>{fmtTokens(item.totalTokens)}</td><td style={{ padding: '10px', borderBottom: '1px solid #f1f5f9' }}>{item.avgDurationMs < 1000 ? `${item.avgDurationMs}ms` : `${(item.avgDurationMs / 1000).toFixed(1)}s`}</td><td style={{ padding: '10px', borderBottom: '1px solid #f1f5f9' }}>{fmtCost(item.costMicrousd)}</td>
        </tr>)}</tbody>
      </table>
    </div>
  );
}

// -----------------------------------------------------------------
// Simple SVG bar chart — no external dependency
// -----------------------------------------------------------------

interface BarChartProps {
  data: BucketCount[];
  width?: number;
  height?: number;
  color?: string;
  labelStep?: number;
}

function BarChart({ data, width = 600, height = 140, color = '#4f46e5', labelStep = 4 }: BarChartProps) {
  if (!data.length) return <div style={{ color: '#94a3b8', fontSize: '0.88rem' }}>暂无数据</div>;

  const maxCount = Math.max(...data.map(d => d.count), 1);
  const barW = (width - 20) / data.length;
  const chartH = height - 28; // reserve bottom for labels

  return (
    <svg
      width={width}
      height={height}
      style={{ display: 'block', maxWidth: '100%' }}
      viewBox={`0 0 ${width} ${height}`}
    >
      {/* Grid lines */}
      {[0.25, 0.5, 0.75, 1].map(pct => {
        const y = chartH * (1 - pct);
        return (
          <line
            key={pct}
            x1={10} y1={y} x2={width - 10} y2={y}
            stroke="#e5e7eb" strokeWidth={1}
          />
        );
      })}

      {/* Bars */}
      {data.map((d, i) => {
        const barH = Math.max(1, (d.count / maxCount) * chartH);
        const x = 10 + i * barW;
        const y = chartH - barH;
        return (
          <g key={d.epochMs}>
            <rect
              x={x + 1} y={y}
              width={Math.max(1, barW - 2)} height={barH}
              fill={color}
              opacity={d.count === 0 ? 0.12 : 0.85}
              rx={3}
            />
            {d.count > 0 && barH > 14 && (
              <text
                x={x + barW / 2} y={y - 3}
                textAnchor="middle"
                fill="#475569"
                fontSize={10}
                fontWeight={500}
              >
                {d.count}
              </text>
            )}
          </g>
        );
      })}

      {/* X-axis labels (show every labelStep bar) */}
      {data.map((d, i) => {
        if (i % labelStep !== 0) return null;
        const x = 10 + (i + 0.5) * barW;
        return (
          <text
            key={`lbl-${d.epochMs}`}
            x={x} y={height - 6}
            textAnchor="middle"
            fill="#94a3b8"
            fontSize={10}
          >
            {d.label}
          </text>
        );
      })}
    </svg>
  );
}

// -----------------------------------------------------------------
//  Sparkline (line chart)
// -----------------------------------------------------------------

interface SparklineProps {
  data: BucketCount[];
  width?: number;
  height?: number;
  color?: string;
}

function Sparkline({ data, width = 600, height = 72, color = '#4f46e5' }: SparklineProps) {
  if (data.length < 2) return null;
  const maxCount = Math.max(...data.map(d => d.count), 1);
  const step = (width - 20) / (data.length - 1);

  const points = data.map((d, i) => {
    const x = 10 + i * step;
    const y = 4 + (height - 8) * (1 - d.count / maxCount);
    return `${x},${y}`;
  }).join(' ');

  // Build area path
  const areaPoints = `10,${height - 4} ${points} ${10 + (data.length - 1) * step},${height - 4}`;

  return (
    <svg width={width} height={height} style={{ display: 'block', maxWidth: '100%' }} viewBox={`0 0 ${width} ${height}`}>
      <defs>
        <linearGradient id="spark-fill" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity={0.18} />
          <stop offset="100%" stopColor={color} stopOpacity={0} />
        </linearGradient>
      </defs>
      <polygon points={areaPoints} fill="url(#spark-fill)" />
      <polyline
        points={points}
        fill="none"
        stroke={color}
        strokeWidth={2.5}
        strokeLinejoin="round"
        strokeLinecap="round"
      />
      {data.map((d, i) => {
        const x = 10 + i * step;
        const y = 4 + (height - 8) * (1 - d.count / maxCount);
        return d.count > 0
          ? <circle key={d.epochMs} cx={x} cy={y} r={2.5} fill={color} />
          : null;
      })}
    </svg>
  );
}

// -----------------------------------------------------------------
//  Page
// -----------------------------------------------------------------

const S: Record<string, React.CSSProperties> = {
  content: { maxWidth: 1100 },
  heading: { fontSize: '1.5rem', fontWeight: 700, color: '#0f172a', marginBottom: '1.75rem', letterSpacing: 0 },
  cards: { display: 'flex', gap: 18, flexWrap: 'wrap', marginBottom: '2.25rem' },
  card: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.25rem 1.6rem', flex: '1 1 180px', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  cardLabel: { fontSize: '0.78rem', color: '#64748b', marginBottom: 10, letterSpacing: 0, fontWeight: 600 },
  cardValue: { fontSize: '2.1rem', fontWeight: 700, color: '#4f46e5', lineHeight: 1, letterSpacing: 0 },
  chartCard: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.5rem 1.75rem', marginBottom: 20, boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  chartTitle: { fontSize: '0.95rem', fontWeight: 600, color: '#0f172a', marginBottom: 14 },
  tabs: { display: 'flex', gap: 8, marginBottom: 0 },
  scopeTabs: { display: 'flex', gap: 6, marginLeft: 'auto' },
  refreshBtn: {
    background: '#ffffff', border: '1px solid #d1d5db', color: '#475569',
    borderRadius: 8, padding: '7px 16px', cursor: 'pointer', fontSize: '0.86rem', marginLeft: 14, fontWeight: 500,
  },
  err: { color: '#dc2626', fontSize: '0.92rem', padding: '14px 18px', background: '#fef2f2', borderRadius: 10, border: '1px solid #fecaca', marginBottom: 20 },
};

function tabBtnStyle(active: boolean): React.CSSProperties {
  return {
    background: active ? '#eef2ff' : '#ffffff',
    border: `1px solid ${active ? '#c7d2fe' : '#d1d5db'}`,
    color: active ? '#4338ca' : '#475569',
    borderRadius: 6, padding: '5px 14px', cursor: 'pointer', fontSize: '0.84rem', fontWeight: active ? 600 : 500,
  };
}

interface UsageDashboardProps {
  canViewPlatform?: boolean;
  initialScope?: UsageScope;
}

export function UsageDashboard({
  canViewPlatform = false,
  initialScope = 'personal',
}: UsageDashboardProps) {
  const [scope, setScope] = useState<UsageScope>(canViewPlatform ? initialScope : 'personal');
  const [summary, setSummary] = useState<UsageSummary | null>(null);
  const [hourly, setHourly] = useState<BucketCount[]>([]);
  const [daily, setDaily] = useState<BucketCount[]>([]);
  const [topUsers,  setTopUsers]  = useState<GroupCount[]>([]);
  const [topAgents, setTopAgents] = useState<GroupCount[]>([]);
  const [models, setModels] = useState<ModelUsage[]>([]);
  const [hourRange, setHourRange] = useState(24);
  const [dayRange, setDayRange] = useState(14);
  const [topRange,  setTopRange]  = useState(7);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const platformScope = scope === 'platform';

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [s, h, d, tm, rankings] = await Promise.all([
        getSummary(scope),
        getHourly(scope, hourRange),
        getDaily(scope, dayRange),
        getModels(scope, 30),
        platformScope
          ? Promise.all([getTopUsers(topRange), getTopAgents(topRange)])
          : Promise.resolve([[], []] as [GroupCount[], GroupCount[]]),
      ]);
      setSummary(s);
      setHourly(h);
      setDaily(d);
      setTopUsers(rankings[0]);
      setTopAgents(rankings[1]);
      setModels(tm);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载用量数据失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
    const refresh = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load();
    }, 15_000);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') void load();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.clearInterval(refresh);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [scope, hourRange, dayRange, topRange]);

  function fmtDuration(ms: number) {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  }

  return (
    <>
      <AdminPageLayout>
      <div style={S.content}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: '1.75rem', flexWrap: 'wrap' }}>
          <h2 style={{ ...S.heading, marginBottom: 0, marginRight: 'auto' }}>
            {platformScope ? '平台用量与指标' : '我的用量'}
          </h2>
          {canViewPlatform && (
            <div style={S.scopeTabs}>
              <button style={tabBtnStyle(platformScope)} onClick={() => setScope('platform')}>平台用量</button>
              <button style={tabBtnStyle(!platformScope)} onClick={() => setScope('personal')}>我的用量</button>
            </div>
          )}
          <button style={S.refreshBtn} onClick={load} disabled={loading}>{loading ? '加载中…' : '↺ 刷新'}</button>
        </div>

        {error && <div style={S.err}>{error}</div>}

        {summary && (
          <div style={S.cards}>
            <div style={S.card}>
              <div style={S.cardLabel}>总 Token</div>
              <div style={S.cardValue}>{fmtTokens(summary.totalTokens)}</div>
            </div>
            <div style={S.card}>
              <div style={S.cardLabel}>缓存 Prompt</div>
              <div style={S.cardValue}>{fmtTokens(summary.cachedPromptTokens)}</div>
            </div>
            <div style={S.card}>
              <div style={S.cardLabel}>累计成本</div>
              <div style={S.cardValue}>{fmtCost(summary.totalCostMicrousd)}</div>
            </div>
            <div style={S.card}>
              <div style={S.cardLabel}>平均耗时</div>
              <div style={S.cardValue}>{fmtDuration(summary.avgDurationMs)}</div>
            </div>
            {platformScope && (
              <div style={S.card}>
                <div style={S.cardLabel}>独立用户数</div>
                <div style={S.cardValue}>{summary.uniqueUsers ?? 0}</div>
              </div>
            )}
            <div style={S.card}>
              <div style={S.cardLabel}>今日请求数</div>
              <div style={S.cardValue}>{summary.todayTurns}</div>
            </div>
          </div>
        )}

        {/* Hourly sparkline */}
        <div style={S.chartCard}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
            <span style={S.chartTitle}>请求次数 - 小时趋势</span>
            <div style={S.tabs}>
              {[12, 24, 48, 72].map(h => (
                <button key={h} style={tabBtnStyle(hourRange === h)} onClick={() => setHourRange(h)}>{h} 小时</button>
              ))}
            </div>
          </div>
          <Sparkline data={hourly} width={840} height={80} color="#4f46e5" />
        </div>

        {/* Daily bar chart */}
        <div style={S.chartCard}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
            <span style={S.chartTitle}>请求次数 - 每日趋势</span>
            <div style={S.tabs}>
              {[7, 14, 30].map(d => (
                <button key={d} style={tabBtnStyle(dayRange === d)} onClick={() => setDayRange(d)}>{d} 天</button>
              ))}
            </div>
          </div>
          <BarChart data={daily} width={840} height={150} color="#6366f1" labelStep={2} />
        </div>

        <div style={S.chartCard}>
          <div style={{ marginBottom: 16 }}>
            <span style={S.chartTitle}>模型用量（近 30 天）</span>
          </div>
          <ModelTable items={models} />
        </div>

        {platformScope && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 20 }}>
            <div style={S.chartCard}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
                <span style={S.chartTitle}>请求次数最多的用户</span>
                <div style={S.tabs}>
                  {[7, 14, 30].map(d => (
                    <button key={d} style={tabBtnStyle(topRange === d)} onClick={() => setTopRange(d)}>{d} 天</button>
                  ))}
                </div>
              </div>
              <TopList title="" items={topUsers} color="#4f46e5" />
            </div>
            <div style={S.chartCard}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
                <span style={S.chartTitle}>请求次数最多的智能体</span>
              </div>
              <TopList title="" items={topAgents} color="#6366f1" />
            </div>
          </div>
        )}

        {summary?.totalTurns === 0 && (
          <p style={{ color: '#94a3b8', fontSize: '0.9rem', textAlign: 'center', marginTop: '1.5rem' }}>
            暂无用量数据。发起对话后再回来查看。
          </p>
        )}
      </div>
      </AdminPageLayout>
    </>
  );
}

export default function AdminUsagePage() {
  return <UsageDashboard canViewPlatform initialScope="platform" />;
}
