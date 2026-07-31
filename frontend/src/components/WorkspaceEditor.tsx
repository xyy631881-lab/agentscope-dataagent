import React, { useEffect, useMemo, useState } from 'react';
import { FileNode, readFile, writeFile } from '../api/workspace';
import VegaChart, { parseStoredVegaLiteArtifact } from './VegaChart';

interface Props {
  agentId: string;
  path: string | null;
  pathType?: FileNode['type'] | null;
  refreshKey?: number;
  sessionKey?: string;
  onChanged?: (selectedPath: string | null) => void;
}

const S: Record<string, React.CSSProperties> = {
  root: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0, background: '#ffffff' },
  bar: {
    height: 48, padding: '0 18px', display: 'flex', alignItems: 'center', gap: 12,
    borderBottom: '1px solid #e2e8f0', background: '#ffffff', flexShrink: 0,
  },
  pathTxt: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88rem', color: '#3730a3', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 },
  textarea: {
    flex: 1, padding: '20px 24px', boxSizing: 'border-box',
    background: '#fcfcfd', border: 'none', outline: 'none',
    color: '#0f172a', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    fontSize: '0.92rem', lineHeight: 1.6, resize: 'none', tabSize: 2,
    overflow: 'auto',
  },
  wrapToggle: {
    background: '#f8fafc', border: '1px solid #e2e8f0', color: '#475569',
    borderRadius: 6, padding: '3px 9px', cursor: 'pointer',
    fontSize: '0.75rem', fontWeight: 500,
  },
  wrapToggleActive: {
    background: '#eef2ff', borderColor: '#c7d2fe', color: '#4338ca',
  },
  empty: { padding: 60, color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  status: { fontSize: '0.82rem', color: '#94a3b8' },
  err: { color: '#dc2626' },
  action: {
    border: '1px solid #cbd5e1', borderRadius: 6, padding: '5px 10px',
    background: '#ffffff', color: '#334155', cursor: 'pointer', fontSize: '0.78rem',
    fontWeight: 600,
  },
  primary: { background: '#2563eb', color: '#ffffff', borderColor: '#2563eb' },
  danger: { color: '#b91c1c', borderColor: '#fecaca', background: '#fff7f7' },
};

const BINARY_EXT = /\.(png|jpe?g|gif|bmp|ico|webp|tiff?|heic|avif|pdf|docx?|xlsx?|pptx?|odt|ods|odp|zip|tar|t?gz|tbz2?|bz2|xz|7z|rar|jar|war|ear|class|exe|dll|so|dylib|a|o|bin|dat|sqlite3?|db|mdb|pyc|pyo|wasm|mp3|mp4|m4a|wav|flac|ogg|opus|aac|avi|mov|mkv|webm|wmv|ttf|otf|woff2?|eot)$/i;

export default function WorkspaceEditor({ agentId, path, pathType, refreshKey, sessionKey, onChanged }: Props) {
  const [content, setContent] = useState('');
  const [loadedContent, setLoadedContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [softWrap, setSoftWrap] = useState(false);
  const [showChartSource, setShowChartSource] = useState(false);

  const isDirectory = pathType === 'dir';
  const viewable = !!path && !isDirectory && !BINARY_EXT.test(path);
  const chartArtifact = useMemo(
    () => (path?.endsWith('.vl.json') ? parseStoredVegaLiteArtifact(content) : null),
    [content, path],
  );
  const dirty = viewable && content !== loadedContent;

  useEffect(() => {
    if (!path) {
      setContent('');
      setLoadedContent('');
      setErr(null);
      setStatus(null);
      return;
    }
    setShowChartSource(false);
    setStatus(null);
    if (!viewable) {
      setContent('');
      setLoadedContent('');
      setErr('二进制文件暂不支持浏览器预览，请通过本地镜像查看。');
      return;
    }
    setLoading(true);
    setErr(null);
    readFile(agentId, path, sessionKey)
      .then(text => {
        setContent(text);
        setLoadedContent(text);
      })
      .catch(e => setErr(e instanceof Error ? e.message : '读取失败'))
      .finally(() => setLoading(false));
  }, [agentId, path, viewable, refreshKey, sessionKey]);

  async function save() {
    if (!path || !viewable || !dirty || saving) return;
    setSaving(true);
    setErr(null);
    setStatus(null);
    try {
      await writeFile(agentId, path, content, sessionKey);
      setLoadedContent(content);
      setStatus('已保存并同步');
      onChanged?.(path);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  }

  if (!path) {
    return <div style={S.root}><div style={S.empty}>从文件树中选择一个文件查看。</div></div>;
  }

  if (isDirectory) {
    return (
      <div style={S.root}>
        <div style={S.bar}><span style={S.pathTxt}>{path}</span></div>
        <div style={S.empty}>已选中文件夹。可在左侧直接新建文件、上传、重命名或删除。</div>
      </div>
    );
  }

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <span style={S.pathTxt}>{path}</span>
        {dirty && <span style={{ ...S.status, color: '#b45309' }}>未保存</span>}
        {status && <span style={{ ...S.status, color: '#15803d' }}>{status}</span>}
        {err && <span style={{ ...S.status, ...S.err }}>{err}</span>}
        {chartArtifact && (
          <button
            type="button"
            style={{ ...S.wrapToggle, ...(showChartSource ? S.wrapToggleActive : {}) }}
            onClick={() => setShowChartSource(value => !value)}
            title={showChartSource ? '查看图表预览' : '编辑图表源文件'}
          >
            {showChartSource ? '预览' : '源码'}
          </button>
        )}
        <button
          type="button"
          style={{ ...S.wrapToggle, ...(softWrap ? S.wrapToggleActive : {}) }}
          onClick={() => setSoftWrap(w => !w)}
          title={softWrap ? '自动换行已开启' : '不换行，长行水平滚动'}
        >
          {softWrap ? '换行: 开' : '换行: 关'}
        </button>
        <button
          type="button"
          style={{ ...S.action, ...S.primary, ...(!dirty || saving ? { opacity: 0.5, cursor: 'default' } : {}) }}
          onClick={save}
          disabled={!dirty || saving || !viewable}
          title="保存文件并同步工作区"
        >
          {saving ? '保存中...' : '保存'}
        </button>
      </div>
      {loading ? (
        <div style={S.empty}>加载中…</div>
      ) : !viewable ? (
        <div style={S.empty}>{err ?? '当前文件不能在浏览器中预览。'}</div>
      ) : chartArtifact && !showChartSource ? (
        <div style={{ padding: 20, overflow: 'auto', background: '#fcfcfd' }}>
          <VegaChart artifact={chartArtifact} />
        </div>
      ) : (
        <textarea
          wrap={softWrap ? 'soft' : 'off'}
          style={{
            ...S.textarea,
            whiteSpace: softWrap ? 'pre-wrap' : 'pre',
            overflowWrap: softWrap ? 'break-word' : 'normal',
            wordBreak: 'normal',
          }}
          value={content}
          onChange={event => {
            setContent(event.target.value);
            setStatus(null);
          }}
          onKeyDown={event => {
            if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
              event.preventDefault();
              void save();
            }
          }}
          spellCheck={false}
        />
      )}
    </div>
  );
}
