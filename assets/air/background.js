/* AIR Navig — bloqueur de traqueurs et réglages des pages, dans le moteur GeckoView.
 * Remplace l'ancien « shouldInterceptRequest » du WebView : chaque requête secondaire
 * vers un domaine de la liste (StevenBlack + règles de l'utilisateur) est annulée,
 * et les domaines bloqués sont envoyés à l'appli par lots toutes les 600 ms.
 */
'use strict';

let port = null;
let blockOn = true;
let hosts = new Set();
let rules = new Set();
let look = { dark: false, force: false };
const verdicts = new Map();
let queue = [];
let flushTimer = 0;

function connect() {
  try {
    port = browser.runtime.connectNative('air-bg');
    port.onMessage.addListener(onNative);
    port.onDisconnect.addListener(() => {
      port = null;
      setTimeout(connect, 1000);
    });
  } catch (e) {
    port = null;
  }
}

function onNative(m) {
  if (!m || typeof m !== 'object') return;
  if (typeof m.hosts === 'string') {
    hosts = new Set(m.hosts.split('\n').filter(Boolean));
    verdicts.clear();
  }
  if (typeof m.rules === 'string') {
    rules = new Set(m.rules.split(',').filter(Boolean));
    verdicts.clear();
  }
  if (typeof m.block === 'boolean') {
    blockOn = m.block;
    verdicts.clear();
  }
  if (m.look && typeof m.look === 'object') look = { dark: !!m.look.dark, force: !!m.look.force };
}

function isTracker(host) {
  const h = host.toLowerCase();
  const v = verdicts.get(h);
  if (v !== undefined) return v;
  let hit = hosts.has(h);
  if (!hit && rules.size) {
    // Même logique que « se termine par .domaine » : on remonte les domaines parents.
    let d = h;
    for (;;) {
      if (rules.has(d)) { hit = true; break; }
      const i = d.indexOf('.');
      if (i < 0) break;
      d = d.slice(i + 1);
    }
  }
  if (hosts.size) {
    if (verdicts.size > 4000) verdicts.clear();
    verdicts.set(h, hit);
  }
  return hit;
}

function report(host) {
  queue.push(host);
  if (flushTimer) return;
  flushTimer = setTimeout(() => {
    flushTimer = 0;
    const list = queue.slice(0, 300);
    queue = [];
    if (port && list.length) port.postMessage({ blocked: list });
  }, 600);
}

browser.webRequest.onBeforeRequest.addListener(
  (d) => {
    if (!blockOn || d.type === 'main_frame') return {};
    const from = d.originUrl || d.documentUrl || '';
    // L'interface d'AIR Navig elle-même n'est jamais filtrée.
    if (from.startsWith('moz-extension:')) return {};
    let host = '';
    try {
      host = new URL(d.url).hostname;
    } catch (e) {
      return {};
    }
    if (host && isTracker(host)) {
      report(host);
      return { cancel: true };
    }
    return {};
  },
  { urls: ['<all_urls>'] },
  ['blocking']
);

// Réglages d'affichage demandés par content.js au chargement de chaque page.
browser.runtime.onMessage.addListener((msg) => {
  if (msg && msg.q === 'look') return Promise.resolve(look);
  return undefined;
});

connect();
