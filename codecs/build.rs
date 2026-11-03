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
 for library in ["jxl_dec","jxl_cms","hwy","brotlidec","brotlicommon"] {
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
