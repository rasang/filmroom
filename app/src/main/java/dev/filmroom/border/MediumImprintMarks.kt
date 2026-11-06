package dev.filmroom.border

/** Reviewed PDF 06 layout, opt-in only. Original assets and legacy queues are never changed.
 * Source hashes and exact source-crop extraction are recorded in provenance.json.
 * This is design standardization of synthetic research strips, not authentic film spacing.
 */
internal object MediumImprintMarks {
    internal data class Move(val left:Int, val right:Int, val targetMm:Double)
    internal data class Layout(
        val assetVersion:String, val widthMm:Double, val cropStartPx:Int,
        val cropWidthPx:Int, val sourcePixelsPerMm:Int,
        /** Largest central clear interval, with 0.5 mm exclusion from all remaining marks. */
        val clearStartMm:Double, val clearEndMm:Double,
        internal val moves:List<Move>,
    ) {
        val clearWidthMm get()=clearEndMm-clearStartMm
        internal val mmPerPixel get()=widthMm/cropWidthPx
        /** Returns source-crop coordinate, or null for an erased original rectangle. */
        internal fun sourceX(localMm:Double):Double? {
            val sx=mmPerPixel
            // PDF paints copies last: destinations take precedence over erased originals.
            for(i in moves.lastIndex downTo 0) {
                val m=moves[i]
                val lo=maxOf(0,m.left-1);val hi=minOf(cropWidthPx,m.right+1)
                if(localMm>=m.targetMm+(lo-m.left)*sx && localMm<m.targetMm+(hi-m.left)*sx)
                    return m.left+(localMm-m.targetMm)/sx
            }
            for(m in moves)if(localMm>=maxOf(0,m.left-1)*sx && localMm<minOf(cropWidthPx,m.right+1)*sx)return null
            return localMm/sx
        }
    }
    private data class Key(val stock:String,val format:MediumFormat)
    private val layouts=mapOf(
        Key("kodak_portra_160", MediumFormat.MF645) to Layout("mf120-v5-portra160-a970a81fe0dc", 47.5, 2318, 284, 6, 11.047503521126757, 36.45249647887324, listOf(Move(46, 54, 8.206), Move(214, 232, 37.11975))),
        Key("kodak_portra_160", MediumFormat.MF66) to Layout("mf120-v5-portra160-a970a81fe0dc", 62.0, 2274, 372, 6, 14.666666666666664, 47.333333333333336, listOf(Move(90, 98, 11.83335), Move(258, 276, 48.0))),
        Key("kodak_portra_160", MediumFormat.MF67) to Layout("mf120-v5-portra160-a970a81fe0dc", 76.0, 2232, 456, 6, 18.166666666666664, 57.833333333333336, listOf(Move(132, 140, 15.33335), Move(300, 318, 58.5))),
        Key("kodak_portra_160", MediumFormat.MF69) to Layout("mf120-v5-portra160-a970a81fe0dc", 90.0, 2190, 540, 6, 26.666666666666664, 63.333333333333336, listOf(Move(6, 24, 13.0), Move(174, 182, 23.83335), Move(342, 360, 64.0), Move(510, 518, 74.83335))),
        Key("kodak_portra_160", MediumFormat.MF617) to Layout("mf120-v5-portra160-a970a81fe0dc", 174.0, 1938, 1044, 6, 60.66666666666667, 113.33333333333333, listOf(Move(90, 98, 21.83335), Move(258, 276, 39.0), Move(426, 434, 57.83335), Move(594, 612, 114.0), Move(762, 770, 132.83335), Move(930, 948, 150.0))),
        Key("kodak_portra_400", MediumFormat.MF645) to Layout("mf120-v6-portra400-3c329d78f008", 47.5, 3862, 476, 10, 10.572489915966386, 36.927510084033614, listOf(Move(239, 261, 37.5273))),
        Key("kodak_portra_400", MediumFormat.MF66) to Layout("mf120-v6-portra400-3c329d78f008", 62.0, 3790, 620, 10, 15.25, 46.75, listOf(Move(17, 60, 10.35), Move(311, 333, 48.4))),
        Key("kodak_portra_400", MediumFormat.MF67) to Layout("mf120-v6-portra400-3c329d78f008", 76.0, 3720, 760, 10, 21.533333, 54.466667, listOf(Move(87, 130, 13.85), Move(381, 403, 55.066667), Move(710, 753, 61.683333))),
        Key("kodak_portra_400", MediumFormat.MF69) to Layout("mf120-v6-portra400-3c329d78f008", 90.0, 3650, 900, 10, 26.19999999999999, 63.80000000000001, listOf(Move(157, 200, 17.35), Move(451, 473, 64.4), Move(780, 823, 73.35))),
        Key("kodak_portra_400", MediumFormat.MF617) to Layout("mf120-v6-portra400-3c329d78f008", 174.0, 3230, 1740, 10, 60.19999999999999, 113.80000000000001, listOf(Move(251, 273, 27.4), Move(577, 620, 50.35), Move(871, 893, 114.4), Move(1200, 1243, 131.35), Move(1525, 1547, 150.4))),
        Key("kodak_portra_800", MediumFormat.MF645) to Layout("mf120-v5-portra800-64b689c66093", 47.5, 3862, 476, 10, 10.871839915966383, 36.62816008403362, listOf(Move(281, 309, 37.22795))),
        Key("kodak_portra_800", MediumFormat.MF66) to Layout("mf120-v5-portra800-64b689c66093", 62.0, 3790, 620, 10, 14.5, 47.5, listOf(Move(40, 53, 11.85), Move(353, 381, 48.1))),
        Key("kodak_portra_800", MediumFormat.MF67) to Layout("mf120-v5-portra800-64b689c66093", 76.0, 3720, 760, 10, 21.833333000000003, 54.166667, listOf(Move(110, 123, 15.35), Move(423, 451, 54.766667), Move(735, 748, 63.183333))),
        Key("kodak_portra_800", MediumFormat.MF69) to Layout("mf120-v5-portra800-64b689c66093", 90.0, 3650, 900, 10, 26.500000000000007, 63.49999999999999, listOf(Move(180, 193, 18.85), Move(493, 521, 64.1), Move(805, 818, 74.85))),
        Key("kodak_portra_800", MediumFormat.MF617) to Layout("mf120-v5-portra800-64b689c66093", 174.0, 3230, 1740, 10, 60.5, 113.5, listOf(Move(288, 317, 27.05), Move(600, 613, 51.85), Move(913, 941, 114.1), Move(1225, 1238, 132.85), Move(1538, 1567, 150.05))),
        Key("kodak_gold_200", MediumFormat.MF645) to Layout("mf120-v4-gold200-6fad2b869ee0", 47.5, 3862, 476, 10, 11.12133991596638, 36.37866008403362, listOf(Move(108, 126, 7.9769), Move(384, 417, 36.97845))),
        Key("kodak_gold_200", MediumFormat.MF66) to Layout("mf120-v4-gold200-6fad2b869ee0", 62.0, 3790, 620, 10, 14.75, 47.25, listOf(Move(180, 198, 11.6), Move(456, 489, 47.85))),
        Key("kodak_gold_200", MediumFormat.MF67) to Layout("mf120-v4-gold200-6fad2b869ee0", 76.0, 3720, 760, 10, 18.25, 57.75, listOf(Move(250, 268, 15.1), Move(526, 559, 58.35))),
        Key("kodak_gold_200", MediumFormat.MF69) to Layout("mf120-v4-gold200-6fad2b869ee0", 90.0, 3650, 900, 10, 26.0, 64.0, listOf(Move(32, 65, 17.85), Move(320, 338, 64.6), Move(596, 629, 73.85))),
        Key("kodak_gold_200", MediumFormat.MF617) to Layout("mf120-v4-gold200-6fad2b869ee0", 174.0, 3230, 1740, 10, 60.75, 113.25, listOf(Move(124, 142, 21.6), Move(452, 485, 38.85), Move(740, 758, 57.6), Move(1016, 1049, 113.85), Move(1355, 1373, 132.6), Move(1675, 1709, 149.8))),
        Key("kodak_ektar_100", MediumFormat.MF645) to Layout("mf120-v4-ektar100-91d648b6bb15", 47.5, 3862, 476, 10, 11.620289915966381, 35.87971008403362, listOf(Move(264, 307, 36.4795))),
        Key("kodak_ektar_100", MediumFormat.MF66) to Layout("mf120-v4-ektar100-91d648b6bb15", 62.0, 3790, 620, 10, 15.25, 46.75, listOf(Move(20, 40, 11.5), Move(336, 379, 47.35))),
        Key("kodak_ektar_100", MediumFormat.MF67) to Layout("mf120-v4-ektar100-91d648b6bb15", 76.0, 3720, 760, 10, 22.583333000000003, 53.416667, listOf(Move(90, 110, 15.0), Move(406, 449, 54.016667), Move(692, 712, 62.833333))),
        Key("kodak_ektar_100", MediumFormat.MF69) to Layout("mf120-v4-ektar100-91d648b6bb15", 90.0, 3650, 900, 10, 27.25, 62.75, listOf(Move(160, 180, 18.5), Move(476, 519, 63.35), Move(762, 782, 74.5))),
        Key("kodak_ektar_100", MediumFormat.MF617) to Layout("mf120-v4-ektar100-91d648b6bb15", 174.0, 3230, 1740, 10, 61.25, 112.75, listOf(Move(262, 305, 26.35), Move(580, 600, 51.5), Move(896, 939, 113.35), Move(1182, 1202, 132.5), Move(1501, 1543, 149.4))),
        Key("kodak_ektachrome_100", MediumFormat.MF645) to Layout("mf120-v4-e100-12d17d86a868", 47.5, 3862, 476, 10, 11.720039915966382, 35.77996008403362, listOf(Move(82, 105, 7.72745), Move(410, 455, 36.37975))),
        Key("kodak_ektachrome_100", MediumFormat.MF66) to Layout("mf120-v4-e100-12d17d86a868", 62.0, 3790, 620, 10, 15.350000000000001, 46.65, listOf(Move(154, 177, 11.35), Move(482, 527, 47.25))),
        Key("kodak_ektachrome_100", MediumFormat.MF67) to Layout("mf120-v4-e100-12d17d86a868", 76.0, 3720, 760, 10, 18.85, 57.15, listOf(Move(224, 247, 14.85), Move(552, 597, 57.75))),
        Key("kodak_ektachrome_100", MediumFormat.MF69) to Layout("mf120-v4-e100-12d17d86a868", 90.0, 3650, 900, 10, 22.349999999999994, 67.65, listOf(Move(294, 317, 18.35), Move(622, 667, 68.25))),
        Key("kodak_ektachrome_100", MediumFormat.MF617) to Layout("mf120-v4-e100-12d17d86a868", 174.0, 3230, 1740, 10, 61.349999999999994, 112.65, listOf(Move(75, 98, 21.35), Move(377, 422, 38.25), Move(714, 737, 57.35), Move(1042, 1087, 113.25), Move(1343, 1366, 132.35), Move(1681, 1726, 149.25))),
        Key("fujifilm_provia_100f", MediumFormat.MF645) to Layout("mf120-v2-provia100f-a897d5f2ea77", 47.5, 3862, 476, 10, 11.720089915966383, 35.77991008403362, listOf(Move(108, 153, 36.3797))),
        Key("fujifilm_provia_100f", MediumFormat.MF66) to Layout("mf120-v2-provia100f-a897d5f2ea77", 62.0, 3790, 620, 10, 17.8, 44.2, listOf(Move(180, 225, 5.9), Move(597, 615, 48.6))),
        Key("fujifilm_provia_100f", MediumFormat.MF67) to Layout("mf120-v2-provia100f-a897d5f2ea77", 76.0, 3720, 760, 10, 24.8, 51.2, listOf(Move(250, 295, 12.9), Move(667, 712, 54.6))),
        Key("fujifilm_provia_100f", MediumFormat.MF69) to Layout("mf120-v2-provia100f-a897d5f2ea77", 90.0, 3650, 900, 10, 31.8, 58.2, listOf(Move(320, 365, 17.25), Move(737, 782, 74.0))),
        Key("fujifilm_provia_100f", MediumFormat.MF617) to Layout("mf120-v2-provia100f-a897d5f2ea77", 174.0, 3230, 1740, 10, 73.8, 100.2, listOf(Move(323, 358, 21.2), Move(740, 785, 50.25), Move(1157, 1202, 119.25), Move(1574, 1618, 143.3))),
        Key("fujifilm_velvia_100", MediumFormat.MF645) to Layout("mf120-v2-velvia100-9836225fb731", 47.5, 3862, 476, 10, 3.0, 44.5, listOf()),
        Key("fujifilm_velvia_100", MediumFormat.MF66) to Layout("mf120-v2-velvia100-9836225fb731", 62.0, 3790, 620, 10, 3.0, 59.0, listOf()),
        Key("fujifilm_velvia_100", MediumFormat.MF67) to Layout("mf120-v2-velvia100-9836225fb731", 76.0, 3720, 760, 10, 3.0, 73.0, listOf()),
        Key("fujifilm_velvia_100", MediumFormat.MF69) to Layout("mf120-v2-velvia100-9836225fb731", 90.0, 3650, 900, 10, 3.0, 87.0, listOf()),
        Key("fujifilm_velvia_100", MediumFormat.MF617) to Layout("mf120-v2-velvia100-9836225fb731", 174.0, 3230, 1740, 10, 3.0, 171.0, listOf()),
        Key("fujifilm_pro_400h", MediumFormat.MF645) to Layout("mf120-v2-pro400h-2d036c5cba8c", 47.5, 3862, 476, 10, 3.0, 44.5, listOf()),
        Key("fujifilm_pro_400h", MediumFormat.MF66) to Layout("mf120-v2-pro400h-2d036c5cba8c", 62.0, 3790, 620, 10, 3.0, 59.0, listOf()),
        Key("fujifilm_pro_400h", MediumFormat.MF67) to Layout("mf120-v2-pro400h-2d036c5cba8c", 76.0, 3720, 760, 10, 3.0, 73.0, listOf()),
        Key("fujifilm_pro_400h", MediumFormat.MF69) to Layout("mf120-v2-pro400h-2d036c5cba8c", 90.0, 3650, 900, 10, 3.0, 87.0, listOf()),
        Key("fujifilm_pro_400h", MediumFormat.MF617) to Layout("mf120-v2-pro400h-2d036c5cba8c", 174.0, 3230, 1740, 10, 3.0, 171.0, listOf()),

    )
    fun layout(snapshot:BorderSnapshot):Layout? {
        if(snapshot.geometryVersion!=5 || snapshot.stripCenterMm!=410.0 || snapshot.mediumLayout==null || snapshot.appearance==null)return null
        val stock=MediumFormatStocks.profiles[snapshot.stock] ?: return null
        val layout=layouts[Key(stock,snapshot.mediumFormat ?: return null)] ?: return null
        return layout.takeIf{it.assetVersion==snapshot.assetVersion}
    }
    /** Caller enables this only for newly captured, enabled imprint settings. Fail closed. */
    fun wrap(snapshot:BorderSnapshot,raster:BorderRaster):BorderRaster {
        val layout=layout(snapshot) ?: return raster
        val physical=when(raster){
            is MediumStripRaster -> raster
            is MediumStripProvider.Alias -> raster.physical
            else -> return raster
        }
        if(physical.version!=layout.assetVersion ||
            physical.stock!=MediumFormatStocks.profiles[snapshot.stock])return raster
        raster.validate(snapshot)
        return Standardized(snapshot,raster,layout)
    }
    private class Standardized(
        private val frozen:BorderSnapshot,private val original:BorderRaster,private val layout:Layout,
    ):BorderRaster by original {
        // No copied pixels or photo-sized intermediate. Layout metadata is shared and immutable.
        override val residentBytes get()=original.residentBytes+256L
        override fun validate(snapshot:BorderSnapshot) {
            require(snapshot==frozen){"120 imprint wrapper belongs to another snapshot"}
            original.validate(snapshot)
            require(residentBytes<=BorderCompositor.MAX_ATLAS_BYTES)
        }
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray) {
            require(snapshot==frozen){"120 imprint wrapper belongs to another snapshot"}
            val g=requireNotNull(snapshot.mediumLayout)
            val mx=(x-snapshot.frameOriginX)/snapshot.pixelsPerMm
            val my=(y-snapshot.frameOriginY)/snapshot.pixelsPerMm
            val local=if(g.rotated)my else mx
            val cross=if(g.rotated)61.0-mx else my
            if(cross<58.5 || local<0.0 || local>layout.widthMm) {
                original.sample(snapshot,x,y,rgb);return
            }
            val source=layout.sourceX(local)
            if(source==null) {
                // Same authored neutral base and existing material presentation as MediumStripRaster.
                if(snapshot.appearance!!.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION){
                    rgb[0]=.58f;rgb[1]=.29f;rgb[2]=.12f
                }else{
                    val base=if(original.stock=="kodak_portra_160")9f/255 else 8f/255
                    rgb[0]=base;rgb[1]=base;rgb[2]=base
                }
                return
            }
            // PDF places the integer crop at physical W; 645 has a tiny crop-rounding scale.
            // Clamp to source crop pixel centers, never import ink from outside the reviewed crop.
            val along=(layout.cropStartPx+source.coerceIn(.5,layout.cropWidthPx-.5))/layout.sourcePixelsPerMm
            val mapped=along-(410.0-layout.widthMm/2)
            if(g.rotated)original.sample(snapshot,x,snapshot.frameOriginY+mapped*snapshot.pixelsPerMm,rgb)
            else original.sample(snapshot,snapshot.frameOriginX+mapped*snapshot.pixelsPerMm,y,rgb)
        }
    }
}
