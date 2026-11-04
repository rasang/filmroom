package dev.filmroom.border

/** Byte-pinned synthetic research strips; provenance and limitations: assets/medium120/README.txt. */
internal object MediumStripDefinitions {
    data class Definition(val stock:String,val path:String,val version:String,val width:Int,val height:Int,val pixelsPerMm:Int,val encodedBytes:Int,val sha256:String,
        val defaultCenterMm:Double=410.0,val coverageStartMm:Double=0.0,val coverageEndMm:Double=820.0,
    ) {
        init {
            require(defaultCenterMm.isFinite() && coverageStartMm.isFinite() && coverageEndMm.isFinite())
            require(coverageStartMm>=0.0 && coverageEndMm<=820.0 && coverageStartMm<coverageEndMm)
            require(defaultCenterMm in coverageStartMm..coverageEndMm)
        }
        fun verifyCoverage(snapshot:BorderSnapshot) {
            val center=requireNotNull(snapshot.stripCenterMm)
            val half=(requireNotNull(snapshot.mediumFormat).longitudinalMm+6.0)/2
            require(center-half>=coverageStartMm && center+half<=coverageEndMm){"120 crop exceeds documented strip coverage"}
        }
    }
    // Frozen v1 jobs must retain these exact paths, hashes and presentation coordinates.
    val legacyDefinitions=listOf(
        Definition("kodak_portra_160","medium120/portra160-rails.png","mf120-v1-portra160-c9c42f121b4d",4920,30,6,7565,"c9c42f121b4d8441f78844e85cf5b97ee76fce0b41b370a9d80328a579ebadfb"),
        Definition("kodak_portra_400","medium120/portra400-rails.png","mf120-v1-portra400-320ec685f5e9",8200,50,10,8271,"320ec685f5e9d88edf48651b723459c83ab59590a0c9d43021b23aa449326b3c"),
        Definition("kodak_portra_800","medium120/portra800-rails.png","mf120-v1-portra800-97f3db5dcab7",8200,50,10,14317,"97f3db5dcab7182c80bea0ae75c2d6d74b655680b53c1bf9a5dddbb658f5e6ac"),
        Definition("kodak_gold_200","medium120/gold200-rails.png","mf120-v1-gold200-489478cd6d24",4920,30,6,3144,"489478cd6d24a7f4d10a54d5efb8b0557aa5338fff5bcd431844d436a3441bc6"),
        Definition("kodak_ektar_100","medium120/ektar100-rails.png","mf120-v1-ektar100-0455d81beb16",4920,30,6,4194,"0455d81beb1668b2d7ce3314ad60e2578321d15d6fa5cb3261703bcae6b480db"),
        Definition("kodak_ektachrome_100","medium120/e100-rails.png","mf120-v1-e100-82c3280b815f",4920,30,6,2785,"82c3280b815f3f1c37170f0cde93ad6d48c1f6f0584a83660ae066b3c9ccfebb"),
        Definition("fujifilm_provia_100f","medium120/provia100f-rails.png","mf120-v1-provia100f-b42a9f003985",8200,50,10,8565,"b42a9f00398587ddab5d0ce23145302bcf8989e40a001ba29c1cd310e40b3339"),
        Definition("fujifilm_velvia_100","medium120/velvia100-rails.png","mf120-v1-velvia100-cac0f6246306",8200,50,10,7155,"cac0f6246306d65351d360a2b725078eef9d235710e1827460aea627ed40bd13"),
        Definition("fujifilm_pro_400h","medium120/pro400h-rails.png","mf120-v1-pro400h-111c05d1353e",8200,50,10,7364,"111c05d1353e9ee9aaa2fc5f8c06871038c12b2eb19efc6d282d193985831191"),
    )
    // Append byte-pinned revisions here; never replace a definition referenced by a queue snapshot.
    private val currentRevisions=listOf<Definition>(
        Definition("kodak_ektachrome_100","medium120/v2/e100-rails.png","mf120-v2-e100-221b4b71c6e5",8200,50,10,20263,"221b4b71c6e58730e5d5f0bab78ded240b9ff9c44ab7b6e3027327683e431329",410.0,319.6882909667428,500.3117090332572),
        Definition("fujifilm_pro_400h","medium120/v2/pro400h-rails.png","mf120-v2-pro400h-2d036c5cba8c",8200,50,10,7593,"2d036c5cba8cc72bb0388f681c4b831077e07d1118ae86bafe4334b274520f56",410.0,0.0,820.0),
        Definition("fujifilm_velvia_100","medium120/v2/velvia100-rails.png","mf120-v2-velvia100-9836225fb731",8200,50,10,7263,"9836225fb7315e1b763a92e94acd162ee2f69c10e9a96fc172301c26d15575fd",410.0,0.0,820.0),
        Definition("fujifilm_provia_100f","medium120/v2/provia100f-rails.png","mf120-v2-provia100f-a897d5f2ea77",8200,50,10,9187,"a897d5f2ea7723d1237319d4bc2df62276c02ffd893766d8693f3ad275d37e91",410.0,0.0,820.0),
        Definition("kodak_portra_400","medium120/v2/portra400-rails.png","mf120-v2-portra400-a096b757d518",8200,50,10,29958,"a096b757d5185c58df1e90b374c7d9afa3e75c68059789004623341af287b1b6",410.0,285.211538,500.865385),
        Definition("kodak_gold_200","medium120/v2/gold200-rails.png","mf120-v2-gold200-12416fd08dba",8200,50,10,21783,"12416fd08dbaf71928c91dd3393f3b40db80435f3ad2108e47824aacd07ff10a",410.0,292.6666666666667,527.3333333333334),
        Definition("kodak_ektar_100","medium120/v2/ektar100-rails.png","mf120-v2-ektar100-98df0ed38e94",8200,50,10,29660,"98df0ed38e94962fa8665470be42015c3a2def8e3599df9ac6f375d78cf21359",410.0,319.85135135135135,500.14864864864865),
        Definition("kodak_portra_400","medium120/v4/portra400-rails.png","mf120-v4-portra400-e4cbfdada2fd",8200,50,10,28577,"e4cbfdada2fd01c9e949ce4bd38c5176fefe0aa0d65e1ab7aa5cfe9e17b901c1",410.0,285.211538,500.865385),
        Definition("kodak_portra_800","medium120/v4/portra800-rails.png","mf120-v4-portra800-d54e235ec886",8200,50,10,14241,"d54e235ec886634867e407def76d135cd37e0c4b6a21e68aaf98af9046ccc7fa",410.0,0.0,820.0),
        Definition("kodak_gold_200","medium120/v4/gold200-rails.png","mf120-v4-gold200-6fad2b869ee0",8200,50,10,21332,"6fad2b869ee046c84d0c573af2803bb9e648aa1ac02d355865e4260e4036b166",410.0,292.6666666666667,527.3333333333334),
        Definition("kodak_ektar_100","medium120/v4/ektar100-rails.png","mf120-v4-ektar100-91d648b6bb15",8200,50,10,27599,"91d648b6bb153c208c67f645e779b0f7cf78f10ce906f12eed3fefaa72043f58",410.0,319.85135135135135,500.14864864864865),
        Definition("kodak_ektachrome_100","medium120/v4/e100-rails.png","mf120-v4-e100-12d17d86a868",8200,50,10,18200,"12d17d86a86873ba8b71534402043ee2eba896d282389ca82816fd4265a5b4ae",410.0,319.6882909667428,500.3117090332572),
        Definition("kodak_portra_160","medium120/v5/portra160-rails.png","mf120-v5-portra160-a970a81fe0dc",4920,30,6,7231,"a970a81fe0dc3449164f0f831a73dc5cb0045d82064f33b0c1b359e68f844c2a",410.0,0.0,820.0),
        Definition("kodak_portra_400","medium120/v5/portra400-rails.png","mf120-v5-portra400-b6bc018bda16",8200,50,10,28606,"b6bc018bda16c372a8f162553514ab74dd4f435a1bacea7bd67b233627d90618",410.0,285.211538,500.865385),
        Definition("kodak_portra_800","medium120/v5/portra800-rails.png","mf120-v5-portra800-64b689c66093",8200,50,10,13946,"64b689c66093f1417fa25cefcb35472d4e2ab802d51b0eef420101e8017fb2cd",410.0,0.0,820.0),
        Definition("kodak_portra_400","medium120/v6/portra400-rails.png","mf120-v6-portra400-3c329d78f008",8200,50,10,29139,"3c329d78f008485437704285ae4441d974b82addc4787cb406c5178578883fd5",410.0,285.211538,500.865385),
    )
    internal class Registry(all:List<Definition>) {
        private val byVersion=all.associateBy{it.stock to it.version}
        val current=all.associateBy{it.stock}
        init { require(byVersion.size==all.size){"Duplicate 120 stock/version registration"} }
        fun exact(stock:String,version:String)=requireNotNull(byVersion[stock to version]){"Unknown frozen 120 asset version"}
        fun latest(stock:String)=requireNotNull(current[stock]){"Unknown 120 physical stock"}
    }
    val registry=Registry(legacyDefinitions+currentRevisions)
    val definitions=registry.current
}
