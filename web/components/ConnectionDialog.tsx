import { useState } from "react";
import { api, type Connection, type ConnectionInput } from "../api";
import { Modal, useConfirm, useToast } from "./ui";

const COLORS = ["#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#ec4899", "#14b8a6", "#64748b"];

export function ConnectionDialog({
  initial,
  onClose,
  onSaved,
  onDeleted,
}: {
  initial?: Connection;
  onClose: () => void;
  onSaved: (c: Connection) => void;
  onDeleted?: (id: string) => void;
}) {
  const toast = useToast();
  const confirm = useConfirm();
  const [form, setForm] = useState({
    name: initial?.name ?? "",
    hosts: initial?.contactPoints.join(", ") ?? "127.0.0.1",
    port: String(initial?.port ?? 9042),
    localDataCenter: initial?.localDataCenter ?? "",
    username: initial?.username ?? "",
    password: "",
    ssl: initial?.ssl ?? false,
    color: initial?.color ?? COLORS[0]!,
  });
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; text: string } | null>(null);
  const set = (k: keyof typeof form, v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));

  const toInput = (): ConnectionInput => ({
    id: initial?.id,
    name: form.name.trim() || form.hosts.split(",")[0]!.trim(),
    contactPoints: form.hosts
      .split(",")
      .map((h) => h.trim())
      .filter(Boolean),
    port: Number(form.port) || 9042,
    localDataCenter: form.localDataCenter.trim() || undefined,
    username: form.username.trim() || undefined,
    // Leaving the password blank on an existing profile keeps the stored one.
    password: form.password || (initial?.hasPassword ? undefined : ""),
    ssl: form.ssl,
    color: form.color,
  });

  const test = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const r = await api.testConnection(toInput());
      setTestResult({ ok: true, text: `Connected to “${r.clusterName}” — Cassandra ${r.version} (${r.datacenter})` });
    } catch (e) {
      setTestResult({ ok: false, text: (e as Error).message });
    } finally {
      setTesting(false);
    }
  };

  const save = async () => {
    setSaving(true);
    try {
      onSaved(await api.saveConnection(toInput()));
    } catch (e) {
      toast({ kind: "error", message: (e as Error).message });
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!initial) return;
    if (!(await confirm({ title: "Delete connection", message: `Delete the saved connection “${initial.name}”?`, danger: true, confirmLabel: "Delete" })))
      return;
    await api.deleteConnection(initial.id);
    onDeleted?.(initial.id);
  };

  return (
    <Modal
      title={initial ? "Edit connection" : "New connection"}
      onClose={onClose}
      footer={
        <>
          {initial && (
            <button className="btn btn-danger-ghost" onClick={remove} style={{ marginRight: "auto" }}>
              Delete
            </button>
          )}
          <button className="btn" onClick={test} disabled={testing}>
            {testing ? "Testing…" : "Test connection"}
          </button>
          <button className="btn btn-primary" onClick={save} disabled={saving || !form.hosts.trim()}>
            Save
          </button>
        </>
      }
    >
      <div className="form-grid">
        <label className="field span-2">
          <span>Name</span>
          <input value={form.name} onChange={(e) => set("name", e.target.value)} placeholder="Production" autoFocus />
        </label>
        <label className="field">
          <span>Contact points</span>
          <input value={form.hosts} onChange={(e) => set("hosts", e.target.value)} placeholder="10.0.0.1, 10.0.0.2" />
        </label>
        <label className="field">
          <span>Port</span>
          <input value={form.port} onChange={(e) => set("port", e.target.value)} inputMode="numeric" />
        </label>
        <label className="field span-2">
          <span>Local datacenter</span>
          <input
            value={form.localDataCenter}
            onChange={(e) => set("localDataCenter", e.target.value)}
            placeholder="Auto-detect"
          />
        </label>
        <label className="field">
          <span>Username</span>
          <input value={form.username} onChange={(e) => set("username", e.target.value)} autoComplete="off" />
        </label>
        <label className="field">
          <span>Password</span>
          <input
            type="password"
            value={form.password}
            onChange={(e) => set("password", e.target.value)}
            placeholder={initial?.hasPassword ? "•••••• (unchanged)" : ""}
            autoComplete="new-password"
          />
        </label>
        <label className="check span-2">
          <input type="checkbox" checked={form.ssl} onChange={(e) => set("ssl", e.target.checked)} /> Use SSL/TLS
        </label>
        <div className="field span-2">
          <span>Color</span>
          <div className="color-row">
            {COLORS.map((c) => (
              <button
                key={c}
                className={`color-dot ${form.color === c ? "active" : ""}`}
                style={{ background: c }}
                onClick={() => set("color", c)}
                aria-label={c}
              />
            ))}
          </div>
        </div>
      </div>
      {testResult && <div className={`test-result ${testResult.ok ? "ok" : "fail"}`}>{testResult.text}</div>}
    </Modal>
  );
}
