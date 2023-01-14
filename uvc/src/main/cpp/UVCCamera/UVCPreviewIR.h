/*
 * UVCCamera
 * library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 * File name: UVCPreview.h
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
 * Files in the jni/libjpeg, jni/libusb, jin/libuvc, jni/rapidjson folder may have a different license, see the respective files.
*/

#ifndef UVCPREVIEW_IR_H_
#define UVCPREVIEW_IR_H_

#include "libUVCCamera.h"
#include "UVCPreview.h"
#ifdef _APPLE_
#include <OpenCL/cl.h>
#else
#include <CL/cl.h>
#endif
#include <pthread.h>
#include <android/native_window.h>
#include "objectarray.h"
#include "OpenCL_foundation.h"


struct irBuffer//使用专业级图像算法所需要的缓存
{
    size_t** midVar;
    unsigned char* destBuffer;
};

class UVCPreviewIR:public UVCPreview{
private:
	inline const bool isRunning() const;
	inline const bool isComputed() const;
	inline const bool isCapturing() const;
    unsigned short *mInitData;
    OpenCL_foundation *myOpencl;
	uvc_device_handle_t *mDeviceHandle;
	float *mTemperatureMeasure;
	//float TemperWhenShut,CoreTemperWhenShut,FpaTmpWhenShut;
	ANativeWindow *mPreviewWindow;
	volatile bool mIsRunning;
	int requestWidth, requestHeight, requestMode;
	int requestMinFps, requestMaxFps;
	float requestBandwidth;
	int frameWidth, frameHeight;
	int frameMode;
	unsigned char *OutBuffer;//使用完的buffer
	unsigned char *HoldBuffer;// 充满新数据的buffer
	unsigned char *RgbaOutBuffer;
	unsigned char *RgbaHoldBuffer;
	int OutPixelFormat;//回调图像输出形式,rgba=0, 原始输出或者yuyv=1
	size_t frameBytes;
	pthread_t preview_thread;
	pthread_mutex_t preview_mutex;
	pthread_cond_t preview_sync;
	bool had_recycled_frame;
	ObjectArray<uvc_frame_t *> previewFrames;
	bool add_frame_to_pool_queue(uvc_frame_t *frame);
	void signal_receive_frame_data();
	void addPreviewFrame(uvc_frame_t *frame);
	uvc_frame_t *get_first_preview_frame() ;
	uvc_frame_t *waitFrame();
	int previewFormat;
	size_t previewBytes;
	int mCurrentAndroidVersion;
	int copyToSurface(uint8_t *frameData, ANativeWindow **window);
//
	volatile bool mIsCapturing;
    volatile bool mIsComputed;
	ANativeWindow *mCaptureWindow;
	pthread_t capture_thread;
	pthread_mutex_t capture_mutex;
	pthread_cond_t capture_sync;
	uvc_frame_t *captureQueu;// keep latest frame
	jobject mFrameCallbackObj;
	convFunc_t mFrameCallbackFunc;
	Fields_iframecallback iframecallback_fields;
	Fields_iTemperatureCallback iTemperatureCallback;
	int mPixelFormat;
	size_t callbackPixelBytes;
// improve performance by reducing memory allocation
	pthread_mutex_t pool_mutex;
	pthread_mutex_t preview_pool_mutex;
	ObjectArray<uvc_frame_t *> mFramePool;
	uvc_frame_t *get_frame(size_t data_bytes);
	uvc_frame_t *get_preview_frame(size_t data_bytes);
	void recycle_frame(uvc_frame_t *frame);
	void recycle_preview_frame(uvc_frame_t *frame);
	void init_pool(size_t data_bytes);
	void init_preview_pool(size_t data_bytes);
	void clear_pool();
	void clear_preview_pool();
	int tempIndex;
	uvc_error_t when_cb_uvc_duplicate_frame(uvc_frame_t *frame,uvc_frame_t *copy);
    irBuffer* irBuffers;
//ir temperature
    bool mIsTemperaturing;
	pthread_t temperature_thread;
	pthread_mutex_t temperature_mutex;
	pthread_cond_t temperature_sync;
	uvc_frame_t *temperatureQueu;
	uvc_frame_t *ReadyToShow;
	jobject mTemperatureCallbackObj;
static void *temperature_thread_func(void *vptr_args);
void do_temperature(JNIEnv *env);
void addTemperatureFrame(uint8_t *frame);
uvc_frame_t  *waitTemperatureFrame();
inline  bool isTemperaturing();
void do_temperature_idle_loop(JNIEnv *env);
void do_temperature_callback(JNIEnv *env, uint8_t *frameData);

	//ir temp para
    int mTempRange;
    int frameNumber;
    /**
     *temperatureTable:温度映射表
     */
    float temperatureTable[16384];
    bool isNeedWriteTable;
    int mTypeOfPalette;
	//测温相关参数，详见thermometry.h
    int rangeMode;
    float floatFpaTmp;
    float correction;
    float Refltmp;
    float Airtmp;
    float humi;
    float emiss;
    unsigned short distance;
    int cameraLens;
    static int isCorrectOK;
    float shutterFix;
	//end -测温相关参数

    char sn[32];//camera序列码
    char cameraSoftVersion[16];//camera软件版本
    unsigned short shutTemper;
    float floatShutTemper;//快门温度
    unsigned short coreTemper;
    float floatCoreTemper;//外壳温度

    float mCbTemper[640*512+10] ;
    unsigned short detectAvg;
    unsigned short fpaTmp;
    unsigned short maxx1;
    unsigned short maxy1;
    unsigned short max;
    unsigned short minx1;
    unsigned short miny1;
    unsigned short min;
    unsigned short avg;
    const unsigned char* paletteIronRainbow;//256*3 铁虹
    const unsigned char* palette3;//256*3 彩虹1
    const unsigned char* paletteRainbow;//224*3 彩虹2
    const unsigned char* paletteHighRainbow;//448*3 高动态彩虹
    const unsigned char* paletteHighContrast;//448*3 高对比彩虹
    unsigned char UserPalette[256*3];


//
	void clearDisplay();
	static void uvc_preview_frame_callback(uint8_t *frame, void *vptr_args,size_t hold_bytes);
	void addFrame();
	uvc_frame_t *waitPreviewFrame();
	void clearPreviewFrame();
	static void *preview_thread_func(void *vptr_args);
	int prepare_preview(uvc_stream_ctrl_t *ctrl);
	void do_preview(uvc_stream_ctrl_t *ctrl);
	void draw_preview_one(uint8_t* frameData, ANativeWindow **window, convFunc_t func, int pixelBytes);
//
	void addCaptureFrame(uvc_frame_t *frame);
	uvc_frame_t *waitCaptureFrame();
	static void *capture_thread_func(void *vptr_args);
	void do_capture(JNIEnv *env);
	void do_capture_surface(JNIEnv *env);
	void do_capture_idle_loop(JNIEnv *env);
	void do_capture_callback(JNIEnv *env, uint8_t *frame);
	void callbackPixelFormatChanged();
	void SetShutValue(uint16_t value);
	void cpyAlterParaInvariability(uint8_t *frameData);





public:
    UVCPreviewIR();
	UVCPreviewIR(uvc_device_handle_t *devh);
	~UVCPreviewIR();
    void whenShutRefresh();
	int setPreviewSize(int width, int height, int min_fps, int max_fps, int mode, float bandwidth ,int currentAndroidVersion);
	int setPreviewDisplay(ANativeWindow *preview_window);
	int setFrameCallback(JNIEnv *env, jobject frame_callback_obj, int pixel_format);
	int setTemperatureCallback(JNIEnv *env, jobject temperature_callback_obj);
	int startPreview();
	int stopPreview();
	int stopTemp();
	int startTemp();
	int isCorrect();
    int stopCapture();
    int startCapture();
	void changePalette(int typeOfPalette);
	void setTempRange(int range);
	void setShutterFix(float mShutterFix);
	void setCameraLens(int mCameraLens);
	float singlePointDistanceFix(float mInputTemp,float mDistance);
	float singlePointThermFix(float mInputTemp,float mDistance);
	int getByteArrayPicture(uint8_t* frame);
	int getByteArrayTemperaturePara(uint8_t* para);
	int setCaptureDisplay(ANativeWindow *capture_window);
	void setUserPalette(uint8_t* palette,int typeOfPalette);
};

#endif /* UVCPREVIEW_H_ */
