#!/usr/bin/env python3
"""Opt-in Adobe DNG SDK 1.7.1 resize oracle; no SDK sources are redistributed.

Requires an independently obtained SDK and its built .o files, plus Filmroom's
libjxl decoder prefix. Example (paths are deliberately local/user supplied):
  python3 codecs/tests/dng_scale_sdk_oracle.py --sdk-source /path/dng_sdk/source \
    --sdk-objects /path/sdk-objects --jxl-prefix /path/jxl/prefix \
    --rustc /path/rustc --output /tmp/scale-oracle-results.json

The object files must have the same feature defines as this wrapper: qLinux=1,
qDNGUseXMP=0, qDNGUseLibJPEG=0, qDNGThreadSafe=0. Build them from the source whose
SHA256 values the output records. This compares only SDK ResampleImage with the
Rust resampler, not full DNG decoding, metadata, color, or orientation.
The SDK renderer itself BYPASSES ResampleImage when source/target sizes match;
ResampleImage called explicitly at equal sizes still clips. Integration tests
must separately check that bypass and preserve over-white pixels.
"""
import argparse, hashlib, json, pathlib, random, struct, subprocess, tempfile

# Recorded 1.7.1 source snapshot used for the committed golden. The run below
# was 100 random cases (seed 971) + 7 fixed cases (seed 7), max absolute error
# 0.0, differing float values 0. These are source hashes, not an archive/git SHA.
REFERENCE_SOURCE_SHA256 = {
    "dng_resample.cpp": "cf8ccb6378316974103aff9608a0c3968a4f4fffffa299325434964044855f3b",
    "dng_reference.cpp": "912afda9ed871fe50ff0037a1be3361d37c1b8ee1230b907eed36d7512c422b6",
    "dng_utils.h": "0eb910dd59b46928d5fee734a0cc096eb1a99bab8594d6d2fec77ae0d6fb11e9",
}

CPP = '#include "dng_host.h"\n#include "dng_image.h"\n#include "dng_pixel_buffer.h"\n#include "dng_tag_types.h"\n#include "dng_resample.h"\n#include "dng_exceptions.h"\n#include <vector>\n#include <cstdio>\n#include <cstdlib>\nint main(int n,char**v){try{if(n!=9)return 1;int w=atoi(v[1]),h=atoi(v[2]),x=atoi(v[3]),y=atoi(v[4]),cw=atoi(v[5]),ch=atoi(v[6]),tw=atoi(v[7]),th=atoi(v[8]);dng_host host;AutoPtr<dng_image> source(host.Make_dng_image(dng_point(h,w),3,ttFloat));std::vector<float> data(w*h*3);if(fread(data.data(),4,data.size(),stdin)!=data.size())return 2;dng_pixel_buffer b;b.fArea=dng_rect(h,w);b.fPlane=0;b.fPlanes=3;b.fRowStep=w*3;b.fColStep=3;b.fPlaneStep=1;b.fPixelType=ttFloat;b.fPixelSize=4;b.fData=data.data();source->Put(b);AutoPtr<dng_image> target(host.Make_dng_image(dng_point(th,tw),3,ttFloat));ResampleImage(host,*source.Get(),*target.Get(),dng_rect(y,x,y+ch,x+cw),target->Bounds(),dng_resample_bicubic::Get());data.resize(tw*th*3);b.fArea=target->Bounds();b.fRowStep=tw*3;b.fData=data.data();target->Get(b);return fwrite(data.data(),4,data.size(),stdout)==data.size()?0:3;}catch(const dng_exception&e){fprintf(stderr,"error %u\\n",e.ErrorCode());return 9;}}\n'
RUST = 'mod dng {#[derive(Clone,Copy)]pub struct Rect{pub x:usize,pub y:usize,pub w:usize,pub h:usize}}\nmod error {pub type Result<T>=std::result::Result<T,String>;pub fn err(c:&str,s:&str)->String{format!("{c}:{s}")}pub fn io_error(e:std::io::Error,s:&str)->String{format!("{s}:{e}")}}\n#[path="__MODULE__"] mod scale;\nfn main(){use std::io::Write;let a:Vec<_>=std::env::args().collect();let v:Vec<usize>=a[2..].iter().map(|s|s.parse().unwrap()).collect();let f=std::fs::File::open(&a[1]).unwrap();let mut o=std::io::stdout().lock();scale::resample(&f,v[0],v[1],dng::Rect{x:v[2],y:v[3],w:v[4],h:v[5]},[v[6],v[7]],&false.into(),|_,row|{for p in row{for c in p{o.write_all(&c.to_le_bytes()).unwrap();}}Ok(())}).unwrap();}\n'

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sdk-source',type=pathlib.Path,required=True)
    p.add_argument('--sdk-objects',type=pathlib.Path,required=True)
    p.add_argument('--jxl-prefix',type=pathlib.Path,required=True)
    p.add_argument('--rustc',default='rustc')
    p.add_argument('--output',type=pathlib.Path)
    p.add_argument('--seed',type=int,default=971)
    p.add_argument('--cases',type=int,default=100)
    args=p.parse_args()
    module=pathlib.Path(__file__).resolve().parents[1]/'src/dng_scale_resample.rs'
    objects=sorted(args.sdk_objects.glob('*.o'))
    if not objects: p.error('No SDK object files found')
    hashes={name:hashlib.sha256((args.sdk_source/name).read_bytes()).hexdigest()
            for name in ('dng_resample.cpp','dng_reference.cpp','dng_utils.h')}
    report={'sdk_version':'1.7.1 source snapshot; source hashes below',
            'sdk_source_sha256':hashes,'matches_reference_source':hashes==REFERENCE_SOURCE_SHA256,'seed':args.seed,'random_cases':args.cases,
            'fixed_cases':7,'max_abs_error':0.0,'differing_float_values':0}
    with tempfile.TemporaryDirectory(prefix='film-scale-oracle-') as d:
        d=pathlib.Path(d); (d/'oracle.cpp').write_text(CPP)
        (d/'ours.rs').write_text(RUST.replace('__MODULE__',str(module)))
        subprocess.run(['g++','-std=c++17','-O1','-ffunction-sections','-fdata-sections',
            '-DqLinux=1','-DqDNGUseXMP=0','-DqDNGUseLibJPEG=0','-DqDNGThreadSafe=0',
            '-I'+str(args.sdk_source),'-I'+str(args.jxl_prefix/'include'),str(d/'oracle.cpp'),
            *map(str,objects),'-Wl,--gc-sections','-L'+str(args.jxl_prefix/'lib'),
            '-ljxl_dec','-ljxl_threads','-ljxl_cms','-lbrotlidec','-lbrotlicommon','-lhwy',
            '-lz','-lpthread','-o',str(d/'sdk')],check=True)
        subprocess.run([args.rustc,'--edition','2024','-O',str(d/'ours.rs'),'-o',str(d/'ours')],check=True)
        def compare(shape, values):
            binary=struct.pack('<%sf'%len(values),*values); (d/'input.f32').write_bytes(binary)
            params=list(map(str,shape))
            actual=subprocess.check_output([str(d/'ours'),str(d/'input.f32'),*params])
            expected=subprocess.check_output([str(d/'sdk'),*params],input=binary)
            if len(actual)!=len(expected): raise AssertionError((shape,'output size'))
            av=struct.unpack('<%sf'%(len(actual)//4),actual)
            ev=struct.unpack('<%sf'%(len(expected)//4),expected)
            error=max(abs(a-b) for a,b in zip(av,ev))
            report['max_abs_error']=max(error,report['max_abs_error'])
            report['differing_float_values']+=sum(a!=b for a,b in zip(av,ev))
            if error>2e-7: raise AssertionError((shape,error))
        r=random.Random(7); values=[r.uniform(-.2,1.3) for _ in range(19*17*3)]
        for crop in [(0,0,19,17,37,35),(3,2,13,11,9,6),(3,2,13,11,26,33),
                     (0,0,19,17,1,1),(0,0,19,17,19,17),(1,0,17,16,53,7),(0,0,19,17,38,34)]:
            compare((19,17,*crop),values)
        r=random.Random(args.seed)
        for _ in range(args.cases):
            w=r.randrange(2,25);h=r.randrange(2,25);x=r.randrange(w);y=r.randrange(h)
            cw=r.randrange(1,w-x+1);ch=r.randrange(1,h-y+1);tw=r.randrange(1,40);th=r.randrange(1,40)
            compare((w,h,x,y,cw,ch,tw,th),[r.uniform(-.2,1.3) for _ in range(w*h*3)])
    result=json.dumps(report,indent=2)
    print(result)
    if args.output:args.output.write_text(result+'\n')
if __name__=='__main__': main()
