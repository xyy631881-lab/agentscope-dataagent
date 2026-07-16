import React, { useEffect, useMemo, useState } from 'react';
import { workspaceFileHref } from './ChatContent';

export type ToolCallStatus = 'running' | 'completed' | 'failed' | 'awaiting_approval' | 'rejected';

export interface ToolCallView {
  id: string;
  name: string;
  input?: string;
  result?: string;
  status?: ToolCallStatus;
}

interface ToolCallBlockProps {
  toolName: string;
  toolCallId: string;
  input?: string;
  result?: string;
  status?: ToolCallStatus;
  compact?: boolean;
  sessionKey?: string | null;
}

interface ExecutionTraceProps {
  tools: ToolCallView[];
  pending?: boolean;
  sessionKey?: string | null;
}

const STATUS: Record<ToolCallStatus, { label: string; color: string; background: string }> = {
  running: { label: '执行中', color: '#2563eb', background: '#eff6ff' },
  completed: { label: '已完成', color: '#15803d', background: '#f0fdf4' },
  failed: { label: '执行失败', color: '#b91c1c', background: '#fef2f2' },
  awaiting_approval: { label: '等待批准', color: '#b45309', background: '#fffbeb' },
  rejected: { label: '已拒绝', color: '#64748b', background: '#f8fafc' },
};

function asText(value?: string): string {
  return value?.trim() ?? '';
}

function pretty(value?: string): string {
  const text = asText(value);
  if (!text) return '';
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

function summary(value?: string): string {
  const text = asText(value).replace(/\s+/g, ' ');
  if (!text) return '';
  return text.length > 88 ? `${text.slice(0, 88)}...` : text;
}

function resolveStatus(status: ToolCallStatus | undefined, result?: string): ToolCallStatus {
  if (status) return status;
  return result ? 'completed' : 'running';
}

function workspacePaths(value?: string): string[] {
  const text = value ?? '';
  const matches = text.match(/(?:plans|artifacts)\/[^\s`'"，。；、)）\]}]+/g) ?? [];
  return [...new Set(matches)];
}

const S: Record<string, React.CSSProperties> = {
  trace: {
    border: '1px solid #dbe4ee', borderRadius: 8, overflow: 'hidden',
    background: '#fbfdff', marginBottom: 12,
  },
  traceHeader: {
    width: '100%', display: 'flex', alignItems: 'center', gap: 8,
    padding: '9px 11px', border: 'none', background: '#f8fafc', cursor: 'pointer',
    color: '#334155', textAlign: 'left', fontSize: '0.82rem', fontWeight: 650,
  },
  traceMeta: { marginLeft: 'auto', color: '#64748b', fontSize: '0.76rem', fontWeight: 500 },
  traceBody: { padding: '4px 8px 8px', display: 'flex', flexDirection: 'column', gap: 4 },
  call: {
    border: '1px solid #e2e8f0', borderRadius: 6, overflow: 'hidden', background: '#ffffff',
  },
  callHeader: {
    width: '100%', display: 'flex', alignItems: 'center', minHeight: 34, gap: 8,
    padding: '7px 9px', border: 'none', background: '#ffffff', cursor: 'pointer',
    color: '#334155', textAlign: 'left', fontSize: '0.8rem',
  },
  chevron: { color: '#94a3b8', width: 12, fontSize: '0.65rem' },
  dot: { width: 7, height: 7, borderRadius: '50%', flexShrink: 0 },
  name: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontWeight: 650, color: '#1e293b' },
  state: { marginLeft: 'auto', padding: '2px 6px', borderRadius: 4, fontSize: '0.7rem', fontWeight: 600, flexShrink: 0 },
  callSummary: {
    color: '#64748b', fontSize: '0.75rem', overflow: 'hidden', textOverflow: 'ellipsis',
    whiteSpace: 'nowrap', minWidth: 0, flex: 1,
  },
  detail: { borderTop: '1px solid #eef2f7', padding: '9px 10px 10px', display: 'grid', gap: 8 },
  detailLabel: { fontSize: '0.7rem', fontWeight: 700, color: '#64748b', letterSpacing: '0.04em' },
  code: {
    margin: '4px 0 0', maxHeight: 240, overflow: 'auto', padding: '8px 9px',
    borderRadius: 5, background: '#f8fafc', color: '#334155', border: '1px solid #edf2f7',
    whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: '0.75rem', lineHeight: 1.5,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
  empty: { color: '#94a3b8', fontSize: '0.76rem' },
  fileLink: { display: 'inline-flex', width: 'fit-content', color: '#1d4ed8', fontSize: '0.76rem', fontWeight: 650 },
};

export function ExecutionTrace({ tools, pending, sessionKey }: ExecutionTraceProps) {
  const hasActiveCall = pending || tools.some(t => {
    const state = resolveStatus(t.status, t.result);
    return state === 'running' || state === 'awaiting_approval';
  });
  const [open, setOpen] = useState(hasActiveCall);

  useEffect(() => {
    if (hasActiveCall) setOpen(true);
  }, [hasActiveCall]);

  const completed = tools.filter(t => resolveStatus(t.status, t.result) === 'completed').length;
  const waiting = tools.filter(t => resolveStatus(t.status, t.result) === 'awaiting_approval').length;
  const meta = waiting > 0
    ? `${waiting} 项待批准`
    : hasActiveCall
      ? '处理中'
      : `${completed}/${tools.length} 已完成`;

  return (
    <section style={S.trace} aria-label="工具执行轨迹">
      <button type="button" style={S.traceHeader} onClick={() => setOpen(value => !value)} aria-expanded={open}>
        <span style={S.chevron}>{open ? '▾' : '▸'}</span>
        <span>执行轨迹</span>
        <span style={S.traceMeta}>{meta}</span>
      </button>
      {open && (
        <div style={S.traceBody}>
          {tools.map(tool => (
            <ToolCallBlock
              key={tool.id}
              toolName={tool.name}
              toolCallId={tool.id}
              input={tool.input}
              result={tool.result}
              status={tool.status}
              compact
              sessionKey={sessionKey}
            />
          ))}
        </div>
      )}
    </section>
  );
}

export default function ToolCallBlock({ toolName, toolCallId, input, result, status, compact, sessionKey }: ToolCallBlockProps) {
  const resolved = resolveStatus(status, result);
  const state = STATUS[resolved];
  const [open, setOpen] = useState(resolved === 'running' || resolved === 'awaiting_approval');
  const resultSummary = useMemo(() => summary(result), [result]);

  useEffect(() => {
    if (resolved === 'running' || resolved === 'awaiting_approval') setOpen(true);
  }, [resolved]);

  const linkedPaths = workspacePaths(result);
  return (
    <article style={{ ...S.call, ...(compact ? {} : { margin: '0.5rem 0' }) }}>
      <button type="button" style={S.callHeader} onClick={() => setOpen(value => !value)} aria-expanded={open}>
        <span style={S.chevron}>{open ? '▾' : '▸'}</span>
        <span style={{ ...S.dot, background: state.color }} />
        <span style={S.name}>{toolName}</span>
        {!open && resultSummary && <span style={S.callSummary}>{resultSummary}</span>}
        <span style={{ ...S.state, color: state.color, background: state.background }}>{state.label}</span>
      </button>
      {open && (
        <div style={S.detail}>
          {toolCallId && (
            <div>
              <div style={S.detailLabel}>调用 ID</div>
              <code style={{ ...S.code, display: 'block', maxHeight: 60 }}>{toolCallId}</code>
            </div>
          )}
          {input && (
            <div>
              <div style={S.detailLabel}>输入参数</div>
              <pre style={S.code}>{pretty(input)}</pre>
            </div>
          )}
          {result && (
            <div>
              <div style={S.detailLabel}>执行结果</div>
              <pre style={S.code}>{pretty(result)}</pre>
              {linkedPaths.map(path => (
                <a key={path} href={workspaceFileHref(path, sessionKey)} style={S.fileLink}>打开 {path}</a>
              ))}
            </div>
          )}
          {!result && resolved === 'running' && <div style={S.empty}>正在等待工具返回结果。</div>}
          {!result && resolved === 'awaiting_approval' && <div style={S.empty}>此操作需要人工批准后才会继续。</div>}
          {!result && resolved === 'rejected' && <div style={S.empty}>该工具调用未获批准。</div>}
        </div>
      )}
    </article>
  );
}
