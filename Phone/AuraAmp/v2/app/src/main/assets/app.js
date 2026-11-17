/* AURA AMP functional alpha: real catalogue only; no fictional music entries. */
(()=>{'use strict';
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon={home:'⌂',search:'⌕',library:'♡',play:'▶',pause:'Ⅱ',back:'←',settings:'⚙',more:'⋯',download:'↓',refresh:'↻',plus:'+',next:'⏭',prev:'⏮'};
const KEY='aura_amp_1_0_1'; // preserve existing device data across alpha updates
const defaults={theme:'dark',account:'Local user',accounts:['Local user'],manifest:'',streamQuality:'best',downloadQuality:'best',autoRefresh:true,offlineWifiOnly:true,playlistAutoDownload:'wifi',downloadedPlaylistIds:[],playlists:[],favorites:[],history:[],trackCache:[],downloaded:[],servers:[]};
let persist={...defaults}; try{persist={...defaults,...JSON.parse(localStorage.getItem(KEY)||'{}')}}catch(e){}
window.AuraNative?.setActiveSyncProfile?.(persist.account);
persist.manifest=window.AuraNative?.hasDeezerArl?.()?'https://eclipse.ultramax.vip/__aura_arl__/manifest.json':'';
const save=()=>{try{localStorage.setItem(KEY,JSON.stringify(persist))}catch(e){note('Unable to save settings')}};
let serverTracks=[],serverBusy=false,serverMessage='',serverSeq=0,showArlHelp=false;
let discoverCategory='Overview', actionTrack=null, lastPage='discover', collapsed=false, playerGestureStart=null, playerTransition='';
let playlistPickTracks=null;let shuffleEnabled=false,repeatMode=0,queueOpen=false,playerView='art',videoState={track:'',loading:false,url:'',title:''};
let artistDetail=null, artistLoading=false, artistError='', artistSourcePage='discover', albumSourcePage='discover', lyricsMode=false, lyricState={track:'',loading:false,error:'',lines:[],plain:false};
let pendingAuraControlAction=null,pendingAuraPlaylistAction=null;
let activeQueueSource='';let busyHome=true; let albumDetail=null;let page='discover', query='', searchTimer, searchVersion=0, category='All', home={trending:[],new:[],top:[],norway:[],norwaySource:'Apple Music'}, results=[], playQueue=[], current=null, playing=false, sourceError='', pending=new Map(), reqId=0, providerManifest=null, providerBase='', providerSearchPath='', busy=false, localSongs=[];
// Restore chart metadata immediately while refreshing in the background.
try{const cached=JSON.parse(localStorage.getItem('aura_amp_chart_cache_v1')||'null');if(cached&&Array.isArray(cached.top)&&cached.top.length){home={...home,...cached};busyHome=false}}catch(_){}
const chartRequestCache=new Map();
function cachedCatalogRequest(url,ttlMs=1800000){
 const previous=chartRequestCache.get(url);
 if(previous&&previous.until>Date.now())return previous.promise;
 const promise=jsonRequest(url).catch(error=>{chartRequestCache.delete(url);throw error});
 chartRequestCache.set(url,{until:Date.now()+ttlMs,promise});return promise;
}
const audio=new Audio(); audio.preload='metadata';
const nativePlayback=!!window.AuraNative?.playAudio;
let nativePosition=0, nativeDuration=0, nativeActive=false;
let nativeStartWatchdog=0, playbackAttempt=0, pendingStart=null, lastNativeEndId='', lastUpcomingId='';let lastFailedKey='',retryCount=0,skipAfterFailures=false;
function cancelNativeWatchdog(){if(nativeStartWatchdog){clearTimeout(nativeStartWatchdog);nativeStartWatchdog=0;}}
function setActualPlaying(value){playing=!!value;syncPlaybackButtons();}
async function fallbackPlayback(attempt,reason){
 if(attempt!==playbackAttempt||!pendingStart||!nativeActive)return;
 const {url,track}=pendingStart;pendingStart=null;cancelNativeWatchdog();
 nativeActive=false;setActualPlaying(false);
 try{
  window.AuraNative?.stopAudio?.();
  if(url.startsWith('aura-offline://'))throw Error('Offline audio is not available through WebView; native playback failed');
  audio.src=url;await audio.play();
  if(attempt!==playbackAttempt){audio.pause();return}
  note('Switched to in-app playback');
 }catch(err){setActualPlaying(false);await retryOrSkip(track,(reason||err.message)+' · '+err.message)}
}
window.AuraPlaybackState=function(payload){
 try{
  const state=typeof payload==='string'?JSON.parse(payload):payload;
  if(!nativeActive)return;
  // Ignore stale media-session states for previously played songs.
  if(state.mediaId&&current&&state.mediaId!==key(current)){
   const matched=lookup(state.mediaId)||playQueue.find(x=>key(x)===state.mediaId);
   if(matched&&!pendingStart){current=matched;render();void findMusicVideo(matched);scheduleNativeQueue(matched)}
   else return;
  }
  nativePosition=Math.max(0,Number(state.position||0)/1000);
  nativeDuration=Math.max(0,Number(state.duration||0)/1000);
  nativeHasNext=!!state.hasNext;
  if(state.error){const attempt=playbackAttempt;fallbackPlayback(attempt,state.error);return}
  if(state.playing){
   const newlyStarted=!!pendingStart;
   pendingStart=null;cancelNativeWatchdog();lastFailedKey='';retryCount=0;lastNativeEndId='';setActualPlaying(true);
   if(newlyStarted&&current&&page!=='swipe')void scheduleNativeQueue(current);
  }else{
   setActualPlaying(false);
   if(state.playbackState===4&&current&&page!=='swipe'&&lastNativeEndId!==key(current)){
    lastNativeEndId=key(current);void next();
   }
  }
 }catch(err){console.warn('Native playback state',err)}
};
window.AuraPlaybackError=function(message){if(nativeActive)fallbackPlayback(playbackAttempt,String(message||'Native audio failed'));};
audio.addEventListener('playing',()=>{if(!nativeActive)setActualPlaying(true)});
audio.addEventListener('play',()=>{if(!nativeActive&&audio.readyState>=2)setActualPlaying(true)});
audio.addEventListener('pause',()=>{if(!nativeActive)setActualPlaying(false)});
audio.addEventListener('waiting',()=>{if(!nativeActive)setActualPlaying(false)});
audio.addEventListener('error',()=>{if(!nativeActive&&current){setActualPlaying(false);retryOrSkip(current,'Audio source failed to load')}});
audio.addEventListener('ended',()=>next());
const jsonRequest=(url)=>new Promise((resolve,reject)=>{const id='a'+(++reqId); if(window.AuraNative&&typeof AuraNative.get==='function'){pending.set(id,{resolve,reject});AuraNative.get(id,url);setTimeout(()=>{if(pending.has(id)){pending.delete(id);reject(Error('Request timed out'))}},18000)}else{fetch(url,{cache:'no-store'}).then(r=>{if(!r.ok)throw Error('HTTP '+r.status);return r.json()}).then(resolve,reject)}});
window.AuraBridgeResponse=(id,ok,text)=>{const item=pending.get(id);if(!item)return;pending.delete(id);try{if(!ok)throw Error(text);item.resolve(JSON.parse(text))}catch(e){item.reject(e)}};
const serverPending=new Map();let serverRequestNumber=0;
window.AuraServerResponse=(id,ok,payload)=>{const call=serverPending.get(id);if(!call)return;serverPending.delete(id);ok?call.resolve(payload):call.reject(Error(payload))};
function serverGet(type,base,token,path){
 if(!window.AuraNative?.serverGet)return Promise.reject(Error('Home servers require the native Android app'));
 return new Promise((resolve,reject)=>{const id='s'+(++serverRequestNumber);serverPending.set(id,{resolve,reject});AuraNative.serverGet(id,type,base,token,path);setTimeout(()=>{if(serverPending.has(id)){serverPending.delete(id);reject(Error('Server request timed out'))}},17000)})}
const serverOrigin=(type,id)=>'server-'+type+'-'+id;
const serverUrl=(base,path)=>base.replace(/\/$/,'')+'/'+path.replace(/^\//,'');
function serverTrack(raw,entry,type,section=''){
 const base=entry.base.replace(/\/$/,''); const token=entry.token;
 if(type==='plex'){
  const p=raw.Media?.[0]?.Part?.[0]||raw.part||{};
  return {id:String(raw.ratingKey||raw.key),origin:serverOrigin(type,entry.id),providerId:String(raw.ratingKey||raw.key),title:raw.title||'Untitled',artist:raw.grandparentTitle||raw.originalTitle||'Unknown artist',album:raw.parentTitle||'',duration:Math.round(Number(raw.duration||0)/1000),cover:raw.thumb?serverUrl(base,raw.thumb)+'?X-Plex-Token='+encodeURIComponent(token):'',stream:p.key?serverUrl(base,p.key)+'?X-Plex-Token='+encodeURIComponent(token):'',downloadAllowed:false,serverId:entry.id};
 }
 const id=String(raw.Id||raw.id||'');const prefix=type==='emby'?'/emby':'';
 const title=raw.Name||raw.name||'Untitled';const album=raw.Album||raw.album||'';
 const artist=(raw.AlbumArtists?.[0]?.Name||raw.Artists?.[0]||raw.ArtistItems?.[0]?.Name||'Unknown artist');
 const ext=raw.ImageTags?.Primary?'?tag='+encodeURIComponent(raw.ImageTags.Primary):'';
 return {id,origin:serverOrigin(type,entry.id),providerId:id,title,artist,album,cover:serverUrl(base,prefix+'/Items/'+encodeURIComponent(id)+'/Images/Primary')+(ext?ext+'&':'?')+'api_key='+encodeURIComponent(token),duration:Math.round(Number(raw.RunTimeTicks||0)/10000000),stream:serverUrl(base,prefix+'/Audio/'+encodeURIComponent(id)+'/stream?static=true&api_key='+encodeURIComponent(token)),downloadAllowed:false,serverId:entry.id};
}
async function fetchServerCatalog(entry){
 const base=entry.base, token=entry.token;
 if(entry.type==='plex'){
  const sectionXML=await serverGet('plex',base,token,'/library/sections');
  const doc=new DOMParser().parseFromString(sectionXML,'application/xml');
  const sections=[...doc.querySelectorAll('Directory')].filter(x=>x.getAttribute('type')==='artist');
  let tracks=[];
  for(const sec of sections.slice(0,5)){
   const response=await serverGet('plex',base,token,'/library/sections/'+encodeURIComponent(sec.getAttribute('key'))+'/all?type=10');
   const tree=new DOMParser().parseFromString(response,'application/xml');
   for(const el of [...tree.querySelectorAll('Track')].slice(0,300)){
    const part=el.querySelector('Media Part');
    tracks.push(serverTrack({ratingKey:el.getAttribute('ratingKey'),title:el.getAttribute('title'),grandparentTitle:el.getAttribute('grandparentTitle'),parentTitle:el.getAttribute('parentTitle'),duration:el.getAttribute('duration'),thumb:el.getAttribute('thumb'),part:{key:part?.getAttribute('key')}},entry,'plex'));
   }
  }
  return tracks.filter(t=>t.stream)
 }
 const prefix=entry.type==='emby'?'/emby':'';
 // The authenticated user's library endpoint is more reliable across Jellyfin and Emby versions.
 let libraryPath=prefix+'/Items?Recursive=true&IncludeItemTypes=Audio&Limit=500&Fields=Genres&SortBy=SortName';
 try{const user=JSON.parse(await serverGet(entry.type,base,token,prefix+'/Users/Me'));if(user?.Id)libraryPath=prefix+'/Users/'+encodeURIComponent(user.Id)+'/Items?Recursive=true&IncludeItemTypes=Audio&Limit=500&Fields=Genres&SortBy=SortName';}catch(e){console.info('Falling back to server-wide catalog');}
 const data=JSON.parse(await serverGet(entry.type,base,token,libraryPath));
 return (data.Items||data.items||[]).map(x=>serverTrack(x,entry,entry.type)).filter(x=>x.id)
}
async function refreshServers(){
 if(!persist.servers?.length){serverTracks=[];return}
 serverBusy=true;serverMessage='Connecting to music servers…';if(page==='library'||page==='downloads'||page==='favorites'||page==='player'||page==='playlist'||page==='album')render();
 const status=await Promise.allSettled(persist.servers.map(fetchServerCatalog));
 serverTracks=unique(status.flatMap(x=>x.status==='fulfilled'?x.value:[]));remember(serverTracks);
 const issues=status.filter(x=>x.status==='rejected').map(x=>x.reason.message);
 serverMessage=serverTracks.length?serverTracks.length+' server songs':issues.length?issues[0]:'No server music found';serverBusy=false;
 if(page==='library'||page==='settings')render();
}
let plexPinCode = null, plexPinId = null, plexPollTimer = null;

async function startPlexPin(){
 try{
  note('Genererer Plex PIN…');
  const res=await fetch('https://plex.tv/api/v2/pins?strong=true',{
   method:'POST',
   headers:{'Accept':'application/json','X-Plex-Client-Identifier':'aura_amp_mobile_'+(persist.account||'user'),'X-Plex-Product':'AURA AMP'}
  });
  const data=await res.json();
  if(data.id&&data.code){
   plexPinId=data.id;
   plexPinCode=data.code;
   render();
   note('Gå til plex.tv/link og tast inn koden: '+data.code);
   if(plexPollTimer)clearInterval(plexPollTimer);
   plexPollTimer=setInterval(pollPlexPin,2500);
  }else{
   note('Kunne ikke hente Plex PIN');
  }
 }catch(e){
  note('Plex PIN feil: '+e.message);
 }
}

async function pollPlexPin(){
 if(!plexPinId)return;
 try{
  const res=await fetch('https://plex.tv/api/v2/pins/'+plexPinId,{
   headers:{'Accept':'application/json','X-Plex-Client-Identifier':'aura_amp_mobile_'+(persist.account||'user'),'X-Plex-Product':'AURA AMP'}
  });
  const data=await res.json();
  if(data.authToken){
   clearInterval(plexPollTimer);
   plexPollTimer=null;
   const token=data.authToken;
   note('Plex tilkoblet! Henter servere…');
   const resRes=await fetch('https://plex.tv/api/v2/resources?includeHttps=1',{
    headers:{'Accept':'application/json','X-Plex-Token':token,'X-Plex-Client-Identifier':'aura_amp_mobile_'+(persist.account||'user')}
   });
   const serversData=await resRes.json();
   const plexServers=(Array.isArray(serversData)?serversData:[]).filter(s=>s.provides?.includes('server'));
   if(plexServers.length){
    for(const s of plexServers){
     const conn=s.connections?.find(c=>c.local)||s.connections?.[0];
     if(conn){
      const base=conn.uri.replace(/\/$/,'');
      persist.servers=persist.servers||[];
      persist.servers.push({id:'srv_'+Date.now(),base,type:'plex',token,name:s.name});
     }
    }
    save();
    note('Lagt til '+plexServers.length+' Plex-server(e)!');
    refreshServers();
   }else{
    note('Fant ingen Plex-servere på din konto');
   }
   plexPinCode=null;
   plexPinId=null;
   render();
  }
 }catch(_){}
}

async function loginServerUser(){
 const base=($('#serverAddress')?.value||'').trim().replace(/\/$/,'');
 const type=$('#serverType')?.value||'jellyfin';
 const username=($('#serverUsername')?.value||'').trim();
 const password=$('#serverPassword')?.value||'';

 if(!base||!username){
  return note('Skriv inn serveradresse og brukernavn');
 }

 note('Logger inn på '+type.toUpperCase()+'…');
 try{
  const authUrl=base+'/Users/AuthenticateByName';
  const res=await fetch(authUrl,{
   method:'POST',
   headers:{
    'Content-Type':'application/json',
    'X-Emby-Authorization':'MediaBrowser Client="AURA AMP", Device="Android", DeviceId="aura_amp_mobile", Version="1.3.4"'
   },
   body:JSON.stringify({Username:username,Pw:password})
  });
  const data=await res.json();
  if(data.AccessToken){
   const token=data.AccessToken;
   const userId=data.User?.Id||'';
   persist.servers=persist.servers||[];
   persist.servers.push({
    id:'srv_'+Date.now(),
    base,
    type,
    token,
    userId,
    userName:username,
    name:`${type.toUpperCase()} (${username})`
   });
   save();
   note('Tilkoblet '+type.toUpperCase()+' som '+username+'!');
   refreshServers();
   render();
  }else{
   note('Innlogging feilet: Ugyldig brukernavn eller passord');
  }
 }catch(e){
  note('Kunne ikke koble til server: '+e.message);
 }
}

function serverSettings(){
 const servers=persist.servers||[];
 const enabled=persist.serversEnabled!==false;

 const serverListHtml=servers.length?servers.map(x=>`
  <div class="settings-row" style="flex-direction:column;align-items:flex-start;gap:8px;padding:12px 0;border-bottom:1px solid var(--outline)">
   <div style="display:flex;justify-content:space-between;width:100%;align-items:center">
    <div>
     <strong style="font-size:14px;color:#fff">${escape(x.type.toUpperCase())} · ${escape(x.name||new URL(x.base).host)}</strong>
     <br><small style="color:var(--secondary)">${escape(x.base)}</small>
    </div>
    <label class="aura-switch">
     <input type="checkbox" data-toggle-server-id="${escape(x.id)}" ${x.enabled!==false?'checked':''}>
     <span class="aura-slider"></span>
    </label>
   </div>
   <button class="settings-row" style="width:100%;margin-top:4px;padding:6px 12px;border-radius:10px;text-align:center" data-remove-server="${escape(x.id)}">Fjern server ×</button>
  </div>
 `).join(''):'<div class="settings-help">Ingen mediaservere tilkoblet ennå.</div>';

 return `<div class="settings-group">
 <h2>3. Home servers · Plex / Jellyfin / Emby</h2>

 <label class="settings-row" style="margin-bottom:12px">
  <span>Aktiver Home Servers</span>
  <label class="aura-switch">
   <input type="checkbox" data-setting="serversEnabled" ${enabled?'checked':''}>
   <span class="aura-slider"></span>
  </label>
 </label>

 ${enabled?`
  <div style="background:var(--pill);padding:14px;border-radius:18px;margin-bottom:14px">
   <strong style="display:block;font-size:14px;margin-bottom:6px">✨ Enkel Plex PIN-tilkobling (Easy PIN)</strong>
   <p style="font-size:12px;color:var(--secondary);margin:0 0 10px 0">Ingen manuell token trengs. Trykk under for å hente PIN-kode, godkjenn på plex.tv/link, og appen finner serverne dine automatisk.</p>
   ${plexPinCode?`
    <div style="text-align:center;padding:12px;background:var(--phone);border-radius:14px;margin-bottom:10px">
     <div style="font-size:12px;color:var(--secondary)">Din Plex PIN-kode:</div>
     <div style="font-size:32px;font-weight:850;color:var(--orange);letter-spacing:4px">${escape(plexPinCode)}</div>
     <button class="settings-primary" style="margin-top:8px" onclick="window.open('https://plex.tv/link','_blank')">Åpne plex.tv/link ↗</button>
     <div style="font-size:11px;color:var(--secondary);margin-top:6px">Venter på godkjenning…</div>
    </div>
   `:`
    <button class="settings-primary" style="margin:0;background:var(--aura-orange-gradient)" data-start-plex-pin="1">✨ Koble til Plex med PIN</button>
   `}
  </div>

  <div style="background:var(--pill);padding:14px;border-radius:18px;margin-bottom:14px">
   <strong style="display:block;font-size:14px;margin-bottom:8px">🔑 Enkel Jellyfin / Emby Innlogging</strong>
   <label class="settings-label">Server type
    <select id="serverType">
     <option value="jellyfin">Jellyfin</option>
     <option value="emby">Emby</option>
    </select>
   </label>
   <label class="settings-label">Serveradresse<input id="serverAddress" placeholder="http://192.168.1.50:8096" autocomplete="off" autocapitalize="off"></label>
   <label class="settings-label">Brukernavn<input id="serverUsername" placeholder="Brukernavn" autocomplete="off" autocapitalize="off"></label>
   <label class="settings-label">Passord<input id="serverPassword" type="password" placeholder="Passord" autocomplete="off"></label>
   <button class="settings-primary" style="margin-top:8px;background:var(--accent,#FF9F43)" data-login-server="1">🔑 Logg inn på Jellyfin / Emby</button>
  </div>

  <h3 style="font-size:1.05rem;margin:14px 0 6px">Tilkoblede Mediaservere (${servers.length})</h3>
  ${serverListHtml}
  <button class="settings-row" style="margin-top:10px" data-refresh-servers="1">Oppdater musikkkatalog fra server <span>↻</span></button>
 `:`
  <div class="settings-help" style="color:var(--secondary)">Home servers er deaktivert. Slå på slideren over for å koble til eller bruke mediaservere.</div>
 `}
 </div>`;
}

function auraMusikSettings(){
 const enabled=persist.auraMusikEnabled!==false;
 return `<div class="settings-group">
  <h2>1. Hovedkilde · AuraMusik Engine (YouTube)</h2>
  <div class="settings-help">AuraMusik er AuraAmps innebygde hovedkilde for direkte søk og strømming av YouTube-musikk og musikkvideoer direkte på enheten.</div>
  <label class="settings-row">
   <span>Aktiver AuraMusik Engine (Hovedkilde)</span>
   <label class="aura-switch">
    <input type="checkbox" data-setting="auraMusikEnabled" ${enabled?'checked':''}>
    <span class="aura-slider"></span>
   </label>
  </label>

  <div id="auraMusikStatus" class="settings-help" style="font-weight:700;margin-top:8px;color:${enabled?'#2ecc71':'var(--secondary)'}">
   ${enabled?'🟢 AuraMusik Engine: Aktiv (Hovedkilde · Innebygd)':'⚪ AuraMusik er deaktivert.'}
  </div>
 </div>`;
}

function extractVideoId(t){
 const raw=t.id||t.videoId||t.songId||t.url||t.uri||'';
 const s=String(raw);
 if(/^[a-zA-Z0-9_-]{11}$/.test(s))return s;
 const m=s.match(/(?:v=|\/embed\/|\/v\/|youtu\.be\/|\/watch\?v=)([a-zA-Z0-9_-]{11})/);
 if(m&&m[1])return m[1];
 const clean=s.replace('https://www.youtube.com/watch?v=','').replace('https://youtu.be/','').replace('/watch?v=','').replace(/^\//,'').trim();
 if(/^[a-zA-Z0-9_-]{11}$/.test(clean))return clean;
 return clean||Math.random().toString(36).slice(2,11);
}

function normalizeAuraMusikTrack(t,type='song'){
 const id=extractVideoId(t);
 const artistName=typeof t.artist==='object'?(t.artist.name||''):(t.artist||t.artistName||t.uploaderName||'Unknown artist');
 const albumTitle=typeof t.album==='object'?(t.album.title||t.album.name||''):(t.album||'');
 const isVideo=type==='video'||t.type==='video'||!!t.videoId||t.is_video===true;

 return {
  id:id,
  origin:'auramusik',
  providerId:id,
  title:t.title||t.name||(isVideo?'Music Video':'Untitled'),
  artist:artistName,
  album:albumTitle,
  cover:t.thumbnail||t.cover||t.image||t.artwork||t.thumbnailUrl||`https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
  duration:Number(t.duration||(t.durationMs?t.durationMs/1000:0)),
  stream:`auramusik://${encodeURIComponent(id)}`,
  isVideo:isVideo,
  videoUrl:isVideo?`auramusik://${encodeURIComponent(id)}`:'',
  videoProvider:isVideo?'auramusik':'',
  videoId:isVideo?id:'',
  downloadAllowed:false,
  isrc:String(t.isrc||'').replace(/[-\s]/g,'').toUpperCase()
 };
}

const auraMusikStreamCache=new Map();

// kind 'audio' → best audio-only stream (used for music playback); 'video' → a muxed progressive stream that carries BOTH audio and
// video in one URL (used for the embedded music-video player). Cached per (kind|videoId).
async function auraMusikStream(videoId,kind='audio'){
 const cacheKey=kind+'|'+videoId;
 if(auraMusikStreamCache.has(cacheKey))return auraMusikStreamCache.get(cacheKey);
 const instances=[
  {type:'piped',url:'https://pipedapi.kavin.rocks/streams/'},
  {type:'piped',url:'https://api.piped.privacydev.net/streams/'},
  {type:'piped',url:'https://pipedapi.drgns.space/streams/'},
  {type:'invidious',url:'https://invidious.privacydev.net/api/v1/videos/'},
  {type:'invidious',url:'https://inv.tux.pizza/api/v1/videos/'},
  {type:'invidious',url:'https://vid.puffyan.us/api/v1/videos/'}
 ];
 for(const inst of instances){
  try{
   const res=await fetch(inst.url+encodeURIComponent(videoId),{cache:'no-store'});
   if(!res.ok)continue;
   const data=await res.json();
   let url=null;
   if(inst.type==='piped'){
    if(kind==='video'){
     url=(data.videoStreams||[]).filter(s=>s&&s.url&&s.videoOnly===false).sort((a,b)=>(parseInt(b.quality)||0)-(parseInt(a.quality)||0))[0]?.url||null;
     if(!url){
      url=(data.videoStreams||[]).filter(s=>s&&s.url).sort((a,b)=>(parseInt(b.quality)||0)-(parseInt(a.quality)||0))[0]?.url||null;
     }
    }else{
     url=(data.audioStreams||[]).sort((a,b)=>(b.bitrate||0)-(a.bitrate||0))[0]?.url||null;
    }
   }else if(inst.type==='invidious'){
    if(kind==='video'){
     url=(data.formatStreams||[]).filter(f=>f&&f.url).sort((a,b)=>(parseInt(b.qualityLabel)||0)-(parseInt(a.qualityLabel)||0))[0]?.url||null;
     if(!url){
      url=(data.adaptiveFormats||[]).filter(f=>f&&f.url).sort((a,b)=>(Number(b.bitrate)||0)-(Number(a.bitrate)||0))[0]?.url||null;
     }
    }else{
     url=(data.adaptiveFormats||[]).filter(f=>f.type?.includes('audio')).sort((a,b)=>(Number(b.bitrate)||0)-(Number(a.bitrate)||0))[0]?.url||null;
    }
   }
   if(url){auraMusikStreamCache.set(cacheKey,url);return url;}
  }catch(_){}
 }
 throw Error('Kunne ikke hente '+(kind==='video'?'videostrøm':'lydelement')+' for video ID: '+videoId);
}

async function auraMusikSearch(q,type='song'){
 if(persist.auraMusikEnabled===false)return [];
 const filter=type==='video'?'videos':'music_songs';
 const instances=[
  `https://pipedapi.kavin.rocks/search?q=${encodeURIComponent(q)}&filter=${filter}`,
  `https://api.piped.privacydev.net/search?q=${encodeURIComponent(q)}&filter=${filter}`,
  `https://invidious.privacydev.net/api/v1/search?q=${encodeURIComponent(q)}&type=${type}`,
  `https://vid.puffyan.us/api/v1/search?q=${encodeURIComponent(q)}&type=${type}`
 ];

 for(const url of instances){
  try{
   const res=await fetch(url,{cache:'no-store'});
   if(!res.ok)continue;
   const data=await res.json();
   const rawItems=data.items||data;
   const items=dataItems(rawItems);
   if(items.length){
    return remember(items.map(t=>normalizeAuraMusikTrack(t,type)));
   }
  }catch(_){}
 }
 return [];
}

const DZ='https://api.deezer.com';const deezer=path=>cachedCatalogRequest(DZ+path,/(chart|releases)/.test(path)?30*60*1000:3*60*1000);const APPLE='https://rss.applemarketingtools.com/api/v2/no/music/';const apple=path=>cachedCatalogRequest(APPLE+path,30*60*1000);const appleSongs=x=>dataItems(x?.feed?.results).map(t=>({id:String(t.id),origin:'apple',providerId:String(t.id),title:t.name||'Untitled',artist:t.artistName||'Unknown artist',album:t.collectionName||'',cover:(t.artworkUrl100||t.artworkUrl||'').replace(/100x100bb/g,'600x600bb'),duration:0,preview:'',stream:'',downloadAllowed:false,albumId:t.id}));
const norwegianItunesSongs=x=>dataItems(x?.feed?.entry).map((t,i)=>({
 id:String(t?.id?.attributes?.['im:id']||'no-'+i),origin:'apple',providerId:String(t?.id?.attributes?.['im:id']||'no-'+i),
 title:t?.['im:name']?.label||'Untitled',artist:t?.['im:artist']?.label||'Unknown artist',
 album:t?.['im:collection']?.['im:name']?.label||'',
 cover:(t?.['im:image']?.slice(-1)?.[0]?.label||'').replace(/170x170bb/g,'600x600bb'),
 stream:'',duration:0,downloadAllowed:false,
}));
async function norwegianCharts(){
 try{const first=appleSongs(await apple('most-played/100/songs.json'));if(first.length)return {tracks:first,source:'Apple Music'}}
 catch(error){console.info('Apple Music Norway chart feed not available:',error.message)}
 const fallback=await cachedCatalogRequest('https://itunes.apple.com/no/rss/topsongs/limit=100/json',30*60*1000);
 const tracks=norwegianItunesSongs(fallback);
 if(!tracks.length)throw Error('Norwegian chart feeds returned no songs');
 return {tracks,source:'iTunes Store'};
}

const itunesSearch=term=>cachedCatalogRequest('https://itunes.apple.com/search?country=no&media=music&entity=album&limit=200&term='+encodeURIComponent(term),30*60*1000);
function recentItunesAlbums(payload){
 const now=Date.now(),age=120*86400000;
 return dataItems(payload).filter(t=>t.wrapperType==='collection'&&t.collectionId&&t.releaseDate)
  .map(t=>({...t,stamp:Date.parse(t.releaseDate)}))
  .filter(t=>Number.isFinite(t.stamp)&&t.stamp>=now-age&&t.stamp<=now+3*86400000)
  .sort((a,b)=>b.stamp-a.stamp)
  .map(t=>({id:String(t.collectionId),providerId:String(t.collectionId),origin:'itunes',title:t.collectionName||'New album',album:t.collectionName||'',artist:t.artistName||'Unknown artist',albumId:String(t.collectionId),cover:(t.artworkUrl100||'').replace(/100x100bb/g,'600x600bb'),date:t.releaseDate,stream:'',duration:0}));
}
const image=t=>t?.album?.cover_xl||t?.album?.cover_big||t?.album?.cover_medium||t?.cover_xl||t?.cover_big||t?.cover_medium||t?.picture_big||t?.picture_medium||t?.poster||t?.image||t?.artwork||'';
function normalize(t,origin='deezer'){const raw=t?.track||t||{}, al=typeof raw.album==='object'?raw.album:{title:raw.album||''}, ar=typeof raw.artist==='object'?raw.artist:{name:raw.artist||raw.artist_name||''};return {id:String(raw.id||raw._id||raw.deezerId||raw.title||Math.random()),origin,providerId:String(raw.id||raw._id||''),title:raw.title||raw.name||raw.track_name||'Untitled',artist:ar.name||raw.artist_name||'Unknown artist',album:al.title||raw.album_name||'',cover:image(raw)||image(al),duration:Number(raw.duration||0),date:al.release_date||raw.release_date||'',artistId:ar.id||raw.artist_id||'',albumId:al.id||raw.album_id||'',artistImage:ar.picture_xl||ar.picture_big||raw.artist_image||'',preview:raw.preview||'',stream:raw.stream_url||raw.audio_url||'',mime:raw.mime||'',quality:raw.quality||'',downloadAllowed:raw.downloadAllowed===true||raw.canDownload===true,isrc:String(raw.isrc||raw.ISRC||raw.identifiers?.isrc||'').replace(/[-\s]/g,'').toUpperCase(),musicVideo:raw.musicVideo||raw.music_video||null,videoUrl:raw.musicVideo?.url||raw.music_video_url||raw.videoUrl||'',videoProvider:raw.musicVideo?.provider||raw.videoProvider||'',videoId:raw.musicVideo?.id||raw.youtubeVideoId||raw.videoId||''};}
const unique=a=>[...new Map(a.map(x=>[`${x.origin}|${x.id}`,x])).values()];
const dataItems=x=>{if(Array.isArray(x))return x;if(x&&Array.isArray(x.data))return x.data;for(const k of ['tracks','results','items','metas'])if(Array.isArray(x?.[k]))return x[k];return []};
const img=(s,cls='')=>s.cover?`<img class="${cls}" src="${escape(s.cover)}" loading="lazy" referrerpolicy="no-referrer" onerror="this.onerror=null;this.style.display='none';this.parentElement.classList.add('missing-art')" alt="">`:`<div class="placeholder-cover ${cls}">♫</div>`;
const downloadIcon=()=>'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v10m-4-4 4 4 4-4M5 18h14"/><circle cx="12" cy="12" r="10" stroke-opacity=".36" stroke-width="1.2"/></svg>';
const downloadToggle=(song)=>{const saved=downloadedTrack(song)||song.origin==='local',id=escape(key(song));return `<button class="track-download-toggle ${saved?'downloaded':''}" data-toggle-download="${id}" aria-label="${saved?'Remove offline download':'Download song'}" title="${saved?'Remove offline download':'Download for offline playback'}" aria-pressed="${saved}">${downloadIcon()}</button>`};
const row=(s)=>`<div class="song-row" data-track="${escape(s.origin)}|${escape(s.id)}"><button class="song-art-play" data-play="${escape(key(s))}" aria-label="Play ${escape(s.title)}">${img(s)}</button><button class="song-info" data-play="${escape(key(s))}"><strong>${escape(s.title)}</strong><small>${escape(s.artist)}${s.duration?' · '+fmt(s.duration):''}</small></button>${downloadToggle(s)}<button class="mini-play-next" data-play-next="${escape(key(s))}" aria-label="Play ${escape(s.title)} next" title="Play next">${glyph('next')}</button><button class="mini-dots" data-more="${escape(key(s))}" aria-label="Track actions">${glyph('dots')}</button></div>`;
const rail=(items)=>`<div class="release-rail">${items.map(s=>`<button class="release" ${s.origin==='itunes'?`data-search-album="${escape((s.artist||'')+' '+(s.album||s.title||''))}"`:`data-album="${escape(s.albumId||s.id)}"`}>${img(s)}<strong>${escape(s.title)}</strong><small>${escape(s.artist)}</small></button>`).join('')}</div>`;
const section=(title,right='')=>`<div class="section-head"><h2>${title}</h2>${right?`<button class="link-button" data-open-list="${escape(right)}">View All</button>`:''}</div>`;
const brand=()=>`<div class="screen-header"><span class="brand-small">AURA <b>AMP</b></span></div>`;
const glyph=(name)=>({home:'<svg viewBox="0 0 24 24"><path d="M3 10.5 12 3l9 7.5V21h-6v-7H9v7H3z"/></svg>',search:'<svg viewBox="0 0 24 24"><circle cx="10.8" cy="10.8" r="7.3"/><path d="m16.5 16.5 4.5 4.5"/></svg>',heart:'<svg viewBox="0 0 24 24"><path d="M20.5 5.8a5.1 5.1 0 0 0-7.2 0L12 7.1l-1.3-1.3a5.1 5.1 0 1 0-7.2 7.2L12 21l8.5-8a5.1 5.1 0 0 0 0-7.2z"/></svg>',wave:'<svg viewBox="0 0 24 24"><path d="M2 11v2M6 7v10M10 3v18M14 8v8M18 5v14M22 10v4"/></svg>',tv:'<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="7" width="20" height="15" rx="2" ry="2"/><polyline points="17 2 12 7 7 2"/></svg>',user:'<svg viewBox="0 0 24 24"><circle cx="12" cy="7.6" r="4"/><path d="M4.5 21v-2.5a7.5 7.5 0 0 1 15 0V21"/></svg>',back:'<svg viewBox="0 0 24 24"><path d="m14.5 5-7 7 7 7M7.5 12H21"/></svg>',shuffle:'<svg viewBox="0 0 24 24"><path d="M3 6h3c6 0 6 12 12 12h3m-4-4 4 4-4 4M3 18h3c2 0 3.4-1.5 4.6-4M14 9c1-1.6 2.2-3 4-3h3m-4-4 4 4-4 4"/></svg>',repeat:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m17 2 4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/><path d="m7 22-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/></svg>',queue:'<svg viewBox="0 0 24 24"><path d="M3 5h18M3 10h18M3 15h11M3 20h9m6-5v7m-3.5-3.5h7"/></svg>',dots:'<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></svg>'})[name]||'';
const nav=()=>`<nav class="bottom-nav"><button data-nav="discover" class="${page==='discover'?'active':''}" aria-label="Discover">${glyph('home')}</button><button data-nav="search" class="${page==='search'?'active':''}" aria-label="Search">${glyph('search')}</button><button data-nav="library" class="${page==='library'||page==='playlist'||page==='album'||page==='artist'?'active':''}" aria-label="Library">${glyph('wave')}</button>${auraConnectReceivers.length>0?`<button data-open-tv-remote="1" class="${tvRemoteActive?'active':''}" aria-label="TV Remote">${glyph('tv')}</button>`:''}<button data-nav="settings" class="${page==='settings'?'active':''}" aria-label="Profile and Settings">${glyph('user')}</button></nav>`;
const playerDock=()=>`<div class="bottom-nav player-control-dock" role="group" aria-label="Playback controls"><button data-shuffle="1" class="${shuffleEnabled?'active':''}" aria-pressed="${shuffleEnabled}" aria-label="${shuffleEnabled?'Slå av tilfeldig avspilling':'Slå på tilfeldig avspilling'}" title="Tilfeldig avspilling">${glyph('shuffle')}</button><button data-player="prev" aria-label="Forrige sang" title="Forrige">${transportIcon('previous')}</button><button class="dock-play ${playing?'is-playing':''}" data-player="toggle" aria-label="${playing?'Pause':'Spill av'}" title="${playing?'Pause':'Spill av'}">${transportIcon(playing?'pause':'play')}</button><button data-player="next" aria-label="Neste sang" title="Neste">${transportIcon('next')}</button><button data-repeat="1" class="${repeatMode?'active':''} ${repeatMode===1?'repeat-one':''}" aria-pressed="${repeatMode!==0}" aria-label="${repeatMode===0?'Gjentakelse av':repeatMode===1?'Gjenta én sang':'Gjenta alle'}" title="${repeatMode===0?'Gjenta av':repeatMode===1?'Gjenta én sang':'Gjenta alle'}">${glyph('repeat')}${repeatMode===1?'<small>1</small>':''}</button></div>`;
const note=s=>{const t=$('#toast');if(t){t.textContent=s;t.classList.add('visible');setTimeout(()=>t.classList.remove('visible'),3700)}};
function discover(){
 const tabs=`<div class="tab-line">${['Overview','Songs','Albums','Artists'].map(c=>`<button data-discover-tab="${c}" class="${discoverCategory===c?'selected':''}">${c}</button>`).join('')}</div>`;
 const all=unique([...home.trending,...home.top,...home.new,...localSongs,...persist.history.map(lookup).filter(Boolean)]);
 const songs=unique([...home.top,...home.trending,...localSongs]);
 const albums=[...new Map([...home.new,...all].filter(x=>x.album||x.albumId).map(x=>[(x.artist+'|'+(x.album||x.title)).toLowerCase(),x])).values()];
 const artists=[...new Map(all.filter(x=>x.artist&&x.artist!=='Unknown artist').map(x=>[x.artist.toLowerCase(),x])).values()];
 let content='';
 if(discoverCategory==='Overview')content=`${section('Trending','trending')}${home.trending.length?`<div class="tile-rail">${home.trending.slice(0,8).map(s=>`<button class="feature-card" data-play="${escape(key(s))}">${img(s)}<span class="feature-info"><span class="feature-name">${escape(s.title)}</span><span class="feature-footer"><span class="small-square-play">▶</span><span class="feature-stat">${escape(s.artist)}</span></span></span></button>`).join('')}</div>`:empty(busyHome?'Loading trending music…':'Trending unavailable · pull down to retry')}${section('New Releases','new')}${home.new.length?rail(home.new.slice(0,15)):empty(busyHome?'Loading new releases…':'New releases unavailable · pull down to retry')}${section('Top Songs','top')}${home.top.length?home.top.slice(0,10).map(row).join(''):empty(busyHome?'Loading top songs…':'Top songs unavailable · pull down to retry')}${section('Norway Top Songs · '+(home.norwaySource||'Apple Music'),'norway')}${home.norway.length?home.norway.slice(0,10).map(row).join(''):empty(busyHome?'Loading Norwegian charts…':'Norwegian chart currently unavailable')}`;
 if(discoverCategory==='Songs')content=`${section('Discover Songs','top')}${songs.length?songs.slice(0,45).map(row).join(''):empty(busyHome?'Loading songs…':'No songs available from music sources')}`;
 if(discoverCategory==='Albums')content=`${section('Discover Albums','new')}${albums.length?`<div class="discover-album-grid">${albums.slice(0,40).map(a=>`<button class="discover-album" data-album-track="${escape(key(a))}">${img(a)}<strong>${escape(a.album||a.title)}</strong><small>${escape(a.artist)}</small></button>`).join('')}</div>`:empty(busyHome?'Loading albums…':'No albums available from music sources')}`;
 if(discoverCategory==='Artists')content=`${section('Discover Artists')}${artists.length?artists.slice(0,50).map(a=>`<button class="artist-discover-row" data-artist="${escape(a.artist)}">${img(a)}<span><strong>${escape(a.artist)}</strong><small>Explore music</small></span><span class="artist-chevron">›</span></button>`).join(''):empty(busyHome?'Loading artists…':'No artists available from music sources')}`;
 return `${brand()}<h1 class="title-large">Discover</h1>${tabs}${content}<div class="view-spacer"></div>`
}
function empty(s){return `<div class="switch-banner">${escape(s)}</div>`}
function searchPage(){return `<div class="search-input"><span>${glyph('search')}</span><input id="searchField" type="text" placeholder="Search songs, artists, albums, YouTube videos…" value="${escape(query)}" autocomplete="off" autocapitalize="off"><button class="clear-search" data-clear="1" aria-label="Clear search">×</button></div><div class="category-chips">${['All','Songs','Albums','Artists','Playlists','YouTube'].map(c=>`<button data-category="${c}" class="${category===c?'selected':''}">${c}</button>`).join('')}</div><div id="resultsStatus" class="small-status"></div><div id="searchResults">${searchMarkup()}</div>`}
function searchMarkup(){if(!query.trim()&&!results.length)return `<div class="search-empty">Search for music, albums, artists and YouTube music videos</div>`;if(busy)return empty('Searching…');if(!results.length)return empty(sourceError||'No matching music found');let arr=results;
if(category==='Albums')return `${section('Albums')}${albumRows(results)}`;
if(category==='Artists')return `${section('Artists')}${artistRows(results)}`;
if(category==='Playlists')return persist.playlists.length?persist.playlists.map(x=>`<button class="song-row" data-list="${escape(x.id)}"><span class="playlist-mini-icon">♫</span><span class="song-info"><strong>${escape(x.name)}</strong><small>${x.tracks.length} Songs</small></span></button>`).join(''):empty('No playlists found');
if(category==='YouTube'){
 const videos=results.filter(x=>x.isVideo||x.origin==='musike'||x.videoUrl);
 return `${section('YouTube Music Videos')}${videos.length?videos.map(row).join(''):empty('No YouTube music videos found for this search')}`;
}
if(category!=='All')return `${section(category)}${arr.map(row).join('')}`;
const best=results[0], songs=results.slice(0,12), albums=results.filter(x=>x.album).slice(0,6), videos=results.filter(x=>x.isVideo||x.origin==='musike').slice(0,6);
return `<div class="result-section">${section('Top Result')}<button class="top-result" data-artist="${escape(best.artist)}">${img({cover:best.artistImage||best.cover})}<div><strong>${escape(best.artist)}</strong><small>Artist · Explore releases</small></div><span>›</span></button></div><div class="result-section">${section('Songs','results-songs')}${songs.map(row).join('')}</div>${videos.length?`<div class="result-section">${section('YouTube Music Videos','results-videos')}${videos.map(row).join('')}</div>`:''}${albums.length?`<div class="result-section">${section('Albums','results-albums')}${albumRows(albums)}</div>`:''}`}
function albumRows(entries){const albums=[...new Map(entries.filter(x=>x.album||x.albumId).map(x=>[(x.artist+'|'+(x.album||x.title)).toLowerCase(),x])).values()];return albums.map(x=>`<button class="catalog-result" ${x.origin==='itunes'?`data-search-album="${escape((x.artist||'')+' '+(x.album||x.title||''))}"`:`data-album-track="${escape(key(x))}"`}>${img(x)}<span><strong>${escape(x.album||x.title)}</strong><small>${escape(x.artist)}</small></span><span class="catalog-chevron">›</span></button>`).join('')||empty('No albums found')}
function artistRows(entries){const artists=[...new Map(entries.filter(x=>x.artist).map(x=>[x.artist.toLowerCase(),x])).values()];return artists.map(x=>`<button class="artist-discover-row" data-artist="${escape(x.artist)}">${img({cover:x.artistImage||x.cover})}<span><strong>${escape(x.artist)}</strong><small>View artist</small></span><span class="artist-chevron">›</span></button>`).join('')||empty('No artists found')}
function playlistPage(){const list=persist.playlists.find(x=>x.id===selectedPlaylist);if(!list)return `${section('Your Playlists')}${persist.playlists.map(l=>`<button class="song-row" data-list="${escape(l.id)}">${playlistCover(l)}<span class="song-info"><strong>${escape(l.name)}</strong><small>${l.tracks.length} Songs</small></span></button>`).join('')||empty('Create a playlist in Library.')}`;
const tracks=(list.tracks||[]).map(k=>lookup(k)).filter(Boolean);const cover=list.coverImage||tracks.find(x=>x.cover)?.cover||'';const total=tracks.reduce((t,s)=>t+(s.duration||0),0);
return `<div class="screen-header playlist-header"><button class="icon-only" data-nav="library">${glyph('back')}</button><button class="icon-only" data-playlist-menu="${escape(list.id)}">${glyph('dots')}</button></div>${playlistCover(list,true)}<h1 class="playlist-title">${escape(list.name)}</h1><p class="playlist-byline">AURA AMP Playlist</p><p class="playlist-byline">${tracks.length} Songs · ${fmt(total)}</p><p class="playlist-description">${escape(list.description||'Your personal music collection.')}</p><div class="playlist-buttons"><button class="primary icon-collection-action" data-play-list="${escape(list.id)}" aria-label="Play playlist" title="Play playlist">${transportIcon('play')}</button><button class="icon-collection-action" data-shuffle-list="${escape(list.id)}" aria-label="Shuffle playlist" title="Shuffle playlist">${glyph('shuffle')}</button><button class="round-add" data-add-tracks="1" aria-label="Add tracks" title="Add tracks">+</button><button class="icon-collection-action" data-edit-playlist="${escape(list.id)}" aria-label="Edit playlist cover and information" title="Edit playlist"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"/><path d="m10 14-1 4 4-1L21 9l-3-3z"/></svg></button><button class="icon-collection-action collection-download ${tracks.length&&tracks.every(t=>downloadedTrack(t)||t.origin==='local')?'downloaded':''}" data-download-playlist="${escape(list.id)}" aria-label="Download playlist" title="Download playlist">${downloadIcon()}</button></div>${tracks.map(row).join('')||empty('No songs yet. Search for music and add tracks to this playlist.')}`}
let playlistEditorId=null,playlistArtDraft='',playlistPresetDraft=0;
const auraCovers=['linear-gradient(140deg,#ff7b21,#82315a 68%,#1a1d32)','linear-gradient(145deg,#113c59,#0c1827 65%,#fc8242)','linear-gradient(130deg,#4e2e65,#f89e42)','linear-gradient(135deg,#06463e,#2c8762,#111a2a)','linear-gradient(150deg,#271d39,#f16939,#ffc066)'];
const coverCSS=(pl)=>pl.coverImage?`background-image:url("${String(pl.coverImage).replace(/"/g,'')}")`:`background:${auraCovers[(pl.coverPreset||0)%5]}`;
const playlistCover=(pl,large=false)=>`<div class="aura-list-cover ${large?'big':''}" style='${coverCSS(pl)}'><span>${escape(pl.name)}</span></div>`;
function playlistEditPage(){const p=playlistEditorId?persist.playlists.find(x=>x.id===playlistEditorId):null;const cover=playlistArtDraft||(p?.coverImage||'');const preset=playlistPresetDraft;return `<div class="screen-header"><button class="icon-only" data-nav="library">${glyph('back')}</button><strong>${p?'Edit':'Create'} Playlist</strong></div><h1 class="title-large">${p?'Edit playlist':'New playlist'}</h1><div class="new-cover-preview" style='${cover?`background-image:url("${cover.replace(/"/g,'')}")`:`background:${auraCovers[preset]}`}'><span id="playlistCoverTitle">${escape(p?.name||'Your Mix')}</span></div><label class="settings-label">Playlist name<input id="playlistNewName" maxlength="64" placeholder="Your playlist name" value="${escape(p?.name||'')}"></label><label class="settings-label">Description (optional)<textarea id="playlistNewDesc" rows="3" maxlength="250" placeholder="What is this playlist about?">${escape(p?.description||'')}</textarea></label><p class="cover-label">AURA covers</p><div class="cover-picker">${auraCovers.map((g,i)=>`<button class="cover-choice ${i===preset&&!cover?'chosen':''}" style="background:${g}" data-cover-preset="${i}" aria-label="AURA background ${i+1}">${i===preset&&!cover?'✓':''}</button>`).join('')}</div><button class="settings-row" data-cover-upload="1">Upload custom cover <span>＋</span></button><input id="playlistCoverFile" type="file" accept="image/*" hidden><button class="settings-primary" data-save-playlist="1">${p?'Save changes':'Create playlist'}</button></div>`}
function openPlaylistEditor(id=null){playlistEditorId=id;const p=persist.playlists.find(x=>x.id===id);playlistPresetDraft=p?.coverPreset||0;playlistArtDraft=p?.coverImage||'';navigate('playlistEditor')}
async function readCover(file){if(!file)return;const img=new Image();const url=URL.createObjectURL(file);await new Promise((ok,fail)=>{img.onload=ok;img.onerror=fail;img.src=url});const cv=document.createElement('canvas');cv.width=400;cv.height=400;const ctx=cv.getContext('2d');const n=Math.min(img.width,img.height),x=(img.width-n)/2,y=(img.height-n)/2;ctx.drawImage(img,x,y,n,n,0,0,400,400);URL.revokeObjectURL(url);playlistArtDraft=cv.toDataURL('image/jpeg',.75);const v=$('.new-cover-preview');if(v)v.style.backgroundImage='url('+playlistArtDraft+')';$$('[data-cover-preset]').forEach(x=>{x.classList.remove('chosen');x.textContent=''})}
let discoveryIndex=0,chartLastRefresh=0,downloadBatchBusy=false,swipeAnimating=false,discoveryPreviewId='',playlistPickTrack=null;
const discoveryStorageKey='aura_discovery_decisions_v1_'+String(persist.account||'default');
const discoveryDecisions=(()=>{try{return JSON.parse(localStorage.getItem(discoveryStorageKey)||'{}')}catch(e){return {}}})();
function recordDiscoveryDecision(track,decision){
 if(!track)return;
 const id=key(track);discoveryDecisions[id]={decision,at:Date.now()};
 try{localStorage.setItem(discoveryStorageKey,JSON.stringify(discoveryDecisions))}catch(error){}
}
function discoveryTracks(){return unique([...home.trending,...home.top,...home.new]).filter(Boolean).filter(x=>!persist.favorites.includes(key(x))&&!discoveryDecisions[key(x)])}
function swipePage(){const tracks=discoveryTracks(),t=tracks.length?tracks[discoveryIndex%tracks.length]:null;return `<div class="screen-header"><button class="icon-only" data-nav="discover">${glyph('back')}</button><strong>Discover Music</strong></div><h1 class="title-large">Your next favorite</h1><p class="settings-help">Tap Play to listen, then throw the card left to pass or right to like.</p>${t?`<div class="swipe-card swipe-enter" id="swipeCard">${img(t)}<h2>${escape(t.title)}</h2><p>${escape(t.artist)}</p><button class="swipe-preview-play aura-orange-button" data-preview-play="1" aria-label="Play this song">${current&&key(current)===key(t)&&playing?"Ⅱ Pause":"▶ Play"}</button><div class="swipe-actions"><button data-swipe="pass" aria-label="Pass">✕ Pass</button><button class="aura-orange-button swipe-like" data-swipe="like" aria-label="Like">♥ Like</button></div></div>`:empty('No music charts available yet. Pull down to refresh Discover.')}`}
async function previewDiscoveryTrack(force=false){
 if(page!=='swipe'||swipeAnimating)return;
 const tracks=discoveryTracks(),track=tracks.length?tracks[discoveryIndex%tracks.length]:null;
 if(!track){discoveryPreviewId='';audio.pause();if(nativeActive)window.AuraNative?.stopAudio?.();return}
 if(!force&&discoveryPreviewId===key(track)&&current&&key(current)===key(track))return;
 discoveryPreviewId=key(track);
 setQueue([track],'discovery-preview');
 await play(track,true);
}
function performSwipe(kind){
 if(swipeAnimating)return;
 const all=discoveryTracks(),track=all.length?all[discoveryIndex%all.length]:null;if(!track)return;
 const nextTrack=all.length>1?all[(discoveryIndex+1)%all.length]:null;
 swipeAnimating=true;
 audio.pause();if(nativeActive)window.AuraNative?.stopAudio?.();pendingStart=null;cancelNativeWatchdog();setActualPlaying(false);
 const card=$('#swipeCard');if(card)card.classList.add(kind==='like'?'swipe-out-right':'swipe-out-left');
 setTimeout(()=>{
  recordDiscoveryDecision(track,kind==='like'?'liked':'passed');
  if(kind==='like'){persist.favorites=uniqueStrings([...(persist.favorites||[]),key(track)]);saveProfile();note('Saved to liked songs')}
  const updated=discoveryTracks();
  if(nextTrack&&updated.some(s=>key(s)===key(nextTrack)))discoveryIndex=updated.findIndex(s=>key(s)===key(nextTrack));
  else discoveryIndex=updated.length?((discoveryIndex+1)%updated.length):0;
  discoveryPreviewId='';swipeAnimating=false;
  if(page==='swipe')render()
 },260);
}
const uniqueStrings=a=>[...new Set(a)];
let selectedPlaylist='';
function folderEntry(label,detail,nav,icon){return `<button class="library-folder" data-nav="${nav}"><span class="library-folder-icon">${icon}</span><span class="song-info"><strong>${label}</strong><small>${detail}</small></span><span class="folder-chevron">›</span></button>`}
function library(){
 const mine=persist.playlists||[],favorites=(persist.favorites||[]).map(lookup).filter(Boolean),downloads=persist.downloaded||[];
 const favAlbums=persist.favoriteAlbums||[];
 const favStrip=favorites.length?`<div class="favorites-strip">${favorites.slice(0,5).map(t=>`<button data-play="${escape(key(t))}" aria-label="Play ${escape(t.title)}">${img(t)}<small>${escape(t.title)}</small></button>`).join('')}</div>`:'';
 const favAlbumsSection=favAlbums.length?`${section('Favorite Albums')}<div class="release-rail">${favAlbums.map(x=>`<button class="release" data-album-id="${escape(x.id)}">${img(x)}<strong>${escape(x.title)}</strong><small>${escape(x.artist)}</small></button>`).join('')}</div>`:'';
 return `<div class="screen-header"><button class="icon-only" data-nav="discover">${glyph('back')}</button><strong>Library</strong><button class="icon-only" data-nav="settings">${glyph('user')}</button></div><h1 class="title-large">Library</h1><div class="library-actions"><button data-add-playlist="1">＋ Create playlist</button><button data-import-local="1">↓ Import music</button></div>
 ${folderEntry('Favorites',favorites.length+' liked songs','favorites',glyph('heart'))}${favStrip}
 ${favAlbumsSection}
 ${folderEntry('Downloads',downloads.length+' downloaded songs','downloads',downloadIcon())}
 ${section('Playlists')}${mine.length?mine.map(l=>`<button class="song-row" data-list="${escape(l.id)}">${playlistCover(l)}<span class="song-info"><strong>${escape(l.name)}</strong><small>${l.tracks.length} Songs</small></span><span>›</span></button>`).join(''):empty('No playlists yet')}
 ${section('Home Servers')}${serverBusy?empty('Loading server libraries…'):serverTracks.length?`<div class="server-rows">${serverTracks.map(row).join('')}</div>`:empty(persist.servers?.length?(serverMessage||'No server music loaded'):'Add a Plex, Emby or Jellyfin server in Settings')}
 ${section('Local Music')}${localSongs.length?localSongs.map(row).join(''):empty('No local songs imported')}`;
}
function libraryFolder(){
 const isFavorite=page==='favorites';const tracks=isFavorite?(persist.favorites||[]).map(lookup).filter(Boolean):(persist.downloaded||[]);
 return `<div class="screen-header"><button class="icon-only" data-nav="library">${glyph('back')}</button><strong>${isFavorite?'Favorites':'Downloads'}</strong><span>${tracks.length} songs</span></div><h1 class="title-large">${isFavorite?'Favorites':'Downloads'}</h1>${tracks.length?tracks.map(row).join(''):empty(isFavorite?'Like songs to find them here.':'Download songs to find them here.')}`;
}

function fmt(t){return `${Math.floor((t||0)/60)}:${String(Math.floor((t||0)%60)).padStart(2,'0')}`}
function wave(){const progressTime=nativeActive?nativePosition:audio.currentTime;const progressDuration=nativeActive?nativeDuration:audio.duration;const fraction=progressDuration>0?progressTime/progressDuration:0;return Array.from({length:52},(_,i)=>{const h=9+Math.round(33*Math.abs(Math.sin(i*1.37)*Math.cos(i*.32))+7*Math.abs(Math.sin(i*.97)));return `<span style="height:${h}px" class="${(i+.5)/52<=fraction?'elapsed':''}"></span>`}).join('')}
function player(){
 const showLyrics=playerView==='lyrics'&&!!current;
 const linkedVideo=associatedVideo(current);
 const showVideo=playerView==='video'&&!!linkedVideo;
 const controls=`<div class="wave-timeline"><span class="stamp" id="elapsed">${fmt(nativeActive?nativePosition:audio.currentTime)}</span><div class="wave-seek"><div class="wave-lines" id="waveBars">${wave()}</div><input id="seek" aria-label="Seek" type="range" min="0" max="${(nativeActive?nativeDuration:audio.duration)||current?.duration||1}" value="${(nativeActive?nativePosition:audio.currentTime)||0}"></div><span class="stamp" id="total">${fmt((nativeActive?nativeDuration:audio.duration)||current?.duration)}</span></div><div class="player-side-actions"><button class="player-side-button auraplay-side-button" data-auraplay="1" aria-label="AuraPlay · Cast til enhet" title="AuraPlay"><span aria-hidden="true">✦</span><small>AuraPlay</small></button><button class="player-side-button ${queueOpen?'active':''}" data-queue="1" aria-pressed="${queueOpen}" aria-label="Spillekø" title="Spillekø">${glyph('queue')}<small>Kø</small></button></div>`;
 const cover=`<div class="player-art">${current?img(current,'cover-large'):`<div class="cover-large missing-cover">♫</div>`}</div><div class="player-song"><h1>${escape(current?.title||'Nothing playing')}</h1><button class="artist-under-title" data-artist="${escape(current?.artist||'')}">${escape(current?.artist||'Choose a song to begin')}</button></div>`;
 const tabs=`<div class="player-mode-tabs" role="group" aria-label="Song visuals"><button data-player-view="lyrics" aria-label="Lyrics and karaoke" aria-pressed="${showLyrics}" class="${showLyrics?'active':''}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="3" width="16" height="18" rx="3"/><path d="M8 8h8M8 12h8M8 16h5"/></svg></button>${linkedVideo?`<button data-player-view="video" aria-label="Music video" aria-pressed="${showVideo}" class="${showVideo?'active':''}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M17 9h4M3 15h4M17 15h4"/><path d="m10 9 5 3-5 3z"/></svg></button>`:''}</div>`;
 const karaoke=`<div class="karaoke-lines" id="karaokeLines" aria-live="off">${lyricsMarkup()}</div>`;
 const video=`<div class="player-video"><div class="aura-video-player">${embeddedVideoMarkup(current)}</div><div class="aura-video-label"><strong>${escape(current?.title||'Music video')}</strong><small>Video linked to this song</small></div></div>`;
 return `<div class="screen-header center-head"><button class="icon-only" data-collapse-player="1" aria-label="Minimer spilleren">${glyph('back')}</button><strong>Now Playing</strong><button class="icon-only" ${current?`data-more="${escape(key(current))}"`:''} aria-label="Sangmeny">${glyph('dots')}</button></div>${tabs}${showLyrics?karaoke:showVideo?video:cover}${controls}${queueOpen?`<div class="player-queue" role="region" aria-label="Current play queue"><strong>Up next · ${playQueue.length} songs</strong>${playQueue.map((track,i)=>`<button data-queue-play="${escape(key(track))}" class="${current&&key(current)===key(track)?'active':''}">${i+1}. ${escape(track.title)} <small>${escape(track.artist)}</small></button>`).join('')}</div>`:''}`;
}
function transportIcon(t){const paths={play:'<path d="M9 5.5 19 12 9 18.5z" fill="currentColor" stroke="none"/>',pause:'<rect x="7" y="5" width="3.4" height="14" rx="1" fill="currentColor" stroke="none"/><rect x="13.6" y="5" width="3.4" height="14" rx="1" fill="currentColor" stroke="none"/>',previous:'<path d="M6 6v12M18 6 8 12l10 6z" fill="currentColor" stroke="none"/>',next:'<path d="M18 6v12M6 6l10 6-10 6z" fill="currentColor" stroke="none"/>'};return `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[t]||''}</svg>`}
function parseLrc(input){return String(input||'').split(/\r?\n/).flatMap(line=>{const re=/\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g;let m,items=[],text=line.replace(re,'').trim();while((m=re.exec(line))){const frac=m[3]?Number(('0.'+m[3])):0;items.push({time:Number(m[1])*60+Number(m[2])+frac,text})}return items}).filter(x=>x.text).sort((a,b)=>a.time-b.time)}
// Only present video when explicitly associated with the currently playing track.
 // Do not infer an official video from title/artist search results.
function associatedVideo(track){
 if(!track)return null;
 if(track.isVideo||track.origin==='auramusik'||track.origin==='musike'){
  const vidId=track.videoId||track.providerId||track.id;
  if(vidId)return {kind:'youtube',id:vidId};
 }
 const source=track.musicVideo||track.music_video||musicVideoResolver?.peek(track)||{};
 const url=String(track.videoUrl||source.url||'');
 if(/^https:\/\//i.test(url)&&!/[?#].*javascript:/i.test(url)){
   try{const parsed=new URL(url);
    if(['youtube.com','www.youtube.com','youtu.be','m.youtube.com'].includes(parsed.hostname)){
      const id=parsed.hostname==='youtu.be'?parsed.pathname.slice(1):parsed.searchParams.get('v')||parsed.pathname.match(/^\/embed\/([a-zA-Z0-9_-]{11})$/)?.[1];
      if(id&&/^[a-zA-Z0-9_-]{11}$/.test(id))return {kind:'youtube',id};
      return null;
    }
    if(/\.(mp4|webm)(?:\?|$)/i.test(url)||track.videoProvider==='direct'||source.provider==='direct')return {kind:'file',url};
   }catch(e){return null}
 }
 if(source.kind==='file'&&String(source.url||'').startsWith('https://'))return {kind:'file',url:source.url};
 const provider=String(track.videoProvider||source.provider||source.kind||'').toLowerCase();
 const id=String(track.videoId||source.id||'');
 if((provider==='youtube'||provider==='auramusik'||provider==='musike')&&id)return {kind:'youtube',id};
 return null;
}
async function musicVideoJsonRequest(url){
 if(url.startsWith('https://musicbrainz.org/ws/2/')){
   // MusicBrainz supports browser CORS; native source bridge has a restricted host list.
   const response=await fetch(url,{headers:{Accept:'application/json'},cache:'no-store'});
   if(!response.ok)throw Error('Video metadata HTTP '+response.status);
   return response.json();
 }
 return jsonRequest(url);
}
const musicVideoResolver=window.AuraVideoLinks?.create({request:musicVideoJsonRequest,storage:localStorage});
async function findMusicVideo(track){
 if(!track||!musicVideoResolver)return null;
 const sourceId=key(track);
 if(associatedVideo(track))return associatedVideo(track);
 // Deezer's full track metadata includes an ISRC even when its chart/search
 // response omits it. Verify the ID, title and artist before enriching.
 if(track.origin==='deezer'&&!track.isrc&&/^\d+$/.test(String(track.providerId||track.id||''))){
   try{
     const full=await deezer('/track/'+encodeURIComponent(track.providerId||track.id));
     if(String(full?.id)===String(track.providerId||track.id)&&
       String(full.title||'').trim().toLowerCase()===String(track.title||'').trim().toLowerCase()&&
       String(full.artist?.name||'').trim().toLowerCase()===String(track.artist||'').trim().toLowerCase()&&
       /^[A-Z]{2}[A-Z0-9]{3}\d{7}$/.test(String(full.isrc||'').toUpperCase())){
        track.isrc=String(full.isrc).toUpperCase();
        if(catalog.has(sourceId))catalog.get(sourceId).isrc=track.isrc;
        remember([track]);
     }
   }catch(err){console.info('Track ISRC enrichment unavailable',err.message)}
 }
 const linked=await musicVideoResolver.resolve(track,{
   providerBase:track.origin==='ultramax'?providerBase:'',
   providerManifest:track.origin==='ultramax'?providerManifest:null,
   useMusicBrainz:true
 });
 if(!linked)return null;
 const original=catalog.get(sourceId);
 if(original)original.musicVideo=linked;
 if(current&&key(current)===sourceId){
   current.musicVideo=linked;
   remember([current]);
   if(page==='player')render();
 }
 return linked;
}
function pauseAudioForVideo(){
 if(nativeActive)window.AuraNative?.toggleAudio&&playing&&AuraNative.toggleAudio();
 else if(!audio.paused)audio.pause();
}
const auraMusikVideoResolved=new Map(); // key(track) -> resolved muxed https URL, so re-renders don't re-resolve or loop
function embeddedVideoMarkup(track){
 if(!track)return '';
 const streamUrl=track.videoUrl||track.stream||'';
 const isAura=streamUrl.startsWith('auramusik://')||track.origin==='auramusik'||track.origin==='musike';
 if(isAura){
  const resolved=auraMusikVideoResolved.get(key(track));
  if(resolved)return `<video class="aura-video-embed" controls autoplay playsinline src="${escape(resolved)}" aria-label="Music video"></video>`;
  const videoId=track.videoId||track.id||decodeURIComponent(streamUrl.replace('auramusik://',''));
  auraMusikStream(videoId,'video').then(directUrl=>{
   if(!directUrl)return;
   auraMusikVideoResolved.set(key(track),directUrl);
   if(current&&key(current)===key(track)){
    const vEl=$('.aura-video-embed');
    if(vEl&&vEl.tagName==='VIDEO'){vEl.src=directUrl;vEl.play().catch(()=>{})}
    else if(page==='player'&&playerView==='video')render();
   }
  }).catch(()=>{});
  return `<div class="switch-banner">Laster inn videostrøm fra AuraMusicEngine…</div>`;
 }
 return `<video class="aura-video-embed" controls autoplay playsinline src="${escape(streamUrl)}" aria-label="Music video"></video>`;
}

function lyricsMarkup(){if(!current)return empty('Choose a song');if(lyricState.track!==key(current)||lyricState.loading)return `<p class="karaoke-message">Loading lyrics…</p>`;if(lyricState.error)return `<p class="karaoke-message">${escape(lyricState.error)}</p>`;if(!lyricState.lines.length)return `<p class="karaoke-message">Lyrics unavailable for this song</p>`;return lyricState.lines.map((l,i)=>`<div class="lyric-line" data-line="${i}">${escape(l.text)}</div>`).join('')}
function updateKaraoke(){if(!lyricsMode||page!=='player'||!current||lyricState.track!==key(current)||lyricState.loading||!lyricState.lines.length)return;const pos=nativeActive?nativePosition:audio.currentTime;let idx=0;if(!lyricState.plain){for(let j=0;j<lyricState.lines.length;j++){if(lyricState.lines[j].time<=pos)idx=j;else break}}const nodes=$$('.lyric-line');nodes.forEach((n,i)=>n.classList.toggle('active',i===idx));const selected=nodes[idx];if(selected&&selected.dataset.visible!=='true'){nodes.forEach(n=>n.dataset.visible='false');selected.dataset.visible='true';selected.scrollIntoView({block:'center',behavior:'smooth'})}}
async function loadLyrics(s){if(!s)return;const k=key(s);if(lyricState.track===k&&(lyricState.lines.length||lyricState.loading))return;lyricState={track:k,loading:true,error:'',lines:[],plain:false};if(lyricsMode&&page==='player')render();try{const params=new URLSearchParams({track_name:s.title,artist_name:s.artist});if(s.album)params.set('album_name',s.album);const data=await jsonRequest('https://lrclib.net/api/get?'+params.toString());if(key(current)!==k)return;const synced=parseLrc(data.syncedLyrics||'');const lines=synced.length?synced:String(data.plainLyrics||'').split(/\r?\n/).filter(Boolean).map((text,i)=>({time:i*4,text}));lyricState={track:k,loading:false,error:data.instrumental?'Instrumental track — no lyrics':'',lines,plain:!synced.length};}catch(e){if(key(current)!==k)return;lyricState={track:k,loading:false,error:'Lyrics unavailable from LRCLIB',lines:[],plain:false}}if(lyricsMode&&page==='player'){render();updateKaraoke()}}
function albumPage(){if(!albumDetail)return empty('No album selected');const a=albumDetail;const favAlbums=persist.favoriteAlbums||[];const isFavAlbum=favAlbums.some(x=>String(x.id||x.albumId||x)===String(a.id));return `<div class="screen-header"><button class="icon-only" data-album-back="1">${glyph('back')}</button><strong>Album</strong><button class="icon-only" ${a.tracks?.length?`data-more="${escape(key(a.tracks[0]))}"`:''}>${glyph('dots')}</button></div><div class="album-art">${img(a,'playlist-art')}</div><h1 class="playlist-title">${escape(a.title)}</h1><button class="artist-under-album" data-artist="${escape(a.artist)}">${escape(a.artist)}</button><p class="playlist-byline">${escape(a.date||'')} · ${(a.tracks||[]).length} Songs</p><div class="playlist-buttons"><button class="primary icon-collection-action" data-album-play="1" aria-label="Play album" title="Play album">${transportIcon('play')}</button><button class="icon-collection-action" data-shuffle-album="1" aria-label="Shuffle album" title="Shuffle album">${glyph('shuffle')}</button><button class="icon-collection-action ${isFavAlbum?'favorite-active':''}" data-album-favorite="1" aria-label="Favorite album" title="Favorite album">${glyph('heart')}</button><button class="icon-collection-action album-add-to-playlist" data-album-add-playlist="1" aria-label="Create playlist from album" title="Create playlist from album">＋</button><button class="icon-collection-action collection-download ${a.tracks?.length&&a.tracks.every(t=>downloadedTrack(t)||t.origin==='local')?'downloaded':''}" data-download-album="1" aria-label="Download album" title="Download album">${downloadIcon()}</button></div>${(a.tracks||[]).map(row).join('')||empty(a.error||'No tracks returned by catalog.')}`}
function artistPage(){if(artistLoading)return `<div class="screen-header"><button class="icon-only" data-artist-back="1">${glyph('back')}</button><strong>Artist</strong></div>${empty('Loading artist…')}`;if(!artistDetail)return empty(artistError||'Artist not available');const a=artistDetail;const top=a.top||[],albums=a.albums||[],latest=[...albums].sort((x,y)=>String(y.date||'').localeCompare(String(x.date||'')));return `<div class="screen-header"><button class="icon-only" data-artist-back="1">${glyph('back')}</button><strong>Artist</strong><button class="icon-only" data-artist-actions="1">${glyph('dots')}</button></div><div class="artist-hero">${a.cover?`<img src="${escape(a.cover)}" alt="">`:''}<div class="artist-hero-caption"><h1>${escape(a.name)}</h1>${a.fans?`<small>${Number(a.fans).toLocaleString()} followers</small>`:''}</div></div><div class="artist-buttons"><button class="primary" data-artist-play="1">▶ Play</button><button data-artist-follow="1">${persist.followedArtists?.includes(a.name)?'Following':'Follow'}</button></div>${section('Popular Songs')}${top.length?top.slice(0,10).map(row).join(''):empty('No popular tracks returned')}${section('Albums')}${albums.length?`<div class="release-rail">${albums.slice(0,20).map(x=>`<button class="release" data-album-id="${escape(x.id)}">${img(x)}<strong>${escape(x.title)}</strong><small>${escape(x.date?.slice(0,4)||'Album')}</small></button>`).join('')}</div>`:empty('No albums found')}${section('Latest Songs')}${(a.latestSongs||[]).length?a.latestSongs.slice(0,8).map(row).join(''):empty('No recent tracks returned')}${section('Latest Releases')}${latest.length?latest.slice(0,5).map(x=>`<button class="catalog-result" data-album-id="${escape(x.id)}">${img(x)}<span><strong>${escape(x.title)}</strong><small>${escape(x.date||'Release')}</small></span><span class="catalog-chevron">›</span></button>`).join(''):empty('No release dates available')}${section('About Artist')}<div class="artist-bio">${escape(a.about||'Artist biography is not available from this catalog.')}</div>`}
async function openArtist(name,seed){if(!name)return;artistSourcePage=page;page='artist';artistLoading=true;artistError='';render();try{let artistId=seed?.artistId||'';let found=null;if(artistId){found=await deezer('/artist/'+encodeURIComponent(artistId));}else{const search=await deezer('/search/artist?q='+encodeURIComponent(name)+'&limit=10');found=dataItems(search).find(a=>a.name?.toLowerCase()===name.toLowerCase())||dataItems(search)[0];if(!found)throw Error('Artist not found');artistId=found.id;}const [tops,albums]=await Promise.allSettled([deezer('/artist/'+encodeURIComponent(artistId)+'/top?limit=40'),deezer('/artist/'+encodeURIComponent(artistId)+'/albums?limit=40')]);const ts=tops.status==='fulfilled'?remember(dataItems(tops.value).map(x=>normalize(x))):[];const al=albums.status==='fulfilled'?dataItems(albums.value).map(x=>({id:String(x.id),title:x.title,artist:found.name,cover:image(x),date:x.release_date||'',albumId:x.id})):[];let latestSongs=[];
 const newest=[...al].filter(a=>a.date).sort((a,b)=>b.date.localeCompare(a.date)).slice(0,2);
 for(const album of newest){try{const tr=await deezer('/album/'+encodeURIComponent(album.id)+'/tracks?limit=20');latestSongs.push(...dataItems(tr).map(x=>normalize({...x,release_date:album.date,album:{id:album.id,title:album.title,cover_big:album.cover}})))}catch(e){console.info('Recent songs unavailable for',album.id)}}
 latestSongs=remember(unique(latestSongs).sort((a,b)=>String(b.date||'').localeCompare(String(a.date||''))));
 artistDetail={name:found.name||name,id:artistId,cover:found.picture_xl||found.picture_big||seed?.artistImage||seed?.cover||'',fans:found.nb_fan||0,top:ts,albums:al,latestSongs,about:found.description||''};}catch(e){artistDetail=null;artistError='Unable to load artist: '+e.message}finally{artistLoading=false;if(page==='artist')render()}}
async function openAlbum(id,seed){if(!id&&!seed)return;albumSourcePage=page;page='album';albumDetail={title:seed?.album||seed?.title||'Album',artist:seed?.artist||'',cover:seed?.cover||'',tracks:[],error:'Loading album…'};render();try{let albumId=id||seed?.albumId;let a;if(seed?.origin==='apple'||!albumId||seed?.origin==='ultramax'){const q=encodeURIComponent(`album:"${seed?.album||seed?.title||''}" artist:"${seed?.artist||''}"`);const data=await deezer('/search/album?q='+q+'&limit=12');const match=dataItems(data).find(x=>x.title?.toLowerCase()===(seed?.album||seed?.title||'').toLowerCase())||dataItems(data)[0];if(!match)throw Error('Album not found in Deezer catalog');albumId=match.id;}a=await deezer('/album/'+encodeURIComponent(albumId));let raw=dataItems(a.tracks);if(!raw.length){const tracks=await deezer('/album/'+encodeURIComponent(albumId)+'/tracks?limit=100');raw=dataItems(tracks)}const tracks=remember(raw.map(t=>normalize({...t,album:{id:albumId,title:a.title,cover_xl:image(a)}})));albumDetail={id:String(albumId),title:a.title||seed?.album||seed?.title||'Album',artist:a.artist?.name||seed?.artist||'',cover:image(a)||seed?.cover||'',date:a.release_date||'',tracks};}catch(e){albumDetail.error='Unable to load album: '+e.message}if(page==='album')render()}

// AURA Sync v1: manual import over encrypted LAN, with explicit consent for provider key.
let syncStatus='Not sharing';
let syncCode='';
function snapshot(){
 const profiles={};
 for(const name of persist.accounts){
  let p={};try{p=JSON.parse(localStorage.getItem('aura_user_'+name)||'{}')}catch(_){}
  if(name===persist.account)p={playlists:persist.playlists,favorites:persist.favorites,history:persist.history};
  profiles[name]={name,playlists:p.playlists||[],favorites:p.favorites||[],history:p.history||[]};
 }
 return JSON.stringify({protocol:2,app:'AURA AMP',created:new Date().toISOString(),profiles,
  tracks:(persist.trackCache||[]).slice(-400)});
}
let syncSelected=null,syncNearby=[],syncScanning=false,syncSharing=false;
function removeSyncShareDialog(){document.querySelector('#auraSyncShareDialog')?.remove()}
function showSyncShareDialog(){
 let shell=$('#auraSyncShareDialog');
 if(!syncSharing){removeSyncShareDialog();return}
 if(!shell){shell=document.createElement('div');shell.id='auraSyncShareDialog';shell.className='aura-sync-modal';shell.innerHTML=`<div class="aura-sync-dialog" role="dialog" aria-modal="true" aria-labelledby="auraSyncDialogTitle"><h2 id="auraSyncDialogTitle">Sync Local · Server aktiv</h2><p>Bruk denne 6-sifrede koden på den andre enheten innen 10 minutter.</p><div class="aura-sync-big-code" id="auraSyncDialogCode">000000</div><p id="auraSyncDialogStatus" role="status">Venter på enhet…</p><button class="settings-primary" id="auraSyncDialogDone">OK · Avslutt</button></div>`;
  document.body.appendChild(shell);
  shell.querySelector('#auraSyncDialogDone')?.addEventListener('click',()=>{window.AuraNative?.stopSync?.();syncSharing=false;syncCode='';syncStatus='Server stoppet';removeSyncShareDialog();const text=$('#syncStatus');if(text)text.textContent=syncStatus});
 }
 const code=$('#auraSyncDialogCode');if(code)code.textContent=syncCode||'••••••';
 const status=$('#auraSyncDialogStatus');if(status)status.textContent=syncStatus;
}

window.AuraSyncNearby=(raw)=>{
 try{syncNearby=JSON.parse(raw);if(!Array.isArray(syncNearby))syncNearby=[];
 const indicator=$('#syncIndicator');if(indicator)indicator.textContent=syncScanning?'⟳ Scanning for nearby devices…':syncNearby.length+' device(s) found';
 const area=$('#syncNearby');if(area)area.innerHTML=syncNearby.length?syncNearby.map((x,i)=>
  `<button class="settings-row" data-sync-select="${i}">${escape(x.name)} <span>›</span></button>`).join(''):(syncScanning?'Searching on the local network…':'No AURA AMP v2 devices found. Start Sync Local on the sender, then scan again.');
 }catch(_){}
};
window.AuraSyncStatus=(json)=>{
 try{const data=JSON.parse(json);
  if(data.event==='hosting'){syncCode=String(data.code||'');syncSharing=true;syncStatus='Server aktiv · venter på mottaker (10 minutter)';showSyncShareDialog();}
  else if(data.event==='error'){syncStatus='Synkronisering feilet: '+data.message;note(syncStatus);syncSharing=false;removeSyncShareDialog()}
  else if(data.event==='sending'){syncStatus='Sender brukerdata…'}
  else if(data.event==='sent'){syncStatus='Brukerdata sendt · synkronisering fullført';syncSharing=false;setTimeout(removeSyncShareDialog,1500);}
  else if(data.event==='receiving'){syncStatus='Mottar brukerdata…'}
  else if(data.event==='received')syncStatus='Brukerdata mottatt · fullført';
  else if(data.event==='expired'){syncStatus='Koden har utløpt';syncSharing=false;removeSyncShareDialog();}
  else if(data.event==='stopped'){syncCode='';syncStatus='Server stoppet';syncScanning=false;syncSharing=false;removeSyncShareDialog();}
  else if(data.event==='scan-started'){syncScanning=true;syncStatus='Scanning nearby AURA AMP devices…'}
  else if(data.event==='scan-found'){syncScanning=true;syncStatus='Found '+(data.count||syncNearby.length)+' device(s)'}
  else if(data.event==='scan-complete'){syncScanning=false;syncStatus=data.message||'Scan finished'}
  const label=$('#syncStatus');if(label)label.textContent=syncStatus;
  const code=$('#syncCode');if(code)code.textContent=syncCode||'— — — — — —';showSyncShareDialog();
  const indicator=$('#syncIndicator');if(indicator)indicator.textContent=syncScanning?'⟳ Søker etter enheter…':syncNearby.length+' enhet(er) funnet';showSyncShareDialog();
 }catch(_){}
};
window.AuraSyncReceived=(json)=>{
 try{
  const data=JSON.parse(json);if(data.protocol!==2||!data.profiles)throw Error('Incompatible sync data. Update both devices to Local Sync v2.1');
  const names=Object.keys(data.profiles);
  for(const name of names){
   const p=data.profiles[name];if(!p||!name)continue;
   const src=p.playlists||p.userPlaylists||[];
   const playlists=src.map(x=>({id:x.id,name:x.name||x.title,tracks:x.tracks||x.ids||[]}));
   localStorage.setItem('aura_user_'+name,JSON.stringify({playlists,favorites:p.favorites||[],history:p.history||[]}));
  }
  persist.accounts=[...new Set([...(persist.accounts||[]),...names])];
  if(Array.isArray(data.tracks))remember(data.tracks.slice(-400));
  save();syncStatus='Imported '+names.length+' user profiles securely';note(syncStatus);navigate('settings');
 }catch(e){syncStatus='Import failed: '+e.message;note(syncStatus)}
};
function syncSection(){return `<div class="settings-group"><h2>AURA Sync · Local Network</h2>
 <div class="settings-help">Share user profiles, playlists, favorites and Deezer ARL securely on the same Wi-Fi. No IP addresses, ports or external servers.</div>
 <button class="settings-primary" data-sync-start="1">Sync Local</button>
 <div class="settings-help">One-time 6-digit code (10 minutes): <strong id="syncCode">${escape(syncCode||'— — — — — —')}</strong></div>
 <button class="settings-row" data-sync-stop="1">Stop sharing <span>×</span></button>
 <h2>Receive from another device</h2>
 <button class="settings-primary" data-sync-scan="1">Scan Local</button>
 <div class="sync-scanning-indicator" id="syncIndicator" role="status" aria-live="polite">${syncScanning?'⟳ Scanning for nearby devices…':syncNearby.length+' device(s) found'}</div>
 <div id="syncNearby" class="settings-help">${syncScanning?'Searching on the local network…':'Select a nearby AURA AMP device'}</div>
 <div class="settings-help" id="syncChosen">${escape(syncSelected?.name||'No device selected')}</div>
 <label class="settings-label">6-digit code<input id="syncPair" type="text" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" placeholder="000000"></label>
 <button class="settings-primary" data-sync-receive="1">Accept &amp; Import</button>
  <div id="syncStatus" class="settings-help">${escape(syncStatus)}</div>
 <div class="settings-help">Imported profiles remain separate. Local audio files are never copied.</div>
 </div>`;}
let auraConnectReceivers=[],auraConnectStatus='';
window.AuraConnectPaired=function(json){
 try{
  const res=JSON.parse(json);
  if(res.ok){
   auraConnectStatus='Paringssuksess med '+(res.name||'TV')+'!';
   note(auraConnectStatus);
   window.AuraNative?.auraConnectReceivers?.();
  }else{
   auraConnectStatus='Paring feilet: '+(res.error||'Ukjent feil');
   note(auraConnectStatus);
  }
 }catch(e){auraConnectStatus='Svarfeil: '+e.message;}
 const el=$('#auraConnectStatus');if(el)el.textContent=auraConnectStatus;
};
window.AuraConnectReceivers=function(json){
 try{
  const list=JSON.parse(json);
  auraConnectReceivers=Array.isArray(list)?list:[];
  const listEl=$('#auraConnectReceiversList');
  if(listEl){
   listEl.innerHTML=auraConnectReceivers.length?auraConnectReceivers.map(r=>
    `<div class="settings-row" style="flex-direction:column;align-items:flex-start;gap:8px;padding:12px 0;border-bottom:1px solid var(--outline)">
      <div><strong>${escape(r.name||'TV')}</strong> <small>(${escape(r.host||r.deviceId)})</small></div>
      <div style="display:flex;gap:8px;flex-wrap:wrap;width:100%">
        <button class="settings-primary" style="flex:1;min-width:130px;margin:0;padding:8px 10px;font-size:12px;background:linear-gradient(135deg,#8e44ad,#9b59b6)" data-auraconnect-send-profile="${escape(r.deviceId)}">👤 Send profil</button>
        <button class="settings-primary" style="flex:1;min-width:130px;margin:0;padding:8px 10px;font-size:12px" data-auraconnect-send-arl="${escape(r.deviceId)}">🔑 Send Deezer ARL</button>
        <button class="settings-primary" style="flex:1;min-width:130px;margin:0;padding:8px 10px;font-size:12px;background:var(--accent,#FF9F43)" data-auraconnect-send-playlists="${escape(r.deviceId)}">🎵 Send spillelister</button>
        <button class="settings-primary" style="flex:1;min-width:130px;margin:0;padding:8px 10px;font-size:12px;background:linear-gradient(135deg,#2980b9,#3498db)" data-auraconnect-send-mediaserver="${escape(r.deviceId)}">🖥️ Send mediaserver</button>
        <button class="settings-row" style="width:100%;margin:4px 0 0;padding:6px 12px;border-radius:10px;text-align:center" data-auraconnect-forget="${escape(r.deviceId)}">Glem enhet ×</button>
      </div>
    </div>`
   ).join(''):'<div class="settings-help">Ingen parede TV-er ennå. Skann QR-koden på TV-skjermen for å pare.</div>';
  }
 }catch(_){}
};
window.AuraConnectSendPlaylistsResult=function(json){
 try{
  const res=JSON.parse(json);
  if(res.ok){
   auraConnectStatus='Spillelister sendt til TV ('+(res.count||0)+' importert'+(res.skipped?.length?`, ${res.skipped.length} hoppet over`:'' )+')';
   note(auraConnectStatus);
   const pending=pendingAuraPlaylistAction;pendingAuraPlaylistAction=null;
   if(pending?.kind==='play')sendAuraTrackHandoff(pending.deviceId,pending.track,pending.upcoming,pending.message);
   else if(pending?.kind==='next')sendAuraQueueBatch(pending.deviceId,pending.tracks,'next',pending.message);
   else if(pending?.kind==='queue')sendAuraQueueBatch(pending.deviceId,pending.tracks,'end',pending.message);
  }else{
   auraConnectStatus='Sending feilet: '+(res.error||'Ukjent feil');
   note(auraConnectStatus);
   pendingAuraPlaylistAction=null;
  }
 }catch(e){auraConnectStatus='Svarfeil: '+e.message;}
 const el=$('#auraConnectStatus');if(el)el.textContent=auraConnectStatus;
};
window.AuraConnectSendResult=function(json){
 try{
  const res=JSON.parse(json);
  if(res.ok){
   auraConnectStatus=res.renew?'ARL sendt, men TV ber om fornying.':'Deezer ARL sendt til TV!';
   note(auraConnectStatus);
  }else{
   auraConnectStatus='Sending feilet: '+(res.error||'Ukjent feil');
   note(auraConnectStatus);
  }
 }catch(e){auraConnectStatus='Svarfeil: '+e.message;}
 const el=$('#auraConnectStatus');if(el)el.textContent=auraConnectStatus;
};
window.AuraConnectControlResult=function(json){
 try{
  const res=typeof json==='string'?JSON.parse(json):json;
  const pending=pendingAuraControlAction;
  if(pending&&res.op===pending.op){
   pendingAuraControlAction=null;
   if(res.ok){if(pending.message)note(pending.message);pending.onSuccess?.(res)}
   else{const failure=res.error||'Kunne ikke utføre kommandoen på TV-en';note(failure);pending.onFailure?.(res)}
  }
  if(res.ok){
   const previousTvError=tvRemoteState.error;
   tvRemoteState.playing=!!res.playing;
   tvRemoteState.loading=!!res.loading;
   tvRemoteState.shuffle=!!res.shuffle;
   tvRemoteState.repeat=!!res.repeat;
   tvRemoteState.positionMs=Number(res.positionMs||0);
   tvRemoteState.durationMs=Number(res.durationMs||0);
   tvRemoteState.trackId=res.trackId||'';
   if(res.title)tvRemoteState.title=res.title;
   if(res.artist)tvRemoteState.artist=res.artist;
   if(res.queueLength!==undefined)tvRemoteState.queueLength=Number(res.queueLength||0);
   if(res.error)tvRemoteState.error=String(res.error);else if(res.op==='playback.status')tvRemoteState.error='';
   if(tvRemoteState.error&&tvRemoteState.error!==previousTvError)note('AuraOS: '+tvRemoteState.error);
   if(tvRemoteActive)renderTvRemote();
  }else{
   auraConnectStatus='TV Control: '+(res.error||'Kunne ikke kontakte TV');
   const el=$('#auraConnectStatus');if(el)el.textContent=auraConnectStatus;
  }
 }catch(e){console.warn('AuraConnectControlResult error:',e.message)}
};
function auraConnectSection(){
 setTimeout(()=>window.AuraNative?.auraConnectReceivers?.(),150);
 return `<div class="settings-group"><h2>AuraConnect · TV-kobling & Profile Sync</h2>
 <div class="settings-help">Koble til TV for å pare, overføre din brukerprofil med ARL, mediaserver, spillelister og favoritter over Wi-Fi.</div>
 <button class="settings-primary" style="padding:9px 14px;font-size:13px" data-auraconnect-scan="1">📷 Skann QR-kode på TV</button>
 <div class="settings-help" style="margin-top:10px">Eller tast inn paringskode fra TV:</div>
 <label class="settings-label">Paringskode / URI<input id="auraConnectInput" type="text" placeholder="F.eks. 123456 eller auraconnect://pair?..."></label>
 <button class="settings-primary" style="padding:9px 14px;font-size:13px" data-auraconnect-pair="1">Par med TV</button>
 <div id="auraConnectStatus" class="settings-help" style="font-weight:bold;color:var(--accent,#FF9F43);margin:8px 0">${escape(auraConnectStatus)}</div>
 <h3 style="font-size:1.05rem;margin:14px 0 8px">Hurtighandlinger for tilkoblet TV</h3>
 <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px">
  <button class="settings-primary" style="flex:1;min-width:130px;margin:0;padding:8px 10px;font-size:12px;background:linear-gradient(135deg,#8e44ad,#9b59b6)" data-auraconnect-send-profile-active="1">👤 Send brukerprofil</button>
  <button class="settings-primary" style="flex:1;min-width:130px;margin:0;padding:8px 10px;font-size:12px" data-auraconnect-send-arl-active="1">🔑 Send Deezer ARL</button>
  <button class="settings-primary" style="flex:1;min-width:130px;margin:0;padding:8px 10px;font-size:12px;background:var(--accent,#FF9F43)" data-auraconnect-send-playlists-active="1">🎵 Send spillelister</button>
  <button class="settings-primary" style="flex:1;min-width:130px;margin:0;padding:8px 10px;font-size:12px;background:linear-gradient(135deg,#2980b9,#3498db)" data-auraconnect-send-mediaserver-active="1">🖥️ Send mediaserver</button>
  <button class="settings-primary" style="width:100%;margin:4px 0 0;padding:8px 10px;font-size:12px;background:rgba(255,255,255,0.12)" data-open-tv-remote="1">📺 Åpne TV-fjernkontroll</button>
 </div>
 <h3 style="font-size:1.05rem;margin:14px 0 6px">Parede TV-er</h3>
 <div id="auraConnectReceiversList" class="settings-help">Laster parede enheter…</div>
 </div>`;
}
let settingsTab='general';
let spotifyPlaylists=[],spotifyStatus='';
function settings(){
 const toggle=(name,field)=>`<label class="settings-row"><span>${name}</span><label class="aura-switch"><input type="checkbox" data-setting="${field}" ${persist[field]!==false?'checked':''}><span class="aura-slider"></span></label></label>`;
 const select=(name,field,vals)=>`<label class="settings-row"><span>${name}</span><select data-setting="${field}">${vals.map(([v,l])=>`<option value="${v}" ${persist[field]===v?'selected':''}>${l}</option>`).join('')}</select></label>`;

 const tabs=[
  ['general','General'],
  ['connections','Connections'],
  ['auraconnect','AuraConnect'],
  ['status','Status'],
  ['about','About']
 ];

 const tabBar=`<div class="settings-tab-bar">
  ${tabs.map(([id,label])=>`<button class="${settingsTab===id?'active':''}" data-settings-tab="${id}">${label}</button>`).join('')}
 </div>`;

 const generalContent=`
  <div class="settings-group"><h2>Appearance</h2>${select('Theme','theme',[['dark','Dark mode'],['light','Light mode']])}</div>
  <div class="settings-group"><h2>Audio quality</h2>${select('Streaming','streamQuality',[['best','Lossless / best available'],['high','High'],['standard','Standard']])}${select('Downloads','downloadQuality',[['best','Lossless / best available'],['high','High'],['standard','Standard']])}<div class="settings-help">Preferences are requests. Actual quality depends on the Ultra MAX streams made available.</div></div>
  <div class="settings-group"><h2>Offline & storage</h2>${toggle('Wi-Fi downloads only','offlineWifiOnly')}${select('New songs in downloaded playlists','playlistAutoDownload',[['off','Off'],['wifi','Automatically on Wi-Fi'],['always','Automatically on any network']])}<button class="settings-row" data-nav="library">Local / downloaded files <span>›</span></button><div class="settings-help">Local imported files are saved on this device where storage permits. Provider downloads require an authorized, downloadable direct URL.</div></div>
  <div class="settings-group"><h2>Library</h2>${toggle('Refresh on launch','autoRefresh')}<button class="settings-row" data-refresh="1">Refresh music charts <span>↻</span></button><button class="settings-row" data-add-playlist="1">Create playlist <span>＋</span></button></div>
  <div class="settings-group">
   <h2>Users · On-device profiles</h2>
   <label class="settings-label">Aktiv bruker
    <select id="accountSelect">${persist.accounts.map(a=>`<option ${persist.account===a?'selected':''}>${escape(a)}</option>`).join('')}</select>
   </label>
   <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">
    <button class="settings-primary" style="flex:1;min-width:110px;margin:0;padding:8px 10px;font-size:12px" data-add-account="1">＋ Ny profil</button>
    <button class="settings-primary" style="flex:1;min-width:110px;margin:0;padding:8px 10px;font-size:12px;background:var(--accent,#FF9F43)" data-edit-account="1">✏️ Endre navn</button>
    ${persist.accounts.length>1?`<button class="settings-primary" style="flex:1;min-width:110px;margin:0;padding:8px 10px;font-size:12px;background:#e74c3c" data-delete-account="1">🗑️ Slett profil</button>`:''}
   </div>
   <button class="settings-primary" style="margin-top:10px;background:linear-gradient(135deg,#8e44ad,#9b59b6);padding:8px 10px;font-size:12px" data-auraconnect-send-profile-active="1">👤 Overfør denne profilen med Aura Profile Sync</button>
   <div class="settings-help">Lokal brukerprofil med Deezer ARL, mediaserver, likte artister/sanger og inntil 10 spillelister.</div>
  </div>
 `;

 const connectionsContent=`
  ${auraMusikSettings()}
  <div class="settings-group">
   <h2>2. Sekundærkilde · Eclipse / Deezer ARL</h2>
   <label class="settings-row" style="margin-bottom:12px">
    <span>Aktiver Deezer / Eclipse (ARL)</span>
    <label class="aura-switch">
     <input type="checkbox" data-setting="deezerEnabled" ${persist.deezerEnabled!==false?'checked':''}>
     <span class="aura-slider"></span>
    </label>
   </label>
   <div class="settings-help">Paste your Deezer ARL to connect to Eclipse. The credential is encrypted on this device and transmitted only to the configured Eclipse provider.</div><label class="settings-label">Deezer ARL<input id="deezerArl" class="arl-secret-input" type="password" autocomplete="new-password" autocapitalize="none" spellcheck="false" placeholder="Paste your Deezer ARL"></label><button class="settings-primary" data-save-arl="1">Save ARL securely</button><button class="settings-row" data-arl-help="1">How to find your ARL <span>›</span></button><div class="settings-help" id="arlStatus">${window.AuraNative?.hasDeezerArl?.()?'ARL saved securely on this device.':'No ARL saved yet.'}</div><p class="arl-warning">AURA AMP connects automatically to Eclipse with your securely stored ARL. The manifest address is hidden.</p><div class="settings-help" id="providerStatus">${escape(providerManifest?'Connected: '+(providerManifest.name||'Ultra MAX'):(persist.manifest?'Connecting automatically / retrying when online…':sourceError||'Paste Deezer ARL to connect to Eclipse.'))}</div></div>
  ${serverSettings()}
  <div class="settings-group"><h2>Spotify · Import playlists</h2><div class="settings-help">Connect using Spotify Authorization Code + PKCE. Register the exact redirect URI http://127.0.0.1:8765/callback in the Spotify Developer Dashboard. Spotify may require Premium and an allowed development user. Under the 2026 API rules, only playlists you own or collaborate on can have their tracks imported. No client secret or Spotify audio is stored.</div><button class="settings-primary" data-spotify-login="1">Import playlist from Spotify</button><div id="spotifyStatus" class="settings-help">${escape(spotifyStatus||'Not connected')}</div>${spotifyPlaylists.length?`<div class="spotify-playlists">${spotifyPlaylists.map(p=>`<button class="settings-row" data-spotify-import="${escape(p.id)}">${escape(p.name)} <span>Import →</span></button>`).join('')}</div>`:''}</div>
 `;

 const auraConnectContent=`
  ${auraConnectSection()}
 `;

 const hasArl=window.AuraNative?.hasDeezerArl?.();
 const hasTv=auraConnectReceivers.length>0;
 const hasServer=(persist.servers||[]).length>0;

 const statusContent=`
  <div class="settings-group">
   <h2>Status for Tjenester & Nettverk</h2>
   <div class="settings-help">Oversikt over tilkoblede kilder og status:</div>

   <div class="settings-row" style="padding:12px 0">
    <div><strong>AuraMusik Engine (Hovedkilde #1)</strong><br><small style="color:var(--secondary)">Innebygd YouTube musikk- &amp; videoskilde</small></div>
    <span style="color:${persist.auraMusikEnabled!==false?'#2ecc71':'#95a5a6'};font-weight:800">${persist.auraMusikEnabled!==false?'🟢 Aktiv (Hovedkilde)':'⚪ Deaktivert (AV)'}</span>
   </div>

   <div class="settings-row" style="padding:12px 0">
    <div><strong>Eclipse / Deezer ARL (Sekundærkilde #2)</strong><br><small style="color:var(--secondary)">Ekstern musikkilde</small></div>
    <span style="color:${hasArl&&persist.deezerEnabled!==false?'#2ecc71':'#95a5a6'};font-weight:800">${hasArl&&persist.deezerEnabled!==false?'🟢 Tilkoblet':'⚪ Deaktivert (AV)'}</span>
   </div>

   <div class="settings-row" style="padding:12px 0">
    <div><strong>Lokal musikk (Local Audio)</strong><br><small style="color:var(--secondary)">Filer på enheten</small></div>
    <span style="color:#2ecc71;font-weight:800">🟢 Aktiv</span>
   </div>

   <div class="settings-row" style="padding:12px 0">
    <div><strong>AuraConnect TV-kobling</strong><br><small style="color:var(--secondary)">Stue/TV mTLS paret</small></div>
    <span style="color:${hasTv?'#2ecc71':'#95a5a6'};font-weight:800">${hasTv?'🟢 Paret ('+escape(auraConnectReceivers[0]?.name||'TV')+')':'⚪ Ingen paret TV'}</span>
   </div>

   <div class="settings-row" style="padding:12px 0">
    <div><strong>Home Servers (Plex / Emby)</strong><br><small style="color:var(--secondary)">Egne mediaservere</small></div>
    <span style="color:${hasServer?'#2ecc71':'#95a5a6'};font-weight:800">${hasServer?'🟢 Konfigurert':'⚪ Ikke konfigurert'}</span>
   </div>

   <div class="settings-row" style="padding:12px 0">
    <div><strong>Spotify Import</strong><br><small style="color:var(--secondary)">PKCE OAuth spillelisteimport</small></div>
    <span style="color:${spotifyPlaylists.length?'#2ecc71':'#95a5a6'};font-weight:800">${spotifyPlaylists.length?'🟢 Tilkoblet':'⚪ Ikke tilkoblet'}</span>
   </div>
  </div>
 `;

 const aboutContent=`
  <div class="settings-group">
   <h2>AURA AMP v1.3.4 Alpha</h2>
   <div class="settings-help">Build 10307 · Flat UIX Architecture · Catalog metadata: Deezer · Playback: Ultra MAX or local audio</div>
   <button class="settings-primary" style="margin-top:12px" data-check-update="1">Sjekk etter oppdatering ↻</button>
  </div>

  <div class="settings-group">
   <h2>Om AURA AMP & Økosystemet</h2>
   <p style="font-size:13px;line-height:1.6;color:var(--ink)">AURA AMP er mobilhjertet i AURA-økosystemet — skapt for å gi deg ren, kompromissløs lydkvalitet og sømløs samhandling mellom mobil, stue og TV uten skyavhengighet eller reklame.</p>

   <h3 style="font-size:15px;margin:14px 0 6px">Hva står AURA for?</h3>
   <p style="font-size:13px;line-height:1.6;color:var(--secondary)">Aura symboliserer atmosfære, harmoni og tilstedeværelse. Vår visjon er å samle alle dine musikkkilder (lokale lydfiler, Eclipse/Deezer, mediaservere) i ét vakkert, lynraskt grensesnitt med mTLS-sikret AuraConnect for direkte, privat TV-kontroll på ditt eget Wi-Fi-nettverk.</p>
  </div>

  <div class="settings-group" style="text-align:center;padding:22px;background:var(--pill);border-radius:22px;margin-top:20px">
   <p style="font-size:16px;font-weight:800;margin:0 0 6px 0">Kjøp en kaffe til utvikleren ☕</p>
   <p style="font-size:13px;color:var(--secondary);line-height:1.5;margin:0 0 14px 0">Liker du AURA AMP? Ditt bidrag bidrar direkte til videreutvikling, nye funksjoner og optimalisering i AURA-økosystemet.</p>
   <button class="settings-primary" style="max-width:240px;margin:0 auto;background:var(--aura-orange-gradient)" data-buy-coffee="1">☕ Kjøp en kaffe (Buy me a coffee)</button>
  </div>
 `;

 let content=generalContent;
 if(settingsTab==='connections')content=connectionsContent;
 else if(settingsTab==='auraconnect')content=auraConnectContent;
 else if(settingsTab==='status')content=statusContent;
 else if(settingsTab==='about')content=aboutContent;

 return `<div class="screen-header"><button class="icon-only" data-nav="discover">←</button><strong>Settings</strong><span>⚙</span></div><h1 class="title-large">Settings</h1>${tabBar}${content}`;}
const view=()=>({discover,search:searchPage,library,settings,playlist:playlistPage,playlistEditor:playlistEditPage,swipe:swipePage,player,album:albumPage,artist:artistPage,favorites:libraryFolder,downloads:libraryFolder})[page]();
function miniMarkup(){if(!current||page==='player')return '';return `<div class="mini-player" id="miniPlayer" role="group" aria-label="Minimized music player"><div class="mini-main"><button class="mini-vinyl ${playing?'spinning':''}" data-expand-player="1" aria-label="Open player"><span class="vinyl-disc"></span><span class="vinyl-label">${current.cover?`<img src="${escape(current.cover)}" alt="">`:'♫'}</span></button><div class="mini-marquee" data-expand-player="1"><div class="mini-text"><strong>${escape(current.artist||'Unknown artist')}</strong><small>${escape(current.title||'Untitled')}</small></div></div><button class="mini-control mini-auraplay" data-auraplay="1" aria-label="AuraPlay cast" title="AuraPlay · Cast til enhet"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#ffffff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9" stroke="#ffffff" stroke-opacity="0.4" stroke-dasharray="2 2"/><circle cx="12" cy="12" r="5" stroke="#ffffff" fill="rgba(255,255,255,0.2)"/><path d="M10.5 9.5L14.5 12L10.5 14.5V9.5Z" fill="#ffffff" stroke="none"/></svg></button><button class="mini-control mini-toggle" data-player="toggle" aria-label="${playing?'Pause':'Play'}">${playing?'Ⅱ':'▶'}</button><button class="mini-control" data-player="next" aria-label="Next">⏭</button></div><button class="mini-grab" data-expand-player="1" aria-label="Drag downward to expand player"><span></span></button></div>`}
function arlHelpMarkup(){if(!showArlHelp)return '';const steps=[['Log in to deezer.com in your browser.'],['Open DevTools (F12) and choose Application (Chrome) or Storage (Firefox).'],['Go to Cookies → deezer.com and find the cookie named arl.'],['Copy its full value and paste it into the Deezer ARL field in Settings.']];return `<div class="arl-help-overlay" id="arlHelpOverlay" role="dialog" aria-modal="true" aria-label="How to find your ARL"><div class="arl-help-body"><h1>How to find your ARL</h1><p class="arl-warning">Your ARL is a session credential. Never share it, put it in an issue, or commit it to GitHub.</p>${steps.map((step,i)=>`<div class="arl-step"><span class="arl-step-number">${i+1}</span><p>${step[0]}</p></div>`).join('')}</div><div class="arl-help-footer"><button class="arl-help-done" data-arl-done="1">Done</button></div></div>`}
function actionMarkup(){if(!actionTrack)return '';const s=actionTrack;const f=persist.favorites.includes(key(s));const followed=persist.followedArtists?.includes(s.artist);const artistActions=s.artist?`<button data-action="artist-profile"><span class="action-glyph">◉</span> Se artistprofil</button><button data-action="artist-follow"><span class="action-glyph">${followed?'♥':'♡'}</span> ${followed?'Slutt å følge':'Følg'} ${escape(s.artist)}</button>`:'';const sleepAction=page==='player'?`<button data-action="sleep"><span class="action-glyph">🌙</span> Sleep Mode</button>`:'';return `<div class="action-shade" data-close-actions="1"></div><div class="action-sheet" role="dialog" aria-label="Sangmeny"><div class="action-head">${img(s)}<div><strong>${escape(s.title)}</strong><small>${escape(s.artist)}${s.album?' · '+escape(s.album):''}</small></div><button data-close-actions="1" aria-label="Lukk">×</button></div><button data-action="tv-next"><span class="action-glyph">📺</span> Spill som neste på TV</button><button data-action="tv-queue"><span class="action-glyph">☷</span> Legg til i TV-kø</button><button data-action="playlist"><span class="action-glyph">＋</span> Add to playlist</button><button data-action="favorite"><span class="action-glyph">${glyph('heart')}</span> ${f?'Remove from favorites':'Add to favorites'}</button><button data-action="info"><span class="action-glyph">ⓘ</span> Song information</button>${artistActions}<button data-action="next"><span class="action-glyph">${glyph('next')}</span> Play next</button><button data-action="queue"><span class="action-glyph">☷</span> Add to end of queue</button><button data-action="download"><span class="action-glyph">${downloadIcon()}</span> Download offline</button>${sleepAction}</div>`}
function render(){document.body.dataset.theme=persist.theme;window.AuraNative?.setTheme?.(persist.theme);const mount=$('.phone[data-screen="discover"] .mobile-slot');const transition=playerTransition;const transitionClass=transition==='enter'?'player-entering':transition==='restore'?'nav-restoring':'';mount.innerHTML=`<div class="screen ${page==='player'?'player-screen':''} ${current&&page!=='player'?'with-mini':''} ${transitionClass}"><div class="scroll-area">${view()}</div>${page==='player'?playerDock():nav()}${miniMarkup()}${actionMarkup()}${playlistPickerMarkup()}${arlHelpMarkup()}</div>`;playerTransition='';if(transitionClass)requestAnimationFrame(()=>setTimeout(()=>$('.mobile-slot .screen')?.classList.remove(transitionClass),720));$$('.phone').forEach(p=>p.classList.toggle('mobile-active',p.dataset.screen==='discover'));updateProgress();refreshDownloadQueueUi();setTimeout(syncNativeCastButton,80)}
function syncNativeCastButton(){
 // The visible Cast icon opens Android's native receiver picker directly.
 window.AuraNative?.hideCastButton?.();
}
window.addEventListener('resize',()=>setTimeout(syncNativeCastButton,60));
function navigate(p){if(p==='player'&&current){if(page!=='player'&&!(page==='artist'&&artistSourcePage==='player'))lastPage=page;collapsed=false;playerTransition=page==='player'?'':'enter'}else if(page==='player'&&p!=='player'){collapsed=!!current;lastPage=p;playerTransition='restore'}page=p;render();if(p==='swipe'){swipeAnimating=false;discoveryPreviewId='';}}
function collapsePlayer(){if(page!=='player')return;const to=lastPage==='player'?'discover':lastPage;const pane=$('.player-screen');if(pane){pane.classList.add('slide-down');setTimeout(()=>navigate(to),500)}else navigate(to)}
function expandPlayer(){if(!current)return;lastPage=page;const mini=$('#miniPlayer');if(tvRemoteActive)closeTvRemote();if(mini){mini.classList.add('mini-expand');setTimeout(()=>navigate('player'),230)}else navigate('player')}
let sleepModeActive=false,sleepClockInterval=null;
function updateSleepClock(){
 const hoursEl=document.querySelector('#sleepHours');
 const minsEl=document.querySelector('#sleepMins');
 if(hoursEl&&minsEl){
  const d=new Date();
  hoursEl.textContent=String(d.getHours()).padStart(2,'0');
  minsEl.textContent=String(d.getMinutes()).padStart(2,'0');
 }
}
function openSleepMode(){
 if(!current)return note('Spill av en sang før du aktiverer Sleep Mode');
 sleepModeActive=true;
 window.AuraNative?.enableSleepMode?.(true);
 renderSleepMode();
 if(sleepClockInterval)clearInterval(sleepClockInterval);
 sleepClockInterval=setInterval(updateSleepClock,1000);
}
function closeSleepMode(){
 sleepModeActive=false;
 window.AuraNative?.enableSleepMode?.(false);
 if(sleepClockInterval)clearInterval(sleepClockInterval);
 $('#sleepModeOverlay')?.remove();
}
function renderSleepMode(){
 if(!sleepModeActive||!current){
  $('#sleepModeOverlay')?.remove();
  return;
 }
 let shell=$('#sleepModeOverlay');
 if(!shell){
  shell=document.createElement('div');
  shell.id='sleepModeOverlay';
  shell.className='sleep-mode-overlay';
  document.body.appendChild(shell);
 }
 const d=new Date();
 const hrs=String(d.getHours()).padStart(2,'0');
 const mins=String(d.getMinutes()).padStart(2,'0');
 shell.innerHTML=`
  <div class="sleep-mode-left">
   <img class="sleep-mode-art" src="${escape(current.cover||'')}" alt="${escape(current.title)}">
   <h1 class="sleep-mode-title">${escape(current.title)}</h1>
   <p class="sleep-mode-artist">${escape(current.artist)}</p>
  </div>
  <div class="sleep-mode-right">
   <div class="sleep-mode-clock">
    <span class="sleep-hours" id="sleepHours">${hrs}</span><span class="sleep-sep">:</span><span class="sleep-mins" id="sleepMins">${mins}</span>
   </div>
   <div class="sleep-mode-bottom-bar">
    <div class="sleep-mode-controls">
     <button class="${shuffleEnabled?'active':''}" data-shuffle="1" title="Miks">${glyph('shuffle')}</button>
     <button data-player="prev" title="Forrige">${transportIcon('previous')}</button>
     <button class="big-play" data-player="toggle" title="${playing?'Pause':'Spill'}">${transportIcon(playing?'pause':'play')}</button>
     <button data-player="next" title="Neste">${transportIcon('next')}</button>
     <button class="${repeatMode?'active':''}" data-repeat="1" title="Gjenta">${glyph('repeat')}${repeatMode===1?'<small style="position:absolute;font-size:10px">1</small>':''}</button>
    </div>
    <button class="sleep-mode-exit" data-exit-sleep="1">✕ Avslutt Sleep Mode</button>
   </div>
  </div>
 `;
}

let tvRemoteActive=false,tvRemoteDevice=null,tvRemotePollTimer=null;
let tvRemoteState={title:'AuraConnect TV',artist:'Tilkoblet enhet',cover:'',playing:false,positionMs:0,durationMs:0,queueLength:0,error:''};

function sendAuraControl(deviceId,op,args,message,onSuccess,onFailure){
 if(!deviceId)return note('Ingen TV er paret ennå. Skann QR-koden på TV-skjermen først!');
 if(!window.AuraNative?.auraConnectControl)return note('AuraConnect er ikke tilgjengelig i denne appversjonen.');
 if(pendingAuraControlAction)return note('AuraConnect behandler allerede en kommando. Prøv igjen om et øyeblikk.');
 pendingAuraControlAction={op,message,onSuccess,onFailure};
 window.AuraNative.auraConnectControl(deviceId,op,JSON.stringify(args||{}));
}

function auraTrackReference(track){return {id:key(track),title:track.title||'Untitled',artist:track.artist||'Unknown',durationMs:Math.max(0,Math.round((track.duration||0)*1000)),isrc:track.isrc||null,cover:track.cover||null}}

function sendAuraTrackHandoff(deviceId,track,upcoming,message){
 if(!track)return note('Ingen sang er valgt for AuraPlay.');
 if(track.origin==='local')return note('Lokale filer på telefonen kan ikke spilles på TV-en ennå.');
 const allowed=(upcoming||[]).filter(t=>t&&t.origin!=='local').slice(0,200);
 const skipped=(upcoming||[]).filter(t=>t?.origin==='local').length;
 const resultMessage=(message||'AuraPlay sendt til TV')+(skipped?` · ${skipped} lokale spor ble hoppet over`:'' );
 sendAuraControl(deviceId,'playback.handoff',{track:auraTrackReference(track),upcoming:allowed.map(auraTrackReference)},resultMessage,()=>pollAuraPlaybackResult(deviceId,4));
}

function pollAuraPlaybackResult(deviceId,attempts){
 if(attempts<=0)return;
 setTimeout(()=>{
  window.AuraNative?.auraConnectControl?.(deviceId,'playback.status','{}');
  pollAuraPlaybackResult(deviceId,attempts-1);
 },1500);
}

function sendAuraQueueBatch(deviceId,tracks,position,message){
 const all=(tracks||[]).filter(Boolean);
 let items=all.filter(t=>t.origin!=='local').slice(0,200);
 const skipped=all.length-items.length;
 if(position==='next')items=items.slice().reverse();
 if(!items.length)return note('Ingen TV-kompatible sanger å legge i kø.');
 let index=0;
 const addNext=()=>{
  const track=items[index];
  sendAuraControl(deviceId,'queue.add',{track:auraTrackReference(track),position},'',()=>{
   index++;
   if(index<items.length)addNext();else if(message)note(message+(skipped?` · ${skipped} lokale spor ble hoppet over`:''));
  });
 };
 addNext();
}

function pollTvStatus(){
 if(!tvRemoteActive||!tvRemoteDevice)return;
 window.AuraNative?.auraConnectControl?.(tvRemoteDevice.deviceId, 'playback.status', '{}');
}

function openTvRemote(device){
 tvRemoteDevice=device||auraConnectReceivers[0]||{name:'AuraConnect TV',deviceId:'tv_1',host:'192.168.10.239'};
 tvRemoteActive=true;
 renderTvRemote();
 pollTvStatus();
 if(tvRemotePollTimer)clearInterval(tvRemotePollTimer);
 tvRemotePollTimer=setInterval(pollTvStatus,1000);
}

function closeTvRemote(){
 tvRemoteActive=false;
 tvRemoteDevice=null;
 if(tvRemotePollTimer)clearInterval(tvRemotePollTimer);
 $('#tvRemoteOverlay')?.remove();
}

function renderTvRemote(){
 if(!tvRemoteActive||!tvRemoteDevice){
  $('#tvRemoteOverlay')?.remove();
  return;
 }
 let shell=$('#tvRemoteOverlay');
 if(!shell){
  shell=document.createElement('div');
  shell.id='tvRemoteOverlay';
  shell.className='tv-remote-overlay';
  document.body.appendChild(shell);
 }
 const miniDock=$('#miniPlayer');
 shell.style.top=miniDock?`${Math.ceil(miniDock.getBoundingClientRect().bottom)}px`:'0';
 const devName=escape(tvRemoteDevice.name||'TV');
 const trackTitle=escape(tvRemoteState.title||(current?current.title:'Ingen aktiv avspilling på TV'));
 const trackArtist=escape(tvRemoteState.artist||(current?current.artist:'AuraConnect TV Remote'));
 const coverUrl=tvRemoteState.cover||(current?current.cover:'');

 const playlistsHtml=(persist.playlists||[]).map(p=>`
  <div class="auraplay-card-btn" style="flex-direction:column;align-items:flex-start;gap:8px;margin:0">
   <div style="display:flex;justify-content:space-between;width:100%;align-items:center">
    <strong style="font-size:14px;color:#fff">${escape(p.name||'Spilleliste')}</strong>
    <small style="color:rgba(255,255,255,0.6)">${(p.tracks||[]).length} sanger</small>
   </div>
   <div style="display:flex;gap:6px;width:100%">
    <button class="settings-primary" style="flex:1;margin:0;padding:6px 8px;font-size:11px;background:var(--aura-orange-gradient)" data-tv-play-playlist="${escape(p.id)}">▶ Spill nå</button>
    <button class="settings-primary" style="flex:1;margin:0;padding:6px 8px;font-size:11px;background:#383c48" data-tv-next-playlist="${escape(p.id)}">⏭ Spill neste</button>
    <button class="settings-primary" style="flex:1;margin:0;padding:6px 8px;font-size:11px;background:#383c48" data-tv-queue-playlist="${escape(p.id)}">＋ Sett i kø</button>
   </div>
  </div>
 `).join('')||'<div class="settings-help">Ingen lokale spillelister ennå.</div>';

 shell.innerHTML=`
  <div class="screen-header center-head" style="width:100%;margin-bottom:20px">
   <button class="icon-only" data-close-tv-remote="1">${glyph('back')}</button>
   <strong>FJERNKONTROLL · ${devName}</strong>
   <span style="font-size:18px">📺</span>
  </div>

  <div class="tv-remote-body" style="display:flex;flex-direction:column;align-items:center;width:100%;max-width:440px;margin:0 auto">
   <div class="player-art" style="margin-bottom:16px">
    ${coverUrl?`<img src="${escape(coverUrl)}" class="cover-large" alt="" style="width:220px;height:220px;border-radius:24px;object-fit:cover;">`:`<div class="cover-large missing-cover" style="width:220px;height:220px;border-radius:24px;display:grid;place-items:center;background:var(--pill);font-size:48px">📺</div>`}
   </div>

   <div class="player-song" style="text-align:center;margin-bottom:12px">
    <h1 style="font-size:22px;margin:0 0 4px 0">${trackTitle}</h1>
    <p class="artist-under-title" style="font-size:15px;color:var(--orange,#FF7A00);margin:0">${trackArtist}</p>
    ${tvRemoteState.durationMs>0?`<div style="font-size:12px;color:var(--secondary);margin-top:4px">${fmt(tvRemoteState.positionMs/1000)} / ${fmt(tvRemoteState.durationMs/1000)} · Kø: ${tvRemoteState.queueLength} sanger</div>`:''}
   </div>

   <div class="player-controls" style="display:flex;align-items:center;justify-content:center;gap:28px;margin:12px 0 20px">
    <button data-tv-action="prev" aria-label="Forrige på TV">${transportIcon('previous')}</button>
    <button class="big-play" data-tv-action="toggle" aria-label="Play/Pause på TV">${transportIcon(tvRemoteState.playing?'pause':'play')}</button>
    <button data-tv-action="next" aria-label="Neste på TV">${transportIcon('next')}</button>
   </div>

   <div class="tv-remote-actions" style="width:100%;display:flex;flex-direction:column;gap:8px;margin-bottom:16px">
    <button class="auraplay-card-btn" style="margin:0" data-auraconnect-send-profile-active="1">
     <span style="display:flex;align-items:center;gap:10px">👤 <strong style="font-size:14px;color:#fff">Aura Profile Sync</strong></span>
     <span style="font-size:12px;color:rgba(255,255,255,0.6)">Send profil &amp; favoritter ›</span>
    </button>

    <button class="auraplay-card-btn" style="margin:0" data-auraconnect-send-arl-active="1">
     <span style="display:flex;align-items:center;gap:10px">🔑 <strong style="font-size:14px;color:#fff">Deezer ARL</strong></span>
     <span style="font-size:12px;color:rgba(255,255,255,0.6)">Send innlogging til TV ›</span>
    </button>

    <button class="auraplay-card-btn" style="margin:0" data-auraconnect-send-playlists-active="1">
     <span style="display:flex;align-items:center;gap:10px">🎵 <strong style="font-size:14px;color:#fff">Send Spillelister</strong></span>
     <span style="font-size:12px;color:rgba(255,255,255,0.6)">Send til TV ›</span>
    </button>

    <button class="auraplay-card-btn" style="margin:0" data-auraconnect-send-mediaserver-active="1">
     <span style="display:flex;align-items:center;gap:10px">🖥️ <strong style="font-size:14px;color:#fff">Send Mediaserver</strong></span>
     <span style="font-size:12px;color:rgba(255,255,255,0.6)">Plex / Emby / Jellyfin ›</span>
    </button>

    <button class="auraplay-card-btn" style="margin:0;background:rgba(231,76,60,0.15)!important;border-color:rgba(231,76,60,0.3)!important" data-tv-action="clear-queue">
     <span style="display:flex;align-items:center;gap:10px;color:#ff6b6b">🗑️ <strong style="font-size:14px;color:#ff6b6b">Tøm TV-kø</strong></span>
     <span style="font-size:12px;color:rgba(255,107,107,0.7)">Nullstill kø ×</span>
    </button>
   </div>

   <div style="width:100%;margin-top:8px">
    <div style="font-size:14px;font-weight:800;color:var(--ink,#fff);margin-bottom:10px;display:flex;align-items:center;justify-content:space-between">
     <span>📚 Dine Spillelister</span>
     <small style="color:var(--secondary)">Velg for å sende/spille på TV</small>
    </div>
    <div class="tv-remote-playlists-list" style="display:flex;flex-direction:column;gap:8px">
     ${playlistsHtml}
    </div>
   </div>
  </div>
 `;
}

let auraPlayActive=false,auraPlayTarget=null;

function openAuraPlayPicker(){
 window.AuraNative?.auraConnectReceivers?.();
 let shell=$('#auraPlayOverlay');
 if(!shell){
  shell=document.createElement('div');
  shell.id='auraPlayOverlay';
  shell.className='auraplay-overlay';
  document.body.appendChild(shell);
 }

 const googleIcon=`<svg viewBox="0 0 24 24" width="20" height="20" fill="none"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z" fill="#FBBC05"/><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z" fill="#EA4335"/></svg>`;
 const tvRemoteIcon=`<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="7" y="2" width="10" height="20" rx="3" ry="3"/><circle cx="12" cy="6" r="1.5" fill="currentColor"/><circle cx="12" cy="11" r="1"/><path d="M10 15h4M10 18h4"/></svg>`;
 const auraPlaySymbol=`<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#ffffff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9" stroke="#ffffff" stroke-opacity="0.35" stroke-dasharray="2 2"/><circle cx="12" cy="12" r="5" stroke="#ffffff"/><path d="M10.5 9.5L14.5 12L10.5 14.5V9.5Z" fill="#ffffff" stroke="none"/></svg>`;

 const receiversHtml=auraConnectReceivers.length?auraConnectReceivers.map(r=>`
  <button class="auraplay-card-btn" data-auraplay-target="${escape(r.deviceId)}">
   <div style="text-align:left">
    <strong style="display:block;font-size:15px;color:#fff">📺 ${escape(r.name||'TV')}</strong>
    <small style="color:rgba(255,255,255,0.6)">AuraConnect mTLS · ${escape(r.host||r.deviceId)}</small>
   </div>
   <span style="display:flex;align-items:center;gap:6px;font-size:13px;font-weight:750;color:#fff;background:rgba(255,255,255,0.12);padding:6px 12px;border-radius:20px">${auraPlaySymbol} AuraPlay</span>
  </button>
 `).join(''):'<div class="settings-help" style="padding:14px 0">Ingen parede AuraConnect-enheter i nærheten. Skann QR-koden på TV-en under Settings → AuraConnect først.</div>';

 shell.innerHTML=`
  <div class="action-shade" data-close-auraplay="1"></div>
  <div class="action-sheet" role="dialog" aria-label="AuraPlay Cast & Output" style="max-height:80vh;overflow-y:auto;background:var(--phone,#1a1b22)">
   <div class="action-head" style="margin-bottom:16px">
    <div style="display:flex;align-items:center;gap:8px">
     <div style="width:36px;height:36px;border-radius:50%;background:#2b2e37;display:grid;place-items:center">${auraPlaySymbol}</div>
     <div>
      <strong style="font-size:17px;color:var(--ink,#fff)">AuraPlay · Enheter &amp; Cast</strong>
      <small style="color:var(--secondary)">Strøm lyd direkte over Wi-Fi uten sky</small>
     </div>
    </div>
    <button data-close-auraplay="1" aria-label="Close">×</button>
   </div>

   <div class="auraplay-device-list">
    ${receiversHtml}
   </div>

   <div style="display:flex;flex-direction:column;gap:8px;margin-top:12px">
    <button class="auraplay-card-btn" style="margin:0" data-open-tv-remote="1">
     <span style="display:flex;align-items:center;gap:10px">${tvRemoteIcon} <strong style="font-size:14px">TV Remote</strong></span>
     <span style="font-size:12px;color:rgba(255,255,255,0.6)">Åpne fjernkontroll ›</span>
    </button>

    <button class="auraplay-card-btn" style="margin:0" data-cast="1">
     <span style="display:flex;align-items:center;gap:10px">${googleIcon} <strong style="font-size:14px">Google Cast</strong></span>
     <span style="font-size:12px;color:rgba(255,255,255,0.6)">Native Android picker ›</span>
    </button>
   </div>
  </div>
 `;
}

function closeAuraPlayPicker(){
 $('#auraPlayOverlay')?.remove();
}

async function startAuraPlayCast(deviceId){
 const target=auraConnectReceivers.find(r=>r.deviceId===deviceId)||(auraConnectReceivers[0]||{name:'TV',deviceId:deviceId});
 if(!current)return note('Spill av en sang før du kaster via AuraPlay');
 if(current.origin==='local')return note('Lokale filer på telefonen kan ikke spilles på TV-en ennå.');
 auraPlayTarget=target;
 auraPlayActive=true;
 tvRemoteDevice=target;
 closeAuraPlayPicker();
 const at=playQueue.findIndex(track=>key(track)===key(current));
 const upcoming=at>=0?playQueue.slice(at+1):[];
 sendAuraTrackHandoff(deviceId,current,upcoming,`Startet «${current.title||'sporet'}» på ${target.name||'TV'} via AuraPlay ✦`);
}
function syncPlaybackButtons(){document.querySelectorAll('[data-shuffle]').forEach(b=>{b.classList.toggle('active',shuffleEnabled);b.setAttribute('aria-pressed',String(shuffleEnabled))});document.querySelectorAll('[data-repeat]').forEach(b=>{b.classList.toggle('active',repeatMode!==0);b.setAttribute('aria-pressed',String(repeatMode!==0))});document.querySelectorAll('[data-player="toggle"]').forEach(b=>{b.innerHTML=b.classList.contains('big-play')||b.classList.contains('dock-play')?transportIcon(playing?'pause':'play'):(playing?'Ⅱ':'▶');b.setAttribute('aria-label',playing?'Pause':'Play');if(b.classList.contains('dock-play'))b.title=playing?'Pause':'Spill av'});document.querySelectorAll('.mini-vinyl').forEach(el=>el.classList.toggle('spinning',playing));updateProgress();if(sleepModeActive)renderSleepMode();}
function updateProgress(){const x=$('#elapsed'),t=$('#total'),bar=$('#seek'),waveRoot=$('#waveBars');const duration=nativeActive?(nativeDuration||current?.duration||0):(Number.isFinite(audio.duration)?audio.duration:(current?.duration||0));const position=nativeActive?nativePosition:audio.currentTime;if(x)x.textContent=fmt(position);if(t)t.textContent=fmt(duration);if(bar){bar.max=duration||1;bar.value=position||0}if(waveRoot){const n=waveRoot.children.length;const fraction=duration>0?position/duration:0;for(let i=0;i<n;i++)waveRoot.children[i].classList.toggle('elapsed',(i+.5)/n<=fraction)}updateKaraoke()}

function key(s){return s.origin+'|'+s.id}
let catalog=new Map((persist.trackCache||[]).map(x=>[x.origin+'|'+x.id,x]));const remember=arr=>{for(const x of arr)catalog.set(key(x),x);persist.trackCache=[...catalog.values()].filter(x=>x.origin!=='local').slice(-3000).map(({blob,stream,...rest})=>rest);save();return arr};const lookup=k=>catalog.get(k)||localSongs.find(s=>key(s)===k)||null;
async function connect(manifestUrl,quiet=false){const u=new URL(manifestUrl);if(u.protocol!=='https:'||!u.pathname.endsWith('manifest.json'))throw Error('Use an HTTPS manifest.json URL'); const m=await jsonRequest(u.toString());if(!m||!m.resources)throw Error('Invalid manifest: resources missing');providerManifest=m;providerBase=u.toString().replace(/manifest\.json(?:\?.*)?$/,'').replace(/\/$/,'');persist.manifest=u.toString();save();if(!quiet)note('Connected: '+(m.name||'music source'));return m}
const providerSearchCache=new Map();
async function providerSearch(q){
 if(!providerBase)return [];
 const cacheKey=providerBase+'|'+q.trim().toLowerCase();
 const cached=providerSearchCache.get(cacheKey);
 if(cached&&cached.expires>Date.now())return cached.value;
 const v=encodeURIComponent(q);
 const routes=[`search?q=${v}`,`search?query=${v}`,`search/track/${v}.json`,`search.json?q=${v}`,`catalog/track/search/${v}.json`];
 const known=providerSearchPath?providerSearchPath.replace(/__Q__/g,v):null;
 const ordered=[...new Set([known,...routes].filter(Boolean))];
 const tryRoute=async route=>{
  try{
   const data=await jsonRequest(providerBase+'/'+route);
   const items=dataItems(data).map(x=>normalize(x,'ultramax'));
   if(items.length)return {route,items};
  }catch(error){}
  throw Error('No tracks from this route');
 };
 let result=null;
 // Use the last known working route first. Otherwise race two API variants.
 if(known){try{result=await tryRoute(known)}catch(e){}}
 if(!result){
  try{result=await Promise.any(ordered.filter(x=>x!==known).slice(0,2).map(tryRoute))}catch(e){}
 }
 if(!result){
  try{result=await Promise.any(ordered.filter(x=>x!==known).slice(2).map(tryRoute))}catch(e){}
 }
 if(result){
  providerSearchPath=result.route.replaceAll(v,'__Q__');
  providerSearchCache.set(cacheKey,{expires:Date.now()+120000,value:result.items});
  return result.items;
 }
 sourceError='No playable results from the connected Ultra MAX source.';
 return [];
}
async function search(q){const id=++searchVersion;busy=true;sourceError=''; updateResults();try{const amEnabled=persist.auraMusikEnabled!==false;const outputs=await Promise.allSettled([providerSearch(q),deezer('/search?q='+encodeURIComponent(q)+'&limit=30'),amEnabled?auraMusikSearch(q,'song'):Promise.resolve([]),amEnabled?auraMusikSearch(q,'video'):Promise.resolve([])]);if(id!==searchVersion)return;const prv=outputs[0].status==='fulfilled'?outputs[0].value:[];const secondary=outputs[1].status==='fulfilled'?dataItems(outputs[1].value).map(x=>normalize(x)):[];const amSongs=outputs[2].status==='fulfilled'?outputs[2].value:[];const amVideos=outputs[3].status==='fulfilled'?outputs[3].value:[];const servers=serverTracks.filter(x=>(x.title+' '+x.artist+' '+x.album).toLowerCase().includes(q.toLowerCase()));results=remember(unique([...servers,...prv,...secondary,...amSongs,...amVideos]));for(const p of prv){if(!p.cover){const m=secondary.find(x=>x.title.toLowerCase()===p.title.toLowerCase()&&x.artist.toLowerCase()===p.artist.toLowerCase());if(m){p.cover=m.cover;p.album=p.album||m.album;p.duration=p.duration||m.duration}}}if(!results.length)sourceError='No results. Ultra MAX: '+(outputs[0].status==='rejected'?outputs[0].reason.message:'none')+'; Deezer: '+(outputs[1].status==='rejected'?outputs[1].reason.message:'none');}catch(e){sourceError=e.message}finally{if(id===searchVersion){busy=false;updateResults()}}}
function updateResults(){const box=$('#searchResults');if(box)box.innerHTML=searchMarkup();const status=$('#resultsStatus');if(status)status.textContent=busy?'Searching…':results.length?results.length+' matching entries':''}
let homeLoadPromise=null;
function storeHomeCache(){try{localStorage.setItem('aura_amp_chart_cache_v1',JSON.stringify({trending:home.trending.slice(0,80),top:home.top.slice(0,80),new:home.new.slice(0,80),norway:home.norway.slice(0,80),norwaySource:home.norwaySource}))}catch(_){}}
function publishChartUpdate(){if(page==='discover'||page==='swipe')render();storeHomeCache()}
function loadHome(){
 if(homeLoadPromise)return homeLoadPromise;
 chartLastRefresh=Date.now();busyHome=!home.top.length&&!home.new.length;const errors=[];
 const applyDeezer=value=>{const tracks=dataItems(value).map(t=>normalize(t));if(tracks.length){home.trending=remember(tracks);home.top=home.trending.slice(0,100);busyHome=false;publishChartUpdate()}};
 const applyNorway=value=>{const norwegian=value.tracks;if(norwegian.length){home.norwaySource=value.source;home.norway=remember(norwegian);if(!home.trending.length)home.trending=home.norway;if(!home.top.length)home.top=home.norway;busyHome=false;publishChartUpdate()}};
 const primary=[
  deezer('/chart/0/tracks?limit=100').then(applyDeezer).catch(e=>errors.push('Deezer charts: '+e.message)),
  norwegianCharts().then(applyNorway).catch(e=>errors.push('Apple charts: '+e.message))
 ];
 homeLoadPromise=Promise.allSettled(primary).then(async()=>{
  // Lower-priority release catalog calls must not delay the music discovery cards.
  busyHome=false;publishChartUpdate();
  const supplementary=await Promise.allSettled([deezer('/editorial/0/releases?limit=100'),itunesSearch('new music'),itunesSearch('Norwegian music'),itunesSearch(String(new Date().getFullYear()))]);
  if(supplementary[0].status==='fulfilled'){
   const value=supplementary[0].value;
   const releases=dataItems(value?.albums||value).map(t=>({...normalize(t),albumId:String(t.id),album:t.title||t.album?.title||'',date:t.release_date||''}));
   if(releases.length)home.new=remember(releases);
  }
  const recent=unique(supplementary.slice(1).filter(j=>j.status==='fulfilled').flatMap(j=>recentItunesAlbums(j.value))).sort((a,b)=>Date.parse(b.date)-Date.parse(a.date));
  if(recent.length)home.new=remember(unique([...recent,...home.new])).slice(0,100);
  if(errors.length)sourceError=errors.join('; ');
  publishChartUpdate();return home;
 }).finally(()=>{homeLoadPromise=null});
 return homeLoadPromise;
}

async function resolve(s,preferredQuality=persist.streamQuality){
 if(s.origin?.startsWith('server-')&&s.stream)return s.stream;
 if(s.origin==='local'&&s.stream)return s.stream;
 if(s.origin==='ultramax'&&s.stream)return s.stream;
 if(s.origin==='musike'){
  const base=(persist.musikeServerBase||'').replace(/\/$/,'');
  if(base)return `${base}/api/stream/${encodeURIComponent(s.id)}/audio`;
  return await musikeDirectStream(s.id);
 }
 if(s.origin==='auramusik'){
  // AuraMusicEngine track: resolve the audio through the custom server if set, otherwise serverless via Piped/Invidious.
  const base=(persist.auraMusikServerBase||'').replace(/\/$/,'');
  if(base)return `${base}/api/stream/${encodeURIComponent(s.id)}/audio`;
  return await auraMusikStream(s.id,'audio');
 }
 if(s.stream&&/^https?:\/\//i.test(s.stream)&&!s.stream.includes('dzcdn.net'))return s.stream;

 if(!providerBase){
  if(!persist.manifest&&window.AuraNative?.hasDeezerArl?.()){
   persist.manifest='https://eclipse.ultramax.vip/__aura_arl__/manifest.json';
   save();
  }
  if(persist.manifest){
   try{await autoConnectUltraMax();}catch(_){}
  }
 }

 const candidates=[`stream/${encodeURIComponent(s.providerId||s.id)}`,`stream/track/${encodeURIComponent(s.providerId||s.id)}.json`,`stream/track/${encodeURIComponent(s.providerId||s.id)}`,`stream?id=${encodeURIComponent(s.providerId||s.id)}`];
 if(s.origin==='deezer'||s.origin==='apple'||s.origin==='spotify'){
  if(providerBase){
   try{
    const matches=await providerSearch(s.artist+' '+s.title);
    const one=matches.find(x=>x.title.toLowerCase()===s.title.toLowerCase())||matches[0];
    if(one)return await resolve(one,preferredQuality);
   }catch(_){}
  }
  try{
   const musikeMatches=await musikeSearch(s.artist+' '+s.title,'song');
   if(musikeMatches.length)return await resolve(musikeMatches[0],preferredQuality);
  }catch(_){}
  try{
   const query=(s.artist+' '+s.title).trim();
   const res=await fetch(`https://pipedapi.kavin.rocks/search?q=${encodeURIComponent(query)}&filter=music_songs`,{cache:'no-store'});
   if(res.ok){
    const data=await res.json();
    const items=data.items||data;
    if(items.length&&items[0].url){
     const videoId=items[0].url.replace('/watch?v=','');
     const stream=await musikeDirectStream(videoId);
     if(stream)return stream;
    }
   }
  }catch(_){}
  if(s.preview&&/^https?:\/\//i.test(s.preview)){
   note('⚠️ Hører på 30s demo. Lim inn Deezer ARL under Settings for FLAC-kvalitet!');
   return s.preview;
  }
  throw Error('Lim inn din Deezer ARL under Settings → Connections for å koble til Eclipse.');
 }

 if(!providerBase)throw Error('Lim inn din Deezer ARL under Settings → Connections for å spille fulle spor.');

 for(const path of candidates){
  try{
   const data=await jsonRequest(providerBase+'/'+path);
   const streams=data?.streams||data?.data?.streams||data?.sources||(data?.stream?[data.stream]:[])||[];
   const items=Array.isArray(streams)?streams:[];
   const choice=(preferredQuality==='best'?items.find(x=>/flac|lossless/i.test((x.quality||'')+' '+(x.name||''))):preferredQuality==='high'?items.find(x=>/320|256|high/i.test((x.quality||'')+' '+(x.name||''))):items.find(x=>/128|standard/i.test((x.quality||'')+' '+(x.name||''))))||items[0];
   const url=(typeof choice==='string'?choice:choice?.url||choice?.src||choice?.streamUrl||choice?.stream_url||choice?.playbackUrl||choice?.link)||(typeof data==='string'?data:data?.url||data?.streamUrl||data?.stream_url||data?.playbackUrl);
   if(url&&/^https?:\/\//.test(url))return url;
  }catch(e){}
 }
 if(s.preview&&/^https?:\/\//i.test(s.preview))return s.preview;
 throw Error('Kunne ikke hente full avspillingslenke for dette sporet');
}
let queueSerial=0;
let nativeQueueRequestId=0;
let nativeHasNext=false; // AURA_AMP_QUEUE_CAST_FIX_20261106
async function scheduleNativeQueue(playingTrack){
 if(!nativeActive||!window.AuraNative?.setUpcomingQueue)return;
 const requestId=++nativeQueueRequestId,serial=++queueSerial;
 const idx=playQueue.findIndex(x=>key(x)===key(playingTrack));
 if(idx<0)return;
 const nextTracks=playQueue.slice(idx+1,idx+51);
 const candidates=await Promise.allSettled(nextTracks.map(async track=>{
  const url=await playbackUrl(track);
  if(!url.startsWith('https://')&&!url.startsWith('aura-offline://'))return null;
  return {url,id:key(track),title:track.title||'',artist:track.artist||'',cover:track.cover||''};
 }));
 if(requestId!==nativeQueueRequestId||serial!==queueSerial||!nativeActive)return;
 const items=candidates.filter(x=>x.status==='fulfilled'&&x.value).map(x=>x.value);
 window.AuraNative.setUpcomingQueue(JSON.stringify(items));
 lastUpcomingId=items[0]?.id||'';
}

function queueNext(item){if(!item)return;const currentIdx=playQueue.findIndex(t=>current&&key(t)===key(current));playQueue=playQueue.filter((t,i)=>key(t)!==key(item)||i===currentIdx);playQueue.splice(currentIdx<0?0:currentIdx+1,0,item);lastUpcomingId='';if(current)void scheduleNativeQueue(current)}
function downloadedTrack(s){return (persist.downloaded||[]).some(x=>key(x)===key(s))}
async function playbackUrl(s){if(s.origin!=='local'&&downloadedTrack(s)&&window.AuraNative?.playAudio)return 'aura-offline://'+encodeURIComponent(key(s));return resolve(s)}
function setQueue(tracks,source){playQueue=tracks.filter(Boolean).slice();activeQueueSource=source||'collection';queueSerial++;lastUpcomingId='';}
async function play(s,stayOnSwipe=false){
 if(!s)return;
 const attempt=++playbackAttempt;
 cancelNativeWatchdog();pendingStart=null;
 if(stayOnSwipe){audio.pause();if(nativeActive)window.AuraNative?.stopAudio?.();}
 queueSerial++;lastUpcomingId='';if(!current||key(current)!==key(s)){lyricState={track:'',loading:false,error:'',lines:[],plain:false};videoState={track:'',loading:false,url:'',title:''};playerView='art';lyricsMode=false;queueOpen=false}current=s;remember([s]);if(lyricsMode)loadLyrics(s);
 setActualPlaying(false);
 try{
  const url=await playbackUrl(s);
  if(attempt!==playbackAttempt)return;
  window.AuraNative?.prepareCastAudio?.(url,s.title||'',s.artist||'',s.cover||'');
  const wasNative=nativeActive;
  nativeActive=nativePlayback&&(/^(https?:\/\/|aura-offline:\/\/)/.test(url));
  if(nativeActive){
   audio.pause();audio.removeAttribute('src');audio.load();
   nativePosition=0;nativeDuration=s.duration||0;
   pendingStart={url,track:s};
   try{window.AuraNative.playAudio(url,s.title||'',s.artist||'',s.cover||'',key(s));}
   catch(err){await fallbackPlayback(attempt,err.message);}
   if(nativeActive&&pendingStart){
    nativeStartWatchdog=setTimeout(()=>fallbackPlayback(attempt,'Native player did not start'),10000);
    setTimeout(()=>scheduleNativeQueue(s),400);
   }
  }else{
   if(wasNative)window.AuraNative?.stopAudio?.();
   audio.src=url;
   await audio.play();
  }
  persist.history=[key(s),...persist.history.filter(k=>k!==key(s))].slice(0,200);
  saveProfile();if(!stayOnSwipe)navigate('player');void findMusicVideo(s);
 }catch(e){if(attempt===playbackAttempt){setActualPlaying(false);if(!stayOnSwipe)navigate('player');retryOrSkip(s,e.message)}}
}
async function retryOrSkip(track,why){const id=key(track);if(lastFailedKey!==id){lastFailedKey=id;retryCount=0}if(retryCount<2){retryCount++;note('Retrying '+escape(track.title)+' ('+retryCount+'/2)');setTimeout(()=>{if(current&&key(current)===id)play(track,page==='swipe')},900*retryCount)}else{retryCount=0;note('Skipping unavailable song');const idx=playQueue.findIndex(x=>key(x)===id);if(idx>=0&&idx+1<playQueue.length)await play(playQueue[idx+1],page==='swipe');else if(page==='swipe'){note('Preview unavailable · swipe to try another song')}else note('End of queue · '+why)}}
async function next(){
  if(nativeActive&&nativeHasNext&&!pendingStart&&window.AuraNative?.skipAudioNext){
    AuraNative.skipAudioNext();return;
  }
  const at=playQueue.findIndex(s=>current&&key(s)===key(current));
  if(!playQueue.length)return;
  const idx=at<0?0:at+1;
  if(idx>=playQueue.length){if(repeatMode!==0)return play(playQueue[repeatMode===1?Math.max(0,at):0],page==='swipe');note('End of playlist');return}
  await play(playQueue[idx],page==='swipe');
}
async function prev(){const at=playQueue.findIndex(s=>key(s)===key(current));if(!playQueue.length)return;await play(playQueue[Math.max(0,at-1)])}
function profileKey(suffix){return persist.account+'::'+suffix}
function setAccount(name){persist.account=name;window.AuraNative?.setActiveSyncProfile?.(name);const data=JSON.parse(localStorage.getItem('aura_user_'+name)||'{}');persist.playlists=data.playlists||[];persist.favorites=data.favorites||[];persist.favoriteAlbums=data.favoriteAlbums||[];persist.history=data.history||[];save();render()}
function syncAndroidAutoLibrary(){
 try{
  const playlists=(persist.playlists||[]).map(p=>({id:p.id,name:p.name,tracks:(p.tracks||[]).filter(x=>typeof x==='string')}));
  window.AuraNative?.syncCarPlaylists?.(JSON.stringify(playlists));
 }catch(e){console.warn('Android Auto playlist sync unavailable')}
}
function saveProfile(){try{localStorage.setItem('aura_user_'+persist.account,JSON.stringify({playlists:persist.playlists,favorites:persist.favorites,favoriteAlbums:persist.favoriteAlbums,history:persist.history}))}catch(e){}save();syncAndroidAutoLibrary()}
function promptText(message,def=''){const str=window.prompt(message,def);return str?.trim()||''}
const DB='aura_amp_audio';let dbPromise=null;function db(){if(dbPromise)return dbPromise;dbPromise=new Promise((res,rej)=>{const r=indexedDB.open(DB,1);r.onupgradeneeded=()=>r.result.createObjectStore('tracks',{keyPath:'id'});r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)});return dbPromise}
async function putAudio(item){const d=await db();return new Promise((res,rej)=>{const tx=d.transaction('tracks','readwrite');tx.objectStore('tracks').put(item);tx.oncomplete=res;tx.onerror=()=>rej(tx.error)})}
async function loadLocal(){try{const d=await db();const values=await new Promise((res,rej)=>{const r=d.transaction('tracks').objectStore('tracks').getAll();r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)});localSongs=values.map(f=>({...f,origin:'local',stream:URL.createObjectURL(f.blob),cover:f.coverBlob?URL.createObjectURL(f.coverBlob):''}));remember(localSongs);if(page==='library')render()}catch(e){note('Local storage unavailable: '+e.message)}}
async function importFiles(files){let count=0;for(const file of files){if(!/\.(mp3|flac|wav|m4a|aac|ogg|opus)$/i.test(file.name)&&!file.type.startsWith('audio/'))continue;const title=file.name.replace(/\.[^.]+$/,'');const tags=await window.AuraTags?.read(file).catch(()=>({}))||{};const item={id:'file_'+Date.now()+'_'+count,origin:'local',title:tags.title||title,artist:tags.artist||'Local music',album:tags.album||'',duration:0,blob:file,coverBlob:tags.coverBlob||null};try{await putAudio(item);count++}catch(e){note('Unable to persist '+file.name)}}await loadLocal();note(count+' local songs added')}
function addToPlaylist(s){playlistPickTrack=s;playlistPickTracks=null;actionTrack=null;render()}
function addAlbumToPlaylist(){const tracks=albumDetail?.tracks||[];if(!tracks.length){note('This album has no songs');return}playlistPickTracks=tracks.slice();playlistPickTrack=tracks[0];actionTrack=null;render()}
function choosePlaylist(id){
 const p=persist.playlists.find(x=>x.id===id);
 if(!p||!playlistPickTrack)return;
 const wasOffline=(persist.downloadedPlaylistIds||[]).includes(id)||(p.tracks.length>0&&p.tracks.map(lookup).filter(Boolean).every(t=>downloadedTrack(t)||t.origin==='local'));
 const additions=(playlistPickTracks||[playlistPickTrack]).filter(t=>!p.tracks.includes(key(t)));
 for(const track of additions)p.tracks.push(key(track));
 saveProfile();playlistPickTrack=null;playlistPickTracks=null;
 note(additions.length+' song'+(additions.length===1?'':'s')+' added to '+p.name);
 if(wasOffline&&persist.playlistAutoDownload!=='off'&&additions.length){
   void downloadCollection(additions,p.name+' · new songs',persist.playlistAutoDownload==='wifi');
 }
 render();
}
function playlistPickerMarkup(){if(!playlistPickTrack)return '';return `<div class="action-shade" data-close-playlist-picker="1"></div><div class="action-sheet playlist-picker" role="dialog" aria-label="Add to playlist"><div class="action-head"><strong>Add to playlist</strong><button data-close-playlist-picker="1" aria-label="Close">×</button></div><button class="settings-primary" data-create-from-picker="1">＋ New playlist</button>${(persist.playlists||[]).map(p=>`<button data-choose-playlist="${escape(p.id)}">${escape(p.name)} <span>＋</span></button>`).join('')}</div>`}
const downloadQueueState={active:false,total:0,finished:0,failed:0,current:'',label:''};
let downloadQueueExpanded=false,downloadQueueDismissed=false;
function refreshDownloadQueueUi(){
 const screen=document.querySelector('.phone.mobile-active .screen');
 if(!screen)return;
 document.querySelectorAll('.download-queue-indicator,.download-queue-panel').forEach(el=>el.remove());
 if(!downloadQueueState.active||downloadQueueDismissed)return;
 const header=screen.querySelector('.screen-header');
 const percent=downloadQueueState.total?Math.round(100*downloadQueueState.finished/downloadQueueState.total):0;
 document.querySelectorAll('.collection-download:not(.downloaded)').forEach(button=>{button.style.setProperty('background',`linear-gradient(0deg,#FF7A00 ${percent}%,var(--pill) ${percent}%)`,'important')});
 if(header){const btn=document.createElement('button');btn.className='download-queue-indicator';btn.setAttribute('aria-label','Download queue '+percent+'%');btn.dataset.downloadQueueOpen='1';
  btn.innerHTML='<span class="download-queue-fill" style="height:'+percent+'%"></span><span class="download-queue-symbol">↓</span>';header.appendChild(btn)}
 if(downloadQueueExpanded){
  const panel=document.createElement('section');panel.className='download-queue-panel';panel.setAttribute('role','status');panel.innerHTML='<strong>Downloading '+escape(downloadQueueState.label)+'</strong><span>'+downloadQueueState.finished+' / '+downloadQueueState.total+' tracks · '+percent+'%</span><div class="download-queue-track"><span style="width:'+percent+'%"></span></div><small>'+escape(downloadQueueState.current||'Preparing downloads')+'</small><small>'+downloadQueueState.failed+' unavailable</small>';
  panel.insertAdjacentHTML('beforeend','<button class="download-queue-done" data-download-queue-done="1">Done</button>');screen.appendChild(panel);
 }
}
window.AuraDownloadEvent=function(id,ok,message){
 if(downloadBatchBusy)return; // Batch listener updates one aggregate indicator, not one popup per song.
 if(ok){note('Download complete · available offline');window.AuraNative?.listDownloads?.();}
 else note('Offline download failed: '+message);
};
window.AuraOfflineIndex=function(payload){try{const items=JSON.parse(payload);persist.downloaded=items.map(x=>({...x,offline:true}));remember(persist.downloaded);save();if(['library','downloads','favorites','player','playlist','album'].includes(page))render()}catch(e){console.warn('Invalid offline index',e)}};
async function downloadCollection(tracks,collectionName='playlist',wifiOnly=persist.offlineWifiOnly){
 if(downloadBatchBusy){note('Another album or playlist download is running');return}
 tracks=(tracks||[]).filter(Boolean);
 if(!tracks.length){note('No tracks available to download');return}
 if(!window.AuraNative?.downloadTrack){note('Offline downloads require Android');return}
 downloadBatchBusy=true;downloadQueueExpanded=false;downloadQueueDismissed=false;
 Object.assign(downloadQueueState,{active:true,total:tracks.length,finished:0,failed:0,current:'Preparing downloads',label:collectionName});
 refreshDownloadQueueUi();
 try{
  for(const track of tracks){
   downloadQueueState.current=track.title||'Unknown song';refreshDownloadQueueUi();
   if(track.origin==='local'||downloadedTrack(track)){downloadQueueState.finished++;refreshDownloadQueueUi();continue}
   try{
    const url=await resolve(track,persist.downloadQuality);
    if(!/^https:\/\//i.test(url)||/\.m3u8(?:\?|$)|\.mpd(?:\?|$)/i.test(url))throw Error('No downloadable direct audio file');
    await new Promise((resolveDone,reject)=>{
     const id=key(track),previous=window.AuraDownloadEvent;
     const restore=()=>{window.AuraDownloadEvent=previous;clearTimeout(timeout)};
     const timeout=setTimeout(()=>{restore();reject(Error('Download timed out'))},180000);
     window.AuraDownloadEvent=(received,ok,msg)=>{
      if(received!==id){previous?.(received,ok,msg);return}
      restore();ok?resolveDone():reject(Error(msg||'Download failed'));
     };
     try{AuraNative.downloadTrack(id,url,track.title||'',track.artist||'',track.album||'',track.cover||'',wifiOnly)}
     catch(error){restore();reject(error)}
    });
   }catch(e){downloadQueueState.failed++;console.warn('Batch offline download failed:',track.title,e?.message||e)}
   downloadQueueState.finished++;refreshDownloadQueueUi();
  }
 }finally{
  downloadBatchBusy=false;
  downloadQueueState.current='Finished';
  refreshDownloadQueueUi();
  window.AuraNative?.listDownloads?.();
  const batchLabel=downloadQueueState.label;
  setTimeout(()=>{if(!downloadBatchBusy&&downloadQueueState.label===batchLabel&&!downloadQueueExpanded){downloadQueueState.active=false;refreshDownloadQueueUi()}},1800);
 }
}

async function downloadPlaylist(id){
 const pl=persist.playlists.find(x=>x.id===id);
 if(!pl){note('Playlist not found');return}
 persist.downloadedPlaylistIds=[...new Set([...(persist.downloadedPlaylistIds||[]),id])];save();return downloadCollection((pl.tracks||[]).map(lookup).filter(Boolean),pl.name);
}
async function download(s){if(!s)return;if(s.origin==='local'){note('Already stored offline');return}if(downloadedTrack(s)){note('Already downloaded');return}if(!window.AuraNative?.downloadTrack){note('Offline downloads require Android app');return}try{const url=await resolve(s,persist.downloadQuality);if(!url.startsWith('https://')||/\.m3u8(?:\?|$)|\.mpd(?:\?|$)/i.test(url))throw Error('This source does not provide a downloadable audio file');AuraNative.downloadTrack(key(s),url,s.title||'',s.artist||'',s.album||'',s.cover||'',persist.offlineWifiOnly);note('Downloading…')}catch(e){note('Unable to download: '+e.message)}}
// Splash: 3.6 s minimum, 9 s maximum. Connection and Discover data begin immediately.
const splashStarted=Date.now();
const startupStatus=message=>{const node=$('#auraStartupStatus');if(node)node.textContent=message};
const finishSplash=()=>{const splash=$('#auraStartup');if(!splash)return;splash.classList.add('is-done');setTimeout(()=>splash.remove(),550)};
let ultraRetryTimer=null,ultraRetryAttempt=0,ultraConnecting=null;
function retryUltraMax(){
 if(!persist.manifest||providerBase||ultraConnecting||ultraRetryTimer||!navigator.onLine)return;
 const delay=Math.min(60000,3000*Math.pow(2,Math.min(ultraRetryAttempt++,4)));
 ultraRetryTimer=setTimeout(()=>{ultraRetryTimer=null;autoConnectUltraMax()},delay);
}
function autoConnectUltraMax(){
 if(!persist.manifest)return Promise.resolve(null);
 if(providerBase)return Promise.resolve(providerManifest);
 if(ultraConnecting)return ultraConnecting;
 ultraConnecting=connect(persist.manifest,true).then(manifest=>{
  ultraRetryAttempt=0;
  sourceError='';
  if(ultraRetryTimer){clearTimeout(ultraRetryTimer);ultraRetryTimer=null}
  return manifest;
 }).catch(err=>{
  console.warn('Ultra MAX auto-connect failed:',err?.message||err);
  return null;
 }).finally(()=>{ultraConnecting=null;if(!providerBase)retryUltraMax()});
 return ultraConnecting;
}
window.addEventListener('online',()=>{
 if(!providerBase&&persist.manifest){if(ultraRetryTimer){clearTimeout(ultraRetryTimer);ultraRetryTimer=null}autoConnectUltraMax()}
 if(!home.trending.length||!home.new.length)loadHome();
});
async function startup(){
 syncAndroidAutoLibrary();
 document.body.dataset.theme=persist.theme;
 render();
 autoConnectUltraMax();
 loadHome();
 loadLocal();
 if(persist.servers?.length)setTimeout(refreshServers,1400);
 if(window.AuraNative?.listDownloads)setTimeout(()=>{try{window.AuraNative.listDownloads()}catch(e){console.warn('Offline index unavailable',e)}},1200);
 setTimeout(finishSplash, 400);
}
document.addEventListener('play',e=>{if(e.target.matches?.('.aura-video-embed'))pauseAudioForVideo()},{capture:true});
document.addEventListener('input',e=>{if(e.target.id==='playlistNewName'){const v=$('#playlistCoverTitle');if(v)v.textContent=e.target.value||'Your Mix';return}if(e.target.id==='searchField'){query=e.target.value;clearTimeout(searchTimer);if(query.trim().length>=2){busy=true;updateResults();searchTimer=setTimeout(()=>search(query),350)}else{results=[];sourceError='';busy=false;updateResults()}}});
document.addEventListener('change',async e=>{const t=e.target;if(t.dataset.toggleServerId){const sId=t.dataset.toggleServerId;const s=(persist.servers||[]).find(x=>x.id===sId);if(s){s.enabled=t.checked;save();note(t.checked?`Aktiverte ${s.type.toUpperCase()}`:`Deaktiverte ${s.type.toUpperCase()}`);refreshServers();}return}if(t.dataset.setting){persist[t.dataset.setting]=t.type==='checkbox'?t.checked:t.value;save();if(t.dataset.setting==='theme'||t.dataset.setting==='deezerEnabled'||t.dataset.setting==='serversEnabled'||t.dataset.setting==='auraMusikEnabled')render()}if(t.id==='accountSelect')setAccount(t.value);if(t.id==='localFilePicker')await importFiles([...t.files]);if(t.id==='playlistCoverFile'&&t.files?.[0])await readCover(t.files[0])});
window.AuraSpotifyPlaylists=function(payload){try{const data=typeof payload==='string'?JSON.parse(payload):payload;if(data.error)throw Error(data.error);spotifyPlaylists=data.items||[];spotifyStatus=spotifyPlaylists.length+ ' playlists available';if(page==='settings')render();note('Spotify connected');}catch(e){spotifyStatus=e.message;if(page==='settings')render();note('Spotify: '+e.message)}};
window.AuraSpotifyTracks=function(payload){try{const data=typeof payload==='string'?JSON.parse(payload):payload;if(data.error)throw Error(data.error);const pl=spotifyPlaylists.find(p=>p.id===data.playlistId);const tracks=(data.items||[]).map(item=>{const t=item.item||item.track||item;return {origin:'spotify',id:String(t.id||''),providerId:String(t.id||''),title:t.name||'Unknown',artist:(t.artists||[]).map(a=>a.name).join(', ')||'Unknown artist',album:t.album?.name||'',cover:t.album?.images?.[0]?.url||'',albumId:t.album?.id||'',spotifyUrl:t.external_urls?.spotify||'',duration:(t.duration_ms||0)/1000};}).filter(t=>t.id);remember(tracks);const playlist={id:'spotify_'+data.playlistId,name:pl?.name||'Spotify Import',description:'Imported Spotify metadata · playback via your connected source',tracks:tracks.map(key),sourceUrl:pl?.external_urls?.spotify||''};persist.playlists=[...(persist.playlists||[]).filter(p=>p.id!==playlist.id),playlist];saveProfile();spotifyStatus='Imported '+tracks.length+' songs';navigate('library');note('Spotify playlist imported');}catch(e){spotifyStatus=e.message;note('Spotify import failed: '+e.message)}};
async function buildArtistContinuation(){if(!artistDetail)return;const artist=artistDetail,source='artist:'+artist.id;const initial=unique([...(artist.top||[]),...(artist.latestSongs||[])]);if(!initial.length)return;setQueue(initial,source);const startingTrack=current?key(current):'';const extra=[];for(const album of (artist.albums||[]).slice(0,30)){try{const data=await deezer('/album/'+encodeURIComponent(album.id)+'/tracks?limit=100');extra.push(...dataItems(data).map(t=>normalize({...t,album:{id:album.id,title:album.title,cover_big:album.cover}})));}catch(err){console.info('Artist album tracks unavailable',err.message)}if(activeQueueSource!==source)return;}if(activeQueueSource!==source)return;const combined=unique([...initial,...extra]);playQueue=combined;remember(combined);if(current)scheduleNativeQueue(current);}
document.addEventListener('click',async e=>{if(e.target.closest('[data-expand-player]')){expandPlayer();return}if(e.target.closest('.action-shade')){actionTrack=null;playlistPickTrack=null;playlistPickTracks=null;render();return}const b=e.target.closest('button');if(!b)return;const d=b.dataset;
  if(d.settingsTab){settingsTab=d.settingsTab;render();return;}
  if(d.editPlaylist){openPlaylistEditor(d.editPlaylist);return}
  if(d.coverPreset!==undefined){playlistPresetDraft=Number(d.coverPreset);playlistArtDraft='';const preview=$('.new-cover-preview');if(preview)preview.style.background=auraCovers[playlistPresetDraft];$$('[data-cover-preset]').forEach(x=>{const selected=Number(x.dataset.coverPreset)===playlistPresetDraft;x.classList.toggle('chosen',selected);x.textContent=selected?'✓':''});return}
  if(d.coverUpload){$('#playlistCoverFile')?.click();return}
  if(d.savePlaylist){const name=$('#playlistNewName')?.value.trim();if(!name){note('Enter playlist name');return}const description=$('#playlistNewDesc')?.value.trim()||'';let p=playlistEditorId?persist.playlists.find(x=>x.id===playlistEditorId):null;if(!p){p={id:'pl'+Date.now(),tracks:[]};persist.playlists.push(p)}p.name=name;p.description=description;p.coverPreset=playlistPresetDraft;p.coverImage=playlistArtDraft;if(playlistPickTrack&&!p.tracks.includes(key(playlistPickTrack)))p.tracks.push(key(playlistPickTrack));playlistPickTrack=null;saveProfile();selectedPlaylist=p.id;playlistEditorId=null;navigate('playlist');return}
  if(d.buyCoffee){
   note('Tusen takk for din støtte til AURA-økosystemet! ☕');
   if(window.AuraNative?.openExternal)AuraNative.openExternal('https://buymeacoffee.com');
   return;
  }
  if(d.arlHelp){showArlHelp=true;render();return}
  if(d.arlDone){const dialog=$('#arlHelpOverlay');if(dialog)dialog.classList.add('closing');setTimeout(()=>{showArlHelp=false;render()},270);return}
  if(d.saveArl){const input=$('#deezerArl');const value=input?.value?.trim()||'';if(!window.AuraNative?.saveDeezerArl){note('Secure ARL storage requires the Android app');return}if(!/^[a-fA-F0-9]{100,256}$/.test(value)){note('ARL must be a long hexadecimal session value');return}const stored=AuraNative.saveDeezerArl(value);if(input)input.value='';const label=$('#arlStatus');if(label)label.textContent=stored?'Connecting to Eclipse…':'Unable to save ARL securely';if(stored){persist.manifest='https://eclipse.ultramax.vip/__aura_arl__/manifest.json';save();providerBase='';providerManifest=null;ultraRetryAttempt=0;try{await connect(persist.manifest);if(label)label.textContent='Connected to Eclipse'}catch(err){if(label)label.textContent='Connection failed: '+err.message;note(err.message)}}else note('Could not save ARL');return}
  if(d.downloadQueueDone){downloadQueueDismissed=true;downloadQueueExpanded=false;if(!downloadBatchBusy)downloadQueueState.active=false;refreshDownloadQueueUi();return}
  if(d.downloadQueueOpen){downloadQueueExpanded=!downloadQueueExpanded;refreshDownloadQueueUi();return}
  if(d.downloadCurrent){if(current)await download(current);return}
  if(d.downloadAlbum){await downloadCollection(albumDetail?.tracks||[],albumDetail?.title||'album');return}if(d.albumAddPlaylist){addAlbumToPlaylist();return}
  if(d.downloadPlaylist){await downloadPlaylist(d.downloadPlaylist);return}
  if(d.swipe){performSwipe(d.swipe);return}
  if(d.previewPlay){const items=discoveryTracks(),t=items[discoveryIndex%items.length];if(!t)return;if(current&&key(current)===key(t)){if(playing){if(nativeActive)AuraNative.toggleAudio();else audio.pause();return}if(!pendingStart){if(nativeActive)AuraNative.toggleAudio();else audio.play().catch(()=>previewDiscoveryTrack(true));return}}void previewDiscoveryTrack(true);return}
  if(d.choosePlaylist){choosePlaylist(d.choosePlaylist);return}
  if(d.createFromPicker){openPlaylistEditor();return}
  if(d.closePlaylistPicker){playlistPickTrack=null;playlistPickTracks=null;render();return}
  if(d.collapsePlayer){collapsePlayer();return}
  if(d.expandPlayer){expandPlayer();return}
  if(d.discoverTab){discoverCategory=d.discoverTab;render();return}
  if(d.toggleLyrics){playerView=playerView==='lyrics'?'art':'lyrics';lyricsMode=playerView==='lyrics';if(lyricsMode)loadLyrics(current);render();return}
  if(d.startPlexPin){startPlexPin();return;}
  if(d.loginServer){loginServerUser();return;}
  if(d.addServer){const base=$('#serverAddress')?.value?.trim().replace(/\/$/,'');const token=$('#serverToken')?.value?.trim();const type=$('#serverType')?.value;if(!base||!token||!['plex','emby','jellyfin'].includes(type))return note('Enter server address and access token');let u;try{u=new URL(base);if(!['http:','https:'].includes(u.protocol))throw Error('URL requires HTTP or HTTPS');}catch(e){return note('Invalid server URL')}const id='srv'+Date.now();persist.servers||=[];persist.servers.push({id,base,type,token});save();note('Connecting to '+type+'…');refreshServers();return}
  if(d.removeServer){persist.servers=(persist.servers||[]).filter(x=>x.id!==d.removeServer);save();refreshServers();return}
  if(d.refreshServers){refreshServers();return}
  if(d.openYoutube){const q=encodeURIComponent(current?(current.artist+' '+current.title):'music');if(window.AuraNative?.openExternal)AuraNative.openExternal('https://www.youtube.com/results?search_query='+q);else window.open('https://www.youtube.com/results?search_query='+q,'_blank');return}
  if(d.airplayKite){if(window.AuraNative?.openAirPlayKite)AuraNative.openAirPlayKite();else note('AirPlay via Kite requires the Android app');return;}
  if(d.cast){
    if(!window.AuraNative?.openCastPicker){note('Google Cast requires the Android app');return}
    if(current){
      try{const url=await playbackUrl(current);if(/^https:\/\//.test(url))AuraNative.prepareCastAudio?.(url,current.title||'',current.artist||'',current.cover||'')}
      catch(err){console.warn('Cast source is not ready',err?.message||err)}
    }
    AuraNative.openCastPicker();return;
  }
  if(d.addAccount){
   const name=promptText('Skriv inn navn på ny brukerprofil:','');
   if(name){
    if(!persist.accounts.includes(name))persist.accounts.push(name);
    setAccount(name);
    note('Brukerprofil «'+name+'» opprettet!');
   }
   return;
  }
  if(d.editAccount){
   const oldName=persist.account||'Hovedbruker';
   const newName=promptText('Endre navn for profil «'+oldName+'»:',oldName);
   if(newName&&newName!==oldName){
    const oldData=localStorage.getItem('aura_user_'+oldName);
    if(oldData){
     localStorage.setItem('aura_user_'+newName,oldData);
     localStorage.removeItem('aura_user_'+oldName);
    }
    persist.accounts=persist.accounts.map(a=>a===oldName?newName:a);
    persist.account=newName;
    save();
    note('Brukerprofil endret til «'+newName+'»');
    render();
   }
   return;
  }
  if(d.deleteAccount){
   const target=persist.account||'Hovedbruker';
   if(persist.accounts.length<=1)return note('Du kan ikke slette den eneste brukerprofilen');
   if(confirm('Er du sikker på at du vil slette brukerprofilen «'+target+'»?')){
    localStorage.removeItem('aura_user_'+target);
    persist.accounts=persist.accounts.filter(a=>a!==target);
    const nextAccount=persist.accounts[0]||'Hovedbruker';
    setAccount(nextAccount);
    note('Brukerprofil «'+target+'» slettet');
    render();
   }
   return;
  }
  if(d.checkUpdate){if(window.AuraNative?.checkForUpdates)AuraNative.checkForUpdates();else note('Update checks require the Android app');return}
  if(d.sleepMode){openSleepMode();return;}
  if(d.exitSleep){closeSleepMode();return;}
  if(d.tvPlayPlaylist||d.tvNextPlaylist||d.tvQueuePlaylist){
   const plId=d.tvPlayPlaylist||d.tvNextPlaylist||d.tvQueuePlaylist;
   const pl=(persist.playlists||[]).find(p=>p.id===plId);
   if(!pl)return note('Spilleliste ikke funnet');
   const deviceId=tvRemoteDevice?.deviceId||auraConnectReceivers[0]?.deviceId||'';
   if(!deviceId)return note('Ingen TV er paret ennå. Skann QR-koden på TV-skjermen under Innstillinger først!');
   const tracks=(pl.tracks||[]).map(lookup).filter(Boolean);
   if(!tracks.length)return note('Spillelisten har ingen tilgjengelige sanger.');
   const pushPl=[{
    id:pl.id,
    title:pl.name||'Spilleliste',
    description:pl.description||'',
    tracks:tracks.map(t=>({
     id:key(t),
     title:t.title||'Untitled',
     artist:t.artist||'Unknown',
     durationMs:(t.duration||0)*1000,
     isrc:t.isrc||null,
     cover:t.cover||null
    }))
   }];
   pendingAuraPlaylistAction=d.tvPlayPlaylist
    ?{deviceId,kind:'play',track:tracks[0],upcoming:tracks.slice(1),message:`Startet «${pl.name}» på TV-en ▶`}
    :d.tvNextPlaylist
     ?{deviceId,kind:'next',tracks,message:`La «${pl.name}» inn som neste på TV-en ⏭`}
     :{deviceId,kind:'queue',tracks,message:`La «${pl.name}» til i TV-køen ＋`};
   window.AuraNative?.auraConnectSendPlaylists?.(deviceId,JSON.stringify(pushPl));
   note(`Sender «${pl.name}» til TV…`);
   return;
  }
  if(d.auraplay){openAuraPlayPicker();return;}
  if(d.closeAuraplay){closeAuraPlayPicker();return;}
  if(d.auraplayTarget){startAuraPlayCast(d.auraplayTarget);return;}
  if(d.openTvRemote){openTvRemote();return;}
  if(d.closeTvRemote){closeTvRemote();return;}
  if(d.tvAction){
   const targetId=tvRemoteDevice?.deviceId||auraConnectReceivers[0]?.deviceId||'';
   if(!targetId)return note('Ingen TV er paret ennå. Skann QR-koden på TV-skjermen under Innstillinger først!');
   if(d.tvAction==='toggle'){
    const op=tvRemoteState.playing?'playback.pause':'playback.play';
    sendAuraControl(targetId,op,{},tvRemoteState.playing?'Satte TV-avspilling på pause Ⅱ':'Startet TV-avspilling ▶');
   }else if(d.tvAction==='next'){
    sendAuraControl(targetId,'playback.next',{},'Hoppet til neste sang på TV ⏭');
   }else if(d.tvAction==='prev'){
    sendAuraControl(targetId,'playback.previous',{},'Gikk til forrige sang på TV ⏮');
   }else if(d.tvAction==='clear-queue'){
    sendAuraControl(targetId,'queue.clear',{},'Tømte TV-køen ×');
   }
   return;
  }
  if(d.spotifyLogin){spotifyStatus='Opening Spotify sign-in…';const status=$('#spotifyStatus');if(status)status.textContent=spotifyStatus;if(window.AuraNative?.spotifyLogin)AuraNative.spotifyLogin();else note('Spotify OAuth requires Android app');return}
  if(d.spotifyImport){if(window.AuraNative?.spotifyImport)AuraNative.spotifyImport(d.spotifyImport);else note('Spotify import needs Android app');return}
  if(d.queue){queueOpen=!queueOpen;render();return}
  if(d.queuePlay){const selected=playQueue.find(t=>key(t)===d.queuePlay);if(selected){queueOpen=false;await play(selected)}return}
  if(d.playerView){
  const nextView=playerView===d.playerView?'art':d.playerView;
  if(nextView==='video'&&!associatedVideo(current))return;
  if(nextView==='video')pauseAudioForVideo();
  playerView=nextView;lyricsMode=playerView==='lyrics';
  if(lyricsMode)loadLyrics(current);
  render();return
 }
  if(d.lyricsView){playerView=d.lyricsView==='lyrics'?'lyrics':'art';lyricsMode=playerView==='lyrics';if(lyricsMode)loadLyrics(current);render();return}
  if(d.lyricLine){const l=lyricState.lines[Number(d.lyricLine)];if(l&&!lyricState.plain){if(nativeActive)AuraNative.seekAudio(Math.round(l.time*1000));else audio.currentTime=l.time}return}
  if(d.artistBack){navigate(artistSourcePage==='artist'?'discover':artistSourcePage);return}
  if(d.albumBack){navigate(albumSourcePage==='album'?'discover':albumSourcePage);return}
  if(d.artistPlay){const tracks=unique([...(artistDetail?.top||[]),...(artistDetail?.latestSongs||[])]);if(!tracks.length){note('No artist songs available');return}setQueue(tracks,'artist:'+(artistDetail?.id||''));await play(playQueue[0]);void buildArtistContinuation();return}
  if(d.artistFollow){if(!persist.followedArtists)persist.followedArtists=[];const name=artistDetail?.name;if(name){persist.followedArtists=persist.followedArtists.includes(name)?persist.followedArtists.filter(x=>x!==name):[...persist.followedArtists,name];save();render()}return}
  if(d.artistActions){note('Artist: '+(artistDetail?.name||''));return}
  if(d.artist){const seed=[...results,...home.top,...home.trending,...localSongs].find(x=>x.artist?.toLowerCase()===d.artist.toLowerCase());await openArtist(d.artist,seed);return}
  if(d.closeActions){actionTrack=null;render();return}
  if(d.action==='artist-follow'){
   const item=actionTrack;actionTrack=null;if(!item?.artist)return;
   persist.followedArtists=persist.followedArtists||[];
   const isFollowed=persist.followedArtists.includes(item.artist);
   persist.followedArtists=isFollowed?persist.followedArtists.filter(name=>name!==item.artist):[...persist.followedArtists,item.artist];
   saveProfile();note(isFollowed?'Sluttet å følge '+item.artist:'Følger '+item.artist);render();return;
  }
  if(d.action==='sleep'){
   actionTrack=null;render();openSleepMode();return;
  }
  if(d.action==='artist-profile'){
   const item=actionTrack;actionTrack=null;if(!item?.artist)return;
   void openArtist(item.artist,item);return;
  }
  if(d.action){const item=actionTrack;if(!item)return;actionTrack=null;if(d.action==='tv-next'||d.action==='tv-queue'){
   if(item.origin==='local')return note('Lokale filer på telefonen kan ikke legges i TV-køen ennå.');
   const targetId=tvRemoteDevice?.deviceId||auraPlayTarget?.deviceId||auraConnectReceivers[0]?.deviceId||'';
   const position=d.action==='tv-next'?'next':'end';
   sendAuraControl(targetId,'queue.add',{track:auraTrackReference(item),position},d.action==='tv-next'?`La «${item.title}» inn som neste på TV-en ⏭`:`La «${item.title}» til i TV-køen ＋`);
   return;
  }else if(d.action==='playlist')addToPlaylist(item);else if(d.action==='favorite'){persist.favorites=persist.favorites.includes(key(item))?persist.favorites.filter(k=>k!==key(item)):[...persist.favorites,key(item)];saveProfile();note('Favorites updated')}else if(d.action==='info'){window.alert([item.title,item.artist,item.album||'Album not provided',item.duration?fmt(item.duration):'Duration unknown',item.quality||'Quality set by music source'].join('\n'))}else if(d.action==='next'){queueNext(item);note('Added to play next')}else if(d.action==='queue'){playQueue.push(item);lastUpcomingId='';if(current)void scheduleNativeQueue(current);note('Added to end of queue')}else if(d.action==='download')await download(item);render();return}
  if(d.tvNext){
   if(!current)return note('Ingen sang spilles av');
   if(current.origin==='local')return note('Lokale filer på telefonen kan ikke legges i TV-køen ennå.');
   const targetId=tvRemoteDevice?.deviceId||auraPlayTarget?.deviceId||auraConnectReceivers[0]?.deviceId||'';
   sendAuraControl(targetId,'queue.add',{track:auraTrackReference(current),position:'next'},`La «${current.title}» inn som neste på TV-en ⏭`);
   return;
  }
  if(d.playNext){const item=lookup(d.playNext);if(item){queueNext(item);note('Added to play next');render()}return}if(d.more){actionTrack=lookup(d.more);render();return}
  if(d.auraconnectScan){
   if(window.AuraNative?.scanQrCode){
    window.AuraNative.scanQrCode();
   }else{
    note('Kamera/QR-skanner er ikke tilgjengelig');
   }
   return;
  }
  if(d.auraconnectPair){
   const code=($('#auraConnectInput')?.value||'').trim();
   if(!code)return note('Skann QR-kode eller tast inn paringskode');
   auraConnectStatus='Kobler til og parer…';
   const el=$('#auraConnectStatus');if(el)el.textContent=auraConnectStatus;
   window.AuraNative?.auraConnectPair?.(code);
   return;
  }
  if(d.auraconnectSendProfile||d.auraconnectSendProfileActive){
   const deviceId=(typeof d.auraconnectSendProfile==='string'&&d.auraconnectSendProfile)?d.auraconnectSendProfile:(auraConnectReceivers[0]?.deviceId||'');
   if(!deviceId)return note('Ingen TV er paret ennå. Skann QR-koden på TV-skjermen først!');
   const profileName=persist.account||'Hovedbruker';
   const playlists=(persist.playlists||[]).slice(0,10).map(p=>({
    id:p.id,
    title:p.name||p.title||'Spilleliste',
    description:p.description||'',
    tracks:(p.tracks||[]).map(lookup).filter(Boolean).map(t=>({
     id:key(t),
     title:t.title||'Untitled',
     artist:t.artist||'Unknown',
     durationMs:(t.duration||0)*1000,
     isrc:t.isrc||null,
     cover:t.cover||null
    }))
   }));
   const profilePayload=[{
    id:'profile_sync_'+Date.now(),
    title:'Aura Profile: '+profileName,
    description:'Aura Profile Sync (ARL, Servere, Favoritter & 10 Spillelister)',
    profileName:profileName,
    followedArtists:persist.followedArtists||[],
    favorites:persist.favorites||[],
    favoriteAlbums:persist.favoriteAlbums||[],
    servers:persist.servers||[],
    tracks:[],
    playlists:playlists
   }];
   auraConnectStatus='Sender profil «'+profileName+'» via Aura Profile Sync…';
   const el=$('#auraConnectStatus');if(el)el.textContent=auraConnectStatus;
   note(auraConnectStatus);
   window.AuraNative?.auraConnectSendPlaylists?.(deviceId,JSON.stringify(profilePayload));
   return;
  }
  if(d.auraconnectSendArl||d.auraconnectSendArlActive){
   const deviceId=(typeof d.auraconnectSendArl==='string'&&d.auraconnectSendArl)?d.auraconnectSendArl:(auraConnectReceivers[0]?.deviceId||'');
   if(!deviceId)return note('Ingen TV er paret ennå. Skann QR-koden på TV-skjermen først!');
   if(!window.AuraNative?.hasDeezerArl?.())return note('Ingen Deezer ARL er lagret på telefonen ennå. Tast inn din ARL under Innstillinger først!');
   auraConnectStatus='Sender ARL til TV…';
   const el=$('#auraConnectStatus');if(el)el.textContent=auraConnectStatus;
   note(auraConnectStatus);
   window.AuraNative?.auraConnectSendArl?.(deviceId);
   return;
  }
  if(d.auraconnectSendPlaylists||d.auraconnectSendPlaylistsActive){
   const deviceId=(typeof d.auraconnectSendPlaylists==='string'&&d.auraconnectSendPlaylists)?d.auraconnectSendPlaylists:(auraConnectReceivers[0]?.deviceId||'');
   if(!deviceId)return note('Ingen TV er paret ennå. Skann QR-koden på TV-skjermen først!');
   const playlists=(persist.playlists||[]).map(p=>({
    id:p.id,
    title:p.name||p.title||'Spilleliste',
    description:p.description||'',
    tracks:(p.tracks||[]).map(lookup).filter(Boolean).map(t=>({
     id:key(t),
     title:t.title||'Untitled',
     artist:t.artist||'Unknown',
     durationMs:(t.duration||0)*1000,
     isrc:t.isrc||null,
     cover:t.cover||null
    }))
   }));
   if(!playlists.length)return note('Ingen spillelister å sende');
   auraConnectStatus='Sender '+playlists.length+' spilleliste(r) til TV…';
   const el=$('#auraConnectStatus');if(el)el.textContent=auraConnectStatus;
   note(auraConnectStatus);
   window.AuraNative?.auraConnectSendPlaylists?.(deviceId,JSON.stringify(playlists));
   return;
  }
  if(d.auraconnectForget){
   window.AuraNative?.auraConnectForget?.(d.auraconnectForget);
   note('Enhet fjernet');
   window.AuraNative?.auraConnectReceivers?.();
   return;
  }
 if(d.searchCategory){category=d.searchCategory;}if(d.nav)return navigate(d.nav);
  if(d.syncStart){if(!window.AuraNative?.startSync)return note('Local Sync requires Android');syncSharing=true;syncStatus='Starter lokal server…';showSyncShareDialog();AuraNative.startSync(snapshot());const el=$('#syncStatus');if(el)el.textContent=syncStatus;return}
  if(d.syncStop){window.AuraNative?.stopSync?.();syncSharing=false;syncStatus='Server stoppet';syncCode='';removeSyncShareDialog();render();return}
  if(d.syncScan){syncNearby=[];syncSelected=null;syncScanning=true;syncStatus='Scanning nearby AURA AMP devices…';render();if(window.AuraNative?.scanSync)window.AuraNative.scanSync();else{syncScanning=false;syncStatus='Scanner unavailable: install the Local Sync v2.1 mobile build';render()}return}
  if(d.syncSelect!==undefined){syncSelected=syncNearby[Number(d.syncSelect)]||null;const el=$('#syncChosen');if(el)el.textContent=syncSelected?.name||'No device selected';return}
  if(d.syncReceive){const code=($('#syncPair')?.value||'').trim();if(!syncSelected)return note('Choose a device first');if(!/^\d{6}$/.test(code))return note('Enter exactly six digits');window.AuraNative?.receiveSync?.(syncSelected.host,Number(syncSelected.port),code);syncStatus='Mottar brukerdata · kobler til sikkert…';const el=$('#syncStatus');if(el)el.textContent=syncStatus;return}
  if(d.nav)return navigate(d.nav);if(d.category){category=d.category;updateResults();$$('[data-category]').forEach(x=>x.classList.toggle('selected',x.dataset.category===category));return}if(d.clear){query='';results=[];render();$('#searchField')?.focus();return}if(d.playlistMenu){openPlaylistEditor(d.playlistMenu);return;const pl=persist.playlists.find(x=>x.id===d.playlistMenu);if(!pl)return;const action=promptText('Playlist: rename / delete','rename').toLowerCase();if(action==='rename'){const name=promptText('Playlist name',pl.name);if(name){pl.name=name;saveProfile();render()}}else if(action==='delete'&&confirm('Delete playlist?')){persist.playlists=persist.playlists.filter(x=>x.id!==pl.id);saveProfile();navigate('library')}return}if(d.addTracks){return navigate('search')}if(d.shuffleList){const pl=persist.playlists.find(x=>x.id===d.shuffleList);const tracks=(pl?.tracks||[]).map(lookup).filter(Boolean);if(!tracks.length){note('This playlist has no available songs');return}shuffleEnabled=true;setQueue(tracks.sort(()=>Math.random()-.5),'playlist:'+d.shuffleList+':shuffle');note('Starting shuffled playlist…');return play(playQueue[0])}if(d.shuffle){
 shuffleEnabled=!shuffleEnabled;
 if(shuffleEnabled&&playQueue.length>1){const at=playQueue.findIndex(x=>current&&key(x)===key(current));const rest=playQueue.filter((_,i)=>i!==at);for(let i=rest.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[rest[i],rest[j]]=[rest[j],rest[i]]}playQueue=at>=0?[current,...rest]:rest}
 window.AuraNative?.setShuffle?.(false);lastUpcomingId='';if(current)void scheduleNativeQueue(current);render();return
}
 if(d.repeat){repeatMode=repeatMode===0?2:repeatMode===2?1:0;audio.loop=repeatMode===1;window.AuraNative?.setRepeatMode?.(repeatMode);render();return}if(d.albumTrack){const seed=lookup(d.albumTrack);await openAlbum(seed?.albumId,seed);return}if(d.albumId){await openAlbum(d.albumId);return}if(d.searchAlbum){query=d.searchAlbum;category='Albums';navigate('search');void search(query);return}if(d.album){const seed=[...home.new,...home.trending,...results].find(x=>String(x.albumId||x.id)===String(d.album));await openAlbum(d.album,seed);return}if(d.shuffleAlbum){const tracks=(albumDetail?.tracks||[]).slice();if(!tracks.length){note('No album tracks available');return}for(let i=tracks.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[tracks[i],tracks[j]]=[tracks[j],tracks[i]]}shuffleEnabled=true;setQueue(tracks,'album:shuffle');return play(playQueue[0])}
  if(d.albumPlay){const tracks=albumDetail?.tracks||[];if(!tracks.length){note(albumDetail?.error||'This album has no available songs');return}setQueue(tracks,'album');note('Spiller album: '+(albumDetail?.title||''));return play(playQueue[0])}
  if(d.albumAddPlaylist){
   const a=albumDetail;
   if(!a||!a.tracks||!a.tracks.length)return note('Ingen sanger i albumet å legge til');
   const name=a.title||'Album Spilleliste';
   const desc=(a.artist?a.artist:'')+(a.date?' · '+a.date.slice(0,4):'')+' · Album';
   const cover=a.cover||a.tracks.find(x=>x.cover)?.cover||'';
   const newPl={
    id:'pl_'+Date.now(),
    name:name,
    description:desc,
    coverImage:cover,
    tracks:a.tracks.map(t=>key(t))
   };
   if(!persist.playlists)persist.playlists=[];
   persist.playlists.push(newPl);
   saveProfile();
   note('Laget spilleliste fra album: '+name);
   selectedPlaylist=newPl.id;
   return navigate('playlist');
  }
  if(d.albumFavorite){
   const a=albumDetail;
   if(!a||!a.id)return;
   if(!persist.favoriteAlbums)persist.favoriteAlbums=[];
   const idStr=String(a.id);
   const idx=persist.favoriteAlbums.findIndex(x=>String(x.id||x.albumId||x)===idStr);
   if(idx>=0){
    persist.favoriteAlbums.splice(idx,1);
    note('Fjernet album fra favoritter');
   }else{
    persist.favoriteAlbums.push({id:idStr,title:a.title,artist:a.artist,cover:a.cover,date:a.date});
    note('Lagt til album i favoritter ♥');
   }
   saveProfile();
   render();
   return;
  }
  if(d.openList){const list=d.openList==='results-songs'||d.openList==='results-albums'?results:(home[d.openList]||[]);results=list.slice();if(!d.openList.startsWith('results-'))query='';category=d.openList==='new'||d.openList==='results-albums'?'Albums':'All';page='search';render();const box=$('#searchResults');if(box)box.innerHTML=(category==='Albums'?albumRows(results):results.map(row).join(''))||empty('No music available');return}if(d.refresh){await loadHome();note('Charts refreshed');return}if(d.connect){const input='https://eclipse.ultramax.vip/__aura_arl__/manifest.json';if(ultraRetryTimer){clearTimeout(ultraRetryTimer);ultraRetryTimer=null}ultraRetryAttempt=0;providerBase='';providerManifest=null;try{await connect(input);$('#providerStatus').textContent='Connected: '+providerManifest.name}catch(err){$('#providerStatus').textContent=err.message;note(err.message)}return}
  if(d.play){
   const s=lookup(d.play);
   if(s){
    let setFromPage=false;
    if(page==='playlist'){
     const pl=persist.playlists.find(x=>x.id===selectedPlaylist);
     const trs=(pl?.tracks||[]).map(lookup).filter(Boolean);
     if(trs.length){setQueue(trs,'playlist:'+selectedPlaylist);setFromPage=true;}
    }else if(page==='album'&&albumDetail?.tracks?.length){
     setQueue(albumDetail.tracks,'album');
     setFromPage=true;
    }else if(page==='artist'){
     const trs=unique([...(artistDetail?.top||[]),...(artistDetail?.latestSongs||[])]);
     if(trs.length){setQueue(trs,'artist:'+(artistDetail?.id||''));setTimeout(buildArtistContinuation,0);setFromPage=true;}
    }else if(page==='library'||page==='downloads'||page==='favorites'){
     const trs=s.origin?.startsWith('server-')?serverTracks.filter(x=>x.origin===s.origin):s.origin==='local'?localSongs:downloadedTrack(s)?(persist.downloaded||[]):persist.favorites.map(lookup).filter(Boolean);
     if(trs.length){setQueue(trs,'library');setFromPage=true;}
    }else if(page==='search'&&results.length){
     setQueue(results,'search');setFromPage=true;
    }else if(page==='discover'&&home.top.length){
     setQueue(home.top,'discover');setFromPage=true;
    }
    if(!setFromPage&&(!playQueue.length||!playQueue.some(x=>key(x)===key(s)))){
     setQueue([s],'single');
    }
    await play(s);
   }
   return;
  }if(d.addPlaylist){openPlaylistEditor();return}if(d.list){selectedPlaylist=d.list;return navigate('playlist')}if(d.renamePlaylist){const p=persist.playlists.find(x=>x.id===d.renamePlaylist),name=promptText('Rename playlist',p?.name);if(p&&name){p.name=name;saveProfile();render()}return}if(d.deletePlaylist){if(confirm('Delete playlist?')){persist.playlists=persist.playlists.filter(x=>x.id!==d.deletePlaylist);saveProfile();navigate('library')}return}if(d.playList){const p=persist.playlists.find(x=>x.id===d.playList);const tracks=(p?.tracks||[]).map(lookup).filter(Boolean);if(!tracks.length){note('This playlist has no available songs');return}setQueue(tracks,'playlist:'+d.playList);note('Starting playlist…');return play(playQueue[0])}if(d.importLocal){$('#localFilePicker').click();return}if(d.addAccount){const name=promptText('Create an on-device profile');if(name&&!persist.accounts.includes(name)){persist.accounts.push(name);save();setAccount(name)}return}if(d.player){if(d.player==='toggle'){if(nativeActive){if(pendingStart){note('Loading audio…')}else AuraNative.toggleAudio()}else if(audio.paused){audio.play().catch(e=>{setActualPlaying(false);note('Playback failed: '+e.message)})}else audio.pause()}else if(d.player==='next')next();else prev();syncPlaybackButtons();return}if(d.fav){persist.favorites=persist.favorites.includes(d.fav)?persist.favorites.filter(x=>x!==d.fav):[...persist.favorites,d.fav];saveProfile();render();return}if(d.deleteOffline){if(confirm('Remove offline download?')){window.AuraNative?.deleteDownload?.(d.deleteOffline)}return}
  if(d.toggleDownload){const track=lookup(d.toggleDownload);if(track){if(track.origin==='local')note('Local file is already stored on this device');else if(downloadedTrack(track))window.AuraNative?.deleteDownload?.(key(track));else await download(track)}return}if(d.download){return download(lookup(d.download))}if(d.openNativeOutput){if(window.AuraNative?.output)AuraNative.output();else note('Select speakers through your device audio settings')}});
document.addEventListener('input',e=>{if(e.target.id==='seek'){if(nativeActive)AuraNative.seekAudio(Math.round(+e.target.value*1000));else if(Number.isFinite(audio.duration))audio.currentTime=+e.target.value}});audio.addEventListener('timeupdate',updateProgress);audio.addEventListener('durationchange',updateProgress);audio.addEventListener('loadedmetadata',updateProgress);
let miniTouch=null;document.addEventListener('touchstart',e=>{if(e.target.closest('#miniPlayer'))miniTouch={y:e.touches[0].clientY,time:Date.now()};if(page==='player'&&!e.target.closest('button,input,.player-actions,.player-mode-tabs,.wave-seek,.player-controls')&&e.target.closest('.player-art,.player-song,.center-head'))playerGestureStart=e.touches[0].clientY;else playerGestureStart=null},{passive:true});document.addEventListener('touchend',e=>{const y=e.changedTouches[0].clientY;if(miniTouch){if(y-miniTouch.y>42)expandPlayer();miniTouch=null}if(page==='player'&&playerGestureStart!==null){if(y-playerGestureStart< -75)collapsePlayer();playerGestureStart=null}},{passive:true});
let pullStart=0;document.addEventListener('touchstart',e=>{const sc=e.target.closest('.scroll-area');if(sc&&sc.scrollTop===0)pullStart=e.touches[0].clientY},{passive:true});document.addEventListener('touchend',e=>{const change=e.changedTouches[0].clientY-pullStart;if(pullStart&&change>110&&page==='discover'){note('Refreshing charts…');loadHome()}pullStart=0},{passive:true});
const chooser=document.createElement('input');chooser.type='file';chooser.accept='audio/*,.mp3,.flac,.ogg,.wav,.m4a,.aac';chooser.multiple=true;chooser.id='localFilePicker';chooser.hidden=true;document.body.append(chooser);
if(nativePlayback)setInterval(()=>window.AuraNative?.requestPlaybackState(),600);
setInterval(()=>{if(document.visibilityState==='visible'&&Date.now()-chartLastRefresh>30*60*1000)loadHome()},60000);
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'&&Date.now()-chartLastRefresh>30*60*1000)loadHome()});
let swipeX=null;
document.addEventListener('touchstart',e=>{if(e.target.closest('#swipeCard')&&!e.target.closest('button')){swipeX=e.touches[0].clientX;document.querySelector('#swipeCard')?.classList.remove('swipe-enter')}},{passive:true});
document.addEventListener('touchmove',e=>{if(swipeX===null||swipeAnimating)return;const diff=e.touches[0].clientX-swipeX;const card=$('#swipeCard');if(card){card.style.transform='translateX('+Math.max(-130,Math.min(130,diff))+'px) rotate('+(diff/18)+'deg)';card.style.opacity=String(1-Math.min(.38,Math.abs(diff)/440))}},{passive:true});
document.addEventListener('touchend',e=>{if(swipeX===null)return;const diff=e.changedTouches[0].clientX-swipeX;swipeX=null;if(Math.abs(diff)>65){performSwipe(diff>0?'like':'pass')}else{const card=$('#swipeCard');if(card){card.style.transform='';card.style.opacity=''}}},{passive:true});
startup();
})();

;(()=>{if(!document.querySelector('#auraSyncV22CSS')){const css=document.createElement('link');css.id='auraSyncV22CSS';css.rel='stylesheet';css.href='sync-v22.css';document.head.appendChild(css)}})();
