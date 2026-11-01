from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import numpy as np,json,hashlib,shutil
D=Path(__file__).parent
for directory in ['frames','webp','masks']:(D/directory).mkdir(exist_ok=True)
E=json.loads((D/'inputs/all-negative-endpoints.json').read_text());C=json.loads((D/'inputs/source-dx-contract.json').read_text());cm={x['physical_stock']:x for x in C['stocks']};records=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def lin(x):
 x=np.asarray(x,dtype=float)/255;return np.where(x<=.04045,x/12.92,((x+.055)/1.055)**2.4)
def enc(x):return np.uint8(np.rint(np.clip(np.where(x<=.0031308,12.92*x,1.055*np.maximum(x,0)**(1/2.4)-.055),0,1)*255))
def show(a,size):
 # linear white-hole matte; checker aperture is purely review display.
 aa=a[:,:,3:4]/255;b=enc(lin(a[:,:,:3])*aa+1-aa); yy,xx=np.indices((960,1440));b[220:1180,40:1480]=np.where((((xx//80)+(yy//80))%2)[...,None],195,175);return Image.fromarray(b).resize(size,Image.Resampling.BOX)
for e in E:
 n=e['stock']
 if '_push' in n:continue
 filename=n+('-font-v11' if n=='kodak_portra_800' else '')+'.webp'; src=D/'inputs'/filename
 assert src.is_file(), "Missing immutable artwork input"
 a=np.array(Image.open(src).convert('RGBA'));bg=np.median(a[250:1150,:35,:3].reshape(-1,3),axis=0);expected=np.full(a.shape[:2],-1,dtype=np.int8)
 if n in cm:
  for s in cm[n]['symbols']:
   x,y,w,h=s['placement']
   for r,k in enumerate(['clock31','data31']):
    for i,bit in enumerate(s[k]):expected[y+r*h:y+(r+1)*h,x+i*w:x+(i+1)*w]=int(bit)
  fg=np.median(a[(expected==1)&(a[:,:,3]==255),:3],axis=0)
 else:
  rails=np.concatenate([a[:80,:,:3].reshape(-1,3),a[1320:,:,:3].reshape(-1,3)]);l=rails.mean(1);fg=np.median(rails[l>=np.percentile(l,97)],axis=0)
 vec=lin(fg)-lin(bg);t=np.clip(np.sum((lin(a[:,:,:3])-lin(bg))*vec,axis=2)/np.dot(vec,vec),0,1);t[expected>=0]=expected[expected>=0]
 rawbase=np.array(e['base_minimum']['linear_srgb']);rawink=np.array(e['maximum_density_bound']['linear_srgb']);base=rawbase.clip(0,1);ink=rawink.clip(0,1)
 b=a.copy();b[:,:,:3]=enc(base*(1-t[:,:,None])+ink*t[:,:,None]);b[b[:,:,3]==0,:3]=0
 assert np.array_equal(a[:,:,3],b[:,:,3]);assert not b[b[:,:,3]==0,:3].any()
 p=D/'frames'/(n+'-direct-model-v12.png');w=D/'webp'/(n+'-direct-model-v12.webp');Image.fromarray(b).save(p);Image.fromarray(b).save(w,format='WEBP',lossless=True,quality=100,method=6,exact=True);assert np.array_equal(b,np.array(Image.open(w).convert('RGBA')))
 eligible=(expected>=0)&(a[:,:,3]>0);threshold=float((base.mean()+ink.mean())/2);light=lin(b[:,:,:3]).mean(2);bad=eligible&((light<threshold)!=expected);assert not bad.any(),n
 blocked=n.startswith('fujifilm_');r={'physical_stock':n,'status':'BLOCKED_COLORED_STRIPES_COLLAPSED_BY_TWO_ENDPOINT_MODEL' if blocked else 'REVIEW_ONLY_MODEL_DMIN_CHANNELWISE_DMAX','source_file':'inputs/'+filename,'source_sha256':sha(src),'png_file':'frames/'+p.name,'png_sha256':sha(p),'png_bytes':p.stat().st_size,'webp_file':'webp/'+w.name,'webp_sha256':sha(w),'webp_bytes':w.stat().st_size,'rgba_equal_png_webp':True,'alpha_byte_exact':True,'alpha0_rgb_zero':True,'pixel_geometry':[1520,1400],'aperture':[40,220,1480,1180],'text_custom25_dx_positions_unmoved':True,'source_material_rgb_anchor':bg.tolist(),'source_ink_rgb_anchor':fg.tolist(),'base_model_linear_srgb':rawbase.tolist(),'channelwise_dmax_model_linear_srgb':rawink.tolist(),'base_display_srgb8':enc(base).tolist(),'ink_display_srgb8':enc(ink).tolist(),'display_clamped_components':{'base':np.where(rawbase!=base)[0].tolist(),'ink':np.where(rawink!=ink)[0].tolist()},'dx_nonzero_alpha_checks':int(eligible.sum()),'dx_wrong_bits':int(bad.sum()),'dx_threshold_linear_mean':threshold,'model_limits':['D65 profile model, not calibrated rawscan or factory printing exposure.','Dmax combines channelwise maxima, not necessarily one common exposure.','Continuous soft coverage derived from existing artwork RGB, not original factory density layer.','Original texture only via scalar coverage; original hue is not preserved.'],'runtime_approved':False}
 if blocked:r['model_limits'].append('RED/GREEN stripe chroma lost. May compress weak colored line contrast; do not integrate this draft. Requires independent multichannel evidence/model.')
 r['model_input_hashes']={Path(k).name:v for k,v in e['input_sha256'].items()}
 if n=='fujifilm_pro_400h':r['model_limits'][-1]='Current own artwork has light single-line rail, not the red/green pair. Position/contrast remains, but stripe-specific dye/exposure is unverified; conservatively blocked alongside Fuji pending separate review.'
 records.append(r)
 if n in cm:cm[n].update(asset_sha256=sha(p),bit1_polarity='darker_than_material',native_linear_midpoint=threshold,color_revision='direct-negative-model-v12',integration_status=r['status'])
 print(n,r['status'],flush=True)
font=ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',21);sm=ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',16)
panel=Image.new('RGB',(1824,2110),'#e7e7e4');dr=ImageDraw.Draw(panel);dr.text((20,12),'14负片逐profile直扫模型审阅：Dmin片基 / 逐通道Dmax上界字迹（非工厂实测）',font=font,fill='black')
for i,r in enumerate(records):
 x=(i%4)*456;y=65+(i//4)*505;dr.text((x+8,y),r['physical_stock'],font=sm,fill='black');a=np.array(Image.open(D/r['png_file']));panel.paste(show(a,(440,405)),(x+8,y+28));dr.text((x+8,y+438),str(r['base_display_srgb8'])+' → '+str(r['ink_display_srgb8']),font=sm,fill='black');dr.text((x+8,y+464),'暂缓：彩色轨丢色相' if r['status'].startswith('BLOCKED') else '离线模型示意 / 未启用',font=sm,fill='#b02020' if r['status'].startswith('BLOCKED') else '#333333')
panel.save(D/'14-stock-model-review.png')
fp=Image.new('RGB',(1520,810),'#e7e7e4');dr=ImageDraw.Draw(fp);dr.text((15,8),'Fuji三款阻塞：原红绿轨 → 双端点失去独立色相，不能直接验收',font=font,fill='#a02020')
for j,r in enumerate([r for r in records if r['status'].startswith('BLOCKED')]):
 y=55+250*j;dr.text((15,y),r['physical_stock']+'  原轨 / 模型投影（保位置不等于保颜色）',font=sm,fill='black');a=np.array(Image.open(D/r['source_file']));b=np.array(Image.open(D/r['png_file']));fp.paste(show(a,(760,700)).crop((0,0,760,110)),(0,y+28));fp.paste(show(b,(760,700)).crop((0,0,760,110)),(760,y+28));fp.paste(show(a,(760,700)).crop((0,625,760,700)),(0,y+140));fp.paste(show(b,(760,700)).crop((0,625,760,700)),(760,y+140))
fp.save(D/'fuji3-original-vs-two-endpoint-BLOCKED.png');C['stocks']=list(cm.values());C['asset_package_version']='direct-negative-model-v12-review';(D/'dx-sampling-contract.json').write_text(json.dumps(C,indent=2));(D/'manifest.json').write_text(json.dumps({'version':'direct-negative-model-v12','status':'11 monochrome review; 3 Fuji BLOCKED, no runtime activation','profiles':{'kodak_portra_800_push1':'kodak_portra_800','kodak_portra_800_push2':'kodak_portra_800'},'assets':records,'endpoint_source_sha256':sha(D/'inputs/all-negative-endpoints.json')},indent=2))
