# CP-Lift welcome hero — recovered source

Reconstructed from the retained complete modeling-script conversation on 2026-11-01 after the executor replacement deleted the old workspace. Not a recovered byte-for-byte original .blend: generated afresh by Blender4.3.2 using the same original geometry, material values, camera, lighting, Cycles128 samples/768px and alpha crop. Visually compared with the approved Library preview libfile_e89756b5093881919cfb94086aa15636. No screenshot was cropped into a production asset.

References: https://reflxlab.com/products/thd-cplift-semi-automatic-film-processor-free-shipping and its CP-Lift manufacturer manual. Reference photos are not included. This is an original illustrative model, not official THD advertising or an engineering replica. Logos and small labels omitted. Black non-metallic housing/tank, white reagent bottles, red control/collars; only shafts/knobs metallic.

Reproduce: `RENDER=1 SAMPLES=128 SIZE=768 HERO_OUTPUT_DIR=/tmp/cplift-output blender -b -t 2 --python model.py`. No external textures. `side.py` optionally renders the opposite side. Run `python3 export_asset.py /tmp/cplift-output/cplift-front.png ../../../app/src/main/res/drawable-nodpi/welcome_cplift.png` with Pillow. Export performs only lossless alpha-bounds crop padded4% each side. Result688pxRGBA; existing ImageView76dp portrait/40dp landscape unchanged.

Integration only changes the welcome ImageView resource. ic_brand and launcher unchanged. Validation gate to re-run: WelcomeHeroHostTest (2), EmptyHomeLandscapeHostTest (1), EmptyHomeInformationHostTest (3). The prior environment passed6/6 before replacement; those historical results do NOT certify this recovered tree. Current recovery phase does not run Gradle while the codec owner holds the build window.
