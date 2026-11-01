package dev.filmroom.io.importing

import dev.filmroom.io.*

internal class RasterInputAdapter(private val decoder: RasterPreviewDecoder): PhotoInputAdapter {
    override fun accepts(header: PhotoInputHeader) = header.rasterMime != null
    override suspend fun load(request: PhotoImportRequest, header: PhotoInputHeader): LoadedPhoto = with(request) {
        val rasterMime=checkNotNull(header.rasterMime)
        if(rasterMime=="image/png")StaticPng.requireStill(file,job)
        val decoded=decoder.decode(file,maxEdge,job)
        val preview=decoded.bitmap;val w=decoded.width;val h=decoded.height;val alpha=decoded.alpha
        return@with LoadedPhoto(PhotoSource(file,name,rasterMime,w,h,file.length(),alpha,photoMetadata=captureMetadata),preview)
    }
}
