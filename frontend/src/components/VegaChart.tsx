import React, { useEffect, useMemo, useRef, useState } from 'react';

export interface ChartArtifact {
  chartType: string;
  spec: Record<string, unknown>;
}

function parseJson(value: unknown): unknown {
  let current = value;
  for (let index = 0; index < 2 && typeof current === 'string'; index += 1) {
    try { current = JSON.parse(current); } catch { break; }
  }
  return current;
}

function containsRemoteUrl(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(containsRemoteUrl);
  if (!value || typeof value !== 'object') return false;
  return Object.entries(value as Record<string, unknown>).some(([key, child]) =>
    key === 'url' || containsRemoteUrl(child),
  );
}

function hasInlineData(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(hasInlineData);
  if (!value || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  if (record.data && typeof record.data === 'object' && Array.isArray((record.data as Record<string, unknown>).values)) return true;
  return Object.values(record).some(hasInlineData);
}

export function extractVegaLiteSpec(input?: string): ChartArtifact | null {
  if (!input) return null;
  const call = parseJson(input);
  if (!call || typeof call !== 'object') return null;
  const fields = call as Record<string, unknown>;
  const candidate = parseJson(fields.vega_lite_spec ?? fields.vegaLiteSpec ?? call);
  if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) return null;
  if (containsRemoteUrl(candidate) || !hasInlineData(candidate)) return null;
  return {
    chartType: typeof fields.chart_type === 'string' ? fields.chart_type : '图表',
    spec: candidate as Record<string, unknown>,
  };
}

export function parseStoredVegaLiteArtifact(content: string): ChartArtifact | null {
  const parsed = parseJson(content);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
  const document = parsed as Record<string, unknown>;
  const candidate = parseJson(document.spec ?? document);
  if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) return null;
  if (containsRemoteUrl(candidate) || !hasInlineData(candidate)) return null;
  return {
    chartType: typeof document.chartType === 'string' ? document.chartType : 'Chart',
    spec: candidate as Record<string, unknown>,
  };
}

const S: Record<string, React.CSSProperties> = {
  card: { marginTop: 12, border: '1px solid #dbe4ee', borderRadius: 7, overflow: 'hidden', background: '#ffffff' },
  header: { padding: '8px 11px', borderBottom: '1px solid #e8eef5', color: '#334155', fontSize: '0.8rem', fontWeight: 700 },
  canvas: { padding: '12px', overflowX: 'auto', minHeight: 88 },
  error: { padding: '12px', color: '#b91c1c', fontSize: '0.8rem', background: '#fef2f2' },
};

export default function VegaChart({ artifact }: { artifact: ChartArtifact }) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const [error, setError] = useState<string | null>(null);
  const serialized = JSON.stringify(artifact.spec);
  const spec = useMemo(() => JSON.parse(serialized) as Record<string, unknown>, [serialized]);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return undefined;
    let disposed = false;
    let finalize: (() => void) | undefined;
    host.replaceChildren();
    setError(null);
    import('vega-embed')
      .then(({ default: vegaEmbed }) => vegaEmbed(host, spec, { actions: false, renderer: 'svg' }))
      .then(result => {
        finalize = () => result.finalize();
      })
      .catch(reason => {
        if (!disposed) setError(reason instanceof Error ? reason.message : '图表规范无法渲染');
      });
    return () => {
      disposed = true;
      void finalize?.();
      host.replaceChildren();
    };
  }, [spec]);

  return (
    <section style={S.card} aria-label={`${artifact.chartType}图表`}>
      <div style={S.header}>{artifact.chartType}图表</div>
      {error ? <div style={S.error}>图表渲染失败：{error}</div> : <div ref={hostRef} style={S.canvas} />}
    </section>
  );
}
