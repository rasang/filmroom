package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Failure JSON must contain diagnostics, never request text or local paths. */
public class PrivacyProbe {
  public static void main(String[] args) throws Exception {
    System.load(args[0]);NativeFilmBridge bridge=new NativeFilmBridge();Path root=Path.of(args[1]);
    long handle=bridge.nativeCreate(root.resolve("files/engine-data").toString());NativeFilmBridge.require(handle!=0,"create");
    ByteBuffer input=NativeFilmBridge.direct(12),output=NativeFilmBridge.direct(12);
    String secret="PRIVATE_GPS_PHOTO_FILENAME_012345";
    String badJson=bridge.nativeRender(handle,input,output,1,1,"{\""+secret+"\":1}");
    String badPath=bridge.nativeExportFile(handle,root.resolve("cache/"+secret).toString(),root.resolve("cache/new.f32le").toString(),1,1,"{}");
    String contentUri=bridge.nativeExportFile(handle,"content://private.example/"+secret,root.resolve("cache/new.f32le").toString(),1,1,"{}");
    Path unreadable=root.resolve("cache/unreadable-"+System.nanoTime()+"-"+secret);Files.write(unreadable,new byte[12]);
    String ioError;
    try {
      Files.setPosixFilePermissions(unreadable,Set.of());
      ioError=bridge.nativeExportFile(handle,unreadable.toString(),root.resolve("cache/new.f32le").toString(),1,1,"{}");
      NativeFilmBridge.require(ioError.contains("IO_ERROR"),"actual IO error branch: "+ioError);
    } finally {
      Files.setPosixFilePermissions(unreadable,Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));Files.delete(unreadable);
    }
    List<String> results=List.of(badJson,badPath,contentUri,ioError);
    for(String result:results){NativeFilmBridge.require(result.contains("\"ok\":false")&&result.contains("\"outputValid\":false"),"structured failure");NativeFilmBridge.require(!result.contains(secret)&&!result.contains(root.toString()),"no path/request text leakage");NativeFilmBridge.require(result.contains("\"stage\":")&&result.contains("\"elapsedMs\":")&&result.contains("\"operation\":"),"stage timing present");}
    bridge.nativeDestroy(handle);
    System.out.println("{\"status\":\"passed\",\"checks\":[\"json_request_text_redacted\",\"filesystem_path_redacted\",\"content_uri_redacted\",\"actual_io_error_path_redacted\",\"error_stage_elapsed_operation_present\"],\"errors\":["+String.join(",",results)+"]}");
  }
}
