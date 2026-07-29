import { useState, useEffect, useRef } from "react";
import {
  ScatterChart,
  Scatter,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  LineChart,
  Line,
  Legend,
} from "recharts";

interface MetricsPayload {
  queryId: string;
  recordCount: number;
  skylineSize: number;
  optimality: number;
  ingestionTimeMs: number;
  localProcessingTimeMs: number;
  globalProcessingTimeMs: number;
  totalProcessingTimeMs: number;
  latencyMs: number;
  points: number[][];
}

type Status = "connected" | "connecting" | "disconnected";

function statusPillStyle(s: Status): React.CSSProperties {
  const color =
    s === "connected" ? "var(--accent-green)" : s === "connecting" ? "var(--accent-yellow)" : "var(--accent)";
  return {
    display: "flex",
    alignItems: "center",
    gap: "6px",
    fontSize: "12px",
    color,
    border: `1px solid ${color}`,
    padding: "2px 10px",
    fontWeight: 600,
    textTransform: "uppercase",
    letterSpacing: "0.08em",
  };
}

function dotStyle(s: Status): React.CSSProperties {
  return {
    width: 7,
    height: 7,
    background:
      s === "connected" ? "var(--accent-green)" : s === "connecting" ? "var(--accent-yellow)" : "var(--accent)",
  };
}

const S: Record<string, React.CSSProperties> = {
  layout: {
    display: "flex",
    flexDirection: "column",
    minHeight: "100vh",
    background: "var(--bg)",
  },
  header: {
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    padding: "0 24px",
    height: "48px",
    background: "var(--surface)",
    borderBottom: "1px solid var(--border)",
    flexShrink: 0,
  },
  headerTitle: {
    display: "flex",
    alignItems: "center",
    gap: "10px",
    color: "var(--text-bright)",
    fontWeight: 700,
    fontSize: "14px",
    letterSpacing: "0.02em",
  },
  flinkBadge: {
    background: "var(--accent)",
    color: "#fff",
    fontSize: "10px",
    fontWeight: 700,
    padding: "2px 6px",
    letterSpacing: "0.05em",
    textTransform: "uppercase" as const,
  },
  main: {
    flex: 1,
    padding: "20px 24px",
    display: "flex",
    flexDirection: "column",
    gap: "16px",
  },
  // ── KPI Strip ──
  kpiStrip: {
    display: "grid",
    gridTemplateColumns: "repeat(4, 1fr)",
    gap: "0",
    border: "1px solid var(--border)",
  },
  kpiCell: {
    padding: "16px 20px",
    borderRight: "1px solid var(--border)",
    background: "var(--surface)",
  },
  kpiCellLast: {
    padding: "16px 20px",
    background: "var(--surface)",
  },
  kpiLabel: {
    fontSize: "11px",
    color: "var(--text-muted)",
    textTransform: "uppercase" as const,
    letterSpacing: "0.08em",
    marginBottom: "6px",
  },
  kpiValue: {
    fontSize: "26px",
    fontWeight: 700,
    color: "var(--text-bright)",
    fontVariantNumeric: "tabular-nums",
    letterSpacing: "-0.02em",
  },
  kpiSub: {
    fontSize: "11px",
    color: "var(--text-muted)",
    marginTop: "4px",
  },
  // ── Panels row ──
  row: {
    display: "grid",
    gridTemplateColumns: "2fr 1fr",
    gap: "16px",
    flex: 1,
  },
  panel: {
    background: "var(--surface)",
    border: "1px solid var(--border)",
  },
  panelHeader: {
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    padding: "10px 16px",
    borderBottom: "1px solid var(--border)",
    background: "var(--surface-alt)",
  },
  panelTitle: {
    fontSize: "12px",
    fontWeight: 700,
    color: "var(--text-bright)",
    textTransform: "uppercase" as const,
    letterSpacing: "0.08em",
  },
  panelBody: {
    padding: "16px",
  },
  // Dimension selectors
  dimRow: {
    display: "flex",
    gap: "8px",
    alignItems: "center",
  },
  dimLabel: {
    fontSize: "11px",
    color: "var(--text-muted)",
    marginRight: "4px",
  },
  select: {
    background: "var(--bg)",
    border: "1px solid var(--border)",
    color: "var(--text)",
    fontSize: "12px",
    padding: "3px 6px",
    outline: "none",
    fontFamily: "inherit",
  },
  // Placeholder
  placeholder: {
    height: "100%",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    color: "var(--text-muted)",
    fontSize: "12px",
    border: "1px dashed var(--border)",
    margin: "8px 0",
  },
  // Timing table
  timingTable: {
    width: "100%",
    borderCollapse: "collapse" as const,
  },
  timingRow: {
    borderBottom: "1px solid var(--border)",
  },
  timingLabel: {
    padding: "10px 12px",
    fontSize: "12px",
    color: "var(--text-muted)",
    verticalAlign: "middle" as const,
  },
  timingBar: {
    padding: "10px 12px",
    verticalAlign: "middle" as const,
    width: "45%",
  },
  timingValue: {
    padding: "10px 12px",
    textAlign: "right" as const,
    fontSize: "13px",
    fontWeight: 700,
    color: "var(--text-bright)",
    whiteSpace: "nowrap" as const,
  },
};

function Bar({
  value,
  max,
  color,
}: {
  value: number;
  max: number;
  color: string;
}) {
  const pct = max > 0 ? Math.min((value / max) * 100, 100) : 0;
  return (
    <div
      style={{
        height: 4,
        background: "var(--border)",
        position: "relative",
        width: "100%",
      }}
    >
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          bottom: 0,
          width: `${pct}%`,
          background: color,
        }}
      />
    </div>
  );
}

export default function App() {
  const [status, setStatus] = useState<
    "connecting" | "connected" | "disconnected"
  >("connecting");
  const [data, setData] = useState<MetricsPayload | null>(null);
  const [history, setHistory] = useState<MetricsPayload[]>([]);
  const [selectedXDim, setSelectedXDim] = useState<number>(0);
  const [selectedYDim, setSelectedYDim] = useState<number>(1);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    connect();
    return () => {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      if (wsRef.current) {
        wsRef.current.onclose = null;
        wsRef.current.onerror = null;
        wsRef.current.close();
      }
    };
  }, []);

  function connect() {
    setStatus("connecting");
    const ws = new WebSocket(import.meta.env.VITE_WS_URL || "ws://localhost:8000/ws");
    wsRef.current = ws;
    ws.onopen = () => setStatus("connected");
    ws.onmessage = (e) => {
      try {
        const p: MetricsPayload = JSON.parse(e.data);
        setData(p);
        setHistory((prev) => {
          const next = [...prev, p];
          return next.length > 30 ? next.slice(-30) : next;
        });
      } catch {}
    };
    ws.onclose = () => {
      setStatus("disconnected");
      reconnectTimeoutRef.current = setTimeout(connect, 5000);
    };
    ws.onerror = () => {
      ws.close();
    };
  }

  const dims = data?.points?.[0]?.length ?? 2;

  // Auto-clamp dimensions when switching datasets/queries with different dimensionalities
  useEffect(() => {
    if (selectedXDim >= dims) setSelectedXDim(0);
    if (selectedYDim >= dims) setSelectedYDim(Math.min(1, dims - 1));
  }, [dims]);

  // Performance Safeguard: Bounding the number of circles rendered on the scatter SVG
  const MAX_PLOTTED_POINTS = 1000;
  const rawPoints = data?.points ?? [];
  const isTruncated = rawPoints.length > MAX_PLOTTED_POINTS;
  const pointsToShow = isTruncated ? rawPoints.slice(0, MAX_PLOTTED_POINTS) : rawPoints;

  const scatterData = pointsToShow.map((p, i) => ({
    x: p[selectedXDim] ?? 0,
    y: p[selectedYDim] ?? 0,
    raw: p,
    i,
  }));

  const maxTime = data
    ? Math.max(
        data.ingestionTimeMs,
        data.localProcessingTimeMs,
        data.globalProcessingTimeMs,
        data.totalProcessingTimeMs
      )
    : 0;

  const timings = [
    {
      label: "Ingestion",
      value: data?.ingestionTimeMs ?? 0,
      color: "var(--accent-blue)",
    },
    {
      label: "Local Processing",
      value: data?.localProcessingTimeMs ?? 0,
      color: "var(--accent-yellow)",
    },
    {
      label: "Global Merge",
      value: data?.globalProcessingTimeMs ?? 0,
      color: "#bf73f2",
    },
    {
      label: "Total Pipeline",
      value: data?.totalProcessingTimeMs ?? 0,
      color: "var(--accent-green)",
    },
    {
      label: "End-to-end Latency",
      value: data?.latencyMs ?? 0,
      color: "var(--accent)",
    },
  ];

  return (
    <div style={S.layout}>
      {/* ── Header ── */}
      <header style={S.header}>
        <div style={S.headerTitle}>
          <span style={S.flinkBadge}>Flink</span>
          Distributed Skyline Processing — Live Dashboard
        </div>
        <div style={statusPillStyle(status)}>
          <span style={dotStyle(status)} />
          {status}
        </div>
      </header>

      <main style={S.main}>
        {/* ── KPI Strip ── */}
        <div style={S.kpiStrip}>
          <div style={S.kpiCell}>
            <div style={S.kpiLabel}>Records Processed</div>
            <div style={S.kpiValue}>
              {data && data.recordCount != null ? data.recordCount.toLocaleString() : "—"}
            </div>
            <div style={S.kpiSub}>before query trigger</div>
          </div>
          <div style={S.kpiCell}>
            <div style={S.kpiLabel}>Skyline Size</div>
            <div style={{ ...S.kpiValue, color: "var(--accent-blue)" }}>
              {data && data.skylineSize != null ? data.skylineSize.toLocaleString() : "—"}
            </div>
            <div style={S.kpiSub}>non-dominated frontier points</div>
          </div>
          <div style={S.kpiCell}>
            <div style={S.kpiLabel}>Pruning Efficiency</div>
            <div style={{ ...S.kpiValue, color: "var(--accent-green)" }}>
              {data && data.optimality != null ? `${(data.optimality * 100).toFixed(1)}%` : "—"}
            </div>
            <div style={S.kpiSub}>local-to-global survivor ratio</div>
          </div>
          <div style={S.kpiCellLast}>
            <div style={S.kpiLabel}>Query Latency</div>
            <div style={{ ...S.kpiValue, color: "var(--accent)" }}>
              {data && data.latencyMs != null ? `${data.latencyMs} ms` : "—"}
            </div>
            <div style={S.kpiSub}>end-to-end from trigger</div>
          </div>
        </div>

        {/* ── Main row ── */}
        <div style={S.row}>
          {/* Scatter chart */}
          <div style={S.panel}>
            <div style={S.panelHeader}>
              <span style={S.panelTitle}>
                Skyline Frontier — 2D Projection
                {isTruncated && (
                  <span style={{ fontSize: 10, color: "var(--accent-yellow)", marginLeft: 8, textTransform: "none", fontWeight: 500 }}>
                    (showing first 1,000 of {rawPoints.length.toLocaleString()} points for performance)
                  </span>
                )}
              </span>
              <div style={S.dimRow}>
                <span style={S.dimLabel}>X:</span>
                <select
                  style={S.select}
                  value={selectedXDim}
                  onChange={(e) => setSelectedXDim(+e.target.value)}
                >
                  {Array.from({ length: dims }, (_, i) => (
                    <option key={i} value={i}>
                      dim {i}
                    </option>
                  ))}
                </select>
                <span style={{ ...S.dimLabel, marginLeft: 8 }}>Y:</span>
                <select
                  style={S.select}
                  value={selectedYDim}
                  onChange={(e) => setSelectedYDim(+e.target.value)}
                >
                  {Array.from({ length: dims }, (_, i) => (
                    <option key={i} value={i}>
                      dim {i}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div style={{ padding: "16px", height: 320 }}>
              {scatterData.length === 0 ? (
                <div style={S.placeholder}>
                  Awaiting first skyline payload from Flink…
                </div>
              ) : (
                <ResponsiveContainer width="100%" height="100%">
                  <ScatterChart>
                    <CartesianGrid
                      strokeDasharray="3 3"
                      stroke="var(--border)"
                    />
                    <XAxis
                      type="number"
                      dataKey="x"
                      stroke="var(--border)"
                      tick={{ fill: "var(--text-muted)", fontSize: 11 }}
                      tickLine={false}
                    />
                    <YAxis
                      type="number"
                      dataKey="y"
                      stroke="var(--border)"
                      tick={{ fill: "var(--text-muted)", fontSize: 11 }}
                      tickLine={false}
                    />
                    <Tooltip
                      cursor={{ strokeDasharray: "3 3", stroke: "var(--border)" }}
                      content={({ active, payload }) => {
                        if (!active || !payload?.length) return null;
                        const p = payload[0].payload;
                        return (
                          <div
                            style={{
                              background: "var(--surface-alt)",
                              border: "1px solid var(--border)",
                              padding: "8px 12px",
                              fontSize: "12px",
                              fontFamily: "inherit",
                            }}
                          >
                            <div style={{ color: "var(--text-muted)", marginBottom: 4 }}>
                              Point #{p.i}
                            </div>
                            <div style={{ color: "var(--text-bright)", fontWeight: 700 }}>
                              {JSON.stringify(p.raw)}
                            </div>
                          </div>
                        );
                      }}
                    />
                    <Scatter
                      data={scatterData}
                      fill="var(--accent-blue)"
                      opacity={0.8}
                    />
                  </ScatterChart>
                </ResponsiveContainer>
              )}
            </div>
          </div>

          {/* Timing table */}
          <div style={S.panel}>
            <div style={S.panelHeader}>
              <span style={S.panelTitle}>Pipeline Timing</span>
              {data && data.queryId != null && (
                <span style={{ fontSize: 11, color: "var(--text-muted)" }}>
                  Query {data.queryId}
                </span>
              )}
            </div>
            <div style={{ padding: "8px 0" }}>
              <table style={S.timingTable}>
                <tbody>
                  {timings.map((t) => (
                    <tr key={t.label} style={S.timingRow}>
                      <td style={S.timingLabel}>{t.label}</td>
                      <td style={S.timingBar}>
                        <Bar value={t.value} max={maxTime} color={t.color} />
                      </td>
                      <td style={{ ...S.timingValue, color: t.color }}>
                        {t.value} ms
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        {/* ── Trend charts ── */}
        <div style={S.panel}>
          <div style={S.panelHeader}>
            <span style={S.panelTitle}>Historical Trends</span>
            <span style={{ fontSize: 11, color: "var(--text-muted)" }}>
              last {history.length} queries
            </span>
          </div>
          <div style={{ padding: "16px", height: 220 }}>
            {history.length === 0 ? (
              <div style={S.placeholder}>
                Accumulating query snapshots…
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart
                  data={history}
                  margin={{ top: 4, right: 16, bottom: 0, left: 0 }}
                >
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                  <XAxis
                    dataKey="queryId"
                    stroke="var(--border)"
                    tick={{ fill: "var(--text-muted)", fontSize: 11 }}
                    tickLine={false}
                  />
                  <YAxis
                    stroke="var(--border)"
                    tick={{ fill: "var(--text-muted)", fontSize: 11 }}
                    tickLine={false}
                  />
                  <Tooltip
                    contentStyle={{
                      background: "var(--surface-alt)",
                      border: "1px solid var(--border)",
                      fontSize: 12,
                      fontFamily: "inherit",
                    }}
                    labelStyle={{ color: "var(--text-muted)" }}
                    itemStyle={{ color: "var(--text-bright)" }}
                  />
                  <Legend
                    wrapperStyle={{ fontSize: 11, color: "var(--text-muted)" }}
                  />
                  <Line
                    type="linear"
                    dataKey="latencyMs"
                    name="Latency (ms)"
                    stroke="var(--accent)"
                    strokeWidth={1.5}
                    dot={false}
                  />
                  <Line
                    type="linear"
                    dataKey="totalProcessingTimeMs"
                    name="Processing (ms)"
                    stroke="var(--accent-green)"
                    strokeWidth={1.5}
                    dot={false}
                  />
                  <Line
                    type="linear"
                    dataKey="skylineSize"
                    name="Skyline Size"
                    stroke="var(--accent-blue)"
                    strokeWidth={1.5}
                    dot={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
      </main>
    </div>
  );
}
