/* AIR Navig — mode sombre des sites (assombrir / forcer), appliqué au chargement de la page.
 * Reprend les scripts injectés auparavant par le WebView (DARK_JS et forceJs).
 */
(function () {
  'use strict';
  if (window.__airLook) return;
  window.__airLook = 1;

  function lum(c) {
    const m = (c || '').match(/rgba?\(([^)]+)\)/);
    if (!m) return -1;
    const p = m[1].split(',').map(parseFloat);
    if (p.length > 3 && p[3] === 0) return -1;
    return (0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]) / 255;
  }

  function addStyle(id, css) {
    let s = document.getElementById(id);
    if (!s) {
      s = document.createElement('style');
      s.id = id;
      (document.head || document.documentElement).appendChild(s);
    }
    if (s.textContent !== css) s.textContent = css;
    return s;
  }

  // « Assombrir les sites » : inverse les pages claires, laisse les pages déjà sombres.
  function dark() {
    if (document.getElementById('__airdark')) return;
    const b = document.body;
    let l = b ? lum(getComputedStyle(b).backgroundColor) : -1;
    if (l < 0) l = lum(getComputedStyle(document.documentElement).backgroundColor);
    if (l >= 0 && l < 0.4) return;
    addStyle('__airdark',
      'html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}' +
      'img,video,picture,canvas,iframe,embed,object,svg image,[style*="background-image"]' +
      '{filter:invert(1) hue-rotate(180deg)!important}');
  }

  // « Forcer les couleurs » : fond et texte imposés partout.
  function force(z) {
    const bg = z ? '#000' : '#fff', fg = z ? '#fff' : '#000', bd = z ? '#333' : '#ddd', inp = z ? '#111' : '#f5f5f5';
    const css = 'html,body{background:' + bg + '!important}' +
      '*:not(img):not(video):not(canvas):not(svg):not(picture):not(iframe):not(embed):not(object)' +
      '{background-color:' + bg + '!important;color:' + fg + '!important;border-color:' + bd + '!important;text-shadow:none!important}' +
      'a,a *{color:' + (z ? '#8ab4ff' : '#1a0dab') + '!important}' +
      'a:visited,a:visited *{color:' + (z ? '#c58af9' : '#681da8') + '!important}' +
      'input,textarea,select,button{background-color:' + inp + '!important;color:' + fg + '!important}' +
      '::placeholder{color:' + (z ? '#999' : '#666') + '!important}svg{fill:currentColor}';
    const old = document.getElementById('__airdark');
    if (old) old.remove();
    const put = () => addStyle('__airforce', css);
    put();
    new MutationObserver(() => { if (!document.getElementById('__airforce')) put(); })
      .observe(document.documentElement, { childList: true, subtree: false });
  }

  browser.runtime.sendMessage({ q: 'look' }).then((look) => {
    if (!look) return;
    if (look.force) {
      force(!!look.dark);
    } else if (look.dark) {
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', dark, { once: true });
      else dark();
    }
  }, () => {});
})();

/* AIR Navig — mode lecture et liaison avec l'appli (port « air_page »).
 * Le mode lecture utilise Readability, la bibliothèque du mode lecture de Firefox (Mozilla, Apache 2.0).
 */
(function () {
  'use strict';
  if (window.top !== window || window.__airPage) return;
  window.__airPage = 1;
  let port = null;
  let reading = false;

  function send(m) {
    try { if (port) port.postMessage(m); } catch (e) { /* page fermée */ }
  }

  // La liaison se refait d'elle-même (page restaurée par le bouton Retour, appli remise au premier plan…).
  function connect() {
    if (port) return;
    try {
      const p = browser.runtime.connectNative('air_page');
      port = p;
      p.onMessage.addListener((m) => {
        if (m && m.reader) toggleReader(m.reader);
      });
      p.onDisconnect.addListener(() => {
        if (port === p) port = null;
        setTimeout(() => { if (!document.hidden) { connect(); report(); } }, 500);
      });
    } catch (e) {
      port = null;
    }
  }

  function readerable() {
    try {
      return typeof isProbablyReaderable === 'function' && isProbablyReaderable(document);
    } catch (e) {
      return false;
    }
  }

  const THEMES = {
    light: { bg: '#ffffff', fg: '#1b1b1f', mu: '#6b6b76', ln: '#0b57d0', bd: '#e6e6ea' },
    sepia: { bg: '#f4ecd8', fg: '#433422', mu: '#7a6a55', ln: '#8a4b0f', bd: '#e2d5b8' },
    dark: { bg: '#121216', fg: '#e8e8ee', mu: '#9a9aaa', ln: '#8ab4ff', bd: '#2a2a33' },
  };

  // Réglages de lecture gardés par l'extension (jamais dans le stockage du site).
  let P = { size: 19, theme: 'light', serif: true };
  try {
    browser.storage.local.get('airReader').then((r) => { if (r && r.airReader) P = Object.assign(P, r.airReader); }, () => {});
  } catch (e) { /* stockage indisponible */ }
  function prefs() { return Object.assign({}, P); }
  function savePrefs(p) {
    P = Object.assign({}, p);
    try { browser.storage.local.set({ airReader: P }); } catch (e) { /* stockage indisponible */ }
  }

  function css(p) {
    const t = THEMES[p.theme] || THEMES.light;
    const font = p.serif ? 'Georgia,"Noto Serif","Droid Serif",serif' : 'system-ui,-apple-system,Roboto,"Segoe UI",sans-serif';
    return 'html,body{margin:0!important;padding:0!important;background:' + t.bg + '!important;color:' + t.fg + '!important}' +
      '#air-reader{max-width:42em;margin:0 auto;padding:18px 20px 80px;font:' + p.size + 'px/1.65 ' + font + ';color:' + t.fg + ';word-wrap:break-word}' +
      '#air-reader h1{font-size:1.6em;line-height:1.25;margin:.4em 0 .3em}' +
      '#air-reader .air-meta{color:' + t.mu + ';font:14px/1.4 system-ui,sans-serif;margin-bottom:1.4em;padding-bottom:1em;border-bottom:1px solid ' + t.bd + '}' +
      '#air-reader img,#air-reader video,#air-reader figure{max-width:100%!important;height:auto!important}' +
      '#air-reader a{color:' + t.ln + '}' +
      '#air-reader pre,#air-reader code{white-space:pre-wrap;font-size:.85em;background:' + t.bd + ';border-radius:6px}' +
      '#air-reader pre{padding:10px}' +
      '#air-reader table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto;font-size:.85em}' +
      '#air-reader td,#air-reader th{border:1px solid ' + t.bd + ';padding:4px 6px}' +
      '#air-reader blockquote{margin:1em 0;padding-left:1em;border-left:3px solid ' + t.bd + ';color:' + t.mu + '}' +
      '#air-reader-bar{position:sticky;top:0;z-index:9;display:flex;flex-wrap:wrap;gap:5px;align-items:center;justify-content:center;padding:7px 6px;background:' + t.bg + ';border-bottom:1px solid ' + t.bd + ';font:13px system-ui,sans-serif}' +
      '#air-reader-bar button{min-width:34px;height:32px;padding:0 9px;border-radius:16px;border:1px solid ' + t.bd + ';background:transparent;color:' + t.fg + ';font:600 13px system-ui,sans-serif}' +
      '#air-reader-bar button.on{border-color:' + t.ln + ';color:' + t.ln + '}';
  }

  /** Secours quand Readability ne trouve pas d'article : le bloc de la page qui contient le plus de texte. */
  function fallbackArticle() {
    try {
      const cands = [...document.querySelectorAll('article,main,[role=main],#content,.content,.post,.entry,.article,body')];
      let best = null, score = 0;
      cands.forEach((c) => {
        const n = [...c.querySelectorAll('p,li,h2,h3,blockquote,pre')].reduce((a, p) => a + (p.innerText || '').trim().length, 0);
        if (n > score * 1.2 || (best && best.contains(c) && n > score * 0.7)) { best = c; score = n; }
      });
      if (!best || score < 150) return null;
      const box = document.createElement('div');
      best.querySelectorAll('h1,h2,h3,h4,p,ul,ol,blockquote,pre,figure,img,table').forEach((n) => {
        if (n.closest('nav,aside,footer,header,form,[role=navigation],[aria-hidden=true]')) return;
        if (n.parentElement && n.parentElement.closest('ul,ol,blockquote,pre,figure,table') && n.parentElement.closest('ul,ol,blockquote,pre,figure,table') !== n) return;
        box.appendChild(n.cloneNode(true));
      });
      const text = box.textContent || '';
      if (text.trim().length < 150) return null;
      return { title: document.title, content: box.innerHTML, textContent: text, byline: '', siteName: location.hostname };
    } catch (e) {
      return null;
    }
  }

  function toggleReader() {
    if (reading) {
      location.reload();
      return;
    }
    let article = null;
    try {
      article = new Readability(document.cloneNode(true), { charThreshold: 250 }).parse();
    } catch (e) {
      article = null;
    }
    if (!article || !article.content || (article.textContent || '').trim().length < 150) article = fallbackArticle();
    if (!article) {
      send({ readerFail: 1 });
      return;
    }
    reading = true;
    const p = prefs();
    const parsed = new DOMParser().parseFromString('<div>' + article.content + '</div>', 'text/html');
    // Pas de scripts ni de gestionnaires d'événements dans la vue lecture.
    parsed.querySelectorAll('script,style,iframe,object,embed,form,noscript').forEach((n) => n.remove());
    parsed.querySelectorAll('*').forEach((n) => {
      [...n.attributes].forEach((a) => { if (/^on/i.test(a.name) || /^\s*javascript:/i.test(a.value)) n.removeAttribute(a.name); });
    });
    const title = article.title || document.title;
    const meta = [article.byline, article.siteName || location.hostname].filter(Boolean).join(' · ');
    const minutes = Math.max(1, Math.round(((article.textContent || '').split(/\s+/).length) / 230));

    const head = document.head || document.createElement('head');
    head.textContent = '';
    const v = document.createElement('meta');
    v.name = 'viewport';
    v.content = 'width=device-width,initial-scale=1';
    head.appendChild(v);
    const st = document.createElement('style');
    st.id = '__airreader';
    st.textContent = css(p);
    head.appendChild(st);
    const body = document.createElement('body');
    const bar = document.createElement('div');
    bar.id = 'air-reader-bar';
    const mk = (label, fn, on) => {
      const b = document.createElement('button');
      b.textContent = label;
      if (on) b.className = 'on';
      b.onclick = fn;
      bar.appendChild(b);
      return b;
    };
    const redraw = () => { st.textContent = css(p); savePrefs(p); [...bar.children].forEach((b) => b.classList.toggle('on', b.dataset.t === p.theme || (b.dataset.f === 'serif' && p.serif))); };
    mk('A−', () => { p.size = Math.max(14, p.size - 1); redraw(); });
    mk('A+', () => { p.size = Math.min(32, p.size + 1); redraw(); });
    mk('Serif', () => { p.serif = !p.serif; redraw(); }, p.serif).dataset.f = 'serif';
    ['light', 'sepia', 'dark'].forEach((k) => { mk({ light: 'Clair', sepia: 'Sépia', dark: 'Sombre' }[k], () => { p.theme = k; redraw(); }, p.theme === k).dataset.t = k; });
    mk('✕', () => location.reload());
    const main = document.createElement('article');
    main.id = 'air-reader';
    const h1 = document.createElement('h1');
    h1.textContent = title;
    const m = document.createElement('div');
    m.className = 'air-meta';
    m.textContent = (meta ? meta + ' · ' : '') + minutes + ' min de lecture';
    main.appendChild(h1);
    main.appendChild(m);
    const content = parsed.body.firstElementChild;
    if (content) main.appendChild(document.importNode(content, true));
    body.appendChild(bar);
    body.appendChild(main);
    document.documentElement.replaceChild(body, document.body || document.documentElement.appendChild(document.createElement('body')));
    if (!document.head) document.documentElement.insertBefore(head, body);
    ['__airdark', '__airforce'].forEach((id) => { const x = document.getElementById(id); if (x) x.remove(); });
    window.scrollTo(0, 0);
    send({ reading: 1, title: title });
  }

  function report() { send({ readerable: readerable(), lang: (document.documentElement.getAttribute('lang') || '').slice(0, 20) }); }
  connect();
  window.addEventListener('pageshow', (e) => { if (e.persisted) { connect(); setTimeout(report, 200); } });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && !port) { connect(); report(); } });
  if (document.readyState === 'complete') setTimeout(report, 300);
  else window.addEventListener('load', () => setTimeout(report, 300), { once: true });
})();
