package dev.filmroom.ui

/** Browsing state only: filtering must never mutate the editor's chosen stock. Categories overlap. */
object FilmCatalogFilter {
    enum class Category { ALL, COLOR, BW, REVERSAL, MOVIE }
    private val monochrome = setOf("kodak_doublex", "kodak_trix", "fujifilm_neopan_acros_100_ii", "ilford_hp5_plus_400", "kodak_tmax_100", "kodak_tri_x_400")
    private val motion = setOf("kodak_doublex", "kodak_trix", "kodak_verita_200d", "kodak_vision3_50d", "kodak_vision3_200t", "kodak_vision3_250d", "kodak_vision3_500t", "cinestill_800t")
    val brands = listOf("kodak", "fujifilm", "ilford", "cinestill")
    fun brand(id: String): String? = brands.firstOrNull { id.startsWith(it + "_") }
    fun matches(id: String, kind: String, category: Category, brand: String?): Boolean {
        if (id == "none") return category == Category.ALL && brand == null
        if (kind !in setOf("negative", "reversal")) return false
        if (brand != null && brand(id) != brand) return false
        return when (category) {
            Category.ALL -> true
            Category.COLOR -> id !in monochrome
            Category.BW -> id in monochrome
            Category.REVERSAL -> kind == "reversal"
            Category.MOVIE -> id in motion
        }
    }
    fun restoreCategory(value: String?) = Category.values().firstOrNull { it.name == value } ?: Category.ALL
    fun restoreBrand(value: String?) = value?.takeIf { it in brands }
    fun label(key: String, language: String): String {
        val labels = when (language) {
            "en" -> listOf("All", "Color", "B&W", "Slide", "Brand", "Cinema", "All brands", "No matching films")
            "ja" -> listOf("すべて", "カラー", "白黒", "リバーサル", "ブランド", "映画用", "全ブランド", "該当するフィルムはありません")
            "ko" -> listOf("전체", "컬러", "흑백", "슬라이드", "브랜드", "영화용", "모든 브랜드", "해당 필름 없음")
            else -> listOf("全部", "彩色", "黑白", "反转", "品牌", "电影", "全部品牌", "暂无符合条件的胶卷")
        }
        return when (key) {
            "ALL" -> labels[0]; "COLOR" -> labels[1]; "BW" -> labels[2]; "REVERSAL" -> labels[3]
            "brand" -> labels[4]; "MOVIE" -> labels[5]; "allBrands" -> labels[6]; "empty" -> labels[7]
            "kodak" -> if (language == "zh") "柯达" else "Kodak"
            "fujifilm" -> if (language == "zh") "富士" else "Fujifilm"
            "ilford" -> if (language == "zh") "伊尔福" else "Ilford"
            "cinestill" -> "CineStill"
            else -> key
        }
    }
}
