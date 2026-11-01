import bpy,os
from pathlib import Path
from mathutils import Vector
P=Path(os.environ.get('HERO_OUTPUT_DIR',str(Path(__file__).resolve().parent)))
bpy.ops.wm.open_mainfile(filepath=str(P/'cplift.blend'));s=bpy.context.scene;s.camera.location=(7,-6,5.5);s.camera.rotation_euler=(Vector((-.15,0,1.6))-s.camera.location).to_track_quat('-Z','Y').to_euler();s.cycles.samples=64;s.render.resolution_x=512;s.render.resolution_y=512;s.render.filepath=str(P/'cplift-side.png');bpy.ops.render.render(write_still=True)
