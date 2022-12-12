package com.jiusiz.infiray.utils

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.OutputStream

object HttpUtils {
    private val client = OkHttpClient()

    fun postStream(url: String, call: (OutputStream) -> Unit) {

        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun writeTo(sink: BufferedSink) {
                call(sink.outputStream())
            }
        }
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        val body = client.newCall(request).execute().body
        if (body != null) {
            Log.i("okhttp", body.string())
        }
    }
}