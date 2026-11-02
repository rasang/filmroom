package dev.filmroom.engine;
import java.nio.ByteBuffer;
/** Isolated host contract: never shipped by this native phase. */
public final class NativeDetailBridge {
 public native String nativeCapabilities();
 public native String nativePlan(String requestJson);
 public native String nativeCreate(String canonicalPrivateRoot);
 public native String nativeBeginOperation(long sessionId);
 public native String nativeBuild(long sessionId,long operationId,String sourceRelative,String requestJson);
 public native String nativeRegion(long sessionId,long operationId,int x,int y,int width,int height,ByteBuffer output);
 public native String nativeTile(long sessionId,long operationId,int cellX,int cellY,ByteBuffer output);
 public native String nativeCancel(long sessionId,long operationId);
 public native String nativeReleaseOperation(long sessionId,long operationId);
 public native String nativeDestroy(long sessionId);
}
