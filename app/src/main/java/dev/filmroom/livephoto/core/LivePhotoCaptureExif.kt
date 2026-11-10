package dev.filmroom.livephoto.core

/** Validated original capture facts. No IFD pointers, output dimensions or vendor flags. */
data class LivePhotoCaptureExif(val group: Int, val tag: Int, val type: Int, val data: ByteArray) {
    internal fun validate() {
        require(group in 0..2 && (group to tag) in allowed) { "Not a capture EXIF field" }
        require(type == allowed.getValue(group to tag)) { "Unexpected capture EXIF type" }
        val unit=when(type){1,2,7->1;3->2;4->4;5,10->8;else->error("Unsupported EXIF type")}
        require(data.isNotEmpty() && data.size<=(if(type==7)8202 else 4097) && data.size%unit==0)
        if(type==2) require(data.last()==0.toByte()) { "ASCII EXIF must be terminated" }
        else if(type!=7) {
            val count=when {
                group==1 && tag==0xa432 -> 4
                group==2 && tag in setOf(2,4,7,20,22) -> 3
                group==2 && tag==0 -> 4
                else -> 1
            }
            require(data.size==count*unit) { "Unexpected capture EXIF count" }
        }
    }
    companion object {
        private val allowed=buildMap<Pair<Int,Int>,Int> {
            fun tags(group:Int,type:Int,vararg tags:Int){tags.forEach{put(group to it,type)}}
            tags(0,2,0x10e,0x10f,0x110,0x13b,0x8298)
            tags(0,3,0x4746,0x4749)
            tags(1,2,0x9004,0x9012,0x9292,0xa430,0xa431,0xa433,0xa434,0xa435,0xa420)
            tags(1,5,0x829a,0x829d,0x9202,0x9205,0x9206,0x920a,0xa404,0xa215,0xa432)
            tags(1,10,0x9201,0x9203,0x9204)
            tags(1,3,0x8827,0x8830,0x8822,0x9207,0x9208,0x9209,0xa405,0xa402,0xa403,0xa406,0xa407,0xa408,0xa409,0xa40a,0xa40c)
            tags(1,4,0x8831,0x8832,0x8833)
            tags(2,2,1,3,8,9,10,12,14,16,18,19,21,23,25,29)
            tags(2,5,2,4,6,7,11,13,15,17,20,22,24,26,31)
            tags(2,1,0,5)
            tags(2,3,30)
            tags(2,7,27,28)
        }
        fun accepts(group:Int,tag:Int)=group to tag in allowed
    }
}
