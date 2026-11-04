package dev.filmroom.border

/** Byte-pinned synthetic research strips; provenance and limitations: assets/medium120/README.txt. */
internal object MediumStripDefinitions {
    data class Definition(val stock:String,val path:String,val version:String,val width:Int,val height:Int,val pixelsPerMm:Int,val encodedBytes:Int,val sha256:String)
    val definitions=listOf(
        Definition("kodak_portra_160","medium120/portra160-rails.png","mf120-v1-portra160-c9c42f121b4d",4920,30,6,7565,"c9c42f121b4d8441f78844e85cf5b97ee76fce0b41b370a9d80328a579ebadfb"),
        Definition("kodak_portra_400","medium120/portra400-rails.png","mf120-v1-portra400-320ec685f5e9",8200,50,10,8271,"320ec685f5e9d88edf48651b723459c83ab59590a0c9d43021b23aa449326b3c"),
        Definition("kodak_portra_800","medium120/portra800-rails.png","mf120-v1-portra800-97f3db5dcab7",8200,50,10,14317,"97f3db5dcab7182c80bea0ae75c2d6d74b655680b53c1bf9a5dddbb658f5e6ac"),
        Definition("kodak_gold_200","medium120/gold200-rails.png","mf120-v1-gold200-489478cd6d24",4920,30,6,3144,"489478cd6d24a7f4d10a54d5efb8b0557aa5338fff5bcd431844d436a3441bc6"),
        Definition("kodak_ektar_100","medium120/ektar100-rails.png","mf120-v1-ektar100-0455d81beb16",4920,30,6,4194,"0455d81beb1668b2d7ce3314ad60e2578321d15d6fa5cb3261703bcae6b480db"),
        Definition("kodak_ektachrome_100","medium120/e100-rails.png","mf120-v1-e100-82c3280b815f",4920,30,6,2785,"82c3280b815f3f1c37170f0cde93ad6d48c1f6f0584a83660ae066b3c9ccfebb"),
        Definition("fujifilm_provia_100f","medium120/provia100f-rails.png","mf120-v1-provia100f-b42a9f003985",8200,50,10,8565,"b42a9f00398587ddab5d0ce23145302bcf8989e40a001ba29c1cd310e40b3339"),
        Definition("fujifilm_velvia_100","medium120/velvia100-rails.png","mf120-v1-velvia100-cac0f6246306",8200,50,10,7155,"cac0f6246306d65351d360a2b725078eef9d235710e1827460aea627ed40bd13"),
        Definition("fujifilm_pro_400h","medium120/pro400h-rails.png","mf120-v1-pro400h-111c05d1353e",8200,50,10,7364,"111c05d1353e9ee9aaa2fc5f8c06871038c12b2eb19efc6d282d193985831191"),
    ).associateBy{it.stock}
}
