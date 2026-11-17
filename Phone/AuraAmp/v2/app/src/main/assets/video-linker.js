/* AURA AMP song -> music-video resolver.
 * No scraping, fuzzy YouTube title search or bypassing provider playback rules.
 * Links require provider track-ID/ISRC attestation or a MusicBrainz recording relationship.
 */
(function(root){
 'use strict';
 const STORE='aura_amp_video_links_v2';
 const POSITIVE_TTL=30*86400000, NEGATIVE_TTL=12*3600000, ERROR_TTL=10*60000;
 const MAX_ENTRIES=400, MB_BASE='https://musicbrainz.org/ws/2/';
 const VIDEO_REL='ce3de655-7451-44d1-9224-87eb948c205d';
 const VIDEO_STREAM_REL='7e41ef12-a124-4324-afdb-fdbae687a89c';
 const youtubeId=id=>/^[A-Za-z0-9_-]{11}$/.test(String(id||''));
 const validIsrc=value=>/^[A-Z]{2}[A-Z0-9]{3}\d{7}$/.test(String(value||'').replace(/[-\s]/g,'').toUpperCase());
 const isrcOf=t=>String(t?.isrc||t?.ISRC||t?.identifiers?.isrc||'').replace(/[-\s]/g,'').toUpperCase();
 const trackId=t=>String(t?.providerId||t?.id||'').trim();
 const trackKey=t=>[String(t?.origin||'unknown'),trackId(t),isrcOf(t)].join('|');
 const name=s=>String(s||'').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase()
   .replace(/\s*[([](?:remaster(?:ed)?|deluxe|explicit|clean|radio edit|album version|mono|stereo|\d{4})[^)\]]*[)\]]/g,'')
   .replace(/[^a-z0-9]+/g,' ').trim();
 function namesMatch(song,recording){
   const title=name(song.title),rec=name(recording.title);
   if(!title||!rec||title!==rec)return false;
   const artist=name(song.artist);
   const credited=name((recording['artist-credit']||[]).map(x=>x?.artist?.name||x?.name||'').join(' '));
   return !!artist&&!!credited&&(artist===credited||credited.startsWith(artist+' ')||artist.startsWith(credited+' '));
 }
 function getYouTubeId(link){
   try{
     const u=new URL(String(link||''));
     if(u.protocol!=='https:')return null;
     const host=u.hostname.toLowerCase();
     const value=host==='youtu.be'?u.pathname.slice(1):
       ['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com'].includes(host)
       ? u.searchParams.get('v')||(/^\/embed\/([\w-]{11})$/.exec(u.pathname)||[])[1]:null;
     return youtubeId(value)?value:null;
   }catch(_){return null}
 }
 function normalizeVideo(video){
   if(!video||typeof video!=='object')return null;
   const kind=String(video.kind||video.provider||'').toLowerCase();
   const id=String(video.id||video.videoId||'');
   const yt=youtubeId(id)&&kind==='youtube'?id:getYouTubeId(video.url);
   if(yt)return {kind:'youtube',id:yt};
   // Direct videos must be explicitly authorized by the connected music provider.
   if(kind==='direct'&&/^https:\/\//.test(String(video.url||''))){
     const url=String(video.url);
     if(/^https:\/\/[^\s]+\.(?:mp4|webm)(?:[?#]|$)/i.test(url))return {kind:'file',url};
   }
   return null;
 }
 function providerPath(manifest){
   const path=manifest?.auraAmp?.musicVideoLookupPath||manifest?.behaviorHints?.musicVideoLookupPath||manifest?.musicVideoLookupPath;
   return typeof path==='string'&&path.length<200&&/^[a-z0-9/_{}.?=&%-]+$/i.test(path)?path:null;
 }
 function providerRequestUrl(base,manifest,track){
   const template=providerPath(manifest);
   if(!template||!base)return null;
   const path=template.replaceAll('{id}',encodeURIComponent(trackId(track)))
                      .replaceAll('{isrc}',encodeURIComponent(isrcOf(track)));
   if(path.includes('{')||path.includes('}')||!trackId(track))return null;
   try{
     const baseUrl=new URL(base.endsWith('/')?base:base+'/');
     const url=new URL(path.replace(/^\//,''),baseUrl);
     if(baseUrl.protocol!=='https:'||url.origin!==baseUrl.origin||!url.pathname.startsWith(baseUrl.pathname))return null;
     return url.href;
   }catch(_){return null}
 }
 function providerResult(raw,track){
   const item=raw?.data||raw;
   if(!item||typeof item!=='object'||item.verified!==true)return null;
   const id=String(item.trackId||item.providerTrackId||'');
   const isrc=String(item.isrc||'').replace(/[-\s]/g,'').toUpperCase();
   const matched=(id&&id===trackId(track))||(validIsrc(isrc)&&isrc===isrcOf(track));
   if(!matched)return null;
   if(item.relation&& !['official_music_video','music_video','provider_track_video'].includes(item.relation))return null;
   return normalizeVideo(item.video||item.musicVideo);
 }
 function musicBrainzVideo(recording){
   for(const r of recording?.relations||[]){
     const url=String(r.url?.resource||'');
     if((r.type==='free streaming'||r['type-id']===VIDEO_STREAM_REL)&&
        (r.attributes||[]).includes('video')){
       const id=getYouTubeId(url);
       if(id)return {kind:'youtube',id};
     }
   }
   return null;
 }
 let lastMusicBrainzRequest=0;
 let musicBrainzLane=Promise.resolve();
 function requestMusicBrainz(url,request){
   // Serialize all lookups, not just one track's lookups: max one request/second.
   const work=musicBrainzLane.catch(()=>{}).then(async()=>{
     const delay=Math.max(0,1150-(Date.now()-lastMusicBrainzRequest));
     if(delay)await new Promise(resolve=>setTimeout(resolve,delay));
     lastMusicBrainzRequest=Date.now();
     return request(url);
   });
   musicBrainzLane=work.catch(()=>{});
   return work;
 }
 async function fromMusicBrainz(track,request){
   const isrc=isrcOf(track);
   if(!validIsrc(isrc)||!track.title||!track.artist)return null;
   // MusicBrainz documents direct ISRC lookup and relationship queries. Do not guess
   // matches from artist/title search results.
   const result=await requestMusicBrainz(MB_BASE+'isrc/'+encodeURIComponent(isrc)+'?inc=artist-credits+recording-rels&fmt=json',request);
   const recordings=(result?.recordings||[]).filter(r=>namesMatch(track,r));
   if(recordings.length!==1)return null; // avoid ambiguous recordings
   const audio=recordings[0];
   for(const rel of audio.relations||[]){
     if(rel['type-id']!==VIDEO_REL&&rel.type!=='music video')continue;
     const videoRecording=rel.recording;
     if(!/^[a-f0-9-]{36}$/i.test(videoRecording?.id||''))continue;
     const video=await requestMusicBrainz(MB_BASE+'recording/'+encodeURIComponent(videoRecording.id)+'?inc=url-rels&fmt=json',request);
     const found=musicBrainzVideo(video);
     if(found)return found;
   }
   return null;
 }
 function storageGet(storage){
   try{
     const raw=JSON.parse(storage?.getItem(STORE)||'{}');
     return raw&&typeof raw==='object'&&!Array.isArray(raw)?raw:{};
   }catch(_){return {}}
 }
 function create({request,storage,now=()=>Date.now()}={}){
   if(typeof request!=='function')throw Error('Music-video resolver requires a JSON request adapter');
   const inFlight=new Map();
   const cache=storageGet(storage);
   const persist=()=>{
     const entries=Object.entries(cache).sort((a,b)=>(b[1].until||0)-(a[1].until||0)).slice(0,MAX_ENTRIES);
     for(const k of Object.keys(cache))delete cache[k];
     for(const [k,v] of entries)cache[k]=v;
     try{storage?.setItem(STORE,JSON.stringify(cache));}catch(_){}
   };
   async function resolve(track,opts={}){
     if(!track||!trackId(track))return null;
     const k=trackKey(track), cached=cache[k];
     if(cached&&cached.until>now())return cached.value||null;
     if(inFlight.has(k))return inFlight.get(k);
     const task=(async()=>{
       let value=null,hadError=false;
       try{
         const url=providerRequestUrl(opts.providerBase,opts.providerManifest,track);
         if(url){
           try{value=providerResult(await request(url),track);}
           catch(err){hadError=true;}
         }
         if(!value&&opts.useMusicBrainz!==false&&validIsrc(isrcOf(track))){
           try{value=await fromMusicBrainz(track,request);}
           catch(err){hadError=true;}
         }
       }catch(err){hadError=true;}
       cache[k]={value,until:now()+(value?POSITIVE_TTL:hadError?ERROR_TTL:NEGATIVE_TTL)};
       persist();return value;
     })().finally(()=>inFlight.delete(k));
     inFlight.set(k,task);
     return task;
   }
   function peek(track){if(!track)return null;const entry=cache[trackKey(track)];return entry?.until>now()?entry.value||null:null}
   function clear(){for(const k of Object.keys(cache))delete cache[k];persist()}
   return {resolve,peek,clear,trackKey,providerResult};
 }
 root.AuraVideoLinks={create,normalizeVideo,providerRequestUrl,providerResult,getYouTubeId,namesMatch,fromMusicBrainz};
 if(typeof module!=='undefined'&&module.exports)module.exports=root.AuraVideoLinks;
})(typeof window!=='undefined'?window:globalThis);
