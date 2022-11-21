package com.jiusiz.uvc

class NativeLib {

    /**
     * 由 uvc 原生库实现的原生方法，与此应用程序一起打包。
     * 在这里写c++原生方法的引用
     */
    external fun stringFromJNI(): String

    companion object {
        // 用于在应用程序启动时加载“uvc”库。 Used to load the 'uvc' library on application startup.
        init {
            System.loadLibrary("uvc")
        }
    }
}