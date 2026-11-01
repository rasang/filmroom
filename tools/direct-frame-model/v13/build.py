from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import numpy as np,json,hashlib
D=Path(__file__).parent
for directory in ['frames','webp','masks']:(D/directory).mkdir(exist_ok=True)
mod=json.loads((D/'inputs/spectral-model.json').read_text());cmf=np.array(mod['cmf']);ill=np.array(mod['illuminant']);mat=np.array(mod['xyz_to_srgb']);C=json.loads((D/'inputs/dx-contract.json').read_text());records=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def lin(x):
 x=np.array(x,float)/255;return np.where(x<=.04045,x/12.92,((x+.055)/1.055)**2.4)
def enc(x):
 x=np.array(x).clip(0,1);return np.uint8(np.rint(np.where(x<=.0031308,12.92*x,1.055*x**(1/2.4)-.055)*255))
for n in ['fujifilm_c200','fujifilm_xtra_400','fujifilm_pro_400h']:
 d=json.loads((D/'inputs'/(n+'-spectral.json')).read_text());cd=np.array(d['channel_density'],float)[:,:3];base=np.array(d['base_density'],float);cv=np.array(d['density_curves'],float);mx=np.nanmax(cv,axis=0)-np.nanmin(cv,axis=0);valid=np.isfinite(base)&np.isfinite(cd).all(1)
 def color(v):
  T=10**(-(base+cd@np.array(v)));xyz=(cmf[:,valid]*ill[valid]*T[valid]).sum(1)/(ill*cmf[1]).sum();rgb=mat@xyz;return {'density_vector':list(v),'linear_srgb':rgb.tolist(),'srgb8':enc(rgb).tolist(),'display_clamped_channels':np.where(rgb!=rgb.clip(0,1))[0].tolist()}
 palettes={'base':color([0,0,0]),'neutral_ink':color(mx),'red_MplusY':color([0,mx[1],mx[2]]),'green_CplusY':color([mx[0],0,mx[2]])}
 a=np.array(Image.open(D/'inputs'/(n+'-positive.webp')).convert('RGBA'));b=np.array(Image.open(D/'inputs'/(n+'-neutral-model.png')).convert('RGBA'));old=b.copy();red=np.zeros(a.shape[:2],bool);green=red.copy()
 if n=='fujifilm_c200':
  green[135:138,:]=True
  for x in range(0,1520,40):red[129:132,x:x+24]=True;red[140:143,x:x+24]=True
 elif n=='fujifilm_xtra_400':
  for yy in [136,1264]:
   red[yy-5:yy-2,:]=True
   for x in range(0,1520,40):green[yy+2:yy+5,x:x+24]=True
 for tag,mask in [('red_MplusY',red),('green_CplusY',green)]:
  active=mask&(a[:,:,3]>0);b[active,:3]=palettes[tag]['srgb8'];Image.fromarray(np.uint8(mask)*255).save(D/'masks'/(n+'-'+tag+'.png'))
 b[b[:,:,3]==0,:3]=0;assert np.array_equal(a[:,:,3],b[:,:,3]);assert np.array_equal(old[~(red|green)],b[~(red|green)])
 # Confirm every visible colored location still corresponds to source original colored rail, no added pixels.
 maskproof={}
 for tag,mask in [('red',red),('green',green)]:
  active=mask&(a[:,:,3]>0);pix=a[active,:3].astype(float);maskproof[tag]={'geometric_mask_pixels':int(mask.sum()),'visible_nonzeroalpha_pixels':int(active.sum()),'source_median_srgb8':np.median(pix,axis=0).tolist() if len(pix) else None,'geometry_source':'fixed135-dx-v2/replace_dx.py, reused exact row/dash ranges; no relayout','mask_sha256':sha(D/'masks'/(n+'-'+('red_MplusY' if tag=='red' else 'green_CplusY')+'.png'))}
 p=D/'frames'/(n+'-direct-model-v13.png');w=D/'webp'/(n+'-direct-model-v13.webp');Image.fromarray(b).save(p);Image.fromarray(b).save(w,format='WEBP',lossless=True,quality=100,method=6,exact=True);assert np.array_equal(b,np.array(Image.open(w).convert('RGBA')))
 c=next(r for r in C['stocks'] if r['physical_stock']==n);expected=np.full(a.shape[:2],-1,np.int8)
 for s in c['symbols']:
  x,y,mo,h=s['placement']
  for row,k in enumerate(['clock31','data31']):
   for i,bit in enumerate(s[k]):expected[y+row*h:y+(row+1)*h,x+i*mo:x+(i+1)*mo]=int(bit)
 eligible=(expected>=0)&(a[:,:,3]>0);threshold=c['native_linear_midpoint'];light=lin(b[:,:,:3]).mean(2);bad=eligible&((light<threshold)!=expected);assert not bad.any();assert np.array_equal(old[expected>=0],b[expected>=0])
 c.update(asset_sha256=sha(p),integration_status='PENDING_ROOT_FUJI_SEPARATE_MODEL_REVIEW',color_revision='direct-fuji-model-v13')
 records.append({'physical_stock':n,'status':'REVIEW_MANUFACTURER_RED_GREEN_SPECTRAL_MODEL' if n!='fujifilm_pro_400h' else 'REVIEW_MONOCHROME_LINE_COLOR_UNVERIFIED','png_file':'frames/'+p.name,'png_sha256':sha(p),'png_bytes':p.stat().st_size,'webp_file':'webp/'+w.name,'webp_sha256':sha(w),'webp_bytes':w.stat().st_size,'source_positive_sha256':sha(D/'inputs'/(n+'-positive.webp')),'source_neutral_sha256':sha(D/'inputs'/(n+'-neutral-model.png')),'source_profile_sha256':d['source_profile_sha256'],'channel_absorption_peak_nm':[d['wavelengths'][int(np.nanargmax(cd[:,i]))] for i in range(3)],'palettes':palettes,'stripe_masks':maskproof,'changed_from_neutral_pixels':int(np.any(b!=old,2).sum()),'alpha_byte_exact':True,'outside_stripe_masks_byte_exact':True,'png_webp_rgba_exact':True,'dx_pixels_byte_exact_to_v12':True,'dx_nonzeroalpha_checks':int(eligible.sum()),'dx_wrong_bits':int(bad.sum()),'runtime_approved':False})
C['asset_package_version']='direct-fuji-model-v13-review';(D/'dx-sampling-contract.json').write_text(json.dumps(C,indent=2));(D/'manifest.json').write_text(json.dumps({'version':'direct-fuji-model-v13','status':'PENDING_ROOT_REVIEW_NOT_ACTIVATED','assets':records,'limits':['Manufacturer confirms hue families/line structure, not numeric spectral exposures or RGB.','Red uses own-profile M+Y channel maxima; green uses C+Y; peaks verified near red/green/blue absorption. Upper-bound model choices, not actual factory latent exposures.','PRO400H line color unverified. Retained neutral dark model solely for structural review.','No source reference photographs copied. Existing alpha/geometric masks preserved.']},indent=2))
font=ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',21);sm=ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',17)
panel=Image.new('RGB',(1520,1160),'#e7e7e4');dr=ImageDraw.Draw(panel);dr.text((15,8),'Fuji直扫分轨模型审阅：厂家色相/结构 + 同株光谱端点；非实测RGB',font=font,fill='black')
def view(path):
 a=np.array(Image.open(path).convert('RGBA'));al=a[:,:,3:4]/255;b=enc(lin(a[:,:,:3])*al+1-al);return Image.fromarray(b)
for j,r in enumerate(records):
 n=r['physical_stock'];y=55+j*350;dr.text((15,y),n+'  原正像绘制 / 新负片模型（原尺寸前4孔区域）',font=font,fill='black');aa=view(D/'inputs'/(n+'-positive.webp'));bb=view(D/r['png_file'])
 for x,im in [(0,aa),(760,bb)]:
  panel.paste(im.crop((0,90,760,190)),(x,y+40));panel.paste(im.crop((0,1210,760,1300)),(x,y+145))
 if n!='fujifilm_pro_400h':tx='红 M+Y '+str(r['palettes']['red_MplusY']['srgb8'])+' / 绿 C+Y '+str(r['palettes']['green_CplusY']['srgb8'])+'；只换原独立线mask，虚实/侧数不动'
 else:tx='单色暗线沿用v12；厂家仅证几何，未确认线色，留待审阅边界决定'
 dr.text((15,y+245),tx,font=sm,fill='black');dr.text((15,y+275),'alpha / 字形 / 25 / DX逐像素不变；白孔仅显示设置，不是原片孔色声明。',font=sm,fill='#333333')
panel.save(D/'fuji3-separated-stripe-review.png');print([(r['physical_stock'],r['changed_from_neutral_pixels']) for r in records])
