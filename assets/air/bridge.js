/* AIR Navig — pont entre l'interface (cette page, dans Gecko) et l'appli Android.
 *
 * Remplace l'ancien « addJavascriptInterface » du WebView :
 *  - interface → appli : AIR.cmd(c, a) envoie un message natif « air-ui » ;
 *  - appli → interface : l'appli envoie {js: "..."} qui est exécuté ici ;
 *  - les valeurs lues au démarrage (thème système, position, navigation privée…)
 *    arrivent dans l'adresse de la page (?i=...) puis sont tenues à jour.
 */
(function () {
  'use strict';
  // Adresse de base des pages internes (moz-extension://…/).
  window.AIR_BASE = location.origin + '/';

  let init = {};
  try {
    init = JSON.parse(new URLSearchParams(location.search).get('i') || '{}') || {};
  } catch (e) {
    init = {};
  }
  let aboutCache = init.about || {};
  let port = null;
  const pending = [];

  function connect() {
    try {
      port = browser.runtime.connectNative('air-ui');
      console.log('AIR bridge: connecté');
      port.onMessage.addListener(onNative);
      port.onDisconnect.addListener((p) => {
        console.log('AIR bridge: déconnecté', p && p.error && p.error.message);
        port = null;
      });
      while (pending.length) port.postMessage(pending.shift());
    } catch (e) {
      console.log('AIR bridge: échec', e && e.message);
      port = null;
    }
  }

  function send(msg) {
    if (!port) connect();
    if (port) port.postMessage(msg);
    else pending.push(msg);
  }

  function onNative(m) {
    if (!m || typeof m !== 'object') return;
    if (m.about) aboutCache = m.about;
    if (typeof m.sysDark === 'boolean') init.sysDark = m.sysDark;
    if (typeof m.js === 'string') {
      try {
        (0, eval)(m.js);
      } catch (e) {
        console.error('AIR js', e);
      }
    }
  }

  window.AIR = Object.freeze({
    cmd(c, a) {
      send({ c: String(c), a: a == null ? '' : String(a) });
    },
    sysDark() { return !!init.sysDark; },
    incog() { return !!init.incog; },
    initSettings() { return init.initSettings || ''; },
    pos() { return init.pos || 'bottom'; },
    about() { return JSON.stringify(aboutCache || {}); },
  });

  // Les favicons cassées étaient cachées par des « onerror » en ligne, interdits dans Gecko :
  // un seul écouteur fait la même chose pour toutes les images.
  document.addEventListener('error', (ev) => {
    const t = ev.target;
    if (t && t.tagName === 'IMG' && t.hasAttribute('onerror')) t.style.visibility = 'hidden';
  }, true);

  connect();
  // L'interface est prête quand son script principal a fini de s'exécuter.
  window.addEventListener('DOMContentLoaded', () => send({ ready: 1 }));
})();
