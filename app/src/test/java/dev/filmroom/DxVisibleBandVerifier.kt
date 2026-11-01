package dev.filmroom

import dev.filmroom.border.BorderSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import kotlin.math.*

/** Dense, color-independent eligibility oracle. H=independent physical alpha occlusion;
 * E=the renderer's nonzero filter support crosses a semantic band/module boundary.
 * No observed output color is used to decide whether a sample is tested.
 */
internal object DxVisibleBandVerifier {
    private val offsets=intArrayOf(-3,0,3)
    private data class Support(val first:Int,val last:Int)
    private fun support(center:Double,footprint:Double):Support {
        if(footprint>1.0){
            val lo=center-footprint/2;val hi=center+footprint/2
            return Support(floor(lo+1e-9).toInt(),ceil(hi-1e-9).toInt()-1)
        }
        val value=center-.5;val nearest=round(value)
        if(abs(value-nearest)<1e-9)return Support(nearest.toInt(),nearest.toInt())
        val first=floor(value).toInt();return Support(first,first+1)
    }
    fun verify(stock:JSONObject,s:BorderSnapshot,threshold:Double,mask:ByteArray,kind:String,read:(Int,Int)->Double){
        require(mask.size==1520*1400)
        val light=stock.getString("bit1_polarity")=="lighter_than_material"
        val symbols=stock.getJSONArray("symbols")
        val scale=s.pixelsPerMm/40.0;val footprint=1/scale
        val minimumComplete=if(maxOf(s.photoWidth,s.photoHeight)>=1000)8 else 2
        for(n in 0 until symbols.length()){
            val symbol=symbols.getJSONObject(n);val p=symbol.getJSONArray("placement")
            val originX=p.getInt(0);val originY=p.getInt(1);val moduleWidth=p.getInt(2);val bandHeight=p.getInt(3)
            assertEquals(16,moduleWidth);assertEquals(40,bandHeight)
            for(band in 0..1){
                val startY=originY+band*bandHeight;val endY=startY+bandHeight
                val expected=symbol.getString(if(band==0)"clock31" else "data31");assertEquals(31,expected.length)
                val coverage=Array(31){IntArray(offsets.size)};val completeRows=mutableListOf<Int>()
                var candidateRows=0;var holes=0;var edges=0;var tested=0;var mismatches=0;var minimumMargin=Double.POSITIVE_INFINITY
                val rowReports=mutableListOf<String>()
                val crossAxisSize=if(s.portrait)s.outputWidth else s.outputHeight
                for(scanline in 0 until crossAxisSize){
                    val nativeCenterY=if(s.portrait)(35-(scanline+.5-s.frameOriginX)/s.pixelsPerMm)*40 else (scanline+.5-s.frameOriginY)/scale
                    if(nativeCenterY<startY || nativeCenterY>=endY)continue
                    candidateRows++;var complete=true
                    for((probeIndex,offset) in offsets.withIndex()){
                        var rowMatch=true
                        val actual=buildString{
                            for(column in expected.indices){
                                val moduleStart=originX+moduleWidth*column;val moduleEnd=moduleStart+moduleWidth
                                val requestedX=moduleStart+moduleWidth/2+offset+.5
                                val along=floor((if(s.portrait)s.frameOriginY else s.frameOriginX)+requestedX*scale).toInt()
                                val x=if(s.portrait)scanline else along;val y=if(s.portrait)along else scanline
                                require(x in 0 until s.outputWidth && y in 0 until s.outputHeight)
                                val nativeCenterX=if(s.portrait)(y+.5-s.frameOriginY)/scale else (x+.5-s.frameOriginX)/scale
                                val xs=support(nativeCenterX,footprint);val ys=support(nativeCenterY,footprint)
                                if(xs.first<moduleStart || xs.last>=moduleEnd || ys.first<startY || ys.last>=endY){append('E');edges++;complete=false;continue}
                                var occluded=false
                                for(yy in ys.first..ys.last)for(xx in xs.first..xs.last){require(xx in 0 until 1520 && yy in 0 until 1400);if((mask[yy*1520+xx].toInt()and 255)!=255)occluded=true}
                                if(occluded){append('H');holes++;complete=false;continue}
                                val value=read(x,y);require(value.isFinite());val bit=if((value>threshold)==light)'1' else '0'
                                append(bit);tested++;coverage[column][probeIndex]++
                                minimumMargin=minOf(minimumMargin,abs(value-threshold))
                                if(bit!=expected[column]){rowMatch=false;mismatches++}
                            }
                        }
                        rowReports+="${stock.getString("physical_stock")},${symbol.getString("label")},$band,$kind-scan${scanline}-offset${offset},${s.photoWidth},${s.photoHeight},$expected,$actual,$rowMatch\n"
                    }
                    if(complete)completeRows+=scanline
                }
                val report=JSONObject().apply{
                    put("stock",stock.getString("physical_stock"));put("symbol",symbol.getString("label"));put("band",if(band==0)"clock" else "data");put("kind",kind)
                    put("photoWidth",s.photoWidth);put("photoHeight",s.photoHeight);put("portrait",s.portrait)
                    put("band_native_y_start",startY);put("band_native_y_end_exclusive",endY);put("module_offsets_native_pixels",JSONArray(offsets.toList()))
                    put("filter_footprint_native_pixels",footprint);put("threshold_linear_srgb_mean",threshold);put("polarity",stock.getString("bit1_polarity"))
                    put("candidate_scanlines",candidateRows);put("fully_visible_31bit_scanlines",JSONArray(completeRows));put("minimum_required_complete_rows",minimumComplete)
                    put("tested_samples",tested);put("hole_mask_exclusions",holes);put("semantic_filter_boundary_exclusions",edges);put("mismatched_samples",mismatches)
                    put("module_probe_coverage",JSONArray(coverage.map{it.toList()}));put("minimum_absolute_threshold_margin",minimumMargin)
                    put("eligibility_uses_observed_output_color",false);put("expected31",expected)
                    put("independent_alpha_png_sha256",stock.getString("independent_alpha_png_sha256"));put("threshold_calibration_native_y",stock.getInt("threshold_calibration_native_y"))
                }
                val path=System.getenv("FILMROOM_DX_REPORT")
                path?.let{File(it).apply{parentFile?.mkdirs();appendText(rowReports.joinToString(""))}}
                (System.getenv("FILMROOM_DX_COVERAGE_REPORT") ?: path?.plus(".coverage.jsonl"))?.let{File(it).apply{parentFile?.mkdirs();appendText(report.toString()+"\n")}}
                val label="${stock.getString("physical_stock")} ${symbol.getString("label")} band$band $kind"
                assertEquals("Unoccluded logical samples: $label",0,mismatches)
                assertTrue("Multiple complete visible31-bit rows: $label ${completeRows.size}",completeRows.size>=minimumComplete)
                for(column in coverage.indices)for(probe in offsets.indices)assertTrue("Every module/probe covered: $label module$column offset${offsets[probe]}",coverage[column][probe]>=minimumComplete)
                if(band==0)assertTrue("Clock top-region hole occlusion must be reported: $label",holes>0)
                else assertEquals("Data band is beyond physical perforations: $label",0,holes)
            }
        }
    }
}
