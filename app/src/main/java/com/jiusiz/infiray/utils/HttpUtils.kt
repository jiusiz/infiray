package com.jiusiz.infiray.utils

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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

    /**
     * 上传图片
     */
    fun uploadJpg(url: String, bytes: ByteArray): String {
        //设置mediatype
        val mediatype = "image/jpeg".toMediaTypeOrNull()
        //设置请求体
        val filebody = bytes.toRequestBody(mediatype)
        //文件上传需要使用MultipartBody
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("catId", "53A48CB6A5454A8D89327EA6B2E44DAB")
            .addFormDataPart("path", "/time_hil/sdey/img/chk")
            .addFormDataPart("file", "xxx.jpg", filebody)
            .build()
        //构造请求对象
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        //上传文件
        val response = client.newCall(request).execute()
        return response.body!!.string()
    }
}