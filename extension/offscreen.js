// Things the background worker cannot do without a document:
// clipboard writes, and blob URLs for saving stored files to disk.
import { getFile } from './store.js';

const urls = new Map(); // blob URL -> revoke when the download is done

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg.target !== 'offscreen') return;
  if (msg.type === 'copy') {
    const t = document.getElementById('t');
    t.value = msg.text;
    t.select();
    document.execCommand('copy');
    return;
  }
  if (msg.type === 'blobUrl') {
    getFile(msg.id).then((f) => {
      if (!f) return reply({ error: 'File no longer available' });
      const url = URL.createObjectURL(f.blob);
      urls.set(url, true);
      reply({ url, name: f.name });
    }, (e) => reply({ error: e.message }));
    return true;
  }
  if (msg.type === 'revoke') {
    URL.revokeObjectURL(msg.url);
    urls.delete(msg.url);
  }
});
