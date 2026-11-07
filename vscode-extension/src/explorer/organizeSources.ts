/** Include REV companions in an offer about WPILOGs without adopting files from nested stores.
 * Directory reads are supplied by the host; the traversal is tested without VS Code. */
import * as path from "path";
import { OrganizeOffer, folderKey } from "./organize";

export interface DirectoryEntry { name: string; directory: boolean; file: boolean; symlink: boolean }
export async function organizeSources(offer: OrganizeOffer, stores: string[],
  read: (directory: string) => Promise<DirectoryEntry[]>, paths: path.PlatformPath = path): Promise<string[]> {
  if (offer.store) return offer.paths;
  const sources = new Set(offer.paths);
  const visit = async (folder: string): Promise<void> => {
    if (stores.some(store => folderKey(store, paths) === folderKey(folder, paths))) return;
    for (const entry of await read(folder)) {
      if (entry.symlink) continue;
      const file = paths.join(folder, entry.name);
      if (entry.directory) await visit(file);
      else if (entry.file && entry.name.toLowerCase().endsWith(".revlog")) sources.add(file);
    }
  };
  await visit(offer.folder);
  return [...sources];
}
