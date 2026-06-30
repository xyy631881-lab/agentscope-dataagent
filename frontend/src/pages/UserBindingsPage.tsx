import React, { useEffect, useState } from 'react';
import { getToken } from '../api/auth';

interface UserBinding {
  channelId: string;
  displayLabel?: string | null;
  sessionScope?: string | null;
  language?: string | null;
  enabledSkills?: string[] | null;
}

function authHeaders() {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` };
}

async function jsonFetch<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, { headers: authHeaders(), ...init });
  if (!res.ok) {
    const t = await res.text().catch(() => res.statusText);
    throw new Error(t || `HTTP ${res.status}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

const apiList   = () => jsonFetch<UserBinding[]>('/api/user/bindings');
const apiAdd    = (b: UserBinding) => jsonFetch<UserBinding>('/api/user/bindings', { method: 'POST', body: JSON.stringify(b) });
const apiUpdate = (i: number, b: UserBinding) => jsonFetch<UserBinding>(`/api/user/bindings/${i}`, { method: 'PUT', body: JSON.stringify(b) });
const apiRemove = (i: number) => jsonFetch<{ removed: boolean }>(`/api/user/bindings/${i}`, { method: 'DELETE' });

const S: Record<string, React.CSSProperties> = {
  content: { padding: '2rem 1.5rem', maxWidth: 900, margin: '0 auto' },
  title:   { fontSize: '1.1rem', fontWeight: 700, color: '#e2e8f0', marginBottom: 12 },
  intro:   { color: '#7c8bad', fontSize: '0.82rem', marginBottom: 18, lineHeight: 1.6 },
  toolbar: { display: 'flex', gap: 10, marginBottom: 14, alignItems: 'center' },
  addBtn:  { background: '#6366f1', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 16px', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 600 },
  err:     { color: '#f87171', fontSize: '0.82rem', background: '#1f1520', border: '1px solid #5b2030', borderRadius: 8, padding: '8px 12px', marginBottom: 12 },
  ok:      { color: '#34d399', fontSize: '0.82rem', background: '#0d1f14', border: '1px solid #166534', borderRadius: 8, padding: '8px 12px', marginBottom: 12 },
  table:   { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.85rem' },
  th:      { textAlign: 'left' as const, padding: '0.5rem 0.75rem', background: '#13151f', color: '#7c8bad', borderBottom: '1px solid #1e2235', fontWeight: 600 },
  td:      { padding: '0.55rem 0.75rem', borderBottom: '1px solid #1e2235', color: '#94a3b8' },
  mono:    { fontFamily: 'monospace', fontSize: '0.78rem' },
  editBtn: { background: '#1e2235', border: 'none', color: '#a5b4fc', borderRadius: 4, padding: '3px 10px', cursor: 'pointer', fontSize: '0.78rem', marginRight: 4 },
  delBtn:  { background: 'transparent', border: '1px solid #5b2030', color: '#f87171', borderRadius: 4, padding: '3px 9px', cursor: 'pointer', fontSize: '0.78rem' },
  modal:   { position: 'fixed' as const, inset: 0, background: 'rgba(0,0,0,0.72)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 },
  modalBox:{ background: '#1a1d27', border: '1px solid #2d3148', borderRadius: 10, padding: '1.5rem', width: 520 },
  modalTitle: { fontSize: '1rem', fontWeight: 700, color: '#e2e8f0', marginBottom: 14 },
  label:   { display: 'block', fontSize: '0.78rem', color: '#94a3b8', fontWeight: 500, marginBottom: 4 },
  hint:    { display: 'block', fontSize: '0.7rem', color: '#4b5280', marginTop: -8, marginBottom: 10 },
  input:   { width: '100%', boxSizing: 'border-box' as const, padding: '7px 10px', background: '#0f1117', border: '1px solid #2d3148', borderRadius: 6, color: '#e2e8f0', fontSize: '0.85rem', marginBottom: 12 },
  btnRow:  { display: 'flex', gap: 8, marginTop: 4 },
  saveBtn: { background: '#6366f1', color: '#fff', border: 'none', borderRadius: 7, padding: '8px 20px', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 600 },
  cancelBtn:{ background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad', borderRadius: 7, padding: '8px 14px', cursor: 'pointer', fontSize: '0.85rem' },
};

interface PreferenceFormProps {
  initial?: { index: number; data: UserBinding };
  onSaved: () => void;
  onClose: () => void;
}

function PreferenceFormModal({ initial, onSaved, onClose }: PreferenceFormProps) {
  const isEdit = !!initial;
  const [channelId,    setChannelId]    = useState(initial?.data.channelId ?? 'chatui');
  const [displayLabel, setDisplayLabel] = useState(initial?.data.displayLabel ?? '');
  const [sessionScope, setSessionScope] = useState(initial?.data.sessionScope ?? '');
  const [language,     setLanguage]     = useState(initial?.data.language ?? '');
  const [skillsText,   setSkillsText]   = useState((initial?.data.enabledSkills ?? []).join(', '));
  const [err,          setErr]          = useState<string | null>(null);
  const [saving,       setSaving]       = useState(false);

  async function submit() {
    setErr(null); setSaving(true);
    try {
      const skills = skillsText
        .split(',')
        .map(s => s.trim())
        .filter(s => s.length > 0);
      const b: UserBinding = {
        channelId: channelId.trim(),
        displayLabel: displayLabel.trim() || null,
        sessionScope: sessionScope.trim() || null,
        language: language.trim() || null,
        enabledSkills: skills.length > 0 ? skills : null,
      };
      if (isEdit) await apiUpdate(initial!.index, b);
      else        await apiAdd(b);
      onSaved();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={S.modal} onClick={onClose}>
      <div style={S.modalBox} onClick={e => e.stopPropagation()}>
        <div style={S.modalTitle}>{isEdit ? 'Edit Preference' : 'New Channel Preference'}</div>
        {err && <div style={S.err}>{err}</div>}

        <label style={S.label}>Channel ID</label>
        <input style={S.input} value={channelId} onChange={e => setChannelId(e.target.value)} placeholder="chatui" />
        <span style={S.hint}>使用 <code>chatui</code> 作为网页 UI；其他 ID 对应集成（如 <code>slack</code>）。</span>

        <label style={S.label}>显示标签（可选）</label>
        <input style={S.input} value={displayLabel} onChange={e => setDisplayLabel(e.target.value)} placeholder="我的默认会话" />

        <label style={S.label}>会话作用域（可选）</label>
        <input style={S.input} value={sessionScope} onChange={e => setSessionScope(e.target.value)} placeholder="MAIN" />
        <span style={S.hint}>聊天 UI 用于推导稳定会话键的提示（默认：<code>MAIN</code>）。</span>

        <label style={S.label}>回复语言（可选，BCP-47）</label>
        <input style={S.input} value={language} onChange={e => setLanguage(e.target.value)} placeholder="zh-CN" />
        <span style={S.hint}>设置后，DataAgent 将使用此语言回复。</span>

        <label style={S.label}>启用的技能（可选，逗号分隔）</label>
        <input style={S.input} value={skillsText} onChange={e => setSkillsText(e.target.value)} placeholder="sql-analysis, chart-rendering" />
        <span style={S.hint}>如果为空，<code>skills/</code> 下的所有技能都可使用。</span>

        <div style={S.btnRow}>
          <button style={S.saveBtn} disabled={saving || !channelId.trim()} onClick={submit}>
            {saving ? '保存中…' : isEdit ? '更新' : '添加'}
          </button>
          <button style={S.cancelBtn} onClick={onClose}>取消</button>
        </div>
      </div>
    </div>
  );
}

export default function UserBindingsPage() {
  const [bindings, setBindings] = useState<UserBinding[]>([]);
  const [err,      setErr]      = useState<string | null>(null);
  const [ok,       setOk]       = useState<string | null>(null);
  const [editing,  setEditing]  = useState<{ index: number; data: UserBinding } | 'new' | null>(null);
  const [loading,  setLoading]  = useState(true);

  async function load() {
    setLoading(true); setErr(null);
    try {
      setBindings(await apiList());
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  async function onDelete(i: number) {
    if (!confirm('Delete this preference?')) return;
    try {
      await apiRemove(i);
      setOk('Preference removed.');
      await load();
      setTimeout(() => setOk(null), 3000);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <>
      <div style={S.content}>
        <h2 style={S.title}>频道偏好设置</h2>
        <p style={S.intro}>
          DataAgent answers every chat — these settings just shape <em>how</em> it answers on a
          given channel. Set a reply language, restrict which skills are loaded, or override the
          default session scope. Preferences apply only to your account.
        </p>

        <div style={S.toolbar}>
          <button style={S.addBtn} onClick={() => setEditing('new')}>+ 新建偏好</button>
        </div>

        {err && <div style={S.err}>{err}</div>}
        {ok  && <div style={S.ok}>{ok}</div>}

        {loading && <p style={{ color: '#7c8bad' }}>加载中…</p>}

        {!loading && bindings.length === 0 && (
          <p style={{ color: '#7c8bad', fontSize: '0.85rem' }}>
            暂无频道偏好 — DataAgent 对每个频道使用默认设置。
          </p>
        )}

        {!loading && bindings.length > 0 && (
          <table style={S.table}>
            <thead>
              <tr>
                <th style={S.th}>#</th>
                <th style={S.th}>Channel</th>
                <th style={S.th}>Label</th>
                <th style={S.th}>Language</th>
                <th style={S.th}>Scope</th>
                <th style={S.th}>Skills</th>
                <th style={S.th}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {bindings.map((b, i) => (
                <tr key={i}>
                  <td style={S.td}>{i}</td>
                  <td style={{ ...S.td, ...S.mono }}>{b.channelId}</td>
                  <td style={S.td}>{b.displayLabel ?? '—'}</td>
                  <td style={{ ...S.td, ...S.mono }}>{b.language ?? '—'}</td>
                  <td style={{ ...S.td, ...S.mono }}>{b.sessionScope ?? '—'}</td>
                  <td style={{ ...S.td, ...S.mono }}>
                    {b.enabledSkills && b.enabledSkills.length > 0
                      ? b.enabledSkills.join(', ')
                      : '(all)'}
                  </td>
                  <td style={S.td}>
                    <button style={S.editBtn} onClick={() => setEditing({ index: i, data: b })}>Edit</button>
                    <button style={S.delBtn} onClick={() => onDelete(i)}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {editing && (
        <PreferenceFormModal
          initial={editing === 'new' ? undefined : editing}
          onSaved={() => { setEditing(null); load(); setOk('Saved.'); setTimeout(() => setOk(null), 3000); }}
          onClose={() => setEditing(null)}
        />
      )}
    </>
  );
}
