package dev.filmroom.engine;

import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/** Existing JNI entry points only, no new production ABI/UI switch.
 * args: exact dedicated-build library, private fixture root (files/engine-data + cache), on|off.
 * This probe is NOT executed by merely building the library or enabling a feature.
 * Android test code can make the same NativeFilmBridge calls and persist the raw
 * returned String before app-level diagnostic filtering. No physical peak is asserted.
 */
public final class ResourceAuditProbe {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void collected(String json, String scope) {
        require(json.contains("\"resourceCensus\":"), "Missing resource-audit feature/native entry wiring");
        require(json.contains("\"collectionStatus\":\"collected\""), json);
        require(json.contains("\"scope\":\"" + scope + "\""), json);
        require(json.contains("\"logicalLiveOwnerPeakBytes\":null"), json);
        require(json.contains("\"physicalDriverPeakBytes\":null"), json);
        require(json.contains("\"peakAdmissionAuthority\":false"), json);
        require(json.contains("\"hostCoverageComplete\":false"), json);
    }
    static long creations(String json) {
        Matcher m=Pattern.compile("\\\"bufferCreationCount\\\":([0-9]+)").matcher(json);
        require(m.find(), "No buffer count: " + json); return Long.parseLong(m.group(1));
    }
    static void save(Path root, String name, String json) throws Exception {
        // Raw JSON is retained as a test artifact, not truncated into Logcat.
        Files.write(root.resolve(name + ".json"), json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Path root=Paths.get(args[1]).toRealPath(); Files.createDirectories(root.resolve("cache"));
        NativeFilmBridge b=new NativeFilmBridge();
        String invalid=b.nativeRender(0,null,null,0,0,"{}");
        require(invalid.contains("INVALID_DIMENSIONS"),invalid);
        if(args[2].equals("off")) {
            require(!invalid.contains("resourceCensus"),"Default build unexpectedly collects");
            System.out.println("RESOURCE_AUDIT_DEFAULT_OFF_PASS"); return;
        }
        collected(invalid,"jni-preview-thread"); require(creations(invalid)==0,invalid);
        Path artifacts=Files.createTempDirectory(root.resolve("cache"),"resource-census-probe-");
        save(artifacts,"invalid-before",invalid);
        long handle=b.nativeCreate(root.resolve("files/engine-data").toString()); require(handle!=0,"private bundled data missing");
        int w=31,h=23,n=w*h*12;
        ByteBuffer in=ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        ByteBuffer out=ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        ByteBuffer spool=ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<n/4;i++){float v=.01f+(i%97)/83f;in.putFloat(i*4,v);spool.putFloat(i*4,v);}
        Path input=artifacts.resolve("input.rgb");Files.write(input,spool.array(),StandardOpenOption.CREATE_NEW);
        try {
            String common="\"useGpu\":true,\"autoExposure\":false,\"scanBlur\":0.5,\"scanSharpenAmount\":0.4,\"scanSharpenRadius\":0.7";
            String preview=b.nativeRender(handle,in,out,w,h,"{"+common+"}");
            save(artifacts,"preview",preview);require(preview.contains("\"ok\":true"),preview);
            collected(preview,"jni-preview-thread");require(creations(preview)>0,"GPU did not execute: "+preview);
            require(preview.contains("\"backend\":\"wgpu\""),preview);
            for(String route:new String[]{"direct","print"}) {
                Path output=artifacts.resolve(route+".rgb");
                String export=b.nativeExportFile(handle,input.toString(),output.toString(),w,h,
                    "{"+common+",\"mode\":\"export\",\"outputRoute\":\""+route+"\"}");
                save(artifacts,"export-"+route,export);require(export.contains("\"ok\":true")&&Files.size(output)==n,export);
                collected(export,"export-execution-thread");require(creations(export)>0,export);
                require(export.contains("\"backend\":\"wgpu\""),export);
                require(export.contains("\"label\":\"blur_in\""),"Standalone scanner allocation missing: "+export);
            }
            String invalidAfter=b.nativeRender(handle,in,out,0,h,"{}");
            collected(invalidAfter,"jni-preview-thread");require(creations(invalidAfter)==0,"Stale census leaked across request");
            save(artifacts,"invalid-after",invalidAfter);
            System.out.println("RESOURCE_AUDIT_JNI_PASS artifacts="+artifacts);
        } finally {b.nativeDestroy(handle);}
    }
}
