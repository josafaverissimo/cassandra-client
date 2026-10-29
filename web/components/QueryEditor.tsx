import { indentWithTab } from "@codemirror/commands";
import { sql, SQLDialect } from "@codemirror/lang-sql";
import { Compartment, EditorState, Prec } from "@codemirror/state";
import { oneDark } from "@codemirror/theme-one-dark";
import { EditorView, keymap } from "@codemirror/view";
import { basicSetup } from "codemirror";
import { useEffect, useMemo, useRef, useState } from "react";
import { api, type StatementResult } from "../api";
import { CONSISTENCY_LEVELS, plainToText } from "../cql";
import { useWorkspace } from "../state";
import { CodeBlock, Modal } from "./ui";

const CQL = SQLDialect.define({
  keywords:
    "add aggregate all allow alter and apply as asc ascii authorize batch begin by called clustering columnfamily compact contains count create custom default delete desc describe distinct drop each_quorum entries execute exists filtering finalfunc from frozen full function functions grant if in index infinity initcond input inet insert into is json key keys keyspace keyspaces language like limit list local_one local_quorum login materialized mbean mbeans modify nan norecursive nosuperuser not null of on one options or order partition password per permission permissions primary quorum rename replace returns revoke role roles schema select set sfunc static storage superuser table tables text timestamp to token trigger truncate ttl tuple type unlogged unset update use user users using values view where with writetime maxwritetime access datacenters cidrs masked unmask select_masked mask_inner mask_outer mask_null mask_default mask_replace mask_hash sai describe",
  types:
    "ascii bigint blob boolean counter date decimal double duration float inet int list map set smallint text time timestamp timeuuid tinyint tuple uuid varchar varint vector frozen",
  builtin: "now uuid totimestamp tojson fromjson token ttl writetime count min max sum avg cast tounixtimestamp currenttimestamp currentdate",
  operatorChars: "+-*/<>=!",
  doubleQuotedStrings: false,
  hashComments: false,
  slashComments: true,
});

interface HistoryEntry {
  cql: string;
  at: number;
  conn: string;
  ok: boolean;
}

const HISTORY_KEY = "cc.history";
const loadHistory = (): HistoryEntry[] => {
  try {
    return JSON.parse(localStorage.getItem(HISTORY_KEY) ?? "[]");
  } catch {
    return [];
  }
};
const pushHistory = (e: HistoryEntry) => {
  const list = [e, ...loadHistory().filter((x) => x.cql !== e.cql)].slice(0, 200);
  localStorage.setItem(HISTORY_KEY, JSON.stringify(list));
};

const darkQuery = window.matchMedia("(prefers-color-scheme: dark)");

export function QueryEditor({
  tabKey,
  initialCql,
  initialKeyspace,
  active,
}: {
  tabKey: string;
  initialCql?: string;
  initialKeyspace?: string;
  active: boolean;
}) {
  const ws = useWorkspace();
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const langConf = useRef(new Compartment());
  const themeConf = useRef(new Compartment());
  const runRef = useRef<() => void>(() => {});

  const [keyspace, setKeyspace] = useState<string>(initialKeyspace ?? "");
  const [consistency, setConsistency] = useState("LOCAL_ONE");
  const [pageSize, setPageSize] = useState(200);
  const [trace, setTrace] = useState(false);
  const [continueOnError, setContinueOnError] = useState(false);
  const [running, setRunning] = useState(false);
  const [results, setResults] = useState<StatementResult[]>([]);
  const [activeResult, setActiveResult] = useState(0);
  const [lastCql, setLastCql] = useState("");
  const [showHistory, setShowHistory] = useState(false);
  const [editorHeight, setEditorHeight] = useState(240);

  const schema = useMemo(() => {
    const ns: Record<string, Record<string, string[]>> = {};
    for (const ks of ws.keyspaces) ns[ks.name] = Object.fromEntries([...ks.tables, ...ks.views].map((t) => [t.name, []]));
    return ns;
  }, [ws.keyspaces]);

  const language = () => sql({ dialect: CQL, schema, defaultSchema: keyspace || undefined, upperCaseKeywords: true });

  useEffect(() => {
    const state = EditorState.create({
      doc: initialCql ?? "",
      extensions: [
        basicSetup,
        Prec.highest(
          keymap.of([
            { key: "Mod-Enter", run: () => (runRef.current(), true) },
            { key: "Shift-Enter", run: () => (runRef.current(), true) },
          ]),
        ),
        keymap.of([indentWithTab]),
        langConf.current.of(language()),
        themeConf.current.of(darkQuery.matches ? oneDark : []),
        EditorView.theme({ "&": { height: "100%" }, ".cm-scroller": { fontFamily: "var(--mono)", fontSize: "13px" } }),
      ],
    });
    view.current = new EditorView({ state, parent: host.current! });
    const onTheme = () => view.current?.dispatch({ effects: themeConf.current.reconfigure(darkQuery.matches ? oneDark : []) });
    darkQuery.addEventListener("change", onTheme);
    return () => {
      darkQuery.removeEventListener("change", onTheme);
      view.current?.destroy();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    view.current?.dispatch({ effects: langConf.current.reconfigure(language()) });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema, keyspace]);

  useEffect(() => {
    if (active) requestAnimationFrame(() => view.current?.focus());
  }, [active]);

  const execute = async (cql: string, pageState?: string | null) => {
    if (!cql.trim()) return;
    setRunning(true);
    try {
      const r = await api.execute(ws.conn.id, {
        cql,
        keyspace: keyspace || null,
        pageSize,
        pageState,
        consistency,
        trace,
        continueOnError,
      });
      if (pageState) {
        // "Load more": append rows to the single existing result.
        setResults((prev) => {
          const [p] = prev;
          const [n] = r.results;
          if (!p || !n || n.error) return r.results;
          return [{ ...n, rows: [...(p.rows ?? []), ...(n.rows ?? [])] }];
        });
      } else {
        setResults(r.results);
        const firstError = r.results.findIndex((x) => x.error);
        setActiveResult(firstError >= 0 ? firstError : r.results.length - 1);
        setLastCql(cql);
        pushHistory({ cql, at: Date.now(), conn: ws.conn.name, ok: firstError < 0 });
      }
      if (r.results.some((x) => !x.error && /^\s*(create|alter|drop)\b/i.test(x.statement))) ws.refreshSchema();
      if (r.keyspace && !keyspace) setKeyspace(r.keyspace);
    } catch (e) {
      setResults([{ statement: cql, error: (e as Error).message, elapsedMs: 0 }]);
    } finally {
      setRunning(false);
    }
  };

  runRef.current = () => {
    const v = view.current;
    if (!v) return;
    const sel = v.state.selection.main;
    const text = sel.empty ? v.state.doc.toString() : v.state.sliceDoc(sel.from, sel.to);
    execute(text);
  };

  const startDrag = (e: React.MouseEvent) => {
    const startY = e.clientY;
    const startH = editorHeight;
    const move = (ev: MouseEvent) => setEditorHeight(Math.max(80, Math.min(window.innerHeight - 200, startH + ev.clientY - startY)));
    const up = () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  };

  const current = results[activeResult];

  return (
    <div className="query">
      <div className="query-toolbar">
        <button className="btn btn-primary btn-sm" onClick={() => runRef.current()} disabled={running}>
          {running ? "Running…" : "▶ Run"}
        </button>
        <span className="muted small">Ctrl+Enter · runs selection if any</span>
        <span className="spacer" />
        <label className="inline-field">
          Keyspace
          <select value={keyspace} onChange={(e) => setKeyspace(e.target.value)}>
            <option value="">(none)</option>
            {ws.keyspaces.map((k) => (
              <option key={k.name}>{k.name}</option>
            ))}
          </select>
        </label>
        <label className="inline-field">
          Consistency
          <select value={consistency} onChange={(e) => setConsistency(e.target.value)}>
            {CONSISTENCY_LEVELS.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </label>
        <label className="inline-field">
          Page
          <select value={pageSize} onChange={(e) => setPageSize(Number(e.target.value))}>
            {[50, 100, 200, 500, 1000, 5000].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        <label className="check small">
          <input type="checkbox" checked={trace} onChange={(e) => setTrace(e.target.checked)} /> Tracing
        </label>
        <label className="check small" title="Keep running the remaining statements after an error">
          <input type="checkbox" checked={continueOnError} onChange={(e) => setContinueOnError(e.target.checked)} /> Continue on error
        </label>
        <button className="btn btn-sm" onClick={() => setShowHistory(true)}>
          History
        </button>
      </div>

      <div className="query-editor" style={{ height: editorHeight }} ref={host} />
      <div className="splitter" onMouseDown={startDrag} />

      <div className="query-results">
        {results.length > 1 && (
          <div className="subtabs">
            {results.map((r, i) => (
              <button key={i} className={`subtab ${i === activeResult ? "active" : ""} ${r.error ? "subtab-error" : ""}`} onClick={() => setActiveResult(i)}>
                {r.error ? "✕ " : ""}
                {r.statement.replace(/\s+/g, " ").slice(0, 32)}
              </button>
            ))}
          </div>
        )}
        {!current && <div className="muted center pad">Run a statement to see results here.</div>}
        {current && (
          <ResultView
            result={current}
            canLoadMore={results.length === 1 && !!current.pageState}
            onLoadMore={() => execute(lastCql, current.pageState)}
            loading={running}
          />
        )}
      </div>

      {showHistory && (
        <HistoryDialog
          onClose={() => setShowHistory(false)}
          onPick={(cql) => {
            const v = view.current!;
            v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: cql } });
            setShowHistory(false);
            v.focus();
          }}
        />
      )}
    </div>
  );
}

function ResultView({
  result,
  canLoadMore,
  onLoadMore,
  loading,
}: {
  result: StatementResult;
  canLoadMore: boolean;
  onLoadMore: () => void;
  loading: boolean;
}) {
  const [view, setView] = useState<"table" | "json" | "trace">("table");
  if (result.error)
    return (
      <div className="result-error">
        <pre>{result.error}</pre>
        <CodeBlock code={result.statement} maxHeight={160} />
      </div>
    );
  const cols = result.columns ?? [];
  const rows = result.rows ?? [];
  const asObjects = () => rows.map((r) => Object.fromEntries(cols.map((c, i) => [c.name, r[i]])));

  const download = (format: "json" | "csv") => {
    const esc = (s: string) => (/[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
    const text =
      format === "json"
        ? JSON.stringify(asObjects(), null, 2)
        : [cols.map((c) => esc(c.name)).join(","), ...rows.map((r) => r.map((v) => (v === null ? "" : esc(plainToText(v)))).join(","))].join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: format === "json" ? "application/json" : "text/csv" }));
    a.download = `result.${format}`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <div className="result">
      <div className="result-bar">
        {cols.length > 0 ? (
          <>
            <span className="small">
              <b>{rows.length}</b> row(s)
            </span>
            <div className="seg">
              <button className={view === "table" ? "active" : ""} onClick={() => setView("table")}>
                Table
              </button>
              <button className={view === "json" ? "active" : ""} onClick={() => setView("json")}>
                JSON
              </button>
              {result.trace && (
                <button className={view === "trace" ? "active" : ""} onClick={() => setView("trace")}>
                  Trace
                </button>
              )}
            </div>
          </>
        ) : (
          <span className="small ok-text">✓ Statement executed</span>
        )}
        <span className="muted small">
          {result.elapsedMs} ms{result.queriedHost ? ` · ${result.queriedHost}` : ""}
        </span>
        {result.warnings?.map((w) => (
          <span key={w} className="warn small">
            ⚠ {w}
          </span>
        ))}
        <span className="spacer" />
        {cols.length > 0 && (
          <>
            <button className="btn btn-xs" onClick={() => download("csv")}>
              CSV
            </button>
            <button className="btn btn-xs" onClick={() => download("json")}>
              JSON
            </button>
          </>
        )}
        {!cols.length && result.trace && (
          <button className="btn btn-xs" onClick={() => setView("trace")}>
            Trace
          </button>
        )}
      </div>
      {view === "trace" && result.trace ? (
        <TraceView trace={result.trace} />
      ) : view === "json" ? (
        <CodeBlock code={JSON.stringify(asObjects(), null, 2)} />
      ) : (
        cols.length > 0 && (
          <div className="grid-scroll">
            <table className="grid">
              <thead>
                <tr>
                  <th className="rownum" />
                  {cols.map((c, i) => (
                    <th key={i}>
                      <div className="th-inner">
                        <span className="th-name">{c.name}</span>
                      </div>
                      <div className="th-type">{c.type}</div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={i}>
                    <td className="rownum muted">{i + 1}</td>
                    {r.map((v, j) => (
                      <td
                        key={j}
                        className={`cell ro ${v === null ? "null" : ""} ${typeof v === "object" && v !== null ? "mono" : ""} ${typeof v === "number" ? "num" : ""}`}
                        title={plainToText(v)}
                      >
                        <span className="cell-text">{plainToText(v)}</span>
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
            {canLoadMore && (
              <div className="center pad">
                <button className="btn btn-sm" onClick={onLoadMore} disabled={loading}>
                  Load more
                </button>
              </div>
            )}
          </div>
        )
      )}
    </div>
  );
}

function TraceView({ trace }: { trace: NonNullable<StatementResult["trace"]> }) {
  if (trace.error) return <pre className="pad">{trace.error}</pre>;
  return (
    <div className="grid-scroll">
      <div className="pad small muted">
        Trace {trace.traceId} · coordinator {trace.coordinator} · {((trace.durationMicros ?? 0) / 1000).toFixed(2)} ms
      </div>
      <table className="list-table">
        <thead>
          <tr>
            <th>Activity</th>
            <th>Source</th>
            <th>Elapsed (µs)</th>
            <th>Thread</th>
          </tr>
        </thead>
        <tbody>
          {trace.events?.map((e, i) => (
            <tr key={i}>
              <td>{e.activity}</td>
              <td className="mono small">{e.source}</td>
              <td className="num">{e.elapsedMicros}</td>
              <td className="muted small">{e.thread}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function HistoryDialog({ onClose, onPick }: { onClose: () => void; onPick: (cql: string) => void }) {
  const [items, setItems] = useState(loadHistory);
  const [q, setQ] = useState("");
  const shown = items.filter((i) => !q || i.cql.toLowerCase().includes(q.toLowerCase()));
  return (
    <Modal
      wide
      title="Query history"
      onClose={onClose}
      footer={
        <button
          className="btn btn-danger-ghost"
          onClick={() => {
            localStorage.removeItem(HISTORY_KEY);
            setItems([]);
          }}
        >
          Clear history
        </button>
      }
    >
      <input className="full mb" placeholder="Search…" value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
      <div className="history">
        {shown.map((h) => (
          <button key={h.at} className="history-item" onClick={() => onPick(h.cql)}>
            <div className="small muted">
              {h.ok ? "✓" : "✕"} {new Date(h.at).toLocaleString()} · {h.conn}
            </div>
            <pre>{h.cql.length > 400 ? h.cql.slice(0, 400) + "…" : h.cql}</pre>
          </button>
        ))}
        {shown.length === 0 && <div className="muted center pad">No queries yet.</div>}
      </div>
    </Modal>
  );
}
