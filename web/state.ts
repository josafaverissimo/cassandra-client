import { createContext, useContext } from "react";
import type { ClusterInfo, Connection, Keyspace } from "./api";

export type Tab =
  | { key: string; type: "table"; keyspace: string; table: string }
  | { key: string; type: "keyspace"; keyspace: string }
  | { key: string; type: "query"; title: string; initialCql?: string; keyspace?: string }
  | { key: string; type: "roles" }
  | { key: string; type: "cluster" };

type DistributiveOmit<T, K extends keyof T> = T extends unknown ? Omit<T, K> : never;
export type TabInput = DistributiveOmit<Tab, "key"> & { key?: string };

export interface Workspace {
  conn: Connection;
  cluster: ClusterInfo;
  keyspaces: Keyspace[];
  refreshSchema: () => Promise<void>;
  openTab: (tab: TabInput) => void;
  closeTab: (key: string) => void;
  renameTab: (key: string, title: string) => void;
}

export const WorkspaceCtx = createContext<Workspace | null>(null);

export function useWorkspace() {
  const ws = useContext(WorkspaceCtx);
  if (!ws) throw new Error("useWorkspace outside of a connection");
  return ws;
}

export function tabKey(tab: TabInput): string {
  switch (tab.type) {
    case "table":
      return `table:${tab.keyspace}.${tab.table}`;
    case "keyspace":
      return `keyspace:${tab.keyspace}`;
    case "query":
      return tab.key ?? `query:${Date.now()}`;
    default:
      return tab.type;
  }
}
