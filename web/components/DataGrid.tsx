import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, type Column, type Filter, type Row, type RowsResult, type TableInfo } from "../api";
import { displayValue, filterValueType, fromEditable, isNull, toEditable, valueKind } from "../cql";
import { useWorkspace } from "../state";
import { CodeBlock, KindBadge, Modal, useConfirm, useToast } from "./ui";

interface FilterDraft {
  id: number;
  column: string;
  op: string;
  text: string;
}

const OPS = ["=", "<", "<=", ">", ">=", "IN", "CONTAINS", "CONTAINS KEY", "LIKE"];
const PAGE_SIZES = [25, 50, 100, 250, 500, 1000];

type CellRef = { row: number; isNew: boolean; col: string };
type Edits = Record<number, Record<string, string | null>>;

let filterSeq = 1;

export function DataGrid({ info }: { info: TableInfo }) {
  const ws = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const conn = ws.conn.id;
  const columns = info.columns;
  const keyCols = useMemo(() => columns.filter((c) => c.kind === "partition_key" || c.kind === "clustering"), [columns]);
  const readOnly = info.kind !== "table";

  const [filters, setFilters] = useState<FilterDraft[]>([]);
  const [applied, setApplied] = useState<Filter[]>([]);
  const [allowFiltering, setAllowFiltering] = useState(false);
  const [pageSize, setPageSize] = useState(() => Number(localStorage.getItem("cc.pageSize")) || 100);
  const [pageStates, setPageStates] = useState<(string | null)[]>([null]);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<RowsResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [edits, setEdits] = useState<Edits>({});
  const [newRows, setNewRows] = useState<Row[]>([]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [editing, setEditing] = useState<CellRef | null>(null);
  const [bigEditor, setBigEditor] = useState<CellRef | null>(null);
  const [count, setCount] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [showCql, setShowCql] = useState(false);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [colMenu, setColMenu] = useState(false);

  const dirtyCount = Object.values(edits).reduce((n, e) => n + Object.keys(e).length, 0) + newRows.length;

  const load = useCallback(
    async (opts: { pageIndex: number; states: (string | null)[]; filters: Filter[]; allow: boolean; size: number }) => {
      setLoading(true);
      setError(null);
      try {
        const r = await api.queryRows(conn, {
          keyspace: info.keyspace,
          table: info.name,
          filters: opts.filters,
          allowFiltering: opts.allow,
          pageSize: opts.size,
          pageState: opts.states[opts.pageIndex],
        });
        setResult(r);
        setPage(opts.pageIndex);
        const states = opts.states.slice(0, opts.pageIndex + 1);
        states[opts.pageIndex + 1] = r.pageState;
        setPageStates(states);
        setEdits({});
        setSelected(new Set());
        setEditing(null);
      } catch (e) {
        setError((e as Error).message);
      } finally {
        setLoading(false);
      }
    },
    [conn, info.keyspace, info.name],
  );

  const reload = (pageIndex = 0, overrides: Partial<{ filters: Filter[]; allow: boolean; size: number }> = {}) =>
    load({
      pageIndex,
      states: pageIndex === 0 ? [null] : pageStates,
      filters: overrides.filters ?? applied,
      allow: overrides.allow ?? allowFiltering,
      size: overrides.size ?? pageSize,
    });

  useEffect(() => {
    reload(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [info]);

  const guardDirty = async () =>
    dirtyCount === 0 ||
    confirm({ title: "Discard changes?", message: `You have ${dirtyCount} unsaved change(s).`, confirmLabel: "Discard", danger: true });

  const applyFilters = async () => {
    try {
      const built = filters
        .filter((f) => f.column && (f.text !== "" || valueKind(colType(f.column)) === "string"))
        .map((f) => buildFilter(f, columns));
      if (!(await guardDirty())) return;
      setNewRows([]);
      setApplied(built);
      setCount(null);
      reload(0, { filters: built });
    } catch (e) {
      toast({ kind: "error", message: (e as Error).message });
    }
  };

  const colType = (name: string) => columns.find((c) => c.name === name)?.type ?? "text";

  const rows = result?.rows ?? [];
  const visibleCols = columns.filter((c) => !hidden.has(c.name));

  const cellValue = (ref: CellRef) =>
    ref.isNew
      ? (newRows[ref.row]?.[ref.col] ?? null)
      : ref.col in (edits[ref.row] ?? {})
        ? edits[ref.row]![ref.col]!
        : (rows[ref.row]?.[ref.col] ?? null);

  const setCell = (ref: CellRef, json: string | null) => {
    if (ref.isNew) {
      setNewRows((rs) => rs.map((r, i) => (i === ref.row ? { ...r, [ref.col]: json } : r)));
      return;
    }
    const original = rows[ref.row]?.[ref.col] ?? null;
    setEdits((all) => {
      const rowEdits = { ...(all[ref.row] ?? {}) };
      const same = (isNull(original) && isNull(json)) || original === json;
      if (same) delete rowEdits[ref.col];
      else rowEdits[ref.col] = json;
      const next = { ...all };
      if (Object.keys(rowEdits).length) next[ref.row] = rowEdits;
      else delete next[ref.row];
      return next;
    });
  };

  const canEdit = (ref: CellRef) => {
    if (readOnly) return false;
    const col = columns.find((c) => c.name === ref.col)!;
    if (!ref.isNew && (col.kind === "partition_key" || col.kind === "clustering")) return false;
    return true;
  };

  const startEdit = (ref: CellRef, forceBig = false) => {
    if (!canEdit(ref)) return;
    const type = colType(ref.col);
    const text = toEditable(cellValue(ref), type);
    if (forceBig || valueKind(type) === "json" || text.length > 80 || text.includes("\n")) setBigEditor(ref);
    else setEditing(ref);
  };

  const keyOf = (row: Row) => Object.fromEntries(keyCols.map((c) => [c.name, row[c.name] ?? "null"]));

  const save = async () => {
    setSaving(true);
    const executed: string[] = [];
    let failed = 0;
    try {
      for (const [idx, changes] of Object.entries(edits)) {
        const row = rows[Number(idx)]!;
        try {
          const counters = Object.entries(changes).filter(([c]) => colType(c) === "counter");
          const regular = Object.fromEntries(Object.entries(changes).filter(([c]) => colType(c) !== "counter"));
          if (Object.keys(regular).length)
            executed.push((await api.updateRow(conn, { keyspace: info.keyspace, table: info.name, key: keyOf(row), values: regular })).cql);
          for (const [column, value] of counters) {
            const delta = (BigInt(value ?? "0") - BigInt(isNull(row[column] ?? null) ? "0" : row[column]!)).toString();
            executed.push(
              (await api.incrementCounter(conn, { keyspace: info.keyspace, table: info.name, key: keyOf(row), column, delta })).cql,
            );
          }
        } catch (e) {
          failed++;
          toast({ kind: "error", message: `Row ${Number(idx) + 1}: ${(e as Error).message}` });
        }
      }
      const remaining: Row[] = [];
      for (const r of newRows) {
        try {
          if (info.isCounter) {
            for (const c of columns.filter((c) => c.type === "counter" && !isNull(r[c.name] ?? null)))
              executed.push(
                (await api.incrementCounter(conn, { keyspace: info.keyspace, table: info.name, key: keyOf(r), column: c.name, delta: r[c.name]! })).cql,
              );
          } else {
            executed.push((await api.insertRow(conn, { keyspace: info.keyspace, table: info.name, values: r })).cql);
          }
        } catch (e) {
          failed++;
          remaining.push(r);
          toast({ kind: "error", message: `New row: ${(e as Error).message}` });
        }
      }
      if (executed.length) toast({ kind: "ok", message: `${executed.length} statement(s) applied`, detail: executed.join(";\n") });
      setNewRows(remaining);
      if (!failed) await reload(page);
    } finally {
      setSaving(false);
    }
  };

  const discard = () => {
    setEdits({});
    setNewRows([]);
    setEditing(null);
  };

  const deleteSelected = async () => {
    const keys = [...selected].map((i) => keyOf(rows[i]!));
    if (!keys.length) return;
    const preview = keys
      .slice(0, 5)
      .map((k) => `DELETE FROM ${info.keyspace}.${info.name} WHERE ${Object.entries(k).map(([c, v]) => `${c} = ${v}`).join(" AND ")};`)
      .join("\n");
    if (
      !(await confirm({
        title: `Delete ${keys.length} row(s)?`,
        cql: preview + (keys.length > 5 ? `\n-- … and ${keys.length - 5} more` : ""),
        danger: true,
        confirmLabel: "Delete",
      }))
    )
      return;
    try {
      const r = await api.deleteRows(conn, { keyspace: info.keyspace, table: info.name, keys });
      toast({ kind: "ok", message: `${keys.length} row(s) deleted`, detail: r.cql });
      reload(page);
    } catch (e) {
      toast({ kind: "error", message: (e as Error).message });
    }
  };

  const addRow = () => setNewRows((rs) => [Object.fromEntries(columns.map((c) => [c.name, null])), ...rs]);

  const runCount = async () => {
    setCount("…");
    try {
      setCount(
        (await api.countRows(conn, { keyspace: info.keyspace, table: info.name, filters: applied, allowFiltering })).count,
      );
    } catch (e) {
      setCount(null);
      toast({ kind: "error", message: (e as Error).message });
    }
  };

  const exportData = (format: "json" | "csv") => {
    const all = rows.map((r, i) => ({ ...r, ...(edits[i] ?? {}) }));
    let text: string;
    if (format === "json") {
      // Compose raw JSON text so bigint/decimal values keep full precision.
      text = "[\n" + all.map((r) => "  {" + visibleCols.map((c) => `${JSON.stringify(c.name)}: ${r[c.name] ?? "null"}`).join(", ") + "}").join(",\n") + "\n]";
    } else {
      const esc = (s: string) => (/[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
      text = [visibleCols.map((c) => esc(c.name)).join(","), ...all.map((r) => visibleCols.map((c) => (isNull(r[c.name] ?? null) ? "" : esc(toEditable(r[c.name]!, c.type)))).join(","))].join("\n");
    }
    const blob = new Blob([text], { type: format === "json" ? "application/json" : "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${info.keyspace}.${info.name}.page${page + 1}.${format}`;
    a.click();
    URL.revokeObjectURL(a.href);
  };

  const allSelected = rows.length > 0 && selected.size === rows.length;
  const needsAllowFiltering = !!error && /ALLOW FILTERING/i.test(error) && !allowFiltering;

  return (
    <div className="grid-wrap">
      <div className="grid-toolbar">
        <button className="btn btn-sm" onClick={async () => (await guardDirty()) && (discard(), reload(page))} title="Reload">
          ⟳
        </button>
        <button
          className={`btn btn-sm ${filters.length ? "btn-active" : ""}`}
          onClick={() => setFilters((fs) => (fs.length ? fs : [{ id: filterSeq++, column: keyCols[0]?.name ?? columns[0]!.name, op: "=", text: "" }]))}
        >
          ⧩ Filters{applied.length ? ` (${applied.length})` : ""}
        </button>
        <div className="col-picker">
          <button className="btn btn-sm" onClick={() => setColMenu((v) => !v)}>
            ☰ Columns {hidden.size ? `(${columns.length - hidden.size}/${columns.length})` : ""}
          </button>
          {colMenu && (
            <div className="menu" onMouseLeave={() => setColMenu(false)}>
              {columns.map((c) => (
                <label key={c.name} className="menu-item check">
                  <input
                    type="checkbox"
                    checked={!hidden.has(c.name)}
                    onChange={() =>
                      setHidden((h) => {
                        const n = new Set(h);
                        n.has(c.name) ? n.delete(c.name) : n.add(c.name);
                        return n;
                      })
                    }
                  />
                  {c.name}
                </label>
              ))}
            </div>
          )}
        </div>
        {!readOnly && (
          <>
            <span className="sep" />
            <button className="btn btn-sm" onClick={addRow}>
              + Add row
            </button>
            <button className="btn btn-sm btn-danger-ghost" disabled={!selected.size} onClick={deleteSelected}>
              Delete{selected.size ? ` (${selected.size})` : ""}
            </button>
          </>
        )}
        <span className="spacer" />
        <button className="btn btn-sm" onClick={runCount} title="SELECT COUNT(*) — can be slow on large tables">
          {count === null ? "Count rows" : `${count} rows`}
        </button>
        <button className="btn btn-sm" onClick={() => exportData("csv")} disabled={!rows.length}>
          CSV
        </button>
        <button className="btn btn-sm" onClick={() => exportData("json")} disabled={!rows.length}>
          JSON
        </button>
      </div>

      {filters.length > 0 && (
        <div className="filter-bar">
          {filters.map((f) => {
            const col = columns.find((c) => c.name === f.column);
            return (
              <div key={f.id} className="filter-row">
                <select value={f.column} onChange={(e) => setFilters((fs) => fs.map((x) => (x.id === f.id ? { ...x, column: e.target.value } : x)))}>
                  {columns.map((c) => (
                    <option key={c.name} value={c.name}>
                      {c.name}
                    </option>
                  ))}
                </select>
                <select value={f.op} onChange={(e) => setFilters((fs) => fs.map((x) => (x.id === f.id ? { ...x, op: e.target.value } : x)))}>
                  {OPS.map((o) => (
                    <option key={o}>{o}</option>
                  ))}
                </select>
                <input
                  value={f.text}
                  placeholder={f.op === "IN" ? "a, b, c  or  JSON array" : col ? filterValueType(col.type, f.op) : ""}
                  onChange={(e) => setFilters((fs) => fs.map((x) => (x.id === f.id ? { ...x, text: e.target.value } : x)))}
                  onKeyDown={(e) => e.key === "Enter" && applyFilters()}
                />
                <button className="icon-btn" onClick={() => setFilters((fs) => fs.filter((x) => x.id !== f.id))}>
                  ✕
                </button>
              </div>
            );
          })}
          <div className="row gap">
            <button
              className="btn btn-xs"
              onClick={() => setFilters((fs) => [...fs, { id: filterSeq++, column: columns[0]!.name, op: "=", text: "" }])}
            >
              + Condition
            </button>
            <label className="check small">
              <input
                type="checkbox"
                checked={allowFiltering}
                onChange={(e) => {
                  setAllowFiltering(e.target.checked);
                  reload(0, { allow: e.target.checked });
                }}
              />
              ALLOW FILTERING
            </label>
            <span className="spacer" />
            <button
              className="btn btn-xs"
              onClick={() => {
                setFilters([]);
                setApplied([]);
                setCount(null);
                reload(0, { filters: [] });
              }}
            >
              Clear
            </button>
            <button className="btn btn-xs btn-primary" onClick={applyFilters}>
              Apply
            </button>
          </div>
        </div>
      )}

      {error && (
        <div className="grid-error">
          <pre>{error}</pre>
          {needsAllowFiltering && (
            <button
              className="btn btn-sm"
              onClick={() => {
                setAllowFiltering(true);
                reload(0, { allow: true });
              }}
            >
              Retry with ALLOW FILTERING
            </button>
          )}
        </div>
      )}

      <div className={`grid-scroll ${loading ? "loading" : ""}`}>
        <table className="grid">
          <thead>
            <tr>
              <th className="rownum">
                {!readOnly && (
                  <input
                    type="checkbox"
                    checked={allSelected}
                    onChange={() => setSelected(allSelected ? new Set() : new Set(rows.map((_, i) => i)))}
                  />
                )}
              </th>
              {visibleCols.map((c) => (
                <th key={c.name} title={`${c.name} ${c.type}${c.kind !== "regular" ? ` (${c.kind.replace("_", " ")})` : ""}`}>
                  <div className="th-inner">
                    <span className="th-name">{c.name}</span>
                    <KindBadge kind={c.kind} />
                  </div>
                  <div className="th-type">{c.type}</div>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {newRows.map((r, i) => (
              <tr key={`new-${i}`} className="row-new">
                <td className="rownum">
                  <button className="icon-btn danger" title="Remove new row" onClick={() => setNewRows((rs) => rs.filter((_, j) => j !== i))}>
                    ✕
                  </button>
                </td>
                {visibleCols.map((c) => (
                  <Cell
                    key={c.name}
                    column={c}
                    value={r[c.name] ?? null}
                    changed={!isNull(r[c.name] ?? null)}
                    required={c.kind === "partition_key" || c.kind === "clustering"}
                    editing={editing?.isNew === true && editing.row === i && editing.col === c.name}
                    editable
                    onStart={(big) => startEdit({ row: i, isNew: true, col: c.name }, big)}
                    onCommit={(v) => {
                      setCell({ row: i, isNew: true, col: c.name }, v);
                      setEditing(null);
                    }}
                    onCancel={() => setEditing(null)}
                  />
                ))}
              </tr>
            ))}
            {rows.map((r, i) => (
              <tr key={i} className={selected.has(i) ? "row-selected" : ""}>
                <td className="rownum">
                  {readOnly ? (
                    <span className="muted">{page * pageSize + i + 1}</span>
                  ) : (
                    <label className="rownum-label">
                      <input
                        type="checkbox"
                        checked={selected.has(i)}
                        onChange={() =>
                          setSelected((s) => {
                            const n = new Set(s);
                            n.has(i) ? n.delete(i) : n.add(i);
                            return n;
                          })
                        }
                      />
                      <span className="muted">{page * pageSize + i + 1}</span>
                    </label>
                  )}
                </td>
                {visibleCols.map((c) => {
                  const ref = { row: i, isNew: false, col: c.name };
                  const changed = c.name in (edits[i] ?? {});
                  return (
                    <Cell
                      key={c.name}
                      column={c}
                      value={changed ? edits[i]![c.name]! : (r[c.name] ?? null)}
                      changed={changed}
                      editing={!editing?.isNew && editing?.row === i && editing.col === c.name}
                      editable={canEdit(ref)}
                      onStart={(big) => startEdit(ref, big)}
                      onCommit={(v) => {
                        setCell(ref, v);
                        setEditing(null);
                      }}
                      onCancel={() => setEditing(null)}
                    />
                  );
                })}
              </tr>
            ))}
            {!loading && !error && rows.length === 0 && newRows.length === 0 && (
              <tr>
                <td colSpan={visibleCols.length + 1} className="grid-empty">
                  No rows{applied.length ? " match these filters" : ""}.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="grid-footer">
        {dirtyCount > 0 ? (
          <div className="pending">
            <b>{dirtyCount}</b> pending change(s)
            <button className="btn btn-sm" onClick={discard} disabled={saving}>
              Discard
            </button>
            <button className="btn btn-sm btn-primary" onClick={save} disabled={saving}>
              {saving ? "Saving…" : `Save ${dirtyCount} change(s)`}
            </button>
          </div>
        ) : (
          <button className="btn btn-xs link" onClick={() => setShowCql((v) => !v)}>
            {result ? `${rows.length} row(s) · ${result.elapsedMs} ms` : ""} {showCql ? "▾" : "▸"} CQL
          </button>
        )}
        <span className="spacer" />
        {result?.warnings.map((w) => (
          <span key={w} className="warn small" title={w}>
            ⚠ {w.slice(0, 60)}
          </span>
        ))}
        <select
          value={pageSize}
          onChange={async (e) => {
            if (!(await guardDirty())) return;
            const size = Number(e.target.value);
            setPageSize(size);
            localStorage.setItem("cc.pageSize", String(size));
            reload(0, { size });
          }}
        >
          {PAGE_SIZES.map((s) => (
            <option key={s} value={s}>
              {s} / page
            </option>
          ))}
        </select>
        <button className="btn btn-sm" disabled={page === 0 || loading} onClick={async () => (await guardDirty()) && reload(page - 1)}>
          ‹ Prev
        </button>
        <span className="small muted">Page {page + 1}</span>
        <button
          className="btn btn-sm"
          disabled={!pageStates[page + 1] || loading}
          onClick={async () => (await guardDirty()) && reload(page + 1)}
        >
          Next ›
        </button>
      </div>
      {showCql && result && dirtyCount === 0 && <CodeBlock code={result.cql} maxHeight={140} />}

      {bigEditor && (
        <BigEditor
          column={columns.find((c) => c.name === bigEditor.col)!}
          value={cellValue(bigEditor)}
          onClose={() => setBigEditor(null)}
          onSave={(v) => {
            setCell(bigEditor, v);
            setBigEditor(null);
          }}
        />
      )}
    </div>
  );
}

function buildFilter(f: FilterDraft, columns: Column[]): Filter {
  const col = columns.find((c) => c.name === f.column)!;
  const type = filterValueType(col.type, f.op);
  if (f.op === "IN") {
    const t = f.text.trim();
    if (t.startsWith("[")) return { column: f.column, op: f.op, value: t };
    const items = t.split(",").map((s) => fromEditable(valueKind(type) === "string" ? s.trim() : s, type));
    return { column: f.column, op: f.op, value: `[${items.join(", ")}]` };
  }
  return { column: f.column, op: f.op, value: fromEditable(f.text, type) };
}

function Cell({
  column,
  value,
  changed,
  editing,
  editable,
  required,
  onStart,
  onCommit,
  onCancel,
}: {
  column: Column;
  value: string | null;
  changed: boolean;
  editing: boolean;
  editable: boolean;
  required?: boolean;
  onStart: (big: boolean) => void;
  onCommit: (json: string | null) => void;
  onCancel: () => void;
}) {
  const toast = useToast();
  const kind = valueKind(column.type);
  const nul = isNull(value);
  const [text, setText] = useState("");
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (editing) {
      setText(toEditable(value, column.type));
      requestAnimationFrame(() => input.current?.select());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editing]);

  const commit = () => {
    try {
      onCommit(text === "" && kind !== "string" ? null : fromEditable(text, column.type));
    } catch (e) {
      toast({ kind: "error", message: (e as Error).message });
    }
  };

  if (editing) {
    return (
      <td className="cell editing">
        {kind === "boolean" ? (
          <select
            autoFocus
            defaultValue={nul ? "" : value!}
            onChange={(e) => onCommit(e.target.value === "" ? null : e.target.value)}
            onBlur={onCancel}
            onKeyDown={(e) => e.key === "Escape" && onCancel()}
          >
            <option value="">NULL</option>
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
        ) : (
          <input
            ref={input}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onBlur={commit}
            onKeyDown={(e) => {
              if (e.key === "Enter") commit();
              else if (e.key === "Escape") onCancel();
            }}
          />
        )}
      </td>
    );
  }

  const display = displayValue(value, column.type);
  return (
    <td
      className={`cell ${nul ? "null" : ""} ${changed ? "changed" : ""} ${kind === "json" ? "mono" : ""} ${kind === "number" ? "num" : ""} ${!editable ? "ro" : ""} ${required && nul ? "required" : ""}`}
      onDoubleClick={() => editable && onStart(false)}
      title={editable ? `${display}\n\nDouble-click to edit` : display}
    >
      <span className="cell-text">{display}</span>
      {editable && (
        <button className="cell-expand" title="Open editor" onClick={() => onStart(true)}>
          ⤢
        </button>
      )}
    </td>
  );
}

function BigEditor({
  column,
  value,
  onClose,
  onSave,
}: {
  column: Column;
  value: string | null;
  onClose: () => void;
  onSave: (json: string | null) => void;
}) {
  const kind = valueKind(column.type);
  const initial = kind === "json" && !isNull(value) ? prettyJson(value!) : toEditable(value, column.type);
  const [text, setText] = useState(initial);
  const [err, setErr] = useState<string | null>(null);
  const save = () => {
    try {
      onSave(fromEditable(kind === "json" ? text.trim() : text, column.type));
    } catch (e) {
      setErr((e as Error).message);
    }
  };
  return (
    <Modal
      wide
      title={
        <>
          {column.name} <span className="muted mono small">{column.type}</span>
        </>
      }
      onClose={onClose}
      footer={
        <>
          <button className="btn btn-danger-ghost" style={{ marginRight: "auto" }} onClick={() => onSave(null)}>
            Set NULL
          </button>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={save}>
            Apply
          </button>
        </>
      }
    >
      <textarea
        className="big-editor mono"
        autoFocus
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setErr(null);
        }}
        onKeyDown={(e) => (e.ctrlKey || e.metaKey) && e.key === "Enter" && save()}
        spellCheck={false}
      />
      {kind === "json" && <p className="muted small">Value in Cassandra JSON format (as accepted by fromJson()). Ctrl+Enter to apply.</p>}
      {err && <div className="test-result fail">{err}</div>}
    </Modal>
  );
}

/** Re-indents JSON text without parsing numbers (keeps bigint precision). */
function prettyJson(text: string) {
  let out = "";
  let indent = 0;
  let inStr = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]!;
    if (inStr) {
      out += ch;
      if (ch === "\\") out += text[++i] ?? "";
      else if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') {
      inStr = true;
      out += ch;
    } else if (ch === "{" || ch === "[") {
      const close = ch === "{" ? "}" : "]";
      const next = text.slice(i + 1).trimStart()[0];
      if (next === close) {
        out += ch + close;
        i = text.indexOf(close, i);
      } else {
        indent++;
        out += ch + "\n" + "  ".repeat(indent);
      }
    } else if (ch === "}" || ch === "]") {
      indent--;
      out += "\n" + "  ".repeat(indent) + ch;
    } else if (ch === ",") out += ",\n" + "  ".repeat(indent);
    else if (ch === ":") out += ": ";
    else if (!/\s/.test(ch)) out += ch;
  }
  return out;
}
