use std::{env,fs,path::PathBuf,process::Command};
fn run(mut c:Command){let status=c.status().expect("spawn C++ build tool");assert!(status.success(),"C++ bridge build failed");}
fn main(){
 let out=PathBuf::from(env::var_os("OUT_DIR").unwrap());let target=env::var("TARGET").unwrap();let host=env::var("HOST").unwrap();
 // Cross compilation requires an explicit target compiler, never silently host-compile.
 let cxx=env::var("HE_CXX").unwrap_or_else(|_|{assert_eq!(target,host,"set HE_CXX and HE_AR for target C++ toolchain");"g++".into()});
 let ar=env::var("HE_AR").unwrap_or_else(|_|{assert_eq!(target,host,"set HE_AR for target archive tool");"ar".into()});
 // Official pinned libjxl is built separately by third-party/libjxl/build.sh.
 // A missing target prefix fails closed; never link a host library into Android.
 let prefix=PathBuf::from(env::var_os("FILM_JXL_PREFIX").expect("set FILM_JXL_PREFIX to the target-specific official libjxl prefix"));
 assert!(prefix.join("include/jxl/decode.h").is_file(),"missing libjxl target headers");
 println!("cargo:rerun-if-env-changed=FILM_JXL_PREFIX");
 println!("cargo:rerun-if-changed=cpp/jxl/bridge.cpp");
 let jxl_object=out.join("film_jxl_bridge.o");
 let mut jxl=Command::new(&cxx);
 jxl.args(["-std=c++17","-O2","-fPIC","-fexceptions","-fvisibility=hidden","-fvisibility-inlines-hidden","-Wall","-Wextra","-Werror","-c","cpp/jxl/bridge.cpp","-o"]).arg(&jxl_object).arg("-I").arg(prefix.join("include"));run(jxl);
 let mut archive=Command::new(&ar);archive.arg("crs").arg(out.join("libfilm_jxl_bridge.a")).arg(jxl_object);run(archive);
 println!("cargo:rustc-link-search=native={}",prefix.join("lib").display());
 println!("cargo:rustc-link-search=native={}",out.display());
 println!("cargo:rustc-link-lib=static=film_jxl_bridge");
 // Before the libjxl archives: static link order matters.
 build_dng_sdk(&out,&cxx,&ar,&target,&prefix);
 for library in ["jxl_dec","jxl_threads","jxl_cms","hwy","brotlidec","brotlicommon"] {
  assert!(prefix.join("lib").join(format!("lib{library}.a")).is_file(),"missing libjxl target library: {library}");
  println!("cargo:rustc-link-lib=static={library}");
 }
 if target.contains("android"){println!("cargo:rustc-link-lib=log");}
 let dir=PathBuf::from("cpp/nikon_he");let mut objects=vec![];
 println!("cargo:rerun-if-changed={}",dir.display());
 let mut sources:Vec<_>=fs::read_dir(&dir).unwrap().map(|e|e.unwrap().path()).collect();sources.sort();
 for p in sources {println!("cargo:rerun-if-changed={}",p.display());if p.extension().and_then(|x|x.to_str())!=Some("cpp"){continue;}
  let o=out.join(p.file_name().unwrap()).with_extension("o");let mut c=Command::new(&cxx);c.args(["-std=c++17","-O2","-fPIC","-fexceptions","-fvisibility=hidden","-fvisibility-inlines-hidden","-fno-omit-frame-pointer","-Icpp/nikon_he","-c"]).arg(&p).arg("-o").arg(&o);run(c);objects.push(o);
 }
 let mut c=Command::new(ar);c.arg("crs").arg(out.join("libfilm_he.a")).args(objects);run(c);
 println!("cargo:rustc-link-search=native={}",out.display());println!("cargo:rustc-link-lib=static=film_he");
 if target.contains("android") {
  // Only this library owns C++ objects; exceptions never cross the C ABI. Bundle
  // the exact target NDK runtime statically, rather than require a missing .so.
  println!("cargo:rustc-link-arg=-Wl,--start-group");
  for file in ["libc++_static.a","libc++abi.a","libunwind.a"] {
   let found=Command::new(&cxx).arg(format!("--print-file-name={file}")).output().expect("query target runtime");assert!(found.status.success());
   let path=PathBuf::from(String::from_utf8(found.stdout).unwrap().trim());assert!(path.is_absolute()&&path.is_file(),"target runtime unavailable: {file}");
   // Do not add the generic NDK library directory to -L: it also contains
   // libc.a and would shadow the API-level shared libc.so selected by clang.
   println!("cargo:rustc-link-arg={}",path.display());
  }
  println!("cargo:rustc-link-arg=-Wl,--end-group");
  println!("cargo:rustc-link-arg=-Wl,--no-undefined");
 } else {println!("cargo:rustc-link-lib=stdc++");}
 println!("cargo:rerun-if-env-changed=HE_CXX");println!("cargo:rerun-if-env-changed=HE_AR");
}

/// Adobe DNG SDK 1.7.1 (unmodified, third-party/adobe-dng-sdk/sdk) plus the
/// XMPCore/expat/libjpeg subset it ships with, and the Filmroom C++ bridge.
/// JPEG XL tiles are decoded by the same pinned libjxl 0.12 used elsewhere.
fn build_dng_sdk(out:&std::path::Path,cxx:&str,ar:&str,target:&str,jxl:&std::path::Path){
 let sdk=PathBuf::from("../third-party/adobe-dng-sdk/sdk").canonicalize().expect("vendored Adobe DNG SDK");
 println!("cargo:rerun-if-changed={}",sdk.display());
 println!("cargo:rerun-if-changed=cpp/dng_sdk");
 let dng=sdk.join("dng_sdk/source");let xt=sdk.join("xmp/toolkit");let jpeg=sdk.join("libjpeg");
 let expat=xt.join("XMPCore/third-party/expat/public/lib");
 let android=target.contains("android");
 // Generated include shims: libjpeg configuration and the expat path used by
 // the public XMP source distribution.
 let shim=out.join("dng_sdk_gen");fs::create_dir_all(shim.join("third-party/expat/lib")).unwrap();
 fs::copy(jpeg.join("jconfig.txt"),shim.join("jconfig.h")).unwrap();
 fs::write(shim.join("third-party/expat/lib/expat.h"),format!("#include \"{}\"\n",expat.join("expat.h").display())).unwrap();
 let plat=if android{"-DqAndroid=1"}else{"-DqLinux=1"};
 let env=if android{"-DANDROID_ENV=1"}else{"-DUNIX_ENV=1"};
 let resource=xt.join(if android{"XMPCore/resource/android"}else{"XMPCore/resource/linux"});
 let common:Vec<String>=["-O2","-fPIC","-fexceptions","-ffunction-sections","-fdata-sections","-fvisibility=hidden","-w"].iter().map(|s|s.to_string()).collect();
 let xmp_defs:Vec<String>=[env,"-DXMP_StaticBuild=1","-DHAVE_EXPAT_CONFIG_H=1","-DXML_STATIC=1","-DENABLE_CPP_DOM_MODEL=0","-DAdobePrivate=0","-DXMP_64=1","-DBUILDING_XMPCORE_LIB=1","-DBUILDING_XMPCORE_AS_STATIC=1","-DXMP_COMPONENT_INT_NAMESPACE=AdobeXMPCore_Int"].iter().map(|s|s.to_string()).collect();
 let inc=|p:&std::path::Path|format!("-I{}",p.display());
 let xmp_inc=vec![inc(&xt),inc(&xt.join("public/include")),inc(&expat),inc(&expat.parent().unwrap()),inc(&xt.join("third-party/zuid/interfaces")),inc(&xt.join("build")),inc(&resource),inc(&shim)];
 let dng_defs:Vec<String>=[plat,env,"-DAdobePrivate=0","-DXMP_64=1","-DqDNGUseXMP=1","-DqDNGXMPFiles=0","-DqDNGXMPDocOps=0","-DqDNGUseLibJPEG=1","-DqDNGThreadSafe=1","-DqDNGValidateTarget=0","-DqDNGValidate=0","-DXMP_StaticBuild=1"].iter().map(|s|s.to_string()).collect();
 let dng_inc=vec![inc(&dng),inc(&xt),inc(&xt.join("public/include")),inc(&shim),inc(&jpeg),inc(&jxl.join("include"))];
 let mut jobs:Vec<(PathBuf,Vec<String>)>=vec![];
 let cxx_flags=|defs:&[String],incs:&[String]|{let mut v=vec!["-std=c++17".to_string(),"-frtti".to_string()];v.extend(common.clone());v.extend(defs.iter().cloned());v.extend(incs.iter().cloned());v};
 let c_flags=|defs:&[String],incs:&[String]|{let mut v=vec!["-x".to_string(),"c".to_string(),"-std=c99".to_string()];v.extend(common.clone());v.extend(defs.iter().cloned());v.extend(incs.iter().cloned());v};
 let mut dng_sources:Vec<_>=fs::read_dir(&dng).unwrap().map(|e|e.unwrap().path()).filter(|p|p.extension().and_then(|x|x.to_str())==Some("cpp")&&p.file_name().unwrap()!="dng_validate.cpp").collect();
 dng_sources.sort();
 for p in dng_sources {jobs.push((p,cxx_flags(&dng_defs,&dng_inc)));}
 let mut bridge_inc=dng_inc.clone();bridge_inc.push("-Icpp/dng_sdk".into());
 for p in ["cpp/dng_sdk/bridge.cpp","cpp/dng_sdk/jxl_encoder_unavailable.cpp"] {jobs.push((PathBuf::from(p),cxx_flags(&dng_defs,&bridge_inc)));}
 let xc=xt.join("XMPCore/source");
 for n in ["ExpatAdapter","ParseRDF","WXMPIterator","WXMPMeta","WXMPUtils","XMPCore_Impl","XMPIterator","XMPMeta","XMPMeta-GetSet","XMPMeta-Parse","XMPMeta-Serialize","XMPUtils","XMPUtils-FileInfo","Android_Utils"] {jobs.push((xc.join(format!("{n}.cpp")),cxx_flags(&xmp_defs,&xmp_inc)));}
 for n in ["UnicodeConversions","XML_Node","XMP_LibUtils","SafeStringAPIs"] {jobs.push((xt.join(format!("source/{n}.cpp")),cxx_flags(&xmp_defs,&xmp_inc)));}
 jobs.push((xt.join("third-party/zuid/sources/MD5.cpp"),cxx_flags(&xmp_defs,&xmp_inc)));
 for n in ["xmlparse","xmlrole","xmltok"] {jobs.push((expat.join(format!("{n}.c")),c_flags(&xmp_defs,&xmp_inc)));}
 for n in "jaricom jcapimin jcapistd jcarith jccoefct jccolor jcdctmgr jchuff jcinit jcmainct jcmarker jcmaster jcomapi jcparam jcprepct jcsample jctrans jdapimin jdapistd jdarith jdatadst jdatasrc jdcoefct jdcolor jddctmgr jdhuff jdinput jdmainct jdmarker jdmaster jdmerge jdpostct jdsample jdtrans jerror jfdctflt jfdctfst jfdctint jidctflt jidctfst jidctint jmemmgr jmemnobs jquant1 jquant2 jutils".split(' ') {
  jobs.push((jpeg.join(format!("{n}.c")),c_flags(&[],&[inc(&shim),inc(&jpeg)])));
 }
 let objdir=out.join("dng_sdk_obj");fs::create_dir_all(&objdir).unwrap();
 let work:Vec<(PathBuf,Vec<String>,PathBuf)>=jobs.into_iter().enumerate().map(|(i,(src,flags))|{let o=objdir.join(format!("{i:03}-{}.o",src.file_stem().unwrap().to_string_lossy()));(src,flags,o)}).collect();
 let threads=env::var("NUM_JOBS").ok().and_then(|v|v.parse::<usize>().ok()).unwrap_or(2).max(1);
 let next=std::sync::atomic::AtomicUsize::new(0);
 std::thread::scope(|s|{for _ in 0..threads{s.spawn(||loop{
  let i=next.fetch_add(1,std::sync::atomic::Ordering::Relaxed);if i>=work.len(){break;}
  let (src,flags,o)=&work[i];
  let stamp=o.with_extension("flags");let key=format!("{cxx}\n{}",flags.join("\n"));
  let fresh=!src.starts_with("cpp/")&&o.is_file()&&fs::read_to_string(&stamp).ok().as_deref()==Some(key.as_str())&&o.metadata().and_then(|m|m.modified()).ok()>=src.metadata().and_then(|m|m.modified()).ok();
  if !fresh {
   let mut c=Command::new(cxx);c.args(flags).arg("-c").arg(src).arg("-o").arg(o);
   let status=c.status().expect("spawn C++ compiler");assert!(status.success(),"DNG SDK compile failed: {}",src.display());
   fs::write(&stamp,key).unwrap();
  }
 });}});
 let lib=out.join("libfilm_dng_sdk.a");let _=fs::remove_file(&lib);
 let mut a=Command::new(ar);a.arg("crs").arg(&lib).args(work.iter().map(|w|&w.2));run(a);
 println!("cargo:rustc-link-lib=static=film_dng_sdk");
 println!("cargo:rustc-link-lib=z");
}
