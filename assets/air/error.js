const p=new URLSearchParams(location.hash.slice(1));
document.documentElement.classList.toggle('light',p.get('t')==='light');
const url=p.get('u')||'', k=p.get('k')||'ssl';
let h=url;try{h=new URL(url).hostname}catch(e){}
document.getElementById('host').textContent=h;
const T={
 ssl:['Certificat non valide','AIR Navig a coupé la connexion',['Le certificat de sécurité de ce site est expiré, mal configuré ou falsifié.','Quelqu’un pourrait essayer d’intercepter ce que tu envoies (mots de passe, cartes…).','Par sécurité, AIR Navig ne permet pas de contourner cette erreur.'],'Si c’est ton propre site, corrige son certificat (ex. Let’s Encrypt). Réessaie plus tard sinon.'],
 https:['Pas de version sécurisée','Ce site ne répond pas en HTTPS',['AIR Navig essaie toujours d’abord la connexion chiffrée (HTTPS).','Ce site ne la propose pas ou elle ne fonctionne pas.','En HTTP, tout ce que tu tapes peut être lu ou modifié sur le réseau.'],'Ne saisis jamais de mot de passe ni de données bancaires sur une page en HTTP.'],
 net:['Page inaccessible','AIR Navig n’a pas pu joindre ce site',['L’adresse est peut-être mal écrite, ou le site est en panne.','Vérifie aussi ta connexion à Internet (Wi-Fi ou données mobiles).'],'Réessaie dans quelques instants.'],
 http:['Le site refuse le HTTPS','Ce site renvoie vers une adresse non chiffrée',['AIR Navig a demandé la version sécurisée (HTTPS).','Le site a redirigé vers la version non chiffrée (HTTP).','En HTTP, tout ce que tu tapes peut être lu ou modifié sur le réseau.'],'Ne saisis jamais de mot de passe ni de données bancaires sur une page en HTTP.']
}[k]||[];
document.getElementById('title').textContent=T[0]||'Connexion non sécurisée';
document.getElementById('sub').textContent=T[1]||'';
const ul=document.getElementById('why');(T[2]||[]).forEach(r=>{const li=document.createElement('li');li.textContent=r;ul.append(li)});
document.getElementById('note').textContent=T[3]||'';
if((k==='https'||k==='http')&&/^http:\/\//i.test(url)){const a=document.getElementById('http');a.style.display='block';a.href='air://insecure?u='+encodeURIComponent(url)}
