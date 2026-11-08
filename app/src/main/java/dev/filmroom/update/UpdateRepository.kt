package dev.filmroom.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

interface UpdateCache {
    data class Entry(val url:String,val body:String,val etag:String?)
    fun read():Entry?
    fun write(entry:Entry)
}
fun interface UpdateTransport {
    fun fetch(url:String,etag:String?):Response
    data class Response(val status:Int,val body:String?,val etag:String?)
}

class UpdateRepository(private val cache:UpdateCache,private val transport:UpdateTransport=HttpsUpdateTransport()) {
    /** Always revalidate online; never report an old cached result as a fresh check after failure. */
    fun check(url:String):UpdateManifest {
        SafeUpdateUrl.requireHttps(url)
        val previous=runCatching{cache.read()}.getOrNull()?.takeIf{it.url==url}
            ?.takeIf{runCatching{UpdateManifest.parse(it.body)}.isSuccess}
        val response=transport.fetch(url,previous?.etag?.takeIf(::safeEtag))
        val body=when(response.status){
            HttpURLConnection.HTTP_OK->response.body ?: throw IOException("Empty response")
            HttpURLConnection.HTTP_NOT_MODIFIED->previous?.body ?: throw IOException("Unusable cache")
            else->throw IOException("Update HTTP failure")
        }
        val result=UpdateManifest.parse(body)
        if(response.status==HttpURLConnection.HTTP_OK)runCatching{cache.write(UpdateCache.Entry(url,body,response.etag?.takeIf(::safeEtag)))}
        return result
    }
    private fun safeEtag(value:String)=value.length in 1..256&&value.none{it<' '||it=='\u007f'}
}

/** Small, manual-only GET. No automatic retries, redirects, cookies, IDs, or APK downloading. */
class HttpsUpdateTransport:UpdateTransport {
    override fun fetch(url:String,etag:String?):UpdateTransport.Response {
        val connection=URL(SafeUpdateUrl.requireHttps(url)).openConnection() as HttpURLConnection
        val started=System.nanoTime()
        try {
            connection.connectTimeout=6000;connection.readTimeout=6000
            connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.requestMethod="GET"
            connection.setRequestProperty("Accept","application/json")
            connection.setRequestProperty("Accept-Encoding","identity")
            connection.setRequestProperty("Cache-Control","no-cache")
            connection.setRequestProperty("User-Agent","Filmroom-Update")
            etag?.let{connection.setRequestProperty("If-None-Match",it)}
            val status=connection.responseCode
            if(status==304)return UpdateTransport.Response(304,null,null)
            if(status!=200)throw IOException("Update HTTP failure")
            if(connection.contentLengthLong>UpdateManifest.MAX_BYTES)throw IOException("Manifest too large")
            val encoding=connection.contentEncoding
            if(!encoding.isNullOrEmpty()&&!encoding.equals("identity",true))throw IOException("Unexpected encoding")
            val output=ByteArrayOutputStream()
            connection.inputStream.use{input->
                val buffer=ByteArray(4096)
                while(true){
                    if(Thread.currentThread().isInterrupted)throw CancellationException()
                    if(System.nanoTime()-started>15_000_000_000L)throw IOException("Update timeout")
                    val count=input.read(buffer);if(count<0)break
                    if(output.size()+count>UpdateManifest.MAX_BYTES)throw IOException("Manifest too large")
                    output.write(buffer,0,count)
                }
            }
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            val body=decoder.decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
            return UpdateTransport.Response(200,body,connection.getHeaderField("ETag"))
        } finally {connection.disconnect()}
    }
}
