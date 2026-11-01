package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
public class MemoryProbe {
  public static void main(String[] args) throws Exception {
    System.load(args[0]);NativeFilmBridge b=new NativeFilmBridge();Path root=Path.of(args[1]);long h=b.nativeCreate(root.resolve("files/engine-data").toString());NativeFilmBridge.require(h!=0,"create");
    ByteBuffer input=NativeFilmBridge.direct(12),output=NativeFilmBridge.direct(12);output.putFloat(0,-999f);
    for(int budget:new int[]{127,513})NativeFilmBridge.require(b.nativeRender(h,input,output,1,1,"{\"memoryBudgetMiB\":"+budget+"}").contains("INVALID_BUDGET"),"invalid budget");
    String preview=b.nativeRender(h,input,output,257,256,"{\"memoryBudgetMiB\":128}");NativeFilmBridge.require(preview.contains("MEMORY_BUDGET_TOO_SMALL")&&output.getFloat(0)==-999f,"preview refusal without copy");
    String small=b.nativePlanExport(h,4000,3000,"{\"memoryBudgetMiB\":128}");String large=b.nativePlanExport(h,10000,5000,"{\"memoryBudgetMiB\":128}");
    NativeFilmBridge.require(small.contains("\"ok\":true")&&small.contains("\"estimatedScratchBytes\":1603108864"),"12MP plan");NativeFilmBridge.require(large.contains("\"ok\":true")&&large.contains("\"estimatedTotalLocalBytes\":7667108864"),"50MP plan");
    NativeFilmBridge.require(b.nativePlanExport(h,Integer.MAX_VALUE,2,"{}").contains("INVALID_DIMENSIONS"),"checked dimensions");
    String idle=b.nativeGetProgress(h);NativeFilmBridge.require(idle.contains("\"status\":\"idle\"")&&idle.contains("\"jobId\":0"),"planning/invalid preview did not begin job");
    b.nativeDestroy(h);NativeFilmBridge.require(b.nativeGetProgress(h).contains("INVALID_HANDLE"),"destroyed progress");
    System.out.println("{\"status\":\"passed\",\"checks\":[\"budget_validation\",\"preview_refused_without_copy\",\"12MP_plan\",\"50MP_plan\",\"plan_dimensions_checked\",\"plan_does_not_mutate_progress\",\"destroyed_progress_handle\"],\"plan12MP\":"+small+",\"plan50MP\":"+large+"}");
  }
}
