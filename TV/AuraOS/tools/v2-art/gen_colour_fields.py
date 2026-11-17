"""Interim colour-field placeholders derived from the approved mockups (median-downsampled, text/UI masked). Not final artwork."""
import numpy as np, os, sys
from PIL import Image, ImageFilter
import pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2]  # AuraOS/
R=str(ROOT/'references/v2')+'/'
OUT=str(ROOT/'tv/app/src/main/res/drawable-nodpi')+'/'
os.makedirs(OUT,exist_ok=True)
def load(n): return np.asarray(Image.open(R+n).convert('RGB')).astype(np.float32)
def field(img,box,cells,mask=None,fill_top=None):
    x0,y0,x1,y1=box; w,h=cells; reg=img[y0:y1,x0:x1].copy()
    H,W=reg.shape[:2]
    out=np.zeros((h,w,3),np.float32); ok=np.ones((h,w),bool)
    for j in range(h):
        for i in range(w):
            ya,yb=int(j*H/h),max(int((j+1)*H/h),int(j*H/h)+1); xa,xb=int(i*W/w),max(int((i+1)*W/w),int(i*W/w)+1)
            out[j,i]=np.median(reg[ya:yb,xa:xb].reshape(-1,3),axis=0)
            if mask:
                gx=x0+(i+.5)*W/w; gy=y0+(j+.5)*H/h
                for (mx0,my0,mx1,my1) in mask:
                    if mx0<=gx<=mx1 and my0<=gy<=my1: ok[j,i]=False
    # inpaint masked cells: horizontal interpolation per row between nearest valid cells
    for j in range(h):
        row_ok=ok[j]
        if row_ok.all(): continue
        idx=np.where(row_ok)[0]
        if len(idx)==0: continue
        for c in range(3): out[j,:,c]=np.interp(np.arange(w),idx,out[j,idx,c])
    # fully masked rows (top nav): copy nearest row that had valid cells
    full=[j for j in range(h) if not ok[j].any()]
    valid=[j for j in range(h) if ok[j].any()]
    for j in full:
        k=min(valid,key=lambda v:abs(v-j)); out[j]=out[k]
    if mask and w>=100:
        out=np.asarray(Image.fromarray(np.clip(out,0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.1))).astype(np.float32)
    return Image.fromarray(np.clip(out,0,255).astype(np.uint8))
def save(im,name,size=None):
    im.save(OUT+name+'.png',optimize=True); print(name,im.size,os.path.getsize(OUT+name+'.png'))
# heroes: full width region incl. area behind top nav; nav row masked
HW,HH=192,54
heroes={
 'art_hero_silver_realm':('v2-08-home-silver-realm.png',(0,0,1672,470),[(0,0,1672,66),(0,66,88,470),(765,412,905,445),(110,95,610,440)]),
 'art_hero_last_voyage':('v2-07-movies-last-voyage.png',(0,0,1672,470),[(0,0,1672,66),(0,66,88,470),(765,412,905,445),(110,85,640,380)]),
 'art_hero_northern_crown':('v2-06-tvshows-northern-crown.png',(0,0,1672,470),[(0,0,1672,66),(0,66,88,470),(765,412,905,445),(110,85,560,370)]),
 'art_hero_aurora_concert':('v2-04-livetv-aurora-concert.png',(0,0,1672,470),[(0,0,1672,66),(0,66,88,470),(765,412,905,445),(110,65,620,365)]),
 'art_hero_velvet_horizon':('v2-02-auramp-velvet-horizon.png',(0,0,1672,470),[(0,0,1672,66),(0,66,88,470),(765,412,905,445),(110,80,700,385)]),
}
for n,(f,b,m) in heroes.items(): save(field(load(f),b,(HW,HH),m),n)
home=load('v2-08-home-silver-realm.png')
cx=[119,399,677,955,1234]; cw=[269,268,267,267,267]
cont=['orbital','riverland','contender','duneward','ledger']
for i,s in enumerate(cont): save(field(home,(cx[i],493,cx[i]+cw[i],587),(48,17)),'card_'+s)   # bottom 26px (progress + label) excluded
lx=[119,341,556,771,987,1202]
lat=['ember_falls','north_star','last_voyage','hollow_crown','faultline','verdigris']
tre=['crimson_tide','skylight','archive','mirage','iron_vale','circuit_9']
for i,s in enumerate(lat): save(field(home,(lx[i],668,lx[i]+206,760),(40,18)),'card_'+s)
for i,s in enumerate(tre): save(field(home,(lx[i],817,lx[i]+206,913),(40,19)),'card_trending_'+s)
save(field(home,(1445,808,1498,860),(16,16)),'art_album_through_the_mist')

# ---- Stage 2-6 assets (other pages) ----
def row(img, names, xs, w, y0, y1, prefix='card_'):
    c=(max(8,w//6), max(6,(y1-y0)//6))
    for n,x in zip(names,xs): save(field(img,(int(x),y0,int(x)+w,y1),c),prefix+n)
mv=load('v2-07-movies-last-voyage.png'); tv=load('v2-06-tvshows-northern-crown.png')
row(mv,['silver_realm'],[119],245,579,670); row(mv,['emerald_plains'],[1371],233,835,918)
row(tv,['iron_veil','marshlight','crown_of_ashes','sentinels','bloodhollow','eastwater'],[124,376,629,881,1133,1385],242,580,665)
amp=load('v2-02-auramp-velvet-horizon.png')
row(amp,['amp_velvet_horizon','amp_mistline','amp_afterglow','amp_tidal_memories','amp_solace','amp_late_circles'],[121+252*i for i in range(6)],239,487,578)
row(amp,['amp_dreamfall','amp_stratos','amp_ember_waves','amp_lunar_tide','amp_hollow_lights','amp_northstar'],[126,353,573,795,1017,1237],215,656,744)
row(amp,['amp_glass_garden','amp_wide_awake','amp_archive_sessions','amp_mirage_deluxe','amp_city_of_echoes'],[126,384,640,895,1148],245,802,922)
box=load('v2-03-aurabox-silver-realm-detail.png')
save(field(box,(132,142,331,454),(33,52)),'art_poster_silver_realm')
for n,x in zip(['ethan','lena','marcus','adrian','elara'],[132,393,657,921,1184]): save(field(box,(x,517,x+118,589),(20,12)),'art_cast_'+n)
save(field(box,(0,0,1672,470),(HW,HH),[(0,0,1672,66),(0,66,88,470),(110,80,790,470)]),'art_hero_aurabox')
live=load('v2-04-livetv-aurora-concert.png')
row(live,['news','entertainment','movies','sports','kids','music'],[126,384,637,891,1145,1399],243,423,522,'art_channel_')
sp=load('v2-05-sports-center.png')
save(field(sp,(0,0,1672,380),(HW,HH),[(0,0,1672,66),(0,66,88,380),(110,70,790,370),(780,330,900,365)]),'art_hero_sports')
row(sp,['football','basketball','nfl'],[124,437,744],299,826,921,'art_upcoming_')
