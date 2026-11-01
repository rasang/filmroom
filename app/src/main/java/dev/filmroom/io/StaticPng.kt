package dev.filmroom.io

import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** acTL must precede IDAT in APNG. Seek bounded chunk headers, never load image data. */
object StaticPng {
    fun requireStill(file:File,job:CoroutineContext) {
        RandomAccessFile(file,"r").use {input->
            input.seek(8)
            repeat(4096){
                job.ensureActive()
                if(input.filePointer>input.length()-12)throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_e9d5f1386eb5))
                val length=input.readInt().toLong() and 0xffffffffL
                val type=input.readInt()
                if(length>input.length()-input.filePointer-4)throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_e9d5f1386eb5))
                if(type==0x6163544c)throw PhotoException(PhotoException.Reason.UNSUPPORTED,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_dac02f5d66dd))
                if(type==0x49444154)return
                if(type==0x49454e44)throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_cab3449611ff))
                input.seek(input.filePointer+length+4)
            }
            throw PhotoException(PhotoException.Reason.UNSUPPORTED,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_d3826ba783c7))
        }
    }
}
