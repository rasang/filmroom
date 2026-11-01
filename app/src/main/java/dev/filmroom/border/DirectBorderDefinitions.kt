package dev.filmroom.border

/** Route-specific reference-model material. Existing45 positive/legacy contracts are unchanged.
 * Dmin/base and channelwise Dmax are model endpoints, NOT measured factory edge exposure.
 * Fuji colored tracks use independently reviewed separated-dye illustration; PRO400H line color is unverified. */
internal object DirectBorderDefinitions {
    val definitions:List<LazyBorderRegistry.Definition> = listOf(
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_ektar_100","fixed135-direct-v12-white-3ce1f2ed8271254a","3ce1f2ed8271254ae09b9d09284df1366d461d78cade969f802b885170cff1d1",28406,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_ektar_100-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_ektar_100","fixed135-direct-v12-black-3ce1f2ed8271254a","3ce1f2ed8271254ae09b9d09284df1366d461d78cade969f802b885170cff1d1",28406,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_ektar_100-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_gold_200","fixed135-direct-v12-white-6242499615a9b734","6242499615a9b734d23bb252f08784be1f566b08d33e71a17b33bd380a368b85",39776,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_gold_200-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_gold_200","fixed135-direct-v12-black-6242499615a9b734","6242499615a9b734d23bb252f08784be1f566b08d33e71a17b33bd380a368b85",39776,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_gold_200-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_portra_160","fixed135-direct-v12-white-4bdeb7d93d6a9aff","4bdeb7d93d6a9affbb4f379dbdeeb17f873a37c7c14c2de9ed329622f1221512",41382,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_portra_160-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_portra_160","fixed135-direct-v12-black-4bdeb7d93d6a9aff","4bdeb7d93d6a9affbb4f379dbdeeb17f873a37c7c14c2de9ed329622f1221512",41382,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_portra_160-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_portra_400","fixed135-direct-v12-white-ef2d4428f8fb85dc","ef2d4428f8fb85dc07a076987ec7a6006b62c1a6f34a94e7c48437cf5f5b4f5e",47126,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_portra_400-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_portra_400","fixed135-direct-v12-black-ef2d4428f8fb85dc","ef2d4428f8fb85dc07a076987ec7a6006b62c1a6f34a94e7c48437cf5f5b4f5e",47126,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_portra_400-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_portra_800","fixed135-direct-v12-white-8c04de8e4aebaeef","8c04de8e4aebaeeffe8487a6cf3eaf5057b01cdde9408f46206e7d973603b8ed",34004,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_portra_800-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_portra_800","fixed135-direct-v12-black-8c04de8e4aebaeef","8c04de8e4aebaeeffe8487a6cf3eaf5057b01cdde9408f46206e7d973603b8ed",34004,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_portra_800-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_ultramax_400","fixed135-direct-v12-white-8c99a01fe2ce9227","8c99a01fe2ce9227d1e0016415c399d95302c6c0a6adb772b6e1d510b97cd280",31818,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_ultramax_400-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_ultramax_400","fixed135-direct-v12-black-8c99a01fe2ce9227","8c99a01fe2ce9227d1e0016415c399d95302c6c0a6adb772b6e1d510b97cd280",31818,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_ultramax_400-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_verita_200d","fixed135-direct-v12-white-2b57d15f5bde0a22","2b57d15f5bde0a224fcc284bf646c46b79f5ccfb28b47c9bd406bc89ec3b1f72",111946,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_verita_200d-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_verita_200d","fixed135-direct-v12-black-2b57d15f5bde0a22","2b57d15f5bde0a224fcc284bf646c46b79f5ccfb28b47c9bd406bc89ec3b1f72",111946,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_verita_200d-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_vision3_200t","fixed135-direct-v12-white-652cdadaf1b9c9bb","652cdadaf1b9c9bb141d0fa3b37fba3e80fd6f22f4b1d6e8c88e915966512c18",287338,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_vision3_200t-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_vision3_200t","fixed135-direct-v12-black-652cdadaf1b9c9bb","652cdadaf1b9c9bb141d0fa3b37fba3e80fd6f22f4b1d6e8c88e915966512c18",287338,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_vision3_200t-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_vision3_250d","fixed135-direct-v12-white-65b80cfaa05cc9d4","65b80cfaa05cc9d450311374b92df5698aa302f54b595b8b3f1449eef879ea5e",139894,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_vision3_250d-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_vision3_250d","fixed135-direct-v12-black-65b80cfaa05cc9d4","65b80cfaa05cc9d450311374b92df5698aa302f54b595b8b3f1449eef879ea5e",139894,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_vision3_250d-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_vision3_500t","fixed135-direct-v12-white-b211114472d41466","b211114472d414665134ca2b3e0814105c957ec3afaf2960316ce1e5b8bfada0",118258,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_vision3_500t-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_vision3_500t","fixed135-direct-v12-black-b211114472d41466","b211114472d414665134ca2b3e0814105c957ec3afaf2960316ce1e5b8bfada0",118258,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_vision3_500t-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_vision3_50d","fixed135-direct-v12-white-5ab106f001f4240d","5ab106f001f4240da746c493321b39274c74d692e2c7f5809275a0032b4b1ffd",213282,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/kodak_vision3_50d-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "kodak_vision3_50d","fixed135-direct-v12-black-5ab106f001f4240d","5ab106f001f4240da746c493321b39274c74d692e2c7f5809275a0032b4b1ffd",213282,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/kodak_vision3_50d-direct-model-v12.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "fujifilm_c200","fixed135-direct-v13-white-c70516a818cff4ed","c70516a818cff4ed98c7699c2c9a3ce1d4c278184a372a59d28e1a87754484b2",27050,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/fujifilm_c200-direct-model-v13.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "fujifilm_c200","fixed135-direct-v13-black-c70516a818cff4ed","c70516a818cff4ed98c7699c2c9a3ce1d4c278184a372a59d28e1a87754484b2",27050,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/fujifilm_c200-direct-model-v13.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "fujifilm_xtra_400","fixed135-direct-v13-white-aae191ded4fd3a66","aae191ded4fd3a66ce6f63a9b151ee7dacf85f2d8af2f88e24069fb5c8b52dbf",7944,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/fujifilm_xtra_400-direct-model-v13.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "fujifilm_xtra_400","fixed135-direct-v13-black-aae191ded4fd3a66","aae191ded4fd3a66ce6f63a9b151ee7dacf85f2d8af2f88e24069fb5c8b52dbf",7944,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/fujifilm_xtra_400-direct-model-v13.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "fujifilm_pro_400h","fixed135-direct-v13-white-7cb5199ed265bf45","7cb5199ed265bf45ddf024cdea99d75dc37ab20a3bad3fbc1898614b22182138",76814,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0xffffff),"fixed135/fujifilm_pro_400h-direct-model-v13.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION),
        LazyBorderRegistry.Definition(StraightAlphaBorderLoader.Spec(
            "fujifilm_pro_400h","fixed135-direct-v13-black-7cb5199ed265bf45","7cb5199ed265bf45ddf024cdea99d75dc37ab20a3bad3fbc1898614b22182138",76814,
            featherTotalAt3072=4.0,encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,holeMatteSrgb=0x0),"fixed135/fujifilm_pro_400h-direct-model-v13.webp",FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION)
    )
    fun selectedDefinition(profile:String,context:FrameAppearanceContext):LazyBorderRegistry.Definition {
        require(context.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION)
        val physical=CandidateBorderDefinitions.profiles[profile]?.physicalStock ?: throw IllegalArgumentException("Unknown border profile")
        return definitions.singleOrNull { it.spec.stock==physical && it.spec.holeMatteSrgb==context.holeMatteSrgb }
            ?: throw dev.filmroom.io.PhotoException(dev.filmroom.io.PhotoException.Reason.UNSUPPORTED,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_bec36dff07be))
    }
}
