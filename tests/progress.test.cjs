/* Barres de progression et notification Android pendant les tâches longues */
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright');
const fs=require('fs');let fails=0;const ok=(c,m)=>{console.log((c?'  ok  ':'  ÉCHEC ')+m);if(!c)fails++};
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:360,height:800}});
await p.route(/fonts|deezer|itunes|musicbrainz|discogs|acoustid|api\.github|coverartarchive/,r=>r.abort());
await p.route(/lamejs.*lame\.min\.js|lib\/lame\.min\.js/,r=>r.fulfill({body:fs.readFileSync(process.env.LAME_JS),contentType:'text/javascript'}));
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.addInitScript(`localStorage.setItem('sillon.autoenrich','0');(${require('./fake-phone.cjs').toString()})();
  window.__WORK=[];window.__BATT=[];Object.assign(window.Capacitor.Plugins.Sillon,{workUpdate:async o=>{__WORK.push(o)},workStop:async()=>{__WORK.push('stop')},
    batteryExempt:async()=>({exempt:false}),requestBatteryExempt:async()=>{__BATT.push(1)}});`);
await p.goto('http://localhost:8765/index.html');await p.waitForTimeout(300);
ok(await p.evaluate(()=>!!WT),'minuteries par worker actives (pas ralenties écran éteint)');
ok(await p.evaluate(async()=>{let n=0;for(let i=0;i<50;i++){await tick();n++}return n===50}),'tick() sans minuterie de page');
const t0=Date.now();await p.evaluate(()=>wait(300));ok(Date.now()-t0>=280,'wait() attend bien');
await p.evaluate(async()=>{FOLDER={uri:'root',name:'Music'};CATALOG.deezer=async()=>({data:[]});CATALOG.itunes=async()=>[];window.mb=async()=>{throw new Error('x')};window.fetchImage=async()=>null;window.thumbOf=async()=>'data:,';
  const wav=sec=>{const sr=44100,n=sec*sr,b=new ArrayBuffer(44+n*4),v=new DataView(b);const w=(o,s)=>[...s].forEach((c,i)=>v.setUint8(o+i,c.charCodeAt(0)));w(0,'RIFF');v.setUint32(4,36+n*4,true);w(8,'WAVE');w(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,2,true);v.setUint32(24,sr,true);v.setUint32(28,sr*4,true);v.setUint16(32,4,true);v.setUint16(34,16,true);w(36,'data');v.setUint32(40,n*4,true);return new Uint8Array(b)};
  for(let i=1;i<=4;i++)await writeNative(`Muse/Absolution/0${i} Piste ${i}.wav`,new Blob([wav(15)]));
  for(let i=1;i<=6;i++)await writeNative(`Vrac/Groupe - Chanson ${i}.mp3`,new Blob([new Uint8Array(3000)]))});
/* pendant l'enrichissement : on relève ce qu'affiche la barre */
const seen=[];const run=p.evaluate(()=>autoEnrich(true));
for(let i=0;i<60;i++){const v=await p.evaluate(()=>!$('#tasks').hidden?$('#tasks').innerText.replace(/\n+/g,' | '):'');if(v)seen.push(v);if(await p.evaluate(()=>!ENRUN))break;await p.waitForTimeout(250)}
await run;
console.log('    aperçu :',seen.find(x=>/Conversion en MP3/.test(x))||seen[0]);
ok(seen.some(x=>/Enrichissement de la discothèque/.test(x)),'tâche « Enrichissement » affichée');
ok(seen.some(x=>/Conversion en MP3 : \d\/4/.test(x)&&/\d+ %/.test(x)),'compteur et pourcentage pendant la conversion');
ok(seen.some(x=>/depuis \d+ s/.test(x)),'temps écoulé affiché');
ok(seen.some(x=>/reste ≈/.test(x)),'temps restant estimé');
await p.waitForTimeout(600);
ok(await p.isHidden('#tasks'),'barre masquée à la fin');
const W=await p.evaluate(()=>__WORK);
ok(W.some(o=>o.label&&/Enrichissement/.test(o.label)&&o.total===1000),'notification Android mise à jour avec la progression');
ok(W[W.length-1]==='stop','service arrêté à la fin');
ok(await p.evaluate(()=>__BATT.length===1&&!$('#battRow').hidden),'première tâche : Android demande une seule fois l\'exemption batterie, bouton « Autoriser » affiché');
console.log('— rangement groupé');
await p.evaluate(()=>{__WORK.length=0});
const seen2=await p.evaluate(async()=>{const log=[];const orig=drawTasks;window.drawTasks=()=>{};const iv=setInterval(()=>{for(const t of TASKS.values())log.push(t.label+' '+t.done+'/'+t.total+' '+t.sub)},5);
  const r=LIB.find(x=>x.unsorted);await moveTracks(r.key,r.tracks.map(t=>t.id),{artist:'Groupe',album:'Best of'});clearInterval(iv);window.drawTasks=orig;return log});
console.log('    aperçu :',seen2[Math.floor(seen2.length/2)]);
ok(seen2.some(x=>/^Rangement dans « Best of » [1-5]\/6 « Chanson/.test(x)),'« Rangement dans « Best of » » : morceau par morceau, sur 6');
console.log('— écran noir automatique');
await p.evaluate(()=>{window.__DIM=[];window.__AW=[];Object.assign(NATIVE,{dim:async o=>{__DIM.push(o.on)},keepAwake:async o=>{__AW.push(o.on)}});window.__T=task('Conversion en MP3');__T.step(1,10,'x');LASTTOUCH=Date.now()-60000});
await p.waitForTimeout(5600);
ok(!(await p.isHidden('#black'))&&(await p.evaluate(()=>__DIM.includes(true)&&__AW.includes(true))),'après 40 s sans toucher : écran noir, luminosité au minimum, écran maintenu');
await p.click('#black');ok(await p.isHidden('#black')&&(await p.evaluate(()=>__DIM[__DIM.length-1]===false)),'un toucher : écran normal, luminosité rendue');
await p.evaluate(()=>{blackOn();__T.end()});await p.waitForTimeout(300);
ok((await p.textContent('#black'))?.includes('Terminé')&&(await p.evaluate(()=>__DIM[__DIM.length-1]===false&&__AW[__AW.length-1]===false)),'fin de tâche : « Terminé », le téléphone peut se mettre en veille');
await p.click('#black');
console.log('— mesure écran éteint');
const diag=await p.evaluate(async()=>{const T=task('Enrichissement de la discothèque');T.step(2,10,'x');let hidden=true;Object.defineProperty(document,'hidden',{get:()=>hidden,configurable:true});
  document.dispatchEvent(new Event('visibilitychange'));HID.at-=120000;/* 2 min « éteint », aucun battement */
  hidden=false;document.dispatchEvent(new Event('visibilitychange'));T.end();return localStorage.getItem('sillon.bgDiag')});
ok(/Écran éteint 2 min pendant « Enrichissement de la discothèque » : Android a mis Sillon en pause \(avance de 0 %\)/.test(diag||''),'diagnostic au retour : '+diag);
await p.screenshot({path:'/tmp/claude-0/s/prog.png',clip:{x:0,y:0,width:360,height:260}});
const over=await p.evaluate(()=>{const W=document.documentElement.clientWidth;return [...document.querySelectorAll('#tasks *')].filter(e=>e.getBoundingClientRect().right>W+1).length});ok(!over,'rien ne déborde à 360 px');
ok(!errs.length,'aucune erreur JavaScript '+errs.join(' | '));
await b.close();console.log(fails?`\n${fails} ÉCHEC(S)`:'\nTout est bon');process.exit(fails?1:0)})();
