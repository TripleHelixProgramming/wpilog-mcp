/** A native document identifies a configured connection, confirmed server and scope. It never
 * supplies a network destination or translates to another copy of the same content. */
export interface SourceAddress { connection: string; owner: string; scope: string; path: string; reference?: { [key: string]: unknown }; }
export function sourceUri(address: SourceAddress): string {
  // VS Code decodes query components when parsing, then encodes the whole query on serialization.
  // One URL-safe payload preserves literal &, +, %, = and Unicode in remote paths and references.
  const encoded = Buffer.from(JSON.stringify(address), "utf8").toString("base64url");
  return `wpilog-source://${address.connection}/${encodeURIComponent(address.path.split(/[\\/]/).pop() ?? "source.wpilog")}?address=${encoded}`;
}
export function readSourceUri(value: string): SourceAddress {
  const uri = new URL(value), raw = uri.search.slice(1);
  const query = new URLSearchParams(raw.includes("=") ? raw : decodeURIComponent(raw));
  const encoded = query.get("address") ?? "";
  if (uri.protocol !== "wpilog-source:" || uri.username || uri.password || uri.hash || value.length > 65536
    || [...query.keys()].join(",") !== "address" || !/^[a-zA-Z0-9_-]+$/.test(encoded)) throw new Error("Invalid source document address");
  const bytes = Buffer.from(encoded, "base64url");
  if (bytes.toString("base64url") !== encoded) throw new Error("Invalid source document address");
  const address = JSON.parse(bytes.toString("utf8")) as SourceAddress;
  if (!address || !["connection,owner,path,scope", "connection,owner,path,reference,scope"].includes(Object.keys(address).sort().join(","))
    || uri.host !== address.connection || !/^[a-z0-9_-]{1,64}$/.test(address.connection)
    || !/^[0-9a-f-]{36}$/i.test(address.owner) || !/^[0-9a-f-]{36}$/i.test(address.scope)
    || typeof address.path !== "string" || !address.path) throw new Error("Invalid source document address");
  if (address.reference !== undefined) {
    const ref = address.reference;
    if (!ref || ref.schema_version !== 2 || ref.server_instance_id !== address.owner || ref.scope_id !== address.scope) throw new Error("Source document reference belongs to another owner or scope");
  }
  return address;
}
