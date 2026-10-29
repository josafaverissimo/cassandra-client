export const quoteId = (name: string) => `"${name.replace(/"/g, '""')}"`;
export const quoteStr = (value: string) => `'${value.replace(/'/g, "''")}'`;
export const qualified = (ks: string, name: string) => `${quoteId(ks)}.${quoteId(name)}`;

export const NATIVE_TYPES = [
  "ascii", "bigint", "blob", "boolean", "counter", "date", "decimal", "double", "duration", "float",
  "inet", "int", "smallint", "text", "time", "timestamp", "timeuuid", "tinyint", "uuid", "varchar", "varint",
];

export const TYPE_SUGGESTIONS = [
  ...NATIVE_TYPES,
  "list<text>", "set<text>", "map<text, text>", "frozen<list<int>>", "tuple<int, text>", "vector<float, 3>",
];

const STRING_TYPES = new Set([
  "ascii", "text", "varchar", "uuid", "timeuuid", "inet", "timestamp", "date", "time", "duration", "blob",
]);
const NUMBER_TYPES = new Set(["bigint", "counter", "decimal", "double", "float", "int", "smallint", "tinyint", "varint"]);

export type ValueKind = "string" | "number" | "boolean" | "json";

export function unfrozen(type: string) {
  let t = type.trim();
  while (/^frozen<.*>$/.test(t)) t = t.slice(7, -1).trim();
  return t;
}

/** How a column's toJson() representation looks, which decides how the editor treats input. */
export function valueKind(type: string): ValueKind {
  const t = unfrozen(type);
  if (STRING_TYPES.has(t)) return "string";
  if (NUMBER_TYPES.has(t)) return "number";
  if (t === "boolean") return "boolean";
  return "json";
}

/** Splits `a, b<c, d>` at top-level commas. */
function splitTypeArgs(s: string) {
  const out: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < s.length; i++) {
    if (s[i] === "<") depth++;
    else if (s[i] === ">") depth--;
    else if (s[i] === "," && depth === 0) {
      out.push(s.slice(start, i).trim());
      start = i + 1;
    }
  }
  out.push(s.slice(start).trim());
  return out;
}

/** Type of the value on the right-hand side of a filter operator (CONTAINS uses the element type). */
export function filterValueType(type: string, op: string) {
  const t = unfrozen(type);
  const m = t.match(/^(list|set|map)<(.*)>$/);
  if (!m) return t;
  const args = splitTypeArgs(m[2]!);
  if (op === "CONTAINS") return m[1] === "map" ? args[1]! : args[0]!;
  if (op === "CONTAINS KEY") return args[0]!;
  return t;
}

/** JSON text from toJson() → text shown in the editor (strings are unquoted). */
export function toEditable(json: string | null, type: string): string {
  if (json === null || json === "null") return "";
  if (valueKind(type) === "string") {
    try {
      return JSON.parse(json) as string;
    } catch {
      return json;
    }
  }
  return json;
}

/** Text typed by the user → JSON text accepted by fromJson(). Throws with a friendly message. */
export function fromEditable(text: string, type: string): string {
  const kind = valueKind(type);
  if (kind === "string") return JSON.stringify(text);
  const trimmed = text.trim();
  if (kind === "number") {
    if (!/^-?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(trimmed) && !/^-?(NaN|Infinity)$/.test(trimmed))
      throw new Error(`"${text}" is not a valid ${type}`);
    return trimmed;
  }
  if (kind === "boolean") {
    if (trimmed !== "true" && trimmed !== "false") throw new Error("Use true or false");
    return trimmed;
  }
  try {
    JSON.parse(trimmed);
  } catch {
    throw new Error(`Invalid JSON for ${type}`);
  }
  return trimmed;
}

/** Compact one-line rendering for the grid. */
export function displayValue(json: string | null, type: string): string {
  if (json === null || json === "null") return "NULL";
  if (valueKind(type) === "string") return toEditable(json, type);
  return json;
}

export function isNull(json: string | null) {
  return json === null || json === "null";
}

/** Renders any plain value (from the query editor) as a cell string. */
export function plainToText(v: unknown): string {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "string") return v;
  return JSON.stringify(v);
}

export const CONSISTENCY_LEVELS = [
  "ANY", "ONE", "TWO", "THREE", "QUORUM", "ALL", "LOCAL_QUORUM", "EACH_QUORUM", "SERIAL", "LOCAL_SERIAL", "LOCAL_ONE",
];

export const PERMISSIONS = [
  "CREATE", "ALTER", "DROP", "SELECT", "MODIFY", "AUTHORIZE", "DESCRIBE", "EXECUTE", "UNMASK", "SELECT_MASKED",
] as const;

export type ResourceKind =
  | "ALL KEYSPACES"
  | "KEYSPACE"
  | "TABLE"
  | "ALL ROLES"
  | "ROLE"
  | "ALL FUNCTIONS"
  | "ALL FUNCTIONS IN KEYSPACE"
  | "ALL MBEANS";

/** Permissions that Cassandra accepts for each resource type. */
export const APPLICABLE: Record<ResourceKind, readonly string[]> = {
  "ALL KEYSPACES": ["CREATE", "ALTER", "DROP", "SELECT", "MODIFY", "AUTHORIZE", "UNMASK", "SELECT_MASKED"],
  KEYSPACE: ["CREATE", "ALTER", "DROP", "SELECT", "MODIFY", "AUTHORIZE", "UNMASK", "SELECT_MASKED"],
  TABLE: ["ALTER", "DROP", "SELECT", "MODIFY", "AUTHORIZE", "UNMASK", "SELECT_MASKED"],
  "ALL ROLES": ["CREATE", "ALTER", "DROP", "AUTHORIZE", "DESCRIBE"],
  ROLE: ["ALTER", "DROP", "AUTHORIZE"],
  "ALL FUNCTIONS": ["CREATE", "ALTER", "DROP", "AUTHORIZE", "EXECUTE"],
  "ALL FUNCTIONS IN KEYSPACE": ["CREATE", "ALTER", "DROP", "AUTHORIZE", "EXECUTE"],
  "ALL MBEANS": ["AUTHORIZE", "DESCRIBE", "EXECUTE", "MODIFY", "SELECT"],
};

export function resourceKindOf(resourceCql: string): ResourceKind | null {
  const kinds = Object.keys(APPLICABLE).sort((a, b) => b.length - a.length) as ResourceKind[];
  return kinds.find((k) => resourceCql === k || resourceCql.startsWith(k + " ")) ?? null;
}

/** Applicable permissions for a resource; FUNCTION/MBEAN fall back to everything. */
export function applicableFor(resourceCql: string): readonly string[] {
  if (resourceCql.startsWith("FUNCTION ")) return ["ALTER", "DROP", "AUTHORIZE", "EXECUTE"];
  if (resourceCql.startsWith("MBEAN ")) return APPLICABLE["ALL MBEANS"];
  const kind = resourceKindOf(resourceCql);
  return kind ? APPLICABLE[kind] : PERMISSIONS;
}

/** Turns `<table ks.t>`-style names into a short readable label. */
export function resourceLabel(resource: string) {
  return resource.replace(/^<|>$/g, "");
}
