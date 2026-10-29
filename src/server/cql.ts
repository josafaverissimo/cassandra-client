import cassandra from "cassandra-driver";

const { types } = cassandra;

/** Always quote identifiers so mixed case and reserved words round-trip exactly. */
export const quoteId = (name: string) => `"${name.replace(/"/g, '""')}"`;
export const quoteStr = (value: string) => `'${value.replace(/'/g, "''")}'`;
export const qualified = (ks: string, name: string) => `${quoteId(ks)}.${quoteId(name)}`;

/** Converts driver values (Long, Uuid, Buffer, Map...) into JSON-safe values. */
export function toPlain(value: unknown): unknown {
  if (value === null || value === undefined) return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : String(value);
  if (typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "bigint") return value.toString();
  if (Buffer.isBuffer(value)) return "0x" + value.toString("hex");
  if (value instanceof Date) return value.toISOString();
  if (value instanceof types.Tuple) return value.values().map(toPlain);
  if (value instanceof Map) {
    const out: Record<string, unknown> = {};
    for (const [k, v] of value) out[typeof k === "string" ? k : JSON.stringify(toPlain(k))] = toPlain(v);
    return out;
  }
  if (value instanceof Set) return [...value].map(toPlain);
  if (Array.isArray(value)) return value.map(toPlain);
  if (ArrayBuffer.isView(value)) return Array.from(value as unknown as ArrayLike<number>);
  if (
    value instanceof types.Long ||
    value instanceof types.BigDecimal ||
    value instanceof types.Integer ||
    value instanceof types.Uuid ||
    value instanceof types.InetAddress ||
    value instanceof types.LocalDate ||
    value instanceof types.LocalTime ||
    value instanceof types.Duration
  ) {
    return value.toString();
  }
  if (typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) out[k] = toPlain(v);
    return out;
  }
  return String(value);
}

/**
 * Splits a CQL script into statements, honouring quoted strings, quoted
 * identifiers, $$ bodies and comments.
 */
export function splitStatements(script: string): string[] {
  const out: string[] = [];
  let current = "";
  let i = 0;
  const n = script.length;
  while (i < n) {
    const ch = script[i];
    const next = script[i + 1];
    if (ch === "'" || ch === '"') {
      let j = i + 1;
      while (j < n) {
        if (script[j] === ch) {
          if (script[j + 1] === ch) j += 2;
          else break;
        } else j++;
      }
      current += script.slice(i, j + 1);
      i = j + 1;
    } else if (ch === "$" && next === "$") {
      const end = script.indexOf("$$", i + 2);
      const j = end === -1 ? n : end + 2;
      current += script.slice(i, j);
      i = j;
    } else if ((ch === "-" && next === "-") || (ch === "/" && next === "/")) {
      const end = script.indexOf("\n", i);
      const j = end === -1 ? n : end;
      current += script.slice(i, j);
      i = j;
    } else if (ch === "/" && next === "*") {
      const end = script.indexOf("*/", i + 2);
      const j = end === -1 ? n : end + 2;
      current += script.slice(i, j);
      i = j;
    } else if (ch === ";") {
      out.push(current);
      current = "";
      i++;
    } else {
      current += ch;
      i++;
    }
  }
  out.push(current);
  return out.map((s) => s.trim()).filter((s) => stripComments(s).trim().length > 0);
}

function stripComments(s: string) {
  return s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(--|\/\/).*$/gm, "");
}

/** Splits the top-level elements of a JSON array without parsing numbers (keeps bigint precision). */
export function splitJsonArray(text: string): string[] {
  const s = text.trim();
  if (!s.startsWith("[") || !s.endsWith("]")) throw new Error("IN expects a JSON array, e.g. [1, 2, 3]");
  const items: string[] = [];
  let depth = 0;
  let inStr = false;
  let start = 1;
  for (let i = 1; i < s.length - 1; i++) {
    const ch = s[i];
    if (inStr) {
      if (ch === "\\") i++;
      else if (ch === '"') inStr = false;
    } else if (ch === '"') inStr = true;
    else if (ch === "[" || ch === "{") depth++;
    else if (ch === "]" || ch === "}") depth--;
    else if (ch === "," && depth === 0) {
      items.push(s.slice(start, i).trim());
      start = i + 1;
    }
  }
  const last = s.slice(start, s.length - 1).trim();
  if (last) items.push(last);
  return items;
}

/** Maps a resource as printed by LIST PERMISSIONS (e.g. `<table ks.t>`) to GRANT/REVOKE syntax. */
export function resourceToCql(resource: string): string {
  const inner = resource.replace(/^<|>$/g, "");
  const m = (re: RegExp) => inner.match(re);
  let r;
  if (inner === "all keyspaces") return "ALL KEYSPACES";
  if (inner === "all roles") return "ALL ROLES";
  if (inner === "all functions") return "ALL FUNCTIONS";
  if (inner === "all mbeans") return "ALL MBEANS";
  if ((r = m(/^all functions in (?:keyspace )?(.+)$/))) return `ALL FUNCTIONS IN KEYSPACE ${quoteId(r[1])}`;
  if ((r = m(/^keyspace (.+)$/))) return `KEYSPACE ${quoteId(r[1])}`;
  if ((r = m(/^table ([^.]+)\.(.+)$/))) return `TABLE ${qualified(r[1], r[2])}`;
  if ((r = m(/^role (.+)$/))) return `ROLE ${quoteStr(r[1])}`;
  if ((r = m(/^mbean (.+)$/))) return `MBEAN ${quoteStr(r[1])}`;
  if ((r = m(/^function ([^.]+)\.([^(]+)(\(.*\))$/))) return `FUNCTION ${qualified(r[1], r[2])}${r[3]}`;
  return inner.toUpperCase();
}
