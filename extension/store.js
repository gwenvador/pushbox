// Files received directly from the phone, kept inside the extension (IndexedDB)
// until the user chooses to download them. Shared by the worker, popup and offscreen page.
const MAX_FILES = 20;
const MAX_BYTES = 500 * 1024 * 1024;

function open() {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open('pushbox-files', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('files', { keyPath: 'id' });
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

async function tx(mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction('files', mode);
    const out = fn(t.objectStore('files'));
    t.oncomplete = () => resolve(out?.result ?? out);
    t.onerror = () => reject(t.error);
  });
}

/** Newest first, without the file contents. */
export async function listFiles() {
  const all = await tx('readonly', (s) => s.getAll());
  return all.map(({ blob, ...meta }) => meta).sort((a, b) => b.at - a.at);
}

export async function getFile(id) {
  return tx('readonly', (s) => s.get(id));
}

export async function removeFile(id) {
  await tx('readwrite', (s) => s.delete(id));
}

/** Store a received file, then drop the oldest ones beyond the limits. */
export async function addFile({ id, name, mime, from, blob }) {
  await tx('readwrite', (s) => s.put({ id, name, mime, size: blob.size, from, at: Date.now(), blob }));
  const files = await listFiles();
  let total = 0;
  for (const [i, f] of files.entries()) {
    total += f.size;
    if (i >= MAX_FILES || total > MAX_BYTES) await removeFile(f.id);
  }
}

/** Save a stored file to the Downloads folder (needs a document for the blob URL). */
export async function saveToDisk(id) {
  const f = await getFile(id);
  if (!f) throw new Error('File no longer available');
  const url = URL.createObjectURL(f.blob);
  const downloadId = await chrome.downloads.download({ url, filename: f.name, conflictAction: 'uniquify', saveAs: false });
  // Keep the blob URL alive until the download is done.
  const done = (delta) => {
    if (delta.id !== downloadId || !delta.state || delta.state.current === 'in_progress') return;
    chrome.downloads.onChanged.removeListener(done);
    URL.revokeObjectURL(url);
  };
  chrome.downloads.onChanged.addListener(done);
  return downloadId;
}
