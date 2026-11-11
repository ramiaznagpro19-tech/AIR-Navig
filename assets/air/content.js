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
