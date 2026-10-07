/** A proxy password belongs to the user, scoped by HTTP origin, never to project settings. */
import { pitEndpoint } from "./explorer/pitServer";
export interface SecretReader { get(key: string): PromiseLike<string | undefined> }
export type HttpHeaders = (url: string) => Promise<Record<string, string>>;
export const noHeaders: HttpHeaders = async () => ({});
export const PIT_SECRET_PREFIX = "wpilog-mcp.pitProxy:";
export function pitSecretKey(url: string): string { return PIT_SECRET_PREFIX + new URL(pitEndpoint(url)!).origin; }
export function basicCredential(user: string, password: string): string {
  if (!user || /[:\r\n]/.test(user)) throw new Error("The proxy username must be nonempty and contain no colon or newline.");
  return `Basic ${Buffer.from(`${user}:${password}`, "utf8").toString("base64")}`;
}
export async function pitRegistration(url: string | undefined, secrets: SecretReader): Promise<{ url: string; authorization: string | null }> {
  const endpoint = pitEndpoint(url);
  return { url: endpoint ?? "", authorization: endpoint ? await secrets.get(pitSecretKey(endpoint)) ?? null : null };
}
export function pitHeaders(url: () => string | undefined, secrets: SecretReader): HttpHeaders {
  return async (request): Promise<Record<string, string>> => {
    const endpoint = pitEndpoint(url());
    if (!endpoint || new URL(request).origin !== new URL(endpoint).origin) return {};
    const auth = await secrets.get(pitSecretKey(endpoint));
    return auth ? { Authorization: auth } : {};
  };
}
/** Claude's bridge registers this URL, which carries no secret and needs the window's live lease. */
export function pitBridgeUrl(local: string, remote: string): string {
  const url = new URL("/pit-mcp", local); url.searchParams.set("url", pitEndpoint(remote)!); return url.href;
}
