package com.jiusiz.uvc

class NativeLib {

    /**
     * A native method that is implemented by the 'uvc' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // 用于在应用程序启动时加载“uvc”库。 Used to load the 'uvc' library on application startup.
        init {
            System.loadLibrary("uvc")
        }
    }
}