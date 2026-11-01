package dev.filmroom.engine;
import java.nio.file.*;
import java.util.*;

/** Tests app-private spool confinement with synthetic files only. */
public class PathProbe {
  public static void main(String[] args) throws Exception {
    System.load(args[0]); NativeFilmBridge bridge=new NativeFilmBridge();
    Path root=Path.of(args[1]).toRealPath(), files=root.resolve("files"),cache=root.resolve("cache");
    long handle=bridge.nativeCreate(files.resolve("engine-data").toString()); NativeFilmBridge.require(handle!=0,"create");
    Path outside=Files.createTempDirectory(root,"outside-"),source=cache.resolve("path-probe-source.f32le");Files.write(source,new byte[12]);
    Path foreign=outside.resolve("foreign.f32le");Files.write(foreign,new byte[12]);
    Path sourceLink=cache.resolve("source-link-"+System.nanoTime());Files.createSymbolicLink(sourceLink,foreign);
    Path parentLink=cache.resolve("parent-link-"+System.nanoTime());Files.createSymbolicLink(parentLink,outside);
    Path dangling=cache.resolve("dangling-"+System.nanoTime());Files.createSymbolicLink(dangling,outside.resolve("missing"));
    String params="{\"mode\":\"export\",\"grainAmount\":0,\"halationAmount\":0}";
    List<String> checks=new ArrayList<>();
    String[][] cases={
      {"symlink_input_escape",sourceLink.toString(),cache.resolve("bad-input-out.f32le").toString()},
      {"symlink_output_parent_escape",source.toString(),parentLink.resolve("bad-output.f32le").toString()},
      {"dangling_output_symlink",source.toString(),dangling.toString()},
      {"bundled_data_input",files.resolve("engine-data/manifest.json").toString(),cache.resolve("bad-data-in.f32le").toString()},
      {"bundled_data_output",source.toString(),files.resolve("engine-data/do-not-create.f32le").toString()},
      {"relative_input","relative.f32le",cache.resolve("bad-relative.f32le").toString()},
      {"relative_output",source.toString(),"relative.f32le"}
    };
    for(String[] c:cases){String result=bridge.nativeExportFile(handle,c[1],c[2],1,1,params);NativeFilmBridge.require(result.contains("INVALID_PATH"),c[0]+": "+result);checks.add(c[0]);}
    NativeFilmBridge.require(bridge.nativeExportFile(handle,null,cache.resolve("null.f32le").toString(),1,1,params).contains("INVALID_PARAMS"),"null path");checks.add("null_path_json_error");
    NativeFilmBridge.require(Files.size(foreign)==12&&!Files.exists(outside.resolve("bad-output.f32le")),"outside untouched");checks.add("outside_files_untouched");
    bridge.nativeDestroy(handle);
    Files.delete(sourceLink);Files.delete(parentLink);Files.delete(dangling);Files.delete(source);Files.delete(foreign);Files.delete(outside);
    System.out.println("{\"status\":\"passed\",\"checks\":[\""+String.join("\",\"",checks)+"\"]}");
  }
}
