import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useOutletContext } from 'react-router-dom';
import {
  listMyContributions,
  submitFromWorkspace,
  type Contribution,
  type ContributionTargetType,
} from '../api/contributions';
import { listAgents, type AgentDefinition } from '../api/agents';
import { tree as fetchTree, type FileNode } from '../api/workspace';
import type { ShellOutletContext } from '../components/EditTierGate';

const S: Record<string, React.CSSProperties> = {
  page: {
    padding: '20px 24px',
    display: 'flex',
    flexDirection: 'column',
    gap: 16,
    height: '100%',
    overflow: 'auto',
  },
  header: { display: 'flex', alignItems: 'center', gap: 12 },
  h1: { fontSize: '1.05rem', fontWeight: 600, color: '#0f172a' },
  sub: { fontSize: '0.78rem', color: '#64748b' },
  backBtn: {
    border: '1px solid #cbd5e1', background: '#ffffff', color: '#334155', borderRadius: 6,
    padding: '6px 10px', cursor: 'pointer', fontSize: '0.78rem', fontWeight: 600,
  },
  panel: { background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 8, padding: 16 },
  panelTitle: { fontSize: '0.85rem', fontWeight: 600, color: '#1e293b', marginBottom: 10 },
  scopeHint: {
    marginTop: 8, padding: '9px 10px', borderRadius: 6,
    background: '#eff6ff', border: '1px solid #bfdbfe', color: '#1e40af',
    fontSize: '0.76rem', lineHeight: 1.55,
  },
  submitGrid: {
    display: 'grid',
    gridTemplateColumns: 'minmax(280px, 1fr) minmax(320px, 1fr)',
    gap: 16,
  },
  treeBox: {
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 6,
    maxHeight: 360,
    overflowY: 'auto',
    padding: '6px 4px',
  },
  row: { display: 'flex', gap: 8, marginBottom: 8 },
  label: { fontSize: '0.72rem', color: '#475569', marginBottom: 4, display: 'block' },
  input: {
    width: '100%',
    background: '#ffffff',
    color: '#0f172a',
    border: '1px solid #cbd5e1',
    borderRadius: 6,
    padding: '6px 8px',
    fontSize: '0.8rem',
  },
  btn: {
    background: '#6366f1',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    padding: '6px 14px',
    cursor: 'pointer',
    fontSize: '0.8rem',
    fontWeight: 600,
  },
  btnDisabled: {
    background: '#c7d2fe',
    color: '#64748b',
    cursor: 'not-allowed',
  },
  table: { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.78rem' },
  th: {
    textAlign: 'left' as const,
    padding: '6px 8px',
    borderBottom: '1px solid #e2e8f0',
    color: '#475569',
    fontWeight: 500,
    fontSize: '0.72rem',
    letterSpacing: 0,
  },
  td: {
    padding: '8px',
    borderBottom: '1px solid #f1f5f9',
    color: '#334155',
    verticalAlign: 'top' as const,
  },
  err: { color: '#b91c1c', fontSize: '0.78rem' },
  ok: { color: '#15803d', fontSize: '0.78rem' },
  pill: {
    display: 'inline-block',
    padding: '1px 6px',
    borderRadius: 4,
    background: '#eef2ff',
    color: '#4338ca',
    fontSize: '0.68rem',
    fontWeight: 600,
    marginLeft: 6,
  },
  treeRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '3px 8px',
    fontSize: '0.8rem',
    color: '#334155',
    cursor: 'pointer',
    userSelect: 'none' as const,
  },
};

const TYPES: ContributionTargetType[] = ['skill', 'subagent', 'memory', 'agents_md', 'knowledge'];

function badgeStyle(status: string): React.CSSProperties {
  return {
    display: 'inline-block',
    padding: '2px 8px',
    borderRadius: 4,
    fontSize: '0.7rem',
    fontWeight: 600,
    background:
      status === 'APPROVED' ? '#dcfce7' : status === 'REJECTED' ? '#fee2e2' : '#eef2ff',
    color: status === 'APPROVED' ? '#15803d' : status === 'REJECTED' ? '#b91c1c' : '#4338ca',
  };
}

// Hide internal/dotfile entries from the picker — same convention as WorkspaceFileTree.
const INTERNAL_BASENAMES = new Set(['_install.meta.json']);
function isHiddenName(name: string): boolean {
  return name.startsWith('.') || INTERNAL_BASENAMES.has(name);
}
function filterTree(nodes: FileNode[]): FileNode[] {
  const out: FileNode[] = [];
  for (const n of nodes) {
    if (isHiddenName(n.name)) continue;
    if (n.type === 'dir' && n.children) {
      out.push({ ...n, children: filterTree(n.children) });
    } else {
      out.push(n);
    }
  }
  return out;
}

/** A directory contribution is sent to the API as its leaf files, preserving relative paths. */
function filePathsFor(node: FileNode): string[] {
  if (node.type === 'file') return [node.path];
  return (node.children ?? []).flatMap(filePathsFor);
}

function directorySelectionState(node: FileNode, selected: Set<string>): 'none' | 'partial' | 'all' {
  const paths = filePathsFor(node);
  if (paths.length === 0 || paths.every(path => !selected.has(path))) return 'none';
  return paths.every(path => selected.has(path)) ? 'all' : 'partial';
}

interface Inferred {
  type: ContributionTargetType;
  path: string;
  warning?: string;
}

/**
 * Infer the target type and the canonical {@code targetPath} from the first selected workspace
 * path. Returns null when nothing is selected. The user can override the type via the dropdown
 * and the path via the input box; this just seeds reasonable defaults.
 */
function inferTarget(selected: string[]): Inferred | null {
  if (selected.length === 0) return null;
  const first = selected[0];
  const segs = first.split('/');
  if (first === 'AGENTS.md') {
    if (selected.length > 1) {
      return { type: 'agents_md', path: 'AGENTS.md', warning: 'agents_md 只能提交一个文件。' };
    }
    return { type: 'agents_md', path: 'AGENTS.md' };
  }
  if (segs[0] === 'skills' && segs.length >= 2) {
    const bundle = segs[1];
    const mismatch = selected.some(p => {
      const s = p.split('/');
      return s[0] !== 'skills' || s[1] !== bundle;
    });
    return {
      type: 'skill',
      path: bundle,
      warning: mismatch
        ? `一个 Skill 包的所有文件必须位于 skills/${bundle}/ 下。`
        : undefined,
    };
  }
  if (segs[0] === 'subagents' && segs.length >= 2) {
    return {
      type: 'subagent',
      path: segs.slice(1).join('/'),
      warning: selected.length > 1 ? 'subagent 只能提交一个文件。' : undefined,
    };
  }
  if (segs[0] === 'memory' && segs.length >= 2) {
    return {
      type: 'memory',
      path: segs.slice(1).join('/'),
      warning: selected.length > 1 ? 'memory 只能提交一个文件。' : undefined,
    };
  }
  if (segs[0] === 'knowledge' && segs.length >= 2) {
    return {
      type: 'knowledge',
      path: segs.slice(1).join('/'),
      warning: selected.length > 1 ? 'knowledge 只能提交一个文件。' : undefined,
    };
  }
  return {
    type: 'knowledge',
    path: segs[segs.length - 1],
    warning: `Path "${first}" doesn't match a known root; defaulting to knowledge.`,
  };
}

interface TreeRowProps {
  node: FileNode;
  depth: number;
  expanded: Set<string>;
  selected: Set<string>;
  toggleExpand: (p: string) => void;
  toggleSelectPaths: (paths: string[]) => void;
}

function TreeRow({ node, depth, expanded, selected, toggleExpand, toggleSelectPaths }: TreeRowProps) {
  const isDir = node.type === 'dir';
  const isOpen = expanded.has(node.path);
  const descendantPaths = isDir ? filePathsFor(node) : [node.path];
  const selectionState = isDir ? directorySelectionState(node, selected) : undefined;
  const isSkillBundleDir = isDir && /^skills\/[^/]+$/.test(node.path);
  const hasSkillManifest = !isSkillBundleDir || (node.children ?? []).some(
    child => child.type === 'file' && child.name === 'SKILL.md',
  );
  return (
    <div>
      <div
        style={{ ...S.treeRow, paddingLeft: 8 + depth * 14 }}
        onClick={() => (isDir ? toggleExpand(node.path) : toggleSelectPaths(descendantPaths))}
        title={node.path}
      >
        <button
          type="button"
          aria-label={isOpen ? `收起 ${node.name}` : `展开 ${node.name}`}
          onClick={e => {
            e.stopPropagation();
            if (isDir) toggleExpand(node.path);
          }}
          disabled={!isDir}
          style={{
            width: 16,
            padding: 0,
            border: 'none',
            background: 'transparent',
            color: '#64748b',
            cursor: isDir ? 'pointer' : 'default',
            visibility: isDir ? 'visible' : 'hidden',
          }}
        >
          {isDir ? (isOpen ? '▾' : '▸') : ''}
        </button>
        <input
          type="checkbox"
          checked={isDir ? selectionState === 'all' : selected.has(node.path)}
          ref={input => {
            if (input) input.indeterminate = selectionState === 'partial';
          }}
          disabled={descendantPaths.length === 0}
          aria-label={isDir ? `选择整个文件夹 ${node.path}` : `选择文件 ${node.path}`}
          onChange={() => toggleSelectPaths(descendantPaths)}
          onClick={e => e.stopPropagation()}
          title={isDir ? '选择整个文件夹及其中所有文件' : '选择文件'}
        />
        <span>{isDir ? '📁' : '📄'}</span>
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{node.name}</span>
        {isDir && (
          <span style={{ color: '#94a3b8', fontSize: '0.7rem' }}>
            {descendantPaths.length} 个文件
          </span>
        )}
        {isSkillBundleDir && !hasSkillManifest && (
          <span style={{ color: '#b91c1c', fontSize: '0.7rem' }}>缺少 SKILL.md，不能作为 Skill 提交</span>
        )}
      </div>
      {isDir &&
        isOpen &&
        node.children?.map(c => (
          <TreeRow
            key={c.path}
            node={c}
            depth={depth + 1}
            expanded={expanded}
            selected={selected}
            toggleExpand={toggleExpand}
            toggleSelectPaths={toggleSelectPaths}
          />
        ))}
    </div>
  );
}

export default function ContributionsPage() {
  const ctx = useOutletContext<ShellOutletContext>();
  const navigate = useNavigate();
  const [items, setItems] = useState<Contribution[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [sourceAgentId, setSourceAgentId] = useState(ctx.activeAgentId);
  const [targetAgentId, setTargetAgentId] = useState('');
  const [teamAgents, setTeamAgents] = useState<AgentDefinition[]>([]);
  const [nodes, setNodes] = useState<FileNode[]>([]);
  const [treeErr, setTreeErr] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [selected, setSelected] = useState<Set<string>>(() => new Set());

  const [overrideType, setOverrideType] = useState<ContributionTargetType | ''>('');
  const [overridePath, setOverridePath] = useState('');
  const [rationale, setRationale] = useState('');
  const [submitErr, setSubmitErr] = useState<string | null>(null);
  const [submitOk, setSubmitOk] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      setItems(await listMyContributions());
    } catch (e) {
      setErr(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const reloadTree = useCallback(async (agentId: string) => {
    setTreeErr(null);
    try {
      const list = await fetchTree(agentId, true);
      const visible = filterTree(list);
      setNodes(visible);
      setExpanded(prev => {
        if (prev.size > 0) return prev;
        const next = new Set<string>();
        for (const n of visible) if (n.type === 'dir') next.add(n.path);
        return next;
      });
    } catch (e: unknown) {
      setTreeErr(e instanceof Error ? e.message : 'Failed to load files');
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);
  useEffect(() => {
    listAgents()
      .then(agents => setTeamAgents(agents.filter(agent => agent.scope === 'global')))
      .catch(() => setTeamAgents([]));
  }, []);
  useEffect(() => {
    setSourceAgentId(ctx.activeAgentId);
  }, [ctx.activeAgentId]);
  useEffect(() => {
    reloadTree(sourceAgentId);
    setSelected(new Set());
  }, [sourceAgentId, reloadTree]);

  const toggleExpand = (p: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });
  };
  const toggleSelectPaths = (paths: string[]) => {
    setSelected(prev => {
      const next = new Set(prev);
      const shouldRemove = paths.length > 0 && paths.every(path => next.has(path));
      for (const path of paths) {
        if (shouldRemove) next.delete(path);
        else next.add(path);
      }
      return next;
    });
  };

  const selectedList = useMemo(() => Array.from(selected).sort(), [selected]);
  const inferred = useMemo(() => inferTarget(selectedList), [selectedList]);

  const effectiveType: ContributionTargetType | '' = overrideType || inferred?.type || '';
  const effectivePath = overridePath || inferred?.path || '';

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitErr(null);
    setSubmitOk(null);
    if (selectedList.length === 0) {
      setSubmitErr('请从工作区选择至少一个文件或文件夹。');
      return;
    }
    if (!effectiveType) {
      setSubmitErr('请选择贡献类型。');
      return;
    }
    if (!effectivePath.trim()) {
      setSubmitErr('请填写目标路径。');
      return;
    }
    if (!targetAgentId.trim()) {
      setSubmitErr('请选择目标团队 Agent。私有 Agent 只能向全局团队 Agent 提交贡献。');
      return;
    }
    if (
      effectiveType === 'skill' &&
      !selectedList.some(path => path === 'SKILL.md' || path.endsWith('/SKILL.md'))
    ) {
      setSubmitErr('Skill 文件夹缺少 SKILL.md。请先将技能说明文件上传或新建到该文件夹根目录后再提交。');
      return;
    }
    setSubmitting(true);
    try {
      const created = await submitFromWorkspace({
        sourceAgentId,
        targetAgentId: targetAgentId.trim(),
        targetType: effectiveType,
        targetPath: effectivePath.trim(),
        rationale: rationale.trim() || null,
        sourcePaths: selectedList,
      });
      setSubmitOk(`已提交为 #${created.id}，等待管理员审核。`);
      setSelected(new Set());
      setOverridePath('');
      setOverrideType('');
      setRationale('');
      load();
    } catch (ex) {
      setSubmitErr(ex instanceof Error ? ex.message : String(ex));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={S.page}>
      <div style={S.header}>
        <button
          type="button"
          style={S.backBtn}
          onClick={() => navigate(`/workspace?agent=${encodeURIComponent(ctx.activeAgentId)}`)}
        >
          ← 返回工作区
        </button>
        <div style={S.h1}>贡献</div>
        <div style={S.sub}>
          将工作区中的 Skill 整个文件夹或单个资源提交到全局团队 Agent，需由其他管理员审核。
        </div>
      </div>

      <div style={S.scopeHint}>
        {ctx.agent?.scope === 'global'
          ? <>当前选择的是全局智能体 <code>{ctx.activeAgentId}</code>；此处读取的仍是当前登录用户的隔离工作区投影。批准后，所选文件才会写入目标智能体的团队共享层。</>
          : <>当前选择的是你的私有智能体 <code>{ctx.activeAgentId}</code>；此处提交的是你的私有工作区内容。批准后，所选文件才会写入目标智能体的团队共享层。</>}
      </div>

      <form onSubmit={onSubmit} style={S.panel}>
        <div style={S.panelTitle}>提交新贡献</div>
        <div style={S.submitGrid}>
          <div>
            <div style={S.row}>
              <div style={{ flex: 1 }}>
                <span style={S.label}>源智能体 ID</span>
                <input
                  style={S.input}
                  value={sourceAgentId}
                  onChange={e => setSourceAgentId(e.target.value)}
                  placeholder={ctx.activeAgentId}
                />
              </div>
              <div style={{ flex: 1 }}>
                <span style={S.label}>目标团队智能体 ID</span>
                <select
                  style={{ ...S.input, padding: '5px 8px' }}
                  value={targetAgentId}
                  onChange={e => setTargetAgentId(e.target.value)}
                >
                  <option value="">请选择团队 Agent</option>
                  {teamAgents.map(agent => (
                    <option key={agent.id} value={agent.id}>
                      {agent.name} ({agent.id})
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <span style={S.label}>
              选择内容{' '}
              <span style={S.pill}>已选 {selectedList.length} 项</span>
            </span>
            <div style={{ ...S.sub, marginBottom: 6, lineHeight: 1.5 }}>
              勾选文件夹会一次选中其中所有文件。提交 Skill 时请直接勾选 <code>skills/技能名</code>，会一并保留
              <code>SKILL.md</code>、脚本、模板及子目录结构。
            </div>
            <div style={S.treeBox}>
              {treeErr && <div style={{ ...S.err, padding: 8 }}>{treeErr}</div>}
              {!treeErr && nodes.length === 0 && (
                <div style={{ padding: 8, fontSize: '0.78rem', color: '#64748b' }}>
                  工作区暂无可提交文件。
                </div>
              )}
              {nodes.map(n => (
                <TreeRow
                  key={n.path}
                  node={n}
                  depth={0}
                  expanded={expanded}
                  selected={selected}
                  toggleExpand={toggleExpand}
                  toggleSelectPaths={toggleSelectPaths}
                />
              ))}
            </div>
          </div>

          <div>
            <div style={S.row}>
              <div style={{ flex: 1 }}>
                <span style={S.label}>类型</span>
                <select
                  style={{ ...S.input, padding: '5px 8px' }}
                  value={overrideType || inferred?.type || 'skill'}
                  onChange={e => setOverrideType(e.target.value as ContributionTargetType)}
                >
                  {TYPES.map(t => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </div>
              <div style={{ flex: 2 }}>
                <span style={S.label}>目标路径</span>
                <input
                  style={S.input}
                  value={overridePath || inferred?.path || ''}
                  onChange={e => setOverridePath(e.target.value)}
                  placeholder={
                    (overrideType || inferred?.type) === 'skill'
                      ? '技能包名称，例如 cohort-builder'
                      : (overrideType || inferred?.type) === 'agents_md'
                      ? 'AGENTS.md'
                      : '该类型目录下的文件路径'
                  }
                />
              </div>
            </div>
            <div style={{ marginBottom: 8 }}>
              <span style={S.label}>给审核管理员的说明（可选）</span>
              <input
                style={S.input}
                value={rationale}
                onChange={e => setRationale(e.target.value)}
              />
            </div>
            <div style={{ marginBottom: 8 }}>
              <span style={S.label}>已选文件</span>
              <div
                style={{
                  ...S.input,
                  minHeight: 60,
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  fontSize: '0.74rem',
                  whiteSpace: 'pre-wrap' as const,
                }}
              >
                {selectedList.length === 0 ? '—' : selectedList.join('\n')}
              </div>
            </div>
            {inferred?.warning && (
              <div style={{ ...S.err, marginBottom: 8 }}>{inferred.warning}</div>
            )}
            <button
              type="submit"
              style={{
                ...S.btn,
                ...(submitting || selectedList.length === 0 ? S.btnDisabled : {}),
              }}
              disabled={submitting || selectedList.length === 0}
            >
              {submitting ? '提交中…' : '提交'}
            </button>
            {submitErr && <div style={{ ...S.err, marginTop: 8 }}>{submitErr}</div>}
            {submitOk && <div style={{ ...S.ok, marginTop: 8 }}>{submitOk}</div>}
          </div>
        </div>
      </form>

      <div style={S.panel}>
        <div style={S.panelTitle}>我的提交</div>
        {loading && <div style={S.sub}>加载中…</div>}
        {err && <div style={S.err}>{err}</div>}
        {!loading && !err && items.length === 0 && (
          <div style={S.sub}>您还没有提交任何贡献。</div>
        )}
        {!loading && items.length > 0 && (
          <table style={S.table}>
            <thead>
              <tr>
                <th style={S.th}>#</th>
                <th style={S.th}>状态</th>
                <th style={S.th}>类型</th>
                <th style={S.th}>目标智能体</th>
                <th style={S.th}>路径</th>
                <th style={S.th}>提交时间</th>
                <th style={S.th}>审核备注</th>
              </tr>
            </thead>
            <tbody>
              {items.map(c => (
                <tr key={c.id}>
                  <td style={S.td}>{c.id}</td>
                  <td style={S.td}>
                    <span style={badgeStyle(c.status)}>{c.status}</span>
                  </td>
                  <td style={S.td}>{c.targetType}</td>
                  <td style={S.td}>{c.targetAgentId || c.sourceAgentId || '—'}</td>
                  <td style={S.td}>
                    <code>{c.targetPath}</code>
                  </td>
                  <td style={S.td}>{new Date(c.createdAt).toLocaleString()}</td>
                  <td style={S.td}>{c.reviewerNote || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
