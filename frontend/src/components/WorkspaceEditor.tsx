import React, { useEffect, useMemo, useState } from 'react';
import { readFile } from '../api/workspace';
import VegaChart, { parseStoredVegaLiteArtifact } from './VegaChart';

interface Props {
  agentId: string;
  path: string | null;
  refreshKey?: number;
  sessionKey?: string;
}

const S: Record<string, React.CSSProperties> = {
  root: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0, background: '#ffffff' },
  bar: {
    height: 48, padding: '0 18px', display: 'flex', alignItems: 'center', gap: 12,
    borderBottom: '1px solid #e2e8f0', background: '#ffffff', flexShrink: 0,
  },
  pathTxt: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88rem', color: '#3730a3', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 500 },
  readonlyBadge: {
    background: '#f1f5f9', color: '#64748b',
    border: '1px solid #e2e8f0', borderRadius: 6,
    padding: '3px 9px', fontSize: '0.72rem', fontWeight: 600,
    textTransform: 'uppercase', letterSpacing: '0.06em',
  },
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
};

const BINARY_EXT = /\.(png|jpe?g|gif|bmp|ico|webp|tiff?|heic|avif|pdf|docx?|xlsx?|pptx?|odt|ods|odp|zip|tar|t?gz|tbz2?|bz2|xz|7z|rar|jar|war|ear|class|exe|dll|so|dylib|a|o|bin|dat|sqlite3?|db|mdb|pyc|pyo|wasm|mp3|mp4|m4a|wav|flac|ogg|opus|aac|avi|mov|mkv|webm|wmv|ttf|otf|woff2?|eot)$/i;

export default function WorkspaceEditor({ agentId, path, refreshKey, sessionKey }: Props) {
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [softWrap, setSoftWrap] = useState(false);

  const viewable = !!path && !BINARY_EXT.test(path);
  const chartArtifact = useMemo(
    () => (path?.endsWith('.vl.json') ? parseStoredVegaLiteArtifact(content) : null),
    [content, path],
  );

  useEffect(() => {
    if (!path) {
      setContent('');
      setErr(null);
      return;
    }
    if (!viewable) {
      setContent('');
      setErr('二进制文件暂不支持浏览器预览，请通过本地镜像查看。');
      return;
    }
    setLoading(true);
    setErr(null);
    readFile(agentId, path, sessionKey)
      .then(text => setContent(text))
      .catch(e => setErr(e instanceof Error ? e.message : '读取失败'))
      .finally(() => setLoading(false));
  }, [agentId, path, viewable, refreshKey, sessionKey]);

  if (!path) {
    return <div style={S.root}><div style={S.empty}>从文件树中选择一个文件查看。</div></div>;
  }

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <span style={S.pathTxt}>{path}</span>
        <span style={S.readonlyBadge}>只读</span>
        {err && <span style={{ ...S.status, ...S.err }}>{err}</span>}
        <button
          type="button"
          style={{ ...S.wrapToggle, ...(softWrap ? S.wrapToggleActive : {}) }}
          onClick={() => setSoftWrap(w => !w)}
          title={softWrap ? '自动换行已开启' : '不换行，长行水平滚动'}
        >
          {softWrap ? '换行: 开' : '换行: 关'}
        </button>
      </div>
      {loading ? (
        <div style={S.empty}>加载中…</div>
      ) : !viewable ? (
        <div style={S.empty}>{err ?? '当前文件不能在浏览器中预览。'}</div>
      ) : chartArtifact ? (
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
          readOnly
          spellCheck={false}
        />
      )}
    </div>
  );
}
