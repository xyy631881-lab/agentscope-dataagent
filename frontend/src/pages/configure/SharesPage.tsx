import React, { useCallback, useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import BackToChatHeader from '../../components/BackToChatHeader';
import { ShellOutletContext } from '../../components/EditTierGate';
import {
  AgentDefinition,
  AgentShareGrant,
  grantShare,
  revokeShare,
  ShareGrantRequest,
} from '../../api/agents';

// ---------------------------------------------------------------------------
//  设计 token（与项目其他页面一致）
// ---------------------------------------------------------------------------
const C = {
  bg:       '#f8fafc',
  surface:  '#ffffff',
  border:   '#e5e7eb',
  text:     '#0f172a',
  muted:    '#64748b',
  dimmed:   '#94a3b8',
  accent:   '#4f46e5',
  accentBg: '#eef2ff',
  green:    '#16a34a',
  greenBg:  '#dcfce7',
  red:      '#dc2626',
  redBg:    '#fef2f2',
  yellow:   '#d97706',
  yellowBg: '#fef3c7',
};

// ---------------------------------------------------------------------------
//  Tier 徽章——用颜色区分三种权限级别
// ---------------------------------------------------------------------------
const TIER_BADGE: Record<string, { bg: string; fg: string; label: string }> = {
  EDIT:  { bg: C.greenBg,  fg: C.green,  label: 'EDIT · 可编辑' },
  RUN:   { bg: C.accentBg, fg: C.accent, label: 'RUN · 可运行' },
  CLONE: { bg: C.yellowBg, fg: C.yellow, label: 'CLONE · 可克隆' },
};

// granteeType 显示名
function granteeLabel(g: AgentShareGrant): string {
  if (g.granteeType === 'WORKSPACE') return '所有登录用户';
  return g.granteeId;
}

export default function SharesPage() {
  const ctx = useOutletContext<ShellOutletContext>();
  const agent = ctx.agent;

  // 本地维护 shares 列表，操作成功后立即更新 UI，不必等整页刷新
  const [shares, setShares] = useState<AgentShareGrant[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);  // 防止重复提交

  // 同步 ctx.agent.shares 到本地 state
  useEffect(() => {
    setShares(agent?.shares ?? []);
    setError(null);
  }, [agent]);

  // -----------------------------------------------------------------
  //  添加 / 更新分享
  // -----------------------------------------------------------------
  const [granteeType, setGranteeType] = useState<'USER' | 'WORKSPACE'>('USER');
  const [granteeId, setGranteeId] = useState('');
  const [tier, setTier] = useState<'CLONE' | 'RUN' | 'EDIT'>('RUN');

  const handleGrant = useCallback(async () => {
    if (!agent) return;
    // USER 类型必须填 granteeId
    if (granteeType === 'USER' && !granteeId.trim()) {
      setError('请填写被授权用户 ID');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const req: ShareGrantRequest = {
        granteeType,
        granteeId: granteeType === 'WORKSPACE' ? '*' : granteeId.trim(),
        tier,
      };
      const updated = await grantShare(agent.id, req);
      setShares(updated.shares ?? []);
      setGranteeId('');  // 清空输入框
      ctx.bumpSidebar();  // 通知侧边栏刷新（如果有依赖）
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '分享失败');
    } finally {
      setBusy(false);
    }
  }, [agent, granteeType, granteeId, tier, ctx]);

  // -----------------------------------------------------------------
  //  撤销分享
  // -----------------------------------------------------------------
  const handleRevoke = useCallback(async (g: AgentShareGrant) => {
    if (!agent) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await revokeShare(agent.id, g.granteeType, g.granteeId);
      setShares(updated.shares ?? []);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '撤销失败');
    } finally {
      setBusy(false);
    }
  }, [agent]);

  // -----------------------------------------------------------------
  //  渲染
  // -----------------------------------------------------------------
  if (!agent) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
        <BackToChatHeader title="分享" subtitle="管理 Agent 的访问授权" />
        <div style={{ padding: '24px 28px', color: C.muted }}>加载中…</div>
      </div>
    );
  }

  // 只有 owner 才能管理分享（后端也会校验，这里做前端兜底）。
  // owner 判定：scope=user 且 ownerId 存在。全局 Agent（scope=global）无 owner，不能分享。
  // 进一步用 tierForCurrentUser==='EDIT' 兜底——非 owner 即便被授权 EDIT 也不能管理分享。
  const isOwner = agent.scope === 'user' && !!agent.ownerId && agent.tierForCurrentUser === 'EDIT';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="分享" subtitle="管理 Agent 的访问授权" />

      {/* 帮助提示条 */}
      <div style={S.helpBar}>
        把这个 Agent 分享给其他用户或所有登录用户。
        <b> CLONE</b> = 可复制；<b> RUN</b> = 可运行；<b> EDIT</b> = 可编辑。
        只有 Agent 的所有者（owner）能管理分享。
      </div>

      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '24px 28px' }}>
        {/* 非 owner 提示 */}
        {!isOwner && (
          <div style={S.notice}>
            这是全局或共享给你的 Agent，你无法管理它的分享。只有 owner 可以操作。
          </div>
        )}

        {error && (
          <div style={S.errorBox}>{error}</div>
        )}

        {/* ── 添加分享表单 ── */}
        {isOwner && (
          <div style={S.card}>
            <div style={S.cardTitle}>添加 / 更新分享</div>
            <div style={S.formRow}>
              <label style={S.label}>
                授权对象
                <select
                  value={granteeType}
                  onChange={e => setGranteeType(e.target.value as 'USER' | 'WORKSPACE')}
                  style={S.select}
                  disabled={busy}
                >
                  <option value="USER">指定用户</option>
                  <option value="WORKSPACE">所有登录用户</option>
                </select>
              </label>

              {granteeType === 'USER' && (
                <label style={S.label}>
                  用户 ID
                  <input
                    type="text"
                    value={granteeId}
                    onChange={e => setGranteeId(e.target.value)}
                    placeholder="例如：alice"
                    style={S.input}
                    disabled={busy}
                  />
                </label>
              )}

              <label style={S.label}>
                权限级别
                <select
                  value={tier}
                  onChange={e => setTier(e.target.value as 'CLONE' | 'RUN' | 'EDIT')}
                  style={S.select}
                  disabled={busy}
                >
                  <option value="CLONE">CLONE · 可克隆</option>
                  <option value="RUN">RUN · 可运行</option>
                  <option value="EDIT">EDIT · 可编辑</option>
                </select>
              </label>

              <button
                onClick={handleGrant}
                disabled={busy}
                style={{ ...S.btn, ...(busy ? S.btnDisabled : {}) }}
              >
                {busy ? '处理中…' : '添加 / 更新'}
              </button>
            </div>
            <div style={S.hint}>
              如果 (授权对象 + 用户 ID) 已存在，会更新它的权限级别（upsert 语义）。
            </div>
          </div>
        )}

        {/* ── 分享列表 ── */}
        <div style={{ ...S.card, marginTop: 20 }}>
          <div style={S.cardTitle}>
            当前分享列表
            {shares.length > 0 && <span style={S.countBadge}>{shares.length}</span>}
          </div>

          {shares.length === 0 ? (
            <div style={S.empty}>还没有任何分享记录。这个 Agent 只有 owner 自己能看到。</div>
          ) : (
            <table style={S.table}>
              <thead>
                <tr>
                  <th style={S.th}>授权对象</th>
                  <th style={S.th}>用户 / 范围</th>
                  <th style={S.th}>权限级别</th>
                  <th style={S.th}>授权人</th>
                  <th style={{ ...S.th, textAlign: 'right' }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {shares.map((g, i) => {
                  const badge = TIER_BADGE[g.tier] ?? { bg: '#f1f5f9', fg: C.muted, label: g.tier };
                  return (
                    <tr key={`${g.granteeType}-${g.granteeId}-${i}`} style={S.tr}>
                      <td style={S.td}>
                        <span style={typeBadgeStyle(g.granteeType)}>
                          {g.granteeType === 'WORKSPACE' ? 'WORKSPACE' : 'USER'}
                        </span>
                      </td>
                      <td style={{ ...S.td, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.85rem' }}>
                        {granteeLabel(g)}
                      </td>
                      <td style={S.td}>
                        <span style={{ ...S.tierBadge, background: badge.bg, color: badge.fg }}>
                          {badge.label}
                        </span>
                      </td>
                      <td style={{ ...S.td, color: C.muted, fontSize: '0.85rem' }}>
                        {g.createdBy ?? '—'}
                      </td>
                      <td style={{ ...S.td, textAlign: 'right' }}>
                        {isOwner && (
                          <button
                            onClick={() => handleRevoke(g)}
                            disabled={busy}
                            style={{ ...S.revokeBtn, ...(busy ? S.btnDisabled : {}) }}
                          >
                            撤销
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
//  样式
// ---------------------------------------------------------------------------
const S: Record<string, React.CSSProperties> = {
  helpBar: {
    padding: '8px 24px',
    fontSize: '0.78rem',
    color: C.muted,
    background: C.bg,
    borderBottom: `1px solid ${C.border}`,
  },
  notice: {
    padding: '12px 16px',
    background: C.yellowBg,
    color: C.yellow,
    borderRadius: 8,
    fontSize: '0.88rem',
    marginBottom: 16,
    border: `1px solid #fde68a`,
  },
  errorBox: {
    padding: '12px 16px',
    background: C.redBg,
    color: C.red,
    borderRadius: 8,
    fontSize: '0.88rem',
    marginBottom: 16,
    border: `1px solid #fecaca`,
  },
  card: {
    background: C.surface,
    border: `1px solid ${C.border}`,
    borderRadius: 14,
    padding: '1.5rem 1.75rem',
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  cardTitle: {
    fontSize: '0.8rem',
    fontWeight: 700,
    color: C.accent,
    textTransform: 'uppercase' as const,
    letterSpacing: '0.06em',
    marginBottom: 18,
    display: 'flex',
    alignItems: 'center',
    gap: 8,
  },
  countBadge: {
    background: C.accentBg,
    color: C.accent,
    fontSize: '0.72rem',
    padding: '2px 8px',
    borderRadius: 10,
    fontWeight: 600,
  },
  formRow: {
    display: 'flex',
    alignItems: 'flex-end',
    gap: 12,
    flexWrap: 'wrap' as const,
  },
  label: {
    display: 'flex',
    flexDirection: 'column' as const,
    gap: 4,
    fontSize: '0.78rem',
    color: C.muted,
    fontWeight: 500,
  },
  input: {
    padding: '7px 10px',
    border: `1px solid ${C.border}`,
    borderRadius: 8,
    fontSize: '0.88rem',
    color: C.text,
    background: C.surface,
    minWidth: 180,
    outline: 'none',
  },
  select: {
    padding: '7px 10px',
    border: `1px solid ${C.border}`,
    borderRadius: 8,
    fontSize: '0.88rem',
    color: C.text,
    background: C.surface,
    minWidth: 160,
    outline: 'none',
  },
  btn: {
    padding: '8px 16px',
    background: C.accent,
    color: '#ffffff',
    border: 'none',
    borderRadius: 8,
    cursor: 'pointer',
    fontSize: '0.86rem',
    fontWeight: 600,
    height: 36,
  },
  btnDisabled: {
    opacity: 0.5,
    cursor: 'not-allowed',
  },
  hint: {
    fontSize: '0.76rem',
    color: C.dimmed,
    marginTop: 10,
  },
  empty: {
    color: C.muted,
    fontSize: '0.9rem',
    padding: '12px 0',
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse' as const,
    fontSize: '0.88rem',
  },
  th: {
    textAlign: 'left' as const,
    padding: '8px 10px',
    borderBottom: `1px solid ${C.border}`,
    fontSize: '0.74rem',
    fontWeight: 600,
    color: C.muted,
    textTransform: 'uppercase' as const,
    letterSpacing: '0.04em',
  },
  tr: {
    borderBottom: `1px solid #f1f5f9`,
  },
  td: {
    padding: '10px',
    color: C.text,
    verticalAlign: 'middle' as const,
  },
  tierBadge: {
    padding: '3px 10px',
    borderRadius: 10,
    fontSize: '0.76rem',
    fontWeight: 600,
  },
  revokeBtn: {
    padding: '4px 12px',
    background: C.surface,
    color: C.red,
    border: `1px solid #fecaca`,
    borderRadius: 6,
    cursor: 'pointer',
    fontSize: '0.82rem',
    fontWeight: 500,
  },
};

// granteeType 徽章样式（用函数返回，因为依赖参数）
function typeBadgeStyle(type: string): React.CSSProperties {
  return {
    padding: '3px 8px',
    borderRadius: 6,
    fontSize: '0.72rem',
    fontWeight: 600,
    background: type === 'WORKSPACE' ? C.accentBg : '#f1f5f9',
    color: type === 'WORKSPACE' ? C.accent : C.muted,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  };
}
