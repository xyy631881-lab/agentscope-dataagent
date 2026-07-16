import React, { useEffect, useMemo, useState } from 'react';
import { FileNode, tree as fetchTree } from '../api/workspace';

interface Props {
  agentId: string;
  selectedPath: string | null;
  onSelect: (path: string) => void;
  refreshKey?: number;
  onRefresh?: () => void;
  sessionKey?: string;
  /** When true, the backend could not reach the agent's sandbox — show a "not synced" hint
   *  instead of silently rendering an empty directory. */
  emptyNotSynced?: boolean;
}

const S: Record<string, React.CSSProperties> = {
  root: {
    width: 264, flexShrink: 0, borderRight: '1px solid #e2e8f0',
    background: '#ffffff', display: 'flex', flexDirection: 'column',
    minHeight: 0,
  },
  header: {
    padding: '14px 14px', borderBottom: '1px solid #f1f5f9',
    display: 'flex', alignItems: 'center', gap: 8,
    fontSize: '0.78rem', color: '#94a3b8', fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: '0.1em',
  },
  subbar: {
    padding: '6px 14px', borderBottom: '1px solid #f1f5f9',
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    fontSize: '0.74rem', color: '#94a3b8',
  },
  miniToggle: {
    display: 'inline-flex', alignItems: 'center', gap: 5,
    background: 'transparent', border: 'none', padding: 0,
    color: '#64748b', cursor: 'pointer', fontSize: '0.74rem',
  },
  refreshBtn: {
    background: '#f8fafc', border: '1px solid #e2e8f0', color: '#475569',
    borderRadius: 7, padding: '4px 9px', cursor: 'pointer',
    fontSize: '0.78rem', fontWeight: 500, lineHeight: 1,
    display: 'inline-flex', alignItems: 'center', gap: 4,
  },
  scroll: { flex: 1, overflowY: 'auto', padding: '8px 6px' },
  row: {
    display: 'flex', alignItems: 'center', gap: 8,
    padding: '7px 10px', cursor: 'pointer', fontSize: '0.9rem',
    color: '#334155', borderRadius: 7, userSelect: 'none',
    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
  },
  rowActive: { background: '#eef2ff', color: '#3730a3', fontWeight: 500 },
  rowHover: { background: '#f8fafc' },
  caret: { width: 12, color: '#94a3b8', flexShrink: 0 },
  err: { padding: 14, fontSize: '0.88rem', color: '#dc2626' },
};

const INTERNAL_BASENAMES = new Set(['_install.meta.json']);

function isHiddenName(name: string): boolean {
  return name.startsWith('.') || INTERNAL_BASENAMES.has(name);
}

function filterTree(nodes: FileNode[]): FileNode[] {
  const out: FileNode[] = [];
  for (const n of nodes) {
    if (isHiddenName(n.name)) continue;
    out.push(n.type === 'dir' && n.children ? { ...n, children: filterTree(n.children) } : n);
  }
  return out;
}

function countAll(nodes: FileNode[]): number {
  let count = 0;
  for (const n of nodes) {
    count += 1;
    if (n.type === 'dir' && n.children) count += countAll(n.children);
  }
  return count;
}

function countHidden(nodes: FileNode[]): number {
  let count = 0;
  for (const n of nodes) {
    if (isHiddenName(n.name)) {
      count += 1;
      if (n.type === 'dir' && n.children) count += countAll(n.children);
    } else if (n.type === 'dir' && n.children) {
      count += countHidden(n.children);
    }
  }
  return count;
}

function parentPaths(path: string | null): string[] {
  if (!path || !path.includes('/')) return [];
  const parts = path.split('/');
  const out: string[] = [];
  for (let i = 1; i < parts.length; i += 1) {
    out.push(parts.slice(0, i).join('/'));
  }
  return out;
}

interface NodeViewProps {
  node: FileNode;
  depth: number;
  selectedPath: string | null;
  onSelect: (path: string) => void;
  expanded: Set<string>;
  toggle: (path: string) => void;
}

function NodeView({ node, depth, selectedPath, onSelect, expanded, toggle }: NodeViewProps) {
  const [hover, setHover] = useState(false);
  const isDir = node.type === 'dir';
  const isOpen = expanded.has(node.path);
  const active = selectedPath === node.path;
  const dimmed = isHiddenName(node.name);

  const handleClick = () => {
    if (isDir) toggle(node.path);
    else onSelect(node.path);
  };

  return (
    <div>
      <div
        style={{
          ...S.row,
          paddingLeft: 8 + depth * 12,
          ...(active ? S.rowActive : hover ? S.rowHover : {}),
          ...(dimmed ? { opacity: 0.55, fontStyle: 'italic' } : {}),
        }}
        onClick={handleClick}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        title={node.path}
      >
        <span style={S.caret}>{isDir ? (isOpen ? '▼' : '▶') : ''}</span>
        <span>{isDir ? '📁' : '📄'}</span>
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{node.name}</span>
      </div>
      {isDir && isOpen && node.children?.map(c => (
        <NodeView
          key={c.path}
          node={c}
          depth={depth + 1}
          selectedPath={selectedPath}
          onSelect={onSelect}
          expanded={expanded}
          toggle={toggle}
        />
      ))}
    </div>
  );
}

export default function WorkspaceFileTree({ agentId, selectedPath, onSelect, refreshKey, onRefresh, sessionKey, emptyNotSynced }: Props) {
  const [nodes, setNodes] = useState<FileNode[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [showHidden, setShowHidden] = useState(false);
  const [loading, setLoading] = useState(false);

  async function reload() {
    setErr(null);
    setLoading(true);
    try {
      const list = await fetchTree(agentId, true, sessionKey);
      setNodes(list);
      setExpanded(prev => {
        const next = new Set(prev);
        if (next.size === 0) {
          for (const n of list) if (n.type === 'dir') next.add(n.path);
        }
        for (const p of parentPaths(selectedPath)) next.add(p);
        return next;
      });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : '加载文件树失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, refreshKey, sessionKey]);

  useEffect(() => {
    if (!selectedPath) return;
    setExpanded(prev => {
      const next = new Set(prev);
      for (const p of parentPaths(selectedPath)) next.add(p);
      return next;
    });
  }, [selectedPath]);

  const toggle = (path: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  const visibleNodes = useMemo(
    () => (showHidden ? nodes : filterTree(nodes)),
    [nodes, showHidden],
  );
  const hiddenCount = useMemo(() => countHidden(nodes), [nodes]);

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span style={{ flex: 1 }}>文件</span>
        <button
          type="button"
          style={S.refreshBtn}
          onClick={() => onRefresh?.()}
          disabled={loading}
          title="刷新文件树"
        >
          {loading ? '...' : '↻'} <span style={{ fontSize: '0.7rem' }}>刷新</span>
        </button>
      </div>
      {hiddenCount > 0 && (
        <div style={S.subbar}>
          <span>{showHidden ? `已显示 ${hiddenCount} 个隐藏项` : `已隐藏 ${hiddenCount} 个内部项`}</span>
          <button
            type="button"
            style={S.miniToggle}
            onClick={() => setShowHidden(s => !s)}
            title={showHidden ? '隐藏内部文件' : '显示全部/点文件'}
          >
            {showHidden ? '隐藏' : '显示全部'}
          </button>
        </div>
      )}
      <div style={S.scroll}>
        {err && <div style={S.err}>{err}</div>}
        {!err && !loading && visibleNodes.length === 0 && (
          emptyNotSynced ? (
            <div style={{ padding: 14, fontSize: '0.88rem', color: '#92400e' }}>
              ⚠️ 工作区尚未同步 — 请先与 agent 发起一次对话，或刷新页面以获取最新文件。
            </div>
          ) : (
            <div style={{ padding: 14, fontSize: '0.88rem', color: '#94a3b8' }}>工作区暂时无文件。</div>
          )
        )}
        {visibleNodes.map(n => (
          <NodeView
            key={n.path}
            node={n}
            depth={0}
            selectedPath={selectedPath}
            onSelect={onSelect}
            expanded={expanded}
            toggle={toggle}
          />
        ))}
      </div>
    </div>
  );
}