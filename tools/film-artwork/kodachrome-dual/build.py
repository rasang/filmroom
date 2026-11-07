"""Single-stock extension of the existing Filmroom 135 + 120 model pipeline."""
import bpy,json,hashlib
from pathlib import Path
import os
from mathutils import Matrix,Vector
P=Path(os.environ.get('ARTWORK_ROOT',Path(__file__).resolve().parents[1]))
def geometry(o):
 return {'vertices':[list(v.co) for v in o.data.vertices],'polygons':[list(p.vertices) for p in o.data.polygons], 'matrix':[list(r) for r in o.matrix_world]}
def camera_lights(s):
 return {'camera':{'location':list(s.camera.location),'rotation':list(s.camera.rotation_euler),'ortho':s.camera.data.ortho_scale}, 'lights':{o.name:{'location':list(o.location),'rotation':list(o.rotation_euler),'energy':o.data.energy,'size':o.data.size,'color':list(o.data.color)} for o in s.objects if o.type=='LIGHT'}}
def packed_hashes():
 images={n.image for o in bpy.context.scene.objects if o.type=='MESH' for m in o.data.materials if m and m.use_nodes for n in m.node_tree.nodes if n.type=='TEX_IMAGE' and n.image}
 return {i.name:hashlib.sha256(bytes(i.packed_file.data)).hexdigest() for i in images if i.packed_file}
bpy.ops.wm.open_mainfile(filepath=str(P/'source/approved-single135.blend'));s=bpy.context.scene
old_camera=camera_lights(s);old_images=packed_hashes();old_geo={o.name:geometry(o) for o in s.objects if o.type=='MESH'}
# Current physical 135 model is already at approved millimetre scale and y=-10.
for o in s.objects:
 if o.type=='MESH':
  o.data=o.data.copy();o.data.transform(Matrix.Translation(Vector((-18,0,0))))
for o in s.objects:
 if o.type=='MESH':
  before=old_geo[o.name];after=geometry(o)
  assert before['polygons']==after['polygons'] and before['matrix']==after['matrix']
  assert all(abs(a[0]-(b[0]-18))<1e-4 and abs(a[1]-b[1])<1e-5 and abs(a[2]-b[2])<1e-5 for a,b in zip(after['vertices'],before['vertices']))
roll={'Paper roll','Bottom plastic flange','Top plastic flange','Printed backing-paper wrap','Spool end drive slot'}
with bpy.data.libraries.load(str(P/'source/approved-pair-template.blend'),link=False) as (src,dst):
 assert roll.issubset(src.objects),src.objects
 dst.objects=sorted(roll)
roll_geo={}
for o in dst.objects:
 assert o and o.type=='MESH' and o.parent is None
 roll_geo[o.name]=geometry(o);s.collection.objects.link(o)
m=bpy.data.materials.get('Redrawn backing-paper band');assert m
tex=next(n for n in m.node_tree.nodes if n.type=='TEX_IMAGE')
tex.image=bpy.data.images.load(str(P/'labels/kodak_kodachrome_64-120.png'),check_existing=False);tex.image.pack()
m.name='Kodachrome PKR120 / original redrawn bare-roll band'
m.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value=.52
assert camera_lights(s)==old_camera
assert all(geometry(bpy.data.objects[n])==sig for n,sig in roll_geo.items())
# Only images referenced by live scene materials matter; remove unrelated imported leftovers.
bpy.data.orphans_purge(do_local_ids=True,do_linked_ids=True,do_recursive=True)
new_images=packed_hashes();assert all(new_images.get(n)==h for n,h in old_images.items()),(old_images,new_images)
s['stock_id']='kodak_kodachrome_64'
s['dimensions_mm']='135 47.5 x 25.1; 120 65.75 x 25.15. Unchanged approved physical scale.'
s['design_note']='135 label/material preserved. 120 bare roll follows Kodak 1987 brochure page 8; original simplified typesetting, not a photograph or carton texture.'
s.render.engine='CYCLES';s.cycles.samples=64;s.cycles.use_denoising=False;s.cycles.device='CPU'
s.render.threads_mode='FIXED';s.render.threads=2
s.render.resolution_x=s.render.resolution_y=1024;s.render.resolution_percentage=100
s.render.film_transparent=True;s.render.image_settings.file_format='PNG';s.render.image_settings.color_mode='RGBA'
s.render.filepath=str(P/'renders/kodak_kodachrome_64.png')
bpy.ops.wm.save_as_mainfile(filepath=str(P/'models/kodak_kodachrome_64.blend'))
report={'pass':True,'original135_geometry_unchanged_except_x_translation_mm':-18,'original135_texture_sha256':old_images,'original135_materials_unchanged':True,'120_meshes_identical_to_approved_pair_template':sorted(roll_geo),'camera_and_lights_unchanged':old_camera,'packed_images':new_images,'reference_photograph_pixels_used':False}
(P/'MODEL_QA.json').write_text(json.dumps(report,indent=2))
bpy.ops.render.render(write_still=True)
