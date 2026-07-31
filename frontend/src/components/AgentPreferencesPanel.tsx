import React, { useEffect, useState } from 'react';
import { confirmAction } from './InteractionHost';
import {
  AgentPreferenceSummary,
  clearAgentPreferences,
  getAgentPreferences,
  getSqlPatternPage,
  SqlPatternPreference,
} from '../api/preferences';

interface Props {
  agentId: string;
  agentScope?: 'global' | 'user';
}

const card: React.CSSProperties = {
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 8,
  padding: '20px 24px',
  margin: '0 36px 32px',
  maxWidth: 960,
};

const sqlDetails: React.CSSProperties = {
  border: '1px solid #e2e8f0',
  borderRadius: 6,
  padding: '8px 10px',
  marginBottom: 7,
  background: '#f8fafc',
};

function SqlPatternRow({ item }: { item: SqlPatternPreference }) {
  return (
    <details style={sqlDetails}>
      <summary style={{ cursor: 'pointer', color: '#334155', fontSize: '0.78rem', minWidth: 0 }}>
        <code
          title={item.sqlPreview}
          style={{ display: 'inline-block', maxWidth: 'calc(100% - 52px)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }}
        >
          {item.sqlPreview}
        </code>
        <span style={{ marginLeft: 8, color: '#64748b' }}>· {item.useCount} 次</span>
      </summary>
      <pre style={{ margin: '10px 0 0', padding: 10, borderRadius: 5, background: '#fff', border: '1px solid #e2e8f0', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: '0.76rem', lineHeight: 1.5, color: '#334155' }}>
        {item.sqlText}
      </pre>
    </details>
  );
}

export default function AgentPreferencesPanel({ agentId, agentScope }: Props) {
  const [summary, setSummary] = useState<AgentPreferenceSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [clearing, setClearing] = useState(false);
  const [allSql, setAllSql] = useState<SqlPatternPreference[] | null>(null);
  const [sqlPage, setSqlPage] = useState(0);
  const [sqlTotal, setSqlTotal] = useState(0);
  const [loadingAllSql, setLoadingAllSql] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setSummary(null);
    setError(null);
    setAllSql(null);
    setSqlPage(0);
    setSqlTotal(0);
    getAgentPreferences(agentId)
      .then(value => { if (!cancelled) setSummary(value); })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '加载偏好失败'); });
    return () => { cancelled = true; };
  }, [agentId]);

  const hasPreferences = Boolean(
    summary && (
      summary.sqlPatterns.length > 0
      || summary.chartPreferences.length > 0
      || summary.tablePreferences.length > 0
      || summary.queryStyles.length > 0
    ),
  );

  async function loadSqlPage(page: number) {
    setLoadingAllSql(true);
    setError(null);
    try {
      const result = await getSqlPatternPage(agentId, page);
      setAllSql(previous => page === 0 ? result.items : [...(previous ?? []), ...result.items]);
      setSqlPage(result.page);
      setSqlTotal(result.total);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载 SQL 偏好失败');
    } finally {
      setLoadingAllSql(false);
    }
  }

  async function clear() {
    if (!(await confirmAction('清空该 Agent 已学习的 SQL 与图表偏好？'))) return;
    setClearing(true);
    setError(null);
    try {
      await clearAgentPreferences(agentId);
      setSummary({ sqlPatterns: [], chartPreferences: [], tablePreferences: [], queryStyles: [], sqlPatternCount: 0 });
      setAllSql(null);
      setSqlPage(0);
      setSqlTotal(0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '清空偏好失败');
    } finally {
      setClearing(false);
    }
  }

  return (
    <section style={card}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 }}>
        <div style={{ flex: 1 }}>
          <h3 style={{ margin: 0, fontSize: '0.92rem', color: '#334155' }}>个人学习偏好</h3>
          <div style={{ marginTop: 4, fontSize: '0.76rem', color: '#64748b' }}>
            {agentScope === 'global'
              ? '当前用户在此全局 Agent 上的 SQL 与图表使用记录，不会修改全局 Agent 配置。'
              : '当前用户在此私有 Agent 上的 SQL 与图表使用记录。'}
          </div>
        </div>
        {hasPreferences && (
          <button
            type="button"
            onClick={clear}
            disabled={clearing}
            style={{
              border: '1px solid #cbd5e1', background: '#fff', color: '#475569',
              borderRadius: 6, padding: '6px 10px', cursor: clearing ? 'wait' : 'pointer',
            }}
          >
            {clearing ? '清空中…' : '清空'}
          </button>
        )}
      </div>
      {error && <div style={{ color: '#dc2626', fontSize: '0.8rem' }}>{error}</div>}
      {!error && !summary && <div style={{ color: '#64748b', fontSize: '0.82rem' }}>加载中…</div>}
      {summary && !hasPreferences && (
        <div style={{ color: '#64748b', fontSize: '0.82rem' }}>暂无已学习偏好</div>
      )}
      {summary && summary.sqlPatterns.length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
            <div style={{ fontSize: '0.76rem', color: '#64748b', flex: 1 }}>常用 SQL · 已收录 {summary.sqlPatternCount} 种</div>
            {summary.sqlPatternCount > summary.sqlPatterns.length && allSql === null && (
              <button type="button" onClick={() => void loadSqlPage(0)} disabled={loadingAllSql} style={linkButton}>
                {loadingAllSql ? '加载中…' : '查看全部'}
              </button>
            )}
          </div>
          {(allSql ?? summary.sqlPatterns).map((item, index) => <SqlPatternRow key={`${item.sqlText}-${index}`} item={item} />)}
          {allSql !== null && allSql.length < sqlTotal && (
            <button type="button" onClick={() => void loadSqlPage(sqlPage + 1)} disabled={loadingAllSql} style={linkButton}>
              {loadingAllSql ? '加载中…' : `加载更多（还剩 ${sqlTotal - allSql.length} 条）`}
            </button>
          )}
        </div>
      )}
      {summary && summary.tablePreferences.length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div style={{ fontSize: '0.76rem', color: '#64748b', marginBottom: 6 }}>常查数据表</div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {summary.tablePreferences.map(item => (
              <span key={item.tableName} style={pill}><code>{item.tableName}</code> · {item.useCount} 次</span>
            ))}
          </div>
        </div>
      )}
      {summary && summary.queryStyles.length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div style={{ fontSize: '0.76rem', color: '#64748b', marginBottom: 6 }}>查询习惯</div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {summary.queryStyles.map(item => (
              <span key={item.label} style={pill}>{item.label} {item.percentage}%</span>
            ))}
          </div>
        </div>
      )}
      {summary && summary.chartPreferences.length > 0 && (
        <div>
          <div style={{ fontSize: '0.76rem', color: '#64748b', marginBottom: 6 }}>图表偏好</div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {summary.chartPreferences.map(item => (
              <span key={item.chartType} style={{ fontSize: '0.78rem', color: '#334155' }}>
                {item.chartType} {item.percentage}%
              </span>
            ))}
          </div>
        </div>
      )}
    </section>
  );
}

const linkButton: React.CSSProperties = {
  border: 'none', background: 'transparent', color: '#4f46e5', padding: 0, cursor: 'pointer', fontSize: '0.76rem',
};

const pill: React.CSSProperties = {
  fontSize: '0.78rem', color: '#334155', background: '#f1f5f9', borderRadius: 4, padding: '4px 7px',
};
