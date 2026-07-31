import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createDir, createFile, deleteNode, FileNode, moveNode, tree as fetchTree, uploadFiles } from '../api/workspace';

interface Props {
  agentId: string;
  selectedPath: string | null;
  selectedType?: FileNode['type'] | null;
  onSelect: (path: string | null, type?: FileNode['type'] | null) => void;
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
  actions: {
    padding: '7px 10px', borderBottom: '1px solid #f1f5f9', display: 'grid',
    gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 5,
  },
  uploadHint: {
    padding: '6px 10px', borderBottom: '1px solid #f1f5f9',
    fontSize: '0.7rem', color: '#64748b', lineHeight: 1.45,
  },
  actionBtn: {
    minWidth: 0, border: '1px solid #e2e8f0', borderRadius: 6, background: '#ffffff',
    color: '#475569', padding: '5px 4px', cursor: 'pointer', fontSize: '0.7rem',
    fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
  },
  actionDanger: { color: '#b91c1c', borderColor: '#fecaca', background: '#fff7f7' },
  selection: {
    padding: '6px 10px', borderBottom: '1px solid #f1f5f9', color: '#475569',
    fontSize: '0.7rem', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
  },
  notice: {
    padding: '8px 10px', borderBottom: '1px solid #bbf7d0', background: '#f0fdf4',
    color: '#166534', fontSize: '0.74rem', lineHeight: 1.45,
  },
  dialogBackdrop: {
    position: 'fixed', inset: 0, zIndex: 50, display: 'flex', alignItems: 'center',
    justifyContent: 'center', padding: 16, background: 'rgba(15, 23, 42, 0.34)',
  },
  dialog: {
    width: 'min(420px, calc(100vw - 32px))', background: '#ffffff', border: '1px solid #cbd5e1',
    borderRadius: 8, boxShadow: '0 18px 48px rgba(15, 23, 42, 0.24)', padding: 20,
  },
  dialogTitle: { margin: 0, color: '#0f172a', fontSize: '1rem', fontWeight: 700 },
  dialogDescription: { margin: '7px 0 16px', color: '#64748b', fontSize: '0.8rem', lineHeight: 1.55 },
  dialogLabel: { display: 'block', marginBottom: 6, color: '#334155', fontSize: '0.78rem', fontWeight: 600 },
  dialogInput: {
    width: '100%', boxSizing: 'border-box', border: '1px solid #94a3b8', borderRadius: 6,
    padding: '8px 9px', color: '#0f172a', background: '#ffffff', fontSize: '0.88rem', outline: 'none',
  },
  dialogSelected: {
    marginTop: 10, padding: '8px 9px', background: '#eff6ff', border: '1px solid #bfdbfe',
    borderRadius: 6, color: '#1e40af', fontSize: '0.76rem', lineHeight: 1.45,
  },
  dialogActions: { display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 },
  dialogSecondaryBtn: {
    border: '1px solid #cbd5e1', borderRadius: 6, padding: '7px 11px', background: '#ffffff',
    color: '#334155', cursor: 'pointer', fontSize: '0.8rem', fontWeight: 600,
  },
  dialogPrimaryBtn: {
    border: '1px solid #1d4ed8', borderRadius: 6, padding: '7px 11px', background: '#2563eb',
    color: '#ffffff', cursor: 'pointer', fontSize: '0.8rem', fontWeight: 700,
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

const INTERNAL_BASENAMES = new Set(['_install.meta.json', 'activity.jsonl']);

function isHiddenName(name: string): boolean {
  return name.startsWith('.') || INTERNAL_BASENAMES.has(name) || /^activity-\d+\.jsonl$/.test(name);
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

function parentPath(path: string | null): string {
  if (!path || !path.includes('/')) return '';
  return path.slice(0, path.lastIndexOf('/'));
}

function sortNodes(nodes: FileNode[]): FileNode[] {
  return [...nodes].sort((a, b) => {
    if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
}

function mergeUploadedNodes(nodes: FileNode[], uploaded: FileNode[]): FileNode[] {
  let merged = nodes;
  for (const file of uploaded) {
    const parts = file.path.split('/').filter(Boolean);
    if (parts.length === 0) continue;
    const insert = (siblings: FileNode[], index: number, parent = ''): FileNode[] => {
      const name = parts[index];
      const path = parent ? `${parent}/${name}` : name;
      const existing = siblings.find(node => node.path === path);
      if (index === parts.length - 1) {
        const next = siblings.filter(node => node.path !== path);
        return sortNodes([...next, { ...file, children: undefined }]);
      }
      const directory: FileNode = existing?.type === 'dir'
        ? { ...existing, children: insert(existing.children ?? [], index + 1, path) }
        : { name, path, type: 'dir', children: insert([], index + 1, path) };
      return sortNodes([...siblings.filter(node => node.path !== path), directory]);
    };
    merged = insert(merged, 0);
  }
  return merged;
}

type DialogKind = 'create-file' | 'create-dir' | 'rename' | 'upload' | 'delete';

interface UploadCandidate {
  file: File;
  relativePath: string;
}

interface WorkspaceDialogState {
  kind: DialogKind;
  files?: UploadCandidate[];
}

const DIALOG_COPY: Record<DialogKind, { title: string; description: string; label: string; action: string }> = {
  'create-file': {
    title: '新建文件', description: '请输入工作区内的相对路径。', label: '文件路径', action: '新建文件',
  },
  'create-dir': {
    title: '新建文件夹', description: '请输入工作区内的相对路径。', label: '文件夹路径', action: '新建文件夹',
  },
  rename: {
    title: '重命名', description: '只修改当前文件或文件夹的名称。', label: '新名称', action: '保存名称',
  },
  upload: {
    title: '上传到工作区', description: '文件将写入当前 Agent 的隔离工作区，并同步到运行时沙箱。', label: '目标目录', action: '开始上传',
  },
  delete: {
    title: '删除工作区项目', description: '删除后会同步到运行时沙箱与快照，此操作不可撤销。', label: '即将删除', action: '删除',
  },
};

interface WorkspaceDialogProps {
  dialog: WorkspaceDialogState;
  value: string;
  busy: boolean;
  onValueChange: (value: string) => void;
  onCancel: () => void;
  onConfirm: () => void;
}

function WorkspaceDialog({ dialog, value, busy, onValueChange, onCancel, onConfirm }: WorkspaceDialogProps) {
  const copy = DIALOG_COPY[dialog.kind];
  const selectedCount = dialog.files?.length ?? 0;
  return (
    <div style={S.dialogBackdrop} onMouseDown={event => { if (event.target === event.currentTarget && !busy) onCancel(); }}>
      <form
        style={S.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="workspace-dialog-title"
        onSubmit={event => { event.preventDefault(); onConfirm(); }}
      >
        <h2 id="workspace-dialog-title" style={S.dialogTitle}>{copy.title}</h2>
        <p style={S.dialogDescription}>{copy.description}</p>
        <label style={S.dialogLabel} htmlFor="workspace-dialog-path">{copy.label}</label>
        {dialog.kind === 'delete' ? (
          <div style={S.dialogSelected}>{value}</div>
        ) : (
          <input
            id="workspace-dialog-path"
            style={S.dialogInput}
            value={value}
            onChange={event => onValueChange(event.target.value)}
            disabled={busy}
            autoFocus
          />
        )}
        {dialog.kind === 'upload' && (
          <div style={S.dialogSelected}>已选择 {selectedCount} 个文件，保留原有文件夹层级。</div>
        )}
        <div style={S.dialogActions}>
          <button type="button" style={S.dialogSecondaryBtn} disabled={busy} onClick={onCancel}>取消</button>
          <button type="submit" style={{ ...S.dialogPrimaryBtn, ...(dialog.kind === 'delete' ? S.actionDanger : {}), ...(busy ? { opacity: 0.65, cursor: 'wait' } : {}) }} disabled={busy}>
            {busy ? '处理中…' : copy.action}
          </button>
        </div>
      </form>
    </div>
  );
}

interface NodeViewProps {
  node: FileNode;
  depth: number;
  selectedPath: string | null;
  onSelect: (path: string, type: FileNode['type']) => void;
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
    onSelect(node.path, node.type);
    if (isDir) toggle(node.path);
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

export default function WorkspaceFileTree({ agentId, selectedPath, selectedType, onSelect, refreshKey, onRefresh, sessionKey, emptyNotSynced }: Props) {
  const [nodes, setNodes] = useState<FileNode[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [showHidden, setShowHidden] = useState(false);
  const [loading, setLoading] = useState(false);
  const [mutating, setMutating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [dialog, setDialog] = useState<WorkspaceDialogState | null>(null);
  const [dialogValue, setDialogValue] = useState('');
  const uploadInputRef = useRef<HTMLInputElement | null>(null);
  const uploadFolderInputRef = useRef<HTMLInputElement | null>(null);

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

  function openDialog(next: WorkspaceDialogState, initialValue: string) {
    setDialog(next);
    setDialogValue(initialValue);
  }

  function targetDirectory(): string {
    return selectedPath && selectedType === 'dir' ? selectedPath : parentPath(selectedPath);
  }

  function openCreate(kind: 'create-file' | 'create-dir') {
    const directory = targetDirectory();
    openDialog({ kind }, directory ? `${directory}/` : '');
  }

  function openRename() {
    if (!selectedPath) return;
    const slash = selectedPath.lastIndexOf('/');
    openDialog({ kind: 'rename' }, slash >= 0 ? selectedPath.slice(slash + 1) : selectedPath);
  }

  function openUpload(files: UploadCandidate[]) {
    if (files.length === 0) return;
    openDialog({ kind: 'upload', files }, targetDirectory() || 'knowledge');
  }

  function chooseFilesFromComputer() {
    if (!uploadInputRef.current) return;
    // Selecting the same file after cancelling the previous upload does not fire `change` unless
    // the native input is cleared first. Resetting it here makes every button press deterministic.
    uploadInputRef.current.value = '';
    uploadInputRef.current.click();
  }

  function openDelete() {
    if (!selectedPath) return;
    openDialog({ kind: 'delete' }, selectedPath);
  }

  function chooseFolderFromComputer() {
    // File System Access API displays a browser permission prompt naming the current origin
    // (for example, "localhost wants to view files"). The native directory input is sufficient
    // here, preserves relative paths, and avoids that extra permission interruption.
    if (!uploadFolderInputRef.current) return;
    uploadFolderInputRef.current.value = '';
    uploadFolderInputRef.current.click();
  }

  async function uploadToDirectory(files: UploadCandidate[], rootDir: string) {
    const prepared = files.map(({ file, relativePath }) => {
      const slash = relativePath.lastIndexOf('/');
      const nestedDir = slash >= 0 ? relativePath.slice(0, slash) : '';
      const destination = [rootDir, nestedDir].filter(Boolean).join('/');
      return { file, path: `${destination}/${file.name}` };
    });
    const result = await uploadFiles(agentId, prepared, sessionKey);
    const lastUploaded = result.uploaded[result.uploaded.length - 1];
    if (lastUploaded) onSelect(lastUploaded.path, 'file');
    // Render confirmed files immediately. Re-fetching the tree right after a sandbox write used
    // to race an audit-log mirror and could replace this state with an empty list until manual
    // refresh; navigation still revalidates the server state.
    if (result.uploaded.length > 0) {
      setNodes(nodes => mergeUploadedNodes(nodes, result.uploaded));
      setExpanded(current => {
        const next = new Set(current);
        for (const file of result.uploaded) {
          for (const parent of parentPaths(file.path)) next.add(parent);
        }
        return next;
      });
    }
    if (result.failed.length > 0) {
      setErr(`已上传 ${result.uploaded.length} 个文件；${result.failed.length} 个失败：${result.failed.slice(0, 3).map(item => `${item.path}: ${item.message}`).join('；')}`);
    } else {
      setNotice(`已上传 ${result.uploaded.length} 个文件，已同步到工作区。`);
    }
  }

  async function submitDialog() {
    if (!dialog) return;
    const value = dialogValue.trim().replace(/^\/+|\/+$/g, '');
    if (!value) {
      setErr(dialog.kind === 'rename' ? '请输入新名称。' : '请输入工作区相对路径。');
      return;
    }
    setMutating(true);
    setErr(null);
    setNotice(null);
    try {
      if (dialog.kind === 'create-file' || dialog.kind === 'create-dir') {
        const created = dialog.kind === 'create-file'
          ? await createFile(agentId, value, sessionKey)
          : await createDir(agentId, value, sessionKey);
        onSelect(created.path, created.type);
        onRefresh?.();
        setNotice(created.type === 'dir' ? '文件夹已创建。' : '文件已创建。');
      } else if (dialog.kind === 'rename') {
        if (!selectedPath) return;
        const parent = parentPath(selectedPath);
        const target = parent ? `${parent}/${value}` : value;
        if (target === selectedPath) {
          setDialog(null);
          return;
        }
        const moved = await moveNode(agentId, selectedPath, target, sessionKey);
        onSelect(moved.path, moved.type);
        onRefresh?.();
        setNotice('已重命名。');
      } else if (dialog.kind === 'delete') {
        if (!selectedPath) return;
        await deleteNode(agentId, selectedPath, sessionKey);
        onSelect(null, null);
        onRefresh?.();
        setNotice('已删除并同步工作区。');
      } else if (dialog.files) {
        await uploadToDirectory(dialog.files, value);
      }
      setDialog(null);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : '工作区操作失败');
    } finally {
      setMutating(false);
      if (uploadInputRef.current) uploadInputRef.current.value = '';
      if (uploadFolderInputRef.current) uploadFolderInputRef.current.value = '';
    }
  }

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
      <div style={S.actions}>
        <button type="button" style={S.actionBtn} onClick={() => openCreate('create-file')} disabled={mutating} title="新建文件">+ 文件</button>
        <button type="button" style={S.actionBtn} onClick={() => openCreate('create-dir')} disabled={mutating} title="新建文件夹">+ 文件夹</button>
        <button type="button" style={S.actionBtn} onClick={chooseFilesFromComputer} disabled={mutating} title="从电脑选择文件上传">上传文件</button>
        <button type="button" style={S.actionBtn} onClick={chooseFolderFromComputer} disabled={mutating} title="从电脑选择文件夹上传">上传文件夹</button>
        <button type="button" style={{ ...S.actionBtn, ...(!selectedPath ? { opacity: 0.45, cursor: 'default' } : {}) }} onClick={openRename} disabled={mutating || !selectedPath} title="重命名选中文件">重命名</button>
        <button type="button" style={{ ...S.actionBtn, ...S.actionDanger, ...(!selectedPath ? { opacity: 0.45, cursor: 'default' } : {}) }} onClick={openDelete} disabled={mutating || !selectedPath} title="删除选中的文件或文件夹">删除</button>
        <input
          ref={uploadInputRef}
          type="file"
          hidden
          multiple
          onChange={event => {
            const files = Array.from(event.target.files ?? []);
            openUpload(files.map(file => ({ file, relativePath: file.name })));
          }}
        />
        <input
          ref={element => {
            uploadFolderInputRef.current = element;
            if (element) element.setAttribute('webkitdirectory', '');
          }}
          type="file"
          hidden
          multiple
          onChange={event => {
            const files = Array.from(event.target.files ?? []);
            openUpload(files.map(file => ({
              file,
              relativePath: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name,
            })));
          }}
        />
      </div>
      <div style={S.uploadHint}>
        可从电脑选择多个文件或整个文件夹；文件夹会保留层级。空文件夹请使用“+ 文件夹”。
      </div>
      {selectedPath && <div style={S.selection}>已选：{selectedPath}{selectedType === 'dir' ? '（文件夹）' : ''}</div>}
      {notice && <div style={S.notice}>{notice}</div>}
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
      {dialog && (
        <WorkspaceDialog
          dialog={dialog}
          value={dialogValue}
          busy={mutating}
          onValueChange={setDialogValue}
          onCancel={() => setDialog(null)}
          onConfirm={() => void submitDialog()}
        />
      )}
    </div>
  );
}
