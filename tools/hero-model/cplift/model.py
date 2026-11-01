import bpy,math,os
from pathlib import Path
from mathutils import Vector
P=Path(os.environ.get('HERO_OUTPUT_DIR',str(Path(__file__).resolve().parent)));P.mkdir(parents=True,exist_ok=True)
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
def mat(n,c,rough=.4,metal=0):
 m=bpy.data.materials.new(n);m.diffuse_color=(*c,1);m.use_nodes=True;s=m.node_tree.nodes.get('Principled BSDF');s.inputs['Base Color'].default_value=(*c,1);s.inputs['Roughness'].default_value=rough;s.inputs['Metallic'].default_value=metal;return m
black=mat('Black moulded housing',(.026,.031,.029),.36);panel=mat('Fine textured control panel',(.042,.047,.044),.52);red=mat('Red lift knob and tank collar',(.55,.018,.012),.28);white=mat('Translucent white chemistry bottle',(.73,.76,.72),.42);cap=mat('White ribbed bottle caps',(.91,.92,.87),.35);steel=mat('Steel shafts and control hardware',(.40,.45,.43),.23,.85);screen=mat('Dark glass temperature display',(.025,.045,.046),.15);ink=mat('Display muted blue',(.30,.58,.61),.3)
def finish(o,n,m,bev=0):
 o.name=n;o.data.materials.append(m)
 if bev:b=o.modifiers.new('Edge radii','BEVEL');b.width=bev;b.segments=3;o.modifiers.new('Weighted normals','WEIGHTED_NORMAL')
 return o
def box(n,loc,sz,m,bev=.04):
 bpy.ops.mesh.primitive_cube_add(size=1,location=loc);o=bpy.context.object;o.dimensions=sz;bpy.ops.object.transform_apply(location=False,rotation=False,scale=True);return finish(o,n,m,bev)
def cyl(n,loc,r,d,m,axis='Z'):
 bpy.ops.mesh.primitive_cylinder_add(vertices=64,radius=r,depth=d,location=loc);o=bpy.context.object
 if axis=='X':o.rotation_euler[1]=math.pi/2
 if axis=='Y':o.rotation_euler[0]=math.pi/2
 for p in o.data.polygons:p.use_smooth=True
 return finish(o,n,m,.015)
def rod(n,a,b,r,m):
 mid=(Vector(a)+Vector(b))/2;o=cyl(n,mid,r,(Vector(b)-Vector(a)).length,m);o.rotation_euler=(Vector(b)-Vector(a)).to_track_quat('Z','Y').to_euler();return o
def ring(w,h,r,z,n=10):
 pts=[]
 for cx,cy,st in [(w/2-r,h/2-r,0),(-w/2+r,h/2-r,90),(-w/2+r,-h/2+r,180),(w/2-r,-h/2+r,270)]:
  for i in range(n):a=math.radians(st+i*90/n);pts.append((cx+r*math.cos(a)+.35,cy+r*math.sin(a),z))
 return pts
rs=[ring(4.9,3.65,.28,.08),ring(5.25,3.85,.3,1.40),ring(5.14,3.74,.27,1.47),ring(4.94,3.54,.23,1.40),ring(4.62,3.24,.2,.28)]
vs=sum(rs,[]);N=len(rs[0]);fs=[]
for j in range(4):
 for i in range(N):a=j*N+i;b=j*N+(i+1)%N;fs.append((a,b,b+N,a+N))
fs+=[tuple(range(4*N,5*N)),tuple(reversed(range(N)))];me=bpy.data.meshes.new('Water bath hollow moulding');me.from_pydata(vs,[],fs);me.update();o=bpy.data.objects.new('Water bath',me);bpy.context.collection.objects.link(o);finish(o,'Water bath',black,.035)
box('Bath separator',(.5,-.23,.87),(3.9,.10,1.14),black,.025)
for y in [.42,1.14]:rod('Polished tank roller shaft',(-1.3,y,1.06),(2.38,y,1.06),.055,steel)
for x in [-.8,1.65]:
 for y in [.42,1.14]:box('Roller support',(x,y,1.12),(.18,.22,.22),white,.025)
verts=[(-2.9,-1.6,.05),(-1.45,-1.6,.05),(-1.45,.45,.05),(-2.9,.45,.05),(-2.9,-1.6,1.56),(-1.45,-1.6,1.56),(-1.45,.45,2.31),(-2.9,.45,2.31)]
me=bpy.data.meshes.new('Sloped control case');me.from_pydata(verts,[],[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]);me.update();o=bpy.data.objects.new('Sloped control case',me);bpy.context.collection.objects.link(o);finish(o,o.name,black,.075)
angle=math.atan(.75/2.05)
def control(n,x,y,w,h,m):
 z=1.56+(y+1.6)*.75/2.05+.035;o=box(n,(x,y,z),(w,h,.055),m,.035);o.rotation_euler[0]=angle;return o
control('Panel surface',-2.175,-.57,1.29,2.0,panel)
control('Digital thermometer bezel',-2.17,.02,1.07,.40,black);control('Temperature display glass',-2.34,.02,.61,.27,screen)
for i in range(2):
 for j in range(2):control('Thermostat tactile button',-1.87+i*.11,-.04+j*.12,.075,.07,steel)
for i,digit in enumerate(['2','2']):
 x=-2.55+i*.19;y=.02;z=1.56+(y+1.6)*.75/2.05+.088
 for dx,dy,w,h in [(0,.08,.09,.014),(.045,.04,.014,.07),(0,0,.09,.014),(-.045,-.04,.014,.07),(0,-.08,.09,.014)]:
  o=box('LCD segment',(x+dx,y+dy,z+dy*math.tan(angle)),(w,h,.006),ink,.002);o.rotation_euler[0]=angle
for x,y,r in [(-2.0,-.76,.15),(-1.65,-.72,.09),(-1.7,-.23,.075)]:
 z=1.56+(y+1.6)*.75/2.05+.09;o=cyl('Control knob / button',(x,y,z),r,.085,steel);o.rotation_euler[0]=angle
box('Lift column',(-2.12,1.02,1.38),(1.32,1.10,2.10),black,.08)
for loc,sz in [((-2.73,1.02,2.97),(.14,1.20,.18)),((-1.51,1.02,2.97),(.14,1.20,.18)),((-2.12,.49,2.97),(1.20,.14,.18)),((-2.12,1.55,2.97),(1.20,.14,.18))]:box('Funnel rim',loc,sz,panel,.025)
box('Deep funnel interior',(-2.12,1.02,2.45),(1.02,.89,.025),mat('Funnel cavity',(.006,.007,.006),.6),.045)
for loc,sz in [((-2.73,1.02,2.68),(.12,1.10,.55)),((-1.51,1.02,2.68),(.12,1.10,.55)),((-2.12,.49,2.68),(1.2,.12,.55)),((-2.12,1.55,2.68),(1.2,.12,.55))]:box('Funnel internal wall',loc,sz,black,.02)
rod('Lift lever',(-2.57,1.60,2.65),(-2.57,1.60,4.12),.055,black);bpy.ops.mesh.primitive_uv_sphere_add(segments=32,ring_count=16,radius=.19,location=(-2.57,1.60,4.15));finish(bpy.context.object,'Red ball lever handle',red)
cyl('Drive cog',(-1.37,.90,2.08),.36,.18,black,'X');cyl('Developing drum',(.10,.90,1.94),.60,2.56,black,'X')
for x in [-1.11,1.31]:cyl('Red collar',(x,.90,1.94),.63,.15,red,'X')
cyl('Tank end cover',(1.43,.90,1.94),.58,.10,black,'X')
for i in range(4):
 x=-.65+i*.86;y=-1.01
 box('White reagent bottle body',(x,y,.85),(.69,.72,1.12),white,.10)
 bpy.ops.mesh.primitive_cone_add(vertices=4,radius1=.44,radius2=.22,depth=.34,rotation=(0,0,math.pi/4),location=(x,y,1.54));finish(bpy.context.object,'Bottle shoulder',white,.06)
 cyl('Bottle neck',(x,y,1.75),.21,.20,white);cyl('Bottle cap',(x,y,1.88),.26,.20,cap)
 for j in range(24):a=j*2*math.pi/24;cyl('Cap grip',(x+.25*math.cos(a),y+.25*math.sin(a),1.88),.012,.16,cap)
o=box('Chemical thermometer',(-.64,-1.38,1.83),(.59,.11,.46),black,.025);box('Thermometer LCD',(-.64,-1.446,1.85),(.47,.015,.29),mat('Pale LCD',(.42,.49,.43),.4),.015)
def area(n,loc,power,size,color):
 bpy.ops.object.light_add(type='AREA',location=loc);o=bpy.context.object;o.name=n;o.data.energy=power;o.data.size=size;o.data.color=color;o.rotation_euler=(Vector((0,0,1.5))-o.location).to_track_quat('-Z','Y').to_euler()
area('Broad neutral studio key',(-4,-5,8),650,5,(1,.96,.90));area('Cool separation softbox',(4,3,7),700,4,(.82,.89,1));area('Front fill',(3,-6,4),220,4,(1,.95,.87))
bpy.ops.object.camera_add(location=(-7,-9,7));c=bpy.context.object;c.rotation_euler=(Vector((-.15,0,1.6))-c.location).to_track_quat('-Z','Y').to_euler();c.data.type='ORTHO';c.data.ortho_scale=8.0;s=bpy.context.scene;s.camera=c;s.world.color=(.12,.12,.12);s.render.engine='CYCLES';s.cycles.samples=int(os.environ.get('SAMPLES','128'));s.cycles.use_denoising=False;s.render.threads_mode='FIXED';s.render.threads=2;s.render.resolution_x=int(os.environ.get('SIZE','768'));s.render.resolution_y=s.render.resolution_x;s.render.resolution_percentage=100;s.render.film_transparent=True;s.render.image_settings.file_format='PNG';s.render.image_settings.color_mode='RGBA';s.view_settings.view_transform='Filmic';s.view_settings.look='Medium High Contrast';s.render.filepath=str(P/'cplift-front.png');bpy.ops.wm.save_as_mainfile(filepath=str(P/'cplift.blend'))
if os.environ.get('RENDER')=='1':bpy.ops.render.render(write_still=True)
