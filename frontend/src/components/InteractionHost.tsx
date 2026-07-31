import React, { useEffect, useState } from 'react';

type ConfirmRequest = {
  message: string;
  resolve: (accepted: boolean) => void;
};

type Notice = { id: number; message: string; tone: 'error' | 'info' };

const confirmListeners = new Set<(request: ConfirmRequest) => void>();
const noticeListeners = new Set<(notice: Notice) => void>();
let noticeId = 0;

export function confirmAction(message: string): Promise<boolean> {
  return new Promise(resolve => {
    const listener = confirmListeners.values().next().value as
      | ((request: ConfirmRequest) => void)
      | undefined;
    if (!listener) {
      resolve(false);
      return;
    }
    listener({ message, resolve });
  });
}

export function showNotice(message: string, tone: Notice['tone'] = 'error') {
  const notice = { id: ++noticeId, message, tone };
  noticeListeners.forEach(listener => listener(notice));
}

export default function InteractionHost() {
  const [pending, setPending] = useState<ConfirmRequest | null>(null);
  const [notices, setNotices] = useState<Notice[]>([]);

  useEffect(() => {
    const onConfirm = (request: ConfirmRequest) => setPending(request);
    const onNotice = (notice: Notice) => {
      setNotices(current => [...current, notice]);
      window.setTimeout(
        () => setNotices(current => current.filter(item => item.id !== notice.id)),
        4_500,
      );
    };
    confirmListeners.add(onConfirm);
    noticeListeners.add(onNotice);
    return () => {
      confirmListeners.delete(onConfirm);
      noticeListeners.delete(onNotice);
    };
  }, []);

  const finish = (accepted: boolean) => {
    const request = pending;
    setPending(null);
    request?.resolve(accepted);
  };

  return (
    <>
      {pending && (
        <div style={styles.backdrop} role="presentation" onMouseDown={() => finish(false)}>
          <div
            style={styles.dialog}
            role="alertdialog"
            aria-modal="true"
            aria-label="确认操作"
            onMouseDown={event => event.stopPropagation()}
          >
            <div style={styles.title}>确认操作</div>
            <div style={styles.message}>{pending.message}</div>
            <div style={styles.actions}>
              <button type="button" style={styles.cancel} onClick={() => finish(false)}>取消</button>
              <button type="button" style={styles.confirm} autoFocus onClick={() => finish(true)}>确认</button>
            </div>
          </div>
        </div>
      )}
      <div style={styles.noticeStack} aria-live="polite">
        {notices.map(notice => (
          <div
            key={notice.id}
            style={{ ...styles.notice, ...(notice.tone === 'error' ? styles.noticeError : {}) }}
          >
            <span style={{ flex: 1 }}>{notice.message}</span>
            <button
              type="button"
              aria-label="关闭提示"
              style={styles.noticeClose}
              onClick={() => setNotices(current => current.filter(item => item.id !== notice.id))}
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </>
  );
}

const styles: Record<string, React.CSSProperties> = {
  backdrop: { position: 'fixed', inset: 0, zIndex: 1000, display: 'grid', placeItems: 'center', background: 'rgba(15, 23, 42, 0.48)', padding: 20 },
  dialog: { width: 'min(440px, 100%)', background: '#fff', border: '1px solid #e2e8f0', borderRadius: 8, boxShadow: '0 20px 50px rgba(15, 23, 42, 0.22)', padding: 20 },
  title: { color: '#0f172a', fontSize: '1rem', fontWeight: 700, marginBottom: 10 },
  message: { color: '#475569', fontSize: '0.9rem', lineHeight: 1.6, overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' },
  actions: { display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 },
  cancel: { border: '1px solid #cbd5e1', background: '#fff', color: '#334155', borderRadius: 6, padding: '7px 16px', cursor: 'pointer' },
  confirm: { border: '1px solid #4f46e5', background: '#4f46e5', color: '#fff', borderRadius: 6, padding: '7px 16px', cursor: 'pointer' },
  noticeStack: { position: 'fixed', zIndex: 1100, right: 20, top: 20, width: 'min(420px, calc(100vw - 40px))', display: 'flex', flexDirection: 'column', gap: 8 },
  notice: { display: 'flex', alignItems: 'flex-start', gap: 10, padding: '11px 12px', border: '1px solid #bfdbfe', borderRadius: 6, background: '#eff6ff', color: '#1e3a8a', boxShadow: '0 8px 24px rgba(15, 23, 42, 0.12)', fontSize: '0.86rem', lineHeight: 1.45 },
  noticeError: { borderColor: '#fecaca', background: '#fef2f2', color: '#991b1b' },
  noticeClose: { border: 0, background: 'transparent', color: 'currentColor', cursor: 'pointer', fontSize: 18, lineHeight: 1, padding: 0 },
};
