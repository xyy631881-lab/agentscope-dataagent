import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { cancelStream, chatRunStatus, currentSession, stream } from '../api/chat';
import type { ConfirmDecision, PendingToolCall } from '../api/chat';
import { TurnEntry, turns as fetchTurns } from '../api/sessions';
import { ExecutionTrace } from './ToolCallBlock';
import type { ToolCallStatus, ToolCallView } from './ToolCallBlock';
import ChatContent from './ChatContent';
import VegaChart, { extractVegaLiteSpec } from './VegaChart';

type Role = 'user' | 'assistant' | 'system';

interface ToolEntry extends ToolCallView {}

interface Message {
  id: string;
  role: Role;
  text: string;
  tools: ToolEntry[];
  pending?: boolean;
  timestampMs: number;
}

interface PendingConfirmation {
  replyId: string;
  messageId: string;
  sessionKey: string;
  toolCalls: PendingToolCall[];
  ready: boolean;
}

interface ActiveRequest {
  requestId: string;
  controller: AbortController;
  replyId: string;
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: '#f8fafc' },
  thread: { flex: 1, overflowY: 'auto', padding: '24px 32px', display: 'flex', flexDirection: 'column', gap: 16 },
  empty: { color: '#64748b', fontSize: '0.92rem', textAlign: 'center', marginTop: 100 },
  messageRow: { width: '100%', display: 'flex', flexDirection: 'column', gap: 5 },
  messageRowUser: { alignItems: 'flex-end' },
  messageRowAssistant: { alignItems: 'flex-start' },
  messageMeta: { display: 'flex', alignItems: 'center', gap: 8, color: '#94a3b8', fontSize: '0.72rem', padding: '0 3px' },
  actor: { color: '#475569', fontWeight: 650 },
  bubble: {
    width: 'fit-content', maxWidth: 'min(860px, 100%)', padding: '13px 15px', borderRadius: 8,
    fontSize: '0.94rem', lineHeight: 1.65, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
  },
  user: { background: '#1d4ed8', color: '#ffffff', boxShadow: '0 1px 2px rgba(30,64,175,0.22)' },
  assistant: { background: '#ffffff', color: '#0f172a', border: '1px solid #dbe4ee', boxShadow: '0 1px 2px rgba(15,23,42,0.04)' },
  system: { alignSelf: 'center', background: 'transparent', color: '#64748b', fontSize: '0.82rem', fontStyle: 'italic', padding: '4px 0' },
  responseText: { minWidth: 24 },
  waiting: { color: '#64748b', fontSize: '0.85rem' },
  composer: { borderTop: '1px solid #dbe4ee', padding: '14px 28px 18px', display: 'flex', gap: 10, background: '#ffffff' },
  textarea: {
    flex: 1, padding: '11px 13px', background: '#ffffff', border: '1px solid #b8c5d6', borderRadius: 7,
    color: '#0f172a', fontSize: '0.94rem', resize: 'none', minHeight: 46, maxHeight: 180, lineHeight: 1.55,
  },
  send: { minWidth: 76, background: '#1d4ed8', color: '#ffffff', border: 'none', borderRadius: 7, cursor: 'pointer', fontSize: '0.9rem', fontWeight: 650 },
  stop: { minWidth: 76, background: '#b91c1c', color: '#ffffff', border: 'none', borderRadius: 7, cursor: 'pointer', fontSize: '0.9rem', fontWeight: 650 },
  sendDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed' },
  approval: { marginTop: 10, border: '1px solid #fbbf24', borderRadius: 7, background: '#fffbeb', overflow: 'hidden' },
  approvalHeader: { padding: '9px 11px', display: 'flex', alignItems: 'center', gap: 8, borderBottom: '1px solid #fde68a' },
  approvalTitle: { fontWeight: 700, color: '#92400e', fontSize: '0.84rem' },
  approvalHint: { color: '#a16207', fontSize: '0.77rem', marginTop: 2 },
  approvalList: { padding: '8px 11px', display: 'flex', flexDirection: 'column', gap: 5 },
  approvalTool: { display: 'flex', alignItems: 'center', gap: 7, color: '#78350f', fontSize: '0.78rem' },
  approvalCode: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontWeight: 650 },
  approvalInput: { color: '#a16207', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  approvalActions: { padding: '9px 11px 10px', display: 'flex', justifyContent: 'flex-end', gap: 8, borderTop: '1px solid #fde68a' },
  denyBtn: { padding: '6px 12px', borderRadius: 6, cursor: 'pointer', border: '1px solid #cbd5e1', fontSize: '0.8rem', fontWeight: 600, background: '#ffffff', color: '#475569' },
  allowBtn: { padding: '6px 12px', borderRadius: 6, cursor: 'pointer', border: '1px solid #15803d', fontSize: '0.8rem', fontWeight: 650, background: '#15803d', color: '#ffffff' },
  orphanedBanner: { marginTop: 10, padding: '10px 11px', borderRadius: 7, background: '#fef2f2', border: '1px solid #fecaca', display: 'flex', flexDirection: 'column', gap: 8 },
  orphanedTitle: { fontSize: '0.82rem', fontWeight: 700, color: '#b91c1c' },
  orphanedText: { fontSize: '0.78rem', color: '#991b1b', lineHeight: 1.5 },
  orphanedActions: { display: 'flex', gap: 8 },
  resetBtn: { padding: '5px 10px', borderRadius: 6, cursor: 'pointer', border: 'none', background: '#b91c1c', color: '#ffffff', fontSize: '0.78rem', fontWeight: 600 },
  retryBtn: { padding: '5px 10px', borderRadius: 6, cursor: 'pointer', border: '1px solid #fca5a5', background: '#ffffff', color: '#b91c1c', fontSize: '0.78rem', fontWeight: 600 },
};

let counter = 0;
const nextId = () => `m${Date.now().toString(36)}-${counter++}`;
const STORAGE_PREFIX = 'claw_chat_session:';
const HISTORY_STORAGE_PREFIX = 'claw_chat_history:';
const storageKey = (agentId: string) => `${STORAGE_PREFIX}${agentId}`;
const historyStorageKey = (agentId: string, sessionKey: string) => `${HISTORY_STORAGE_PREFIX}${agentId}:${sessionKey}`;

function loadCachedMessages(agentId: string, sessionKey: string): Message[] {
  try {
    const raw = localStorage.getItem(historyStorageKey(agentId, sessionKey));
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    return Array.isArray(parsed) ? parsed as Message[] : [];
  } catch {
    return [];
  }
}

const ORPHANED_CONFIRM_PATTERNS = [
  /To resume.*ConfirmResult/i,
  /agentscope_confirm_results/i,
  /human-in-the-loop confirmation/i,
  /paused for human-in-the-loop/i,
  /cannot proceed.*confirm/i,
  /pending confirmation/i,
];

function isOrphanedConfirmText(text: string): boolean {
  return ORPHANED_CONFIRM_PATTERNS.some(pattern => pattern.test(text));
}

function inputText(input: unknown): string | undefined {
  if (input == null) return undefined;
  if (typeof input === 'string') return input;
  try { return JSON.stringify(input); } catch { return String(input); }
}

function inputPreview(input: unknown): string {
  return (inputText(input) ?? '').replace(/\s+/g, ' ').slice(0, 84);
}

function timeLabel(timestampMs: number): string {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(timestampMs);
}

function failedResult(value?: string): boolean {
  return /^(error|failed|exception|错误|失败)/i.test(value?.trim() ?? '');
}

function turnsToMessages(entries: TurnEntry[]): Message[] {
  const out: Message[] = [];
  for (const turn of entries) {
    const role = String(turn.role).toUpperCase();
    if (role === 'USER') {
      out.push({ id: turn.id, role: 'user', text: turn.content ?? '', tools: [], timestampMs: turn.timestampMs });
    } else if (role === 'ASSISTANT') {
      out.push({ id: turn.id, role: 'assistant', text: turn.content ?? '', tools: [], timestampMs: turn.timestampMs });
    } else if (role === 'TOOL') {
      const host = [...out].reverse().find(message => message.role === 'assistant');
      const result = turn.toolResult ?? undefined;
      const tool: ToolEntry = {
        id: turn.id,
        name: turn.toolName ?? 'tool',
        input: turn.toolInput ?? undefined,
        result,
        status: result ? (failedResult(result) ? 'failed' : 'completed') : 'running',
      };
      if (host) host.tools = [...host.tools, tool];
      else out.push({ id: `${turn.id}-host`, role: 'assistant', text: '', tools: [tool], timestampMs: turn.timestampMs });
    }
  }
  return out;
}

export interface ChatPanelProps {
  agentId: string;
  onSessionUpdate?: () => void;
}

export default function ChatPanel({ agentId, onSessionUpdate }: ChatPanelProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [sessionKey, setSessionKey] = useState<string | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirmation | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const activeRequestRef = useRef<ActiveRequest | null>(null);
  const recoveredRequestIdRef = useRef<string | null>(null);
  const hasLiveTurnRef = useRef(false);
  const pendingConfirmSessionRef = useRef<string | null>(null);
  const restoredRef = useRef<{ agentId: string; sessionKey: string | null } | null>(null);
  const urlSession = searchParams.get('session');

  const persistSession = useCallback((key: string | null) => {
    try {
      if (key) localStorage.setItem(storageKey(agentId), key);
      else localStorage.removeItem(storageKey(agentId));
    } catch { /* local storage is optional */ }
  }, [agentId]);

  const applyRunStatus = useCallback((key: string, status: Awaited<ReturnType<typeof chatRunStatus>>) => {
    recoveredRequestIdRef.current = status.running ? status.requestId ?? null : null;
    const pending = status.pendingConfirmation;
    if (pending) {
      // Rebuilding from /status: the original SSE confirm event was lost during navigation.
      // Always restore the confirmation dialog — never trust pendingConfirmSessionRef for
      // de-duplication because it may carry a stale session ref from before the disconnect.
      const messageId = `pending-confirm:${pending.replyId}`;
      pendingConfirmSessionRef.current = key;
      setMessages(previous => previous.some(m => m.id === messageId)
        ? previous
        : [...previous, {
          id: messageId,
          role: 'assistant',
          text: '',
          tools: pending.toolCalls.map(call => ({
            id: call.id,
            name: call.name,
            input: inputText(call.input),
            status: 'awaiting_approval' as ToolCallStatus,
          })),
          pending: false,
          timestampMs: Date.now(),
        }]);
      setPendingConfirm({
        replyId: pending.replyId,
        messageId,
        sessionKey: key,
        toolCalls: pending.toolCalls,
        ready: true,
      });
      setBusy(false);
      return;
    }
    if (!status.running) setBusy(false);
    else if (!activeRequestRef.current) setBusy(true);
  }, []);

  useEffect(() => {
    if (
      urlSession
      && restoredRef.current?.agentId === agentId
      && restoredRef.current.sessionKey === urlSession
    ) {
      // Updating the URL after creating a conversation is bookkeeping, not a session switch.
      // Do not clear a just-received human approval request during that update.
      return;
    }
    let cancelled = false;
    hasLiveTurnRef.current = false;
    setMessages([]);
    setInput('');
    if (pendingConfirmSessionRef.current !== urlSession) {
      pendingConfirmSessionRef.current = null;
      setPendingConfirm(null);
    }
    setRestoring(true);
    const stored = (() => { try { return localStorage.getItem(storageKey(agentId)); } catch { return null; } })();

    async function restore() {
      let key: string | null = urlSession;
      if (!key) {
        try {
          const current = await currentSession(agentId, stored ?? undefined);
          key = current.sessionKey || stored || null;
        } catch {
          key = stored || null;
        }
      }
      if (cancelled) return;
      setSessionKey(key);
      restoredRef.current = { agentId, sessionKey: key };
      if (key) {
        let restoredMessages: Message[] = [];
        try {
          const entries = await fetchTurns(agentId, key);
          restoredMessages = turnsToMessages(entries);
        } catch {
          // The server-side transcript can be temporarily unavailable while a sandbox is restored.
        }
        if (restoredMessages.length === 0) {
          restoredMessages = loadCachedMessages(agentId, key);
        }
        if (!cancelled && restoredMessages.length > 0 && !hasLiveTurnRef.current) {
          setMessages(restoredMessages);
        }
        try {
          const status = await chatRunStatus(agentId, key);
          if (!cancelled) applyRunStatus(key, status);
        } catch {
          // The normal transcript remains usable if the transient runtime status is unavailable.
        }
      }
      if (cancelled) return;
      setRestoring(false);
      if (key && key !== urlSession) {
        const next = new URLSearchParams(searchParams);
        next.set('session', key);
        setSearchParams(next, { replace: true });
      }
    }
    restore();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, urlSession]);

  const recoveringRef = useRef(false);

  useEffect(() => {
    // The initial restore above always reads status once. Poll only for a detached active turn;
    // idle conversations and a visible live SSE already have no recovery work to do.
    if (restoring || !sessionKey || !busy || pendingConfirm || activeRequestRef.current) return;
    let cancelled = false;
    const refresh = async () => {
      const wasDetachedRun = recoveredRequestIdRef.current !== null && !activeRequestRef.current;
      try {
        const status = await chatRunStatus(agentId, sessionKey);
        if (cancelled) return;
        applyRunStatus(sessionKey, status);
        // Re-attach: the original SSE stream was dropped during navigation, but the agent is
        // still running.  Send an empty-message stream request so the backend subscribes this
        // client to the active event pipeline.
        if (wasDetachedRun && status.running && !recoveringRef.current) {
          recoveringRef.current = true;
          const lastMsg = messages.length > 0 ? messages[messages.length - 1] : null;
          void runStream(
            { message: '', sessionKey },
            { attachReplyId: lastMsg?.role === 'assistant' ? lastMsg.id : undefined },
          );
          return;
        }
        if (wasDetachedRun && !status.running && !hasLiveTurnRef.current) {
          const entries = await fetchTurns(agentId, sessionKey);
          if (!cancelled && entries.length > 0) setMessages(turnsToMessages(entries));
        }
      } catch {
        // Polling is a recovery path. Keep the current transcript when a refresh fails.
      }
    };
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
      recoveringRef.current = false;
    };
  }, [agentId, applyRunStatus, restoring, sessionKey]);

  useEffect(() => {
    if (restoring || !sessionKey || messages.length === 0) return;
    try {
      localStorage.setItem(historyStorageKey(agentId, sessionKey), JSON.stringify(messages));
    } catch {
      // Browser storage is an availability fallback; the server remains the source of record.
    }
  }, [agentId, messages, restoring, sessionKey]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, pendingConfirm]);

  const canSend = useMemo(
    () => !busy && !restoring && !pendingConfirm && input.trim().length > 0,
    [busy, restoring, pendingConfirm, input],
  );

  const updateTool = useCallback((toolId: string | undefined, toolName: string | undefined, patch: Partial<ToolEntry>, fallbackMessageId?: string) => {
    setMessages(previous => {
      let matched = false;
      const updated = previous.map(message => {
        const index = message.tools.findIndex(tool => toolId ? tool.id === toolId : tool.name === toolName && !tool.result);
        if (index < 0) return message;
        matched = true;
        const tools = [...message.tools];
        tools[index] = { ...tools[index], ...patch };
        return { ...message, tools };
      });
      if (matched || !fallbackMessageId) return updated;
      return updated.map(message => message.id === fallbackMessageId
        ? { ...message, tools: [...message.tools, {
          id: toolId ?? `${toolName ?? 'tool'}-${Date.now()}`,
          name: toolName ?? 'tool',
          ...patch,
        }] }
        : message);
    });
  }, []);

  async function runStream(
    req: { message: string; sessionKey?: string; confirmResults?: ConfirmDecision[]; requestId?: string },
    options?: { systemNote?: string; skipUserMessage?: boolean; attachReplyId?: string },
  ) {
    const isAttach = !!options?.attachReplyId && !req.message && !req.confirmResults;
    hasLiveTurnRef.current = true;
    if (!isAttach) setBusy(true);
    const now = Date.now();
    const additions: Message[] = [];

    if (isAttach) {
      // Re-attach: no new user/reply messages.  Reuse the last assistant message (the one
      // that was showing "thinking" before the SSE connection dropped) as the reply target
      // so that incoming tool_call / token events update the existing thread entry.
    } else {
      if (!options?.skipUserMessage && req.message) additions.push({ id: nextId(), role: 'user', text: req.message, tools: [], timestampMs: now });
      if (options?.systemNote) additions.push({ id: nextId(), role: 'system', text: options.systemNote, tools: [], timestampMs: now });
    }
    const reply: Message = isAttach
      ? { id: options!.attachReplyId!, role: 'assistant', text: '', tools: [], pending: true, timestampMs: now }
      : { id: nextId(), role: 'assistant', text: '', tools: [], pending: true, timestampMs: now };
    if (!isAttach) additions.push(reply);
    setMessages(previous => {
      if (isAttach) return previous.map(message => message.id === reply.id ? { ...message, pending: true } : message);
      return [...previous, ...additions];
    });
    let streamDone = false;
    let resolvedSessionKey = req.sessionKey ?? sessionKey ?? undefined;
    const requestId = nextId();
    const controller = new AbortController();
    activeRequestRef.current = { requestId, controller, replyId: reply.id };

    try {
      for await (const event of stream(agentId, { ...req, requestId }, controller.signal)) {
        if (event.type === 'session') {
          if (event.sessionKey) {
            resolvedSessionKey = event.sessionKey;
            restoredRef.current = { agentId, sessionKey: event.sessionKey };
            setSessionKey(event.sessionKey);
            persistSession(event.sessionKey);
            const next = new URLSearchParams(searchParams);
            if (next.get('session') !== event.sessionKey) {
              next.set('session', event.sessionKey);
              setSearchParams(next, { replace: true });
            }
          }
        } else if (event.type === 'token') {
          const chunk = event.data ?? '';
          setMessages(previous => previous.map(message => message.id === reply.id ? { ...message, text: message.text + chunk } : message));
        } else if (event.type === 'tool_call') {
          updateTool(event.toolCallId, event.toolName, {
            input: event.toolInput,
            status: 'running',
          }, reply.id);
        } else if (event.type === 'tool_result') {
          const result = event.toolResult;
          updateTool(event.toolCallId, event.toolName, {
            result,
            status: failedResult(result) ? 'failed' : 'completed',
          }, reply.id);
        } else if (event.type === 'confirm') {
          const calls = event.toolCalls ?? [];
          setMessages(previous => previous.map(message => {
            if (message.id !== reply.id) return message;
            const existing = new Map(message.tools.map(tool => [tool.id, tool]));
            for (const call of calls) {
              const current = existing.get(call.id);
              existing.set(call.id, {
                id: call.id,
                name: call.name,
                input: inputText(call.input) ?? current?.input,
                result: current?.result,
                status: 'awaiting_approval',
              });
            }
            return { ...message, pending: false, tools: [...existing.values()] };
          }));
          setBusy(false);
          pendingConfirmSessionRef.current = resolvedSessionKey ?? null;
          setPendingConfirm({
            replyId: event.replyId ?? '',
            messageId: reply.id,
            sessionKey: resolvedSessionKey ?? '',
            toolCalls: calls,
            ready: true,
          });
        } else if (event.type === 'done') {
          streamDone = true;
          setBusy(false);
          setPendingConfirm(current => current ? { ...current, ready: true } : current);
          if (event.sessionKey) {
            resolvedSessionKey = event.sessionKey;
            restoredRef.current = { agentId, sessionKey: event.sessionKey };
            setSessionKey(event.sessionKey);
            persistSession(event.sessionKey);
            const next = new URLSearchParams(searchParams);
            if (next.get('session') !== event.sessionKey) {
              next.set('session', event.sessionKey);
              setSearchParams(next, { replace: true });
            }
          }
          setMessages(previous => previous.map(message => message.id === reply.id ? { ...message, pending: false } : message));
        } else if (event.type === 'error') {
          if (streamDone) continue;
          const raw = event.error ?? '未知错误';
          const text = raw.includes('No active sandbox')
            ? '当前会话的沙箱不可用。请新建会话后重试。'
            : raw.includes('paused for human-in-the-loop') || raw.includes('cannot proceed')
              ? '上一次人工确认已失效。请重置会话后重试。'
              : raw;
          setMessages(previous => previous.map(message => {
            if (message.id !== reply.id) return message;
            return {
              ...message,
              pending: false,
              text: message.text + (message.text ? '\n' : '') + `执行错误：${text}`,
              tools: message.tools.map(tool => tool.status === 'running' ? { ...tool, status: 'failed' } : tool),
            };
          }));
        }
      }
      onSessionUpdate?.();
    } catch (error: unknown) {
      if (controller.signal.aborted) {
        setMessages(previous => previous.map(message => message.id === reply.id
          ? {
            ...message,
            pending: false,
            text: message.text || 'Request stopped by user.',
            tools: message.tools.map(tool =>
              tool.status === 'running' ? { ...tool, status: 'failed' } : tool),
          }
          : message));
        return;
      }
      const raw = error instanceof Error ? error.message : '流连接失败';
      const text = raw.includes('No active sandbox') ? '当前会话的沙箱不可用。请新建会话后重试。' : raw;
      setMessages(previous => previous.map(message => message.id === reply.id
        ? {
          ...message,
          pending: false,
          text: message.text + (message.text ? '\n' : '') + `执行错误：${text}`,
          tools: message.tools.map(tool => tool.status === 'running' ? { ...tool, status: 'failed' } : tool),
        }
        : message));
    } finally {
      if (activeRequestRef.current?.requestId === requestId) {
        activeRequestRef.current = null;
        hasLiveTurnRef.current = false;
      }
      setBusy(false);
      inputRef.current?.focus();
    }
  }

  function resetConversation() {
    if (sessionKey) {
      try { localStorage.removeItem(historyStorageKey(agentId, sessionKey)); } catch { /* ignore */ }
    }
    setSessionKey(null);
    persistSession(null);
    setMessages([]);
    pendingConfirmSessionRef.current = null;
    setPendingConfirm(null);
    setSearchParams({}, { replace: true });
  }

  async function handleSend() {
    if (!canSend) return;
    const text = input.trim();
    setInput('');
    if (text === '/reset') {
      resetConversation();
      return;
    }
    runStream({ message: text, sessionKey: sessionKey ?? undefined });
  }

  async function handleConfirm(approved: boolean) {
    if (!pendingConfirm || !pendingConfirm.ready || !sessionKey) return;
    const decisions: ConfirmDecision[] = pendingConfirm.toolCalls.map(call => ({ toolCallId: call.id, approved }));
    const nextStatus: ToolCallStatus = approved ? 'running' : 'rejected';
    setMessages(previous => previous.map(message => message.id === pendingConfirm.messageId
      ? { ...message, tools: message.tools.map(tool => decisions.some(decision => decision.toolCallId === tool.id) ? { ...tool, status: nextStatus } : tool) }
      : message));
    setPendingConfirm(null);
    pendingConfirmSessionRef.current = null;
    runStream(
      { message: '', sessionKey, confirmResults: decisions },
      { systemNote: approved ? '已批准，继续执行。' : '已拒绝该操作。', skipUserMessage: true },
    );
  }

  function handleStop() {
    const active = activeRequestRef.current;
    if (!active) {
      const recoveredRequestId = recoveredRequestIdRef.current;
      if (!recoveredRequestId) return;
      recoveredRequestIdRef.current = null;
      setBusy(false);
      void cancelStream(agentId, recoveredRequestId).catch(() => undefined);
      return;
    }
    active.controller.abort();
    setBusy(false);
    void cancelStream(agentId, active.requestId).catch(() => undefined);
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      handleSend();
    }
  }

  return (
    <div style={S.root}>
      <div style={S.thread} ref={threadRef}>
        {restoring && messages.length === 0 && <div style={S.empty}>正在加载对话记录...</div>}
        {!restoring && messages.length === 0 && (
          <div style={S.empty}>开始新对话。输入 <code style={{ background: '#e2e8f0', padding: '1px 5px', borderRadius: 3 }}>/reset</code> 可清空当前会话。</div>
        )}
        {messages.map((message, index) => {
          const orphaned = message.role === 'assistant' && !message.pending && isOrphanedConfirmText(message.text);
          const confirmation = pendingConfirm && (
            pendingConfirm.messageId === message.id
            || (!messages.some(item => item.id === pendingConfirm.messageId)
              && message.role === 'assistant'
              && !messages.slice(index + 1).some(item => item.role === 'assistant'))
          ) ? pendingConfirm : null;
          const charts = message.tools
            .map(tool => ({ id: tool.id, artifact: extractVegaLiteSpec(tool.input) }))
            .filter((item): item is { id: string; artifact: NonNullable<typeof item.artifact> } => item.artifact !== null);
          if (message.role === 'system') return <div key={message.id} style={S.system}>{message.text}</div>;
          const isUser = message.role === 'user';
          return (
            <div key={message.id} style={{ ...S.messageRow, ...(isUser ? S.messageRowUser : S.messageRowAssistant) }}>
              <div style={S.messageMeta}>
                <span style={S.actor}>{isUser ? '你' : '数据助手'}</span>
                <time>{timeLabel(message.timestampMs)}</time>
              </div>
              <div style={{ ...S.bubble, ...(isUser ? S.user : S.assistant) }}>
                {!isUser && message.tools.length > 0 && <ExecutionTrace tools={message.tools} pending={message.pending} sessionKey={sessionKey} />}
                {message.text && <div style={S.responseText}>{isUser ? message.text : <ChatContent text={message.text} sessionKey={sessionKey} />}</div>}
                {!message.text && message.pending && message.tools.length === 0 && <span style={S.waiting}>正在思考...</span>}
                {charts.map(chart => <VegaChart key={chart.id} artifact={chart.artifact} />)}
                {confirmation && (
                  <section style={S.approval} aria-label="人工确认">
                    <div style={S.approvalHeader}>
                      <span aria-hidden="true">!</span>
                        <div>
                          <div style={S.approvalTitle}>需要人工确认</div>
                          <div style={S.approvalHint}>请核对工具参数后决定是否继续。</div>
                      </div>
                    </div>
                    <div style={S.approvalList}>
                      {confirmation.toolCalls.map(call => (
                        <div key={call.id} style={S.approvalTool}>
                          <code style={S.approvalCode}>{call.name}</code>
                          {inputPreview(call.input) && <span style={S.approvalInput}>{inputPreview(call.input)}</span>}
                        </div>
                      ))}
                    </div>
                    <div style={S.approvalActions}>
                      <button
                        type="button"
                        style={{ ...S.denyBtn, ...(!confirmation.ready ? { opacity: 0.5, cursor: 'default' } : {}) }}
                        onClick={() => handleConfirm(false)}
                        disabled={!confirmation.ready}
                      >拒绝</button>
                      <button
                        type="button"
                        style={{ ...S.allowBtn, ...(!confirmation.ready ? { opacity: 0.5, cursor: 'default' } : {}) }}
                        onClick={() => handleConfirm(true)}
                        disabled={!confirmation.ready}
                      >批准并继续</button>
                    </div>
                  </section>
                )}
                {orphaned && (
                  <div style={S.orphanedBanner}>
                    <div style={S.orphanedTitle}>人工确认状态已失效</div>
                    <div style={S.orphanedText}>原操作无法安全恢复。重置会话后可重新发起请求。</div>
                    <div style={S.orphanedActions}>
                      <button type="button" style={S.resetBtn} onClick={resetConversation}>重置会话</button>
                      <button type="button" style={S.retryBtn} onClick={() => {
                        const lastUser = [...messages].reverse().find(item => item.role === 'user');
                        if (!lastUser) return;
                        resetConversation();
                        runStream({ message: lastUser.text });
                      }}>重试上一条</button>
                    </div>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
      <div style={S.composer}>
        <textarea
          ref={inputRef}
          style={S.textarea}
          value={input}
          onChange={event => setInput(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={restoring ? '加载中...' : pendingConfirm ? '请先处理当前的人工确认' : `向 ${agentId} 发送消息...`}
          rows={1}
          autoFocus
          disabled={restoring || Boolean(pendingConfirm)}
        />
        {busy ? (
          <button type="button" style={S.stop} onClick={handleStop}>Stop</button>
        ) : (
          <button type="button" style={{ ...S.send, ...(canSend ? {} : S.sendDisabled) }} onClick={handleSend} disabled={!canSend}>
            {'\u53d1\u9001'}
          </button>
        )}
      </div>
    </div>
  );
}
