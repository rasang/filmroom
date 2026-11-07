package dev.filmroom.border

/** User-approved positive-display A material and v2 25/25A drawing.
 * Each exact atlas is independent of 7266/5219. These are display reconstructions,
 * not measured emulsion density or certified/machine-readable edge codes.
 * Direct variants separately follow the existing D65 / profile base-to-Dmax
 * display-model rule. They are not light-table measurements or real negative scans.
 */
internal object Reviewed135Definitions {
    private data class Asset(val stock:String,val sha:String,val bytes:Int,
        val direct:Boolean=false)
    private val assets=listOf(
        Asset("cinestill_800t","e40bcf1b510e2c2c943e6fec9a89a7b973b936522cf049a7efe9242b4cb51c8d",175174),
        Asset("fujifilm_neopan_acros_100_ii","6ab165da32b8d2b27b12e5c7f604f21e6a7bd54ecccdfe5ba595f1fefbf85c51",157994),
        Asset("ilford_hp5_plus_400","d56f257a7d7b9b9de83dd044617d3933dc31cff377246364d97ee9390f80b5a7",170268),
        Asset("kodak_tmax_100","ecd9e3fa04929eca8bd8dcba1f9662ea66ac796d904f8b12c7ce1da2104547bb",166202),
        Asset("kodak_tri_x_400","5f60b2909c48706fb8ec5a05aee25bb865c1180ca0684eb8a46204f33bf036df",160338)
    )
    private val directAssets=listOf(
        Asset("kodak_tri_x_400","58abe9312055daa0f5f7eae544cd0bb4ac2c876c5bd85874528cc71a7e2c157c",8394,true),
        Asset("kodak_tmax_100","6f0fa50096ca17f955f4365ddcb32479b54efba6deef00e4e08fe03a80b068c4",8862,true),
        Asset("ilford_hp5_plus_400","5f6dfc6b83f0592d859c3b01bdfe7aff4282ecad511ec6145ffff96e982b4137",8292,true),
        Asset("fujifilm_neopan_acros_100_ii","1992f43727ac9890596fb52d67e9fa0b3d32eaf81e22d99e52337483c168b0fa",8358,true),
        Asset("cinestill_800t","ec855b3962e8bd634053e804634fbca9cdb6215757f630d88df1f8625efd886f",8168,true)
    )
    val stocks=assets.map{it.stock}.toSet()
    val definitions=(assets+directAssets).flatMap { asset ->
        listOf(false,true).map { white ->
            val variant=if(asset.direct)"direct-model" else "A"
            val version="reviewed135-v1-$variant-${asset.stock}-${if(white)"white" else "black"}-${asset.sha.take(12)}"
            LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
                asset.stock,version,asset.sha,asset.bytes,featherTotalAt3072=0.0,
                encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=if(white)0xffffff else 0),
                "fixed135/${asset.stock}-reviewed-$variant-v1.webp",
                if(asset.direct)FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION else FrameAppearanceContext.Material.POSITIVE_DISPLAY)
        }
    }
    val profiles=definitions.filter{it.spec.holeMatteSrgb==0xffffff && it.material==FrameAppearanceContext.Material.POSITIVE_DISPLAY}.associate{it.spec.stock to it.key}
    fun matches(stock:String,version:String)=definitions.any{it.spec.stock==stock && it.spec.version==version}
    fun selected(stock:String,context:FrameAppearanceContext):LazyBorderRegistry.Definition {
        return definitions.single{it.spec.stock==stock && it.spec.holeMatteSrgb==context.holeMatteSrgb && it.material==context.material}
    }
}
