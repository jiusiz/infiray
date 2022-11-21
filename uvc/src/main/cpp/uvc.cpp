#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
// 在这里写原生c++程序
// Java_包名_类名_方法名
Java_com_jiusiz_uvc_NativeLib_stringFromJNI(JNIEnv *env, jobject /* this */)
{
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}
