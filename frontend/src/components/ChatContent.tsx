import React from 'react';

export const workspaceFileHref = (path: string, sessionKey?: string | null) => {
  const params = new URLSearchParams({ path });
  const currentSession = new URLSearchParams(window.location.search).get('session');
  const resolvedSession = sessionKey ?? currentSession;
  if (resolvedSession) params.set('session', resolvedSession);
  return `/workspace?${params.toString()}`;
};

const WORKSPACE_PATH_PATTERN = /(plans\/[^\s`'"，。；、)）\]}]+|artifacts\/[^\s`'"，。；、)）\]}]+|agents\/[^\s`'"，。；、)）\]}]+\/sessions\/[^\s`'"，。；、)）\]}]+)/g;

function unwrapContent(value: string): string {
  let current: unknown = value.trim();
  for (let index = 0; index < 2 && typeof current === 'string'; index += 1) {
    try {
      const parsed: unknown = JSON.parse(current);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) break;
      const record = parsed as Record<string, unknown>;
      const content = record.content ?? record.text ?? record.message;
      if (typeof content !== 'string') break;
      current = content;
    } catch {
      break;
    }
  }
  return typeof current === 'string' ? current : value;
}

function splitCells(line: string): string[] {
  return line.trim().replace(/^\||\|$/g, '').split('|').map(cell => cell.trim());
}

function isTableSeparator(line: string): boolean {
  return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
}

function renderInline(value: string, keyPrefix: string, sessionKey?: string | null): React.ReactNode[] {
  return value
    .split(new RegExp(`(\`[^\`]+\`|\\*\\*[^*]+\\*\\*|${WORKSPACE_PATH_PATTERN.source})`, 'g'))
    .filter(Boolean)
    .map((part, index) => {
      const key = `${keyPrefix}-${index}`;
      if (WORKSPACE_PATH_PATTERN.test(part)) {
        WORKSPACE_PATH_PATTERN.lastIndex = 0;
        return <a key={key} href={workspaceFileHref(part, sessionKey)} style={{ color: '#1d4ed8', fontWeight: 650 }}>{part}</a>;
      }
      WORKSPACE_PATH_PATTERN.lastIndex = 0;
      if (part.startsWith('`') && part.endsWith('`')) {
        return <code key={key} style={{ background: '#eef2f7', color: '#334155', padding: '1px 4px', borderRadius: 3 }}>{part.slice(1, -1)}</code>;
      }
      if (part.startsWith('**') && part.endsWith('**')) {
        return <strong key={key}>{part.slice(2, -2)}</strong>;
      }
      return <React.Fragment key={key}>{part}</React.Fragment>;
    });
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'grid', gap: 9 },
  paragraph: { margin: 0, whiteSpace: 'pre-wrap' },
  h1: { margin: '2px 0 1px', fontSize: '1.08rem', lineHeight: 1.4 },
  h2: { margin: '2px 0 1px', fontSize: '1rem', lineHeight: 1.4 },
  h3: { margin: '2px 0 1px', fontSize: '0.94rem', lineHeight: 1.4 },
  list: { margin: 0, paddingLeft: 20, display: 'grid', gap: 4 },
  code: { margin: 0, padding: '9px 10px', borderRadius: 5, background: '#0f172a', color: '#e2e8f0', overflowX: 'auto', fontSize: '0.79rem', lineHeight: 1.55 },
  tableWrap: { overflowX: 'auto', border: '1px solid #dbe4ee', borderRadius: 5 },
  table: { borderCollapse: 'collapse', width: '100%', fontSize: '0.8rem' },
  th: { padding: '7px 9px', textAlign: 'left', color: '#334155', background: '#f8fafc', borderBottom: '1px solid #dbe4ee', whiteSpace: 'nowrap' },
  td: { padding: '7px 9px', color: '#334155', borderBottom: '1px solid #edf2f7', verticalAlign: 'top' },
};

export default function ChatContent({ text, sessionKey }: { text: string; sessionKey?: string | null }) {
  const lines = unwrapContent(text).replace(/\r\n/g, '\n').split('\n');
  const blocks: React.ReactNode[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) {
      index += 1;
      continue;
    }
    if (line.startsWith('```')) {
      const code: string[] = [];
      index += 1;
      while (index < lines.length && !lines[index].startsWith('```')) code.push(lines[index++]);
      if (index < lines.length) index += 1;
      blocks.push(<pre key={`code-${index}`} style={S.code}>{code.join('\n')}</pre>);
      continue;
    }
    if (line.includes('|') && index + 1 < lines.length && isTableSeparator(lines[index + 1])) {
      const headers = splitCells(line);
      const rows: string[][] = [];
      index += 2;
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) rows.push(splitCells(lines[index++]));
      blocks.push(
        <div key={`table-${index}`} style={S.tableWrap}>
          <table style={S.table}>
            <thead><tr>{headers.map((header, cell) => <th key={cell} style={S.th}>{renderInline(header, `h-${cell}`, sessionKey)}</th>)}</tr></thead>
            <tbody>{rows.map((row, rowIndex) => <tr key={rowIndex}>{headers.map((_, cell) => <td key={cell} style={S.td}>{renderInline(row[cell] ?? '', `r-${rowIndex}-${cell}`, sessionKey)}</td>)}</tr>)}</tbody>
          </table>
        </div>,
      );
      continue;
    }
    const heading = /^(#{1,3})\s+(.+)$/.exec(line);
    if (heading) {
      const level = heading[1].length;
      const style = level === 1 ? S.h1 : level === 2 ? S.h2 : S.h3;
      blocks.push(<div key={`heading-${index}`} style={style}>{renderInline(heading[2], `heading-${index}`, sessionKey)}</div>);
      index += 1;
      continue;
    }
    const ordered = /^\d+\.\s+/.test(line);
    if (ordered || /^[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && (ordered ? /^\d+\.\s+/.test(lines[index]) : /^[-*]\s+/.test(lines[index]))) {
        items.push(lines[index++].replace(ordered ? /^\d+\.\s+/ : /^[-*]\s+/, ''));
      }
      const Tag = ordered ? 'ol' : 'ul';
      blocks.push(<Tag key={`list-${index}`} style={S.list}>{items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item, `list-${index}-${itemIndex}`, sessionKey)}</li>)}</Tag>);
      continue;
    }
    const paragraph: string[] = [line];
    index += 1;
    while (
      index < lines.length
      && lines[index].trim()
      && !lines[index].startsWith('```')
      && !/^(#{1,3})\s+/.test(lines[index])
      && !/^[-*]\s+/.test(lines[index])
      && !/^\d+\.\s+/.test(lines[index])
      && !(lines[index].includes('|') && index + 1 < lines.length && isTableSeparator(lines[index + 1]))
    ) {
      paragraph.push(lines[index++]);
    }
    blocks.push(<p key={`p-${index}`} style={S.paragraph}>{renderInline(paragraph.join('\n'), `p-${index}`, sessionKey)}</p>);
  }
  return <div style={S.root}>{blocks}</div>;
}
