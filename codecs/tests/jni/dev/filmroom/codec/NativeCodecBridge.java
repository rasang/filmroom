package dev.filmroom.codec;
public final class NativeCodecBridge {
 public static native long nativeCreate(String privateRoot);
 public static native String nativeProbe(long h,String input,String budget);
 public static native String nativeDecode(long h,String input,String output,String budget);
 public static native String nativeEncode(long h,String input,String output,String params);
 public static native void nativeCancel(long h);
 public static native void nativeDestroy(long h);
}
