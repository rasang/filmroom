"""Run only in a released render window: blender -b --python tools/paper-artwork/render.py.
Reuses approved film artwork camera/light rig; original hand-built carton/can meshes.
"""
import bpy,json,os,math
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parents[2]
T=Path(os.environ.get('FILMROOM_ARTWORK_TEMPLATE','/workspace/shared/filmroom-kodachrome-thumbnail-review/source/approved-single135.blend'))
items=json.loads((R/'tools/paper-artwork/models.json').read_text())
out=R/'tools/paper-artwork/renders';out.mkdir(exist_ok=True)
def mat(name,rgba,metal=0):
 m=bpy.data.materials.new(name);m.diffuse_color=(*rgba,1);m.use_nodes=True;n=m.node_tree.nodes['Principled BSDF'];n.inputs['Base Color'].default_value=(*rgba,1);n.inputs['Roughness'].default_value=.62;n.inputs['Metallic'].default_value=metal;return m
def box(name,loc,dim,m):
 bpy.ops.mesh.primitive_cube_add(size=1,location=loc);o=bpy.context.object;o.name=name;o.dimensions=dim;bpy.ops.object.transform_apply(location=False,rotation=False,scale=True);o.data.materials.append(m);b=o.modifiers.new('Carton soft fold','BEVEL');b.width=.45;b.segments=3;o.modifiers.new('Corner normals','WEIGHTED_NORMAL');return o
for item in items:
 bpy.ops.wm.open_mainfile(filepath=str(T));s=bpy.context.scene
 for o in list(s.objects):
  if o.type=='MESH':bpy.data.objects.remove(o,do_unlink=True)
 # Keep approved camera, lighting, display transform. Contents normalized to thumbnail scale.
 bpy.ops.object.light_add(type='AREA',location=(0,-40,220));fill=bpy.context.object;fill.name='Paper top fill';fill.data.energy=400000;fill.data.shape='DISK';fill.data.size=160
 h=item['color'].lstrip('#');c=tuple(int(h[i:i+2],16)/255 for i in (0,2,4));body=mat('Matte paperboard',c)
 w,d,h=(56,7,64) if item['shape']=='flat' else (56,32,55)
 if item['shape']=='long':w,d,h=(45,40,60)
 if item['shape']=='can':
  bpy.ops.mesh.primitive_cylinder_add(vertices=96,radius=33,depth=9,location=(0,0,34),rotation=(math.pi/2,0,0));bpy.context.object.data.materials.append(mat('Historical metal can',(.4,.4,.38),.65));w,h=60,60;d=9
 else:
  box('Light-tight carton',(0,0,h/2),(w,d,h),body)
  box('Lid fold',(0,0,h+.2),(w+.45,d+.45,2),body)
 # Front label is original typography, not a source photo.
 m=mat('Redrawn product label',(1,1,1));n=m.node_tree.nodes.new('ShaderNodeTexImage');n.image=bpy.data.images.load(str(R/'tools/paper-artwork/labels'/f"{item['id']}.png"));m.node_tree.links.new(n.outputs['Color'],m.node_tree.nodes['Principled BSDF'].inputs['Base Color'])
 y=-d/2-.6;z=34 if item['shape']=='can' else h/2
 if item['shape']=='can':
  verts=[(0,y,z)]+[(29*math.cos(i*math.tau/96),y,z+29*math.sin(i*math.tau/96)) for i in range(96)]
  faces=[(0,i+1,(i+1)%96+1) for i in range(96)]
 else:
  verts=[(-w/2,y,z-h/2),(w/2,y,z-h/2),(w/2,y,z+h/2),(-w/2,y,z+h/2)];faces=[(0,1,2,3)]
 mesh=bpy.data.meshes.new('Label plane');mesh.from_pydata(verts,[],faces);mesh.uv_layers.new()
 for loop in mesh.loops:
  v=verts[loop.vertex_index];mesh.uv_layers.active.data[loop.index].uv=((v[0]/w)+.5,((v[2]-z)/h)+.5)
 o=bpy.data.objects.new('Product face',mesh);s.collection.objects.link(o);mesh.materials.append(m)
 # Slight shared turn exposes package depth while keeping readable face.
 for o in list(s.objects):
  if o.type=='MESH':o.rotation_euler.z+=math.radians(-12)
 s.render.engine='CYCLES';s.cycles.samples=16;s.cycles.use_denoising=False;s.cycles.device='CPU';s.render.threads_mode='FIXED';s.render.threads=1
 s.render.resolution_x=s.render.resolution_y=384;s.render.resolution_percentage=100;s.render.film_transparent=True;s.render.image_settings.file_format='PNG';s.render.image_settings.color_mode='RGBA';s.render.filepath=str(out/(item['id']+'.png'))
 bpy.ops.render.render(write_still=True)
