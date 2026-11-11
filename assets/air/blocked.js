const p=new URLSearchParams(location.hash.slice(1));
document.documentElement.classList.toggle('light',p.get('t')==='light');
const url=p.get('u')||'';
let h=url;try{h=new URL(url).hostname}catch(e){}
document.getElementById('host').textContent=h;
const ul=document.getElementById('why');
(p.get('r')||'').split('|').filter(Boolean).forEach(r=>{const li=document.createElement('li');li.textContent=r;ul.append(li)});
document.getElementById('sb').href='air://sandbox?u='+encodeURIComponent(url);
