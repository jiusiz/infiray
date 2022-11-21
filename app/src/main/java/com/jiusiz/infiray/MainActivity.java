package com.jiusiz.infiray;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

//import com.serenegiant.usb.ITemperatureCallback;
//import com.serenegiant.usb.IFrameCallback;
//import com.serenegiant.usb.UVCCamera;
//import com.serenegiant.usb.USBMonitor;
import com.jiusiz.uvc.ITemperatureCallback;
import com.jiusiz.uvc.IFrameCallback;
import com.jiusiz.uvc.UVCCamera;
import com.jiusiz.uvc.USBMonitor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArraySet;

//基本功能：
//1.打开相机/关闭相机
//2.开启测温/停止测温
// 3.拍照（保存图片）
// 4.打快门 5.切换色板（设置色板）
// 6.获取参数/设置参数
// 7.切换宽测温
public class MainActivity extends Activity {
    private int PREVIEW_WIDTH = 384;
    private int PREVIEW_HEIGHT = 292;
    private SimpleUVCCameraTextureView mUVCCameraView;
    private USBMonitor mUSBMonitor;
    private String TAG = "MainActivity";
    private Surface mPreviewSurface;
    private Button mCameraButton;
    private Button mShutter;
    private Button mColor;
    private Button mRange;
    //    private Button mPreview;
    private Button mCapture;
    private TextView tTemp;
    private int iColor = 0;//色板颜色
    private int TemperatureRange = 120;//色板颜色

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        Log.e(TAG, "onCreate:");
        mUVCCameraView = (SimpleUVCCameraTextureView) findViewById(R.id.camera_view123);
        mUVCCameraView.setAspectRatio(PREVIEW_WIDTH / (float) PREVIEW_HEIGHT);
        mCameraButton = (Button) findViewById(R.id.camera_button);
        mShutter = (Button) findViewById(R.id.shut_button);
        mColor = (Button) findViewById(R.id.color_button);
        mRange = (Button) findViewById(R.id.range_button);
//        mPreview = (Button) findViewById(R.id.preview_button);
        mCapture = (Button) findViewById(R.id.getcapture_button);
        tTemp = (TextView) findViewById(R.id.tv_temp);
        mCameraButton.setOnClickListener(mOnClickListener);
        mColor.setOnClickListener(mOnClickListener);
//        mPreview.setOnClickListener(mOnClickListener);
        mRange.setOnClickListener(mOnClickListener);
        mCapture.setOnClickListener(mOnClickListener);
        mShutter.setOnClickListener(mOnClickListener);
        mUSBMonitor = new USBMonitor(MainActivity.this, mOnDeviceConnectListener);

    }

    private boolean isMyDevice(UsbDevice udv) {
        return udv.getProductName().contains("T2") || udv.getProductName().contains("T3") || udv.getProductName().contains("T5") || udv.getProductName().contains("S0") || udv.getProductName().contains("Xtherm") || udv.getProductName().contains("Xmodule") || udv.getProductName().contains("FX3") || udv.getProductName().contains("PNS");
    }

    private void getDevice() {
        if (!mUSBMonitor.isRegistered()) {
            mUSBMonitor.register();
        }
        mUVCCameraView.onResume();
    }

    private boolean isTemp = false;
    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(final View view) {
            switch (view.getId()) {
//                case R.id.preview_button:
//                    getDevice();
//                    break;
                case R.id.camera_button:
                    synchronized (mSync) {
                        if (!isTemp) {
                            tempTureing();//开启测温
                            //Log.e(TAG, "isCorrect:" + mUVCCamera.isCorrect());
                        } else {
                            stopTemp();//关闭测温
                        }
                    }
                    break;
                case R.id.shut_button:
                    mUVCCamera.setZoom(0x8000);//打快门
//                    getTempPara();//获取参数
//                    getTempPara();//获取参数
//                    setDistance(1);//设置距离参数
//                    setCorrection(0);//设置校正
//                    setReflection(25);//设置反射温度
//                    setAmbtemp(25);//设置环境温度
//                    setHumidity(0.45f);//设置湿度
//                    setEmissivity(0.98f);//设置发射率
                    break;
                case R.id.getcapture_button:
                    Bitmap bitmap = mUVCCameraView.getBitmap();
                    saveImage(bitmap);//拍照
                    break;
                case R.id.range_button://切换宽测温
                    if (TemperatureRange == 120) {
                        TemperatureRange = 400;
                        mUVCCamera.setTempRange(TemperatureRange);
                        new Handler().postDelayed(() -> {
                            mUVCCamera.setZoom(0x8021);
                        }, 100);
                    } else {
                        TemperatureRange = 120;
                        mUVCCamera.setTempRange(TemperatureRange);
                        new Handler().postDelayed(() -> {
                            mUVCCamera.setZoom(0x8020);
                        }, 100);
                    }
                    new Handler().postDelayed(() -> {
                        mUVCCamera.setZoom(0x8000);
                    }, 600);
                    new Handler().postDelayed(() -> {
                        mUVCCamera.whenShutRefresh();
                    }, 1500);
                    break;
                case R.id.color_button:
                    switch (iColor) {
                        case 0:
                            mUVCCamera.changePalette(1);//切换色板
                            iColor = 1;
                            break;
                        case 1:
                            mUVCCamera.changePalette(2);
                            iColor = 2;
                            break;
                        case 2:
                            mUVCCamera.changePalette(3);
                            iColor = 3;
                            break;
                        case 3:
                            mUVCCamera.changePalette(4);
                            iColor = 4;
                            break;
                        case 4:
                            mUVCCamera.changePalette(5);
                            iColor = 5;
                        case 5:
                            mUVCCamera.changePalette(0);
                            iColor = 0;
                            break;
                    }

                    break;
            }
        }
    };

    // 每隔三分钟打一次快门
    private Timer timerEveryTime;

    private void everyShutter() {
        timerEveryTime = new Timer();
        timerEveryTime.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                mUVCCamera.setZoom(0x8000);//打快门
                Log.e(TAG, "每隔3分钟执行一次操作");
            }
        }, 1000, 180000);
    }

    // 保存图片
    public void saveImage(Bitmap bmp) {
        File appDir = new File(Environment.getExternalStorageDirectory(), "Xtherm");
        if (!appDir.exists()) {
            appDir.mkdir();
        }
        String fileName = System.currentTimeMillis() + ".jpg";
        File file = new File(appDir, fileName);
        try {
            FileOutputStream fos = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        if (!mUSBMonitor.isRegistered()) {
            mUSBMonitor.register();
        }
        super.onResume();
    }

    private void tempTureing() {
        ITemperatureCallback mTempCb = ahITemperatureCallback;
        mUVCCamera.setTemperatureCallback(mTempCb);
        mUVCCamera.startTemp();
        isTemp = true;
    }

    private void stopTemp() {
        mUVCCamera.stopTemp();
        isTemp = false;
    }

    @Override
    protected void onPause() {
        Log.e(TAG, "onPause:");
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.e(TAG, "onStop:");
        if (mUSBMonitor != null) {
            if (mUSBMonitor.isRegistered()) {
                mUSBMonitor.unregister();
            }
        }
        if (mUVCCameraView != null)
            mUVCCameraView.onPause();
        releaseCamera();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Log.e(TAG, "onDestroy:");
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        mUVCCameraView = null;
        mPreviewSurface = null;
        super.onDestroy();
    }

    //Activity从后台重新回到前台时被调用
    @Override
    protected void onRestart() {
        Log.e(TAG, "onRestart:");
        mUVCCameraView.onResume();
        super.onRestart();
    }

    private int mWidth = 384, mHeight = 292;
    private Object mSync = new Object();

    public void handleOpen(USBMonitor.UsbControlBlock ctrlBlock) {

        mUVCCamera = new UVCCamera(0);
        mUVCCamera.open(ctrlBlock);
//        mUVCCamera = camera;

        if (mUVCCamera != null) {
            String mSupportedSize = mUVCCamera.getSupportedSize();
            int find_str_postion = mSupportedSize.indexOf("384x292");
            if (find_str_postion >= 0) {
                mWidth = 384;
                mHeight = 292;
                Log.e(TAG, "handleOpen: 384 DEVICE ");
            }
            find_str_postion = mSupportedSize.indexOf("240x184");
            if (find_str_postion >= 0) {
                mWidth = 240;
                mHeight = 184;
                Log.e(TAG, "handleOpen: 240 DEVICE ");
            }
            find_str_postion = mSupportedSize.indexOf("256x196");
            if (find_str_postion >= 0) {
                mWidth = 256;
                mHeight = 196;
                Log.e(TAG, "handleOpen: 256 DEVICE ");
            }
            find_str_postion = mSupportedSize.indexOf("640x516");
            if (find_str_postion >= 0) {
                mWidth = 640;
                mHeight = 516;
                Log.e(TAG, "handleOpen: 640 DEVICE ");
            }
        }
    }

    private UsbDevice usbDevice;
    private USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            if (device.getDeviceClass() == 239 && device.getDeviceSubclass() == 2) {
                Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        usbDevice = device;
                        Log.e(TAG, "onAttach:" + usbDevice.getProductName());
                        mUSBMonitor.requestPermission(device);
                    }
                }, 100);
            }
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            Log.e(TAG, "onConnect:");
            handleOpen(ctrlBlock);
            startPreview();
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    UVCsetValue(UVCCamera.CTRL_ZOOM_ABS, 0x8004);//切换数据输出8004原始8005yuv,80ff保存
                }
            }, 300);


            /*handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.changePalette(3);//设置色板
                }
            }, 500);*/

        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            Log.e(TAG, "onDisconnect:");
        }

        @Override
        public void onDettach(UsbDevice device) {
            Log.e(TAG, "onDettach:");
//            Handler handler = new Handler(Looper.getMainLooper());
//            handler.postDelayed(new Runnable() {
//                @Override
//                public void run() {
            onPause();
            if (isTemp) {
                stopTemp();
            }
            onStop();
//                    onDestroy();
//                    android.os.Process.killProcess(android.os.Process.myPid());
//                    System.exit(0);
//                }
//            }, 100);
        }

        @Override
        public void onCancel(UsbDevice device) {
            Log.e(TAG, "onCancel:");
//            System.exit(0);
        }
    };

    private byte[] tempPara;
    private float Fix = 0, Refltmp = 0, Airtmp = 0, humi = 0, emiss = 0;
    private short distance = 0;
    private String stFix, stRefltmp, stAirtmp, stHumi, stEmiss, stDistance, stProductSoftVersion, SN;

    //获取参数
    private void getTempPara() {
        tempPara = mUVCCamera.getByteArrayTemperaturePara(128);
        Fix = getFloat(tempPara, 0);
        Refltmp = getFloat(tempPara, 4);
        Airtmp = getFloat(tempPara, 8);
        humi = getFloat(tempPara, 12);
        emiss = getFloat(tempPara, 16);
        distance = getShort(tempPara, 20);
        stFix = String.valueOf(Fix);
        stRefltmp = String.valueOf(Refltmp);
        stAirtmp = String.valueOf(Airtmp);
        stHumi = String.valueOf(humi);
        stEmiss = String.valueOf(emiss);
        stDistance = String.valueOf(distance);
        stProductSoftVersion = new String(tempPara, 128 - 16, 16);
        SN = usbDevice.getSerialNumber();
        Log.e(TAG, "校正:" + stFix //温度校正
                + ",反射温度:" + stRefltmp//反射温度
                + ",环境温度:" + stAirtmp//环境温度
                + ",湿度:" + stHumi//湿度
                + ",发射率:" + stEmiss//发射率
                + ",距离:" + stDistance//距离
                + ",SN:" + SN //产品序列号
                + ",产品固件版本:" + stProductSoftVersion);//产品固件版本
    }

    /**
     * 通过byte数组取到short
     */
    public static short getShort(byte[] b, int index) {
        return (short) (((b[index + 1] << 8) | b[index + 0] & 0xff));
    }

    /**
     * 通过byte数组取得float
     */
    public static float getFloat(byte[] b, int index) {
        int l;
        l = b[index + 0];
        l &= 0xff;
        l |= ((long) b[index + 1] << 8);
        l &= 0xffff;
        l |= ((long) b[index + 2] << 16);
        l &= 0xffffff;
        l |= ((long) b[index + 3] << 24);
        return Float.intBitsToFloat(l);
    }

    //释放相机
    private synchronized void releaseCamera() {
        Log.e(TAG, "releaseCamera:");
        synchronized (mSync) {
            if (mUVCCamera != null) {
                try {
//                    mUVCCameraView = null;
//                    mCameraButton = null;
//                    mUVCCamera.close();
                    mUVCCamera.destroy();
                } catch (Exception e) {
                    //
                }
                mUVCCamera = null;
            }
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "关闭红外相机！", Toast.LENGTH_SHORT)
                            .show());

        }
    }

    public interface CameraCallback {
        public void onOpen();

        public void onClose();

        public void onStartPreview();

        public void onStopPreview();

        public void onError(Exception e);
    }

    private Set<CameraCallback> mCallbacks = new CopyOnWriteArraySet<CameraCallback>();

    private void callOnError(Exception e) {
        for (CameraCallback callback : mCallbacks) {
            try {
                callback.onError(e);
            } catch (Exception e1) {
                mCallbacks.remove(callback);
                Log.w(TAG, e);
            }
        }
    }

    /**
     * for accessing UVC camera
     */
    private UVCCamera mUVCCamera;

    public void handleStartPreview(Object surface) {
        Log.e(TAG, "handleStartPreview:mUVCCamera" + mUVCCamera + " mIsPreviewing:");
        if ((mUVCCamera == null)) return;
        Log.e(TAG, "handleStartPreview2 ");
        try {
            mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 26, 0, UVCCamera.DEFAULT_BANDWIDTH, 0);
            Log.e(TAG, "handleStartPreview3 mWidth: " + mWidth + "mHeight:" + mHeight);
        } catch (IllegalArgumentException e) {
            try {
                // fallback to YUV mode
                mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 26, UVCCamera.DEFAULT_PREVIEW_MODE, UVCCamera.DEFAULT_BANDWIDTH, 0);
                Log.e(TAG, "handleStartPreview4");
            } catch (IllegalArgumentException e1) {
                callOnError(e1);
                return;
            }
        }
        if (surface instanceof SurfaceHolder) {
            Log.e(TAG, "SurfaceHolder:");
            mUVCCamera.setPreviewDisplay((SurfaceHolder) surface);
        } else if (surface instanceof Surface) {
            Log.e(TAG, "Surface:");
            mUVCCamera.setPreviewDisplay((Surface) surface);
        } else if (surface instanceof SurfaceTexture) {
            Log.e(TAG, "SurfaceTexture:");
            mUVCCamera.setPreviewTexture((SurfaceTexture) surface);
        }
        Log.e(TAG, "handleStartPreview: startPreview1");
        mUVCCamera.startPreview();
        /*===========================================================================
         * if need rgba callback
         *set this setFrameCallback(...) function
         *==========================================================================*/
        mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_RGBX);
        mUVCCamera.startCapture();
        Toast.makeText(MainActivity.this, "成功打开红外相机!", Toast.LENGTH_SHORT).show();
//        Log.e(TAG, "handleStartPreview: startPreview2：" + result);
        everyShutter();//每隔三分钟打一次快门
    }

    private byte[] FrameData = new byte[640 * 512 * 4];
    private final IFrameCallback mIFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frameData) {
//            Log.e(TAG, "mIFrameCallback: onFrame------");
            frameData.get(FrameData, 0, frameData.capacity());
//            Bitmap bm = Bitmap.createBitmap(mWidth, mHeight-4, Bitmap.Config.ARGB_8888);
//            bm.copyPixelsFromBuffer(ByteBuffer.wrap(FrameData));
            //bm操作
//            saveImage(bm);
        }
    };

    private void startPreview() {
        Log.e(TAG, "startPreview: getSurfaceTexture");
        SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
        mPreviewSurface = new Surface(st);
        handleStartPreview(mPreviewSurface);
    }

    public int UVCsetValue(int flag, int value) {
        if (mUVCCamera != null) {
            if (flag == UVCCamera.PU_BRIGHTNESS) {
                mUVCCamera.setBrightness(value);
                return mUVCCamera.getBrightness();
            } else if (flag == UVCCamera.PU_CONTRAST) {
                mUVCCamera.setContrast(value);
                return mUVCCamera.getContrast();
            } else if (flag == UVCCamera.CTRL_ZOOM_ABS) {
                mUVCCamera.setZoom(value);
                return 1;
            }
        }
        return 100;
    }

    //测温数据返回
    private float[] temperature1 = new float[640 * 512 + 10];
    public final ITemperatureCallback ahITemperatureCallback = new ITemperatureCallback() {
        @Override
        public void onReceiveTemperature(float[] temperature) {
            Log.e("temperature1[0]:", temperature1[0] + "");
            // temperature1[0]//中心温度
            //temperature1[1]//MAXX1，最高温点X坐标
            //temperature1[2]//MAXY1，最高温点Y坐标
            //temperature1[3]//最高温
            //temperature1[4]//MINX1，最低温点X坐标
            //temperature1[5] //MIXY1，最低温点Y坐标
            //temperature1[6]最低温，
            //7，8和9为备用参数
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tTemp.setText("中心温度：" + String.valueOf(temperature1[0]).substring(0, 5));
                }
            });
            System.arraycopy(temperature, 0, temperature1, 0, (mHeight - 4) * mWidth + 10);
        }
    };


    private ByteUtil mByteUtil = new ByteUtil();
    private sendCommand mSendCommand = new sendCommand();

    //设置校正
    private void setCorrection(float fiputCo) {
        byte[] iputCo = new byte[4];
        mByteUtil.putFloat(iputCo, fiputCo, 0);
        mSendCommand.sendFloatCommand(0 * 4, iputCo[0], iputCo[1], iputCo[2], iputCo[3], 20, 40, 60, 80, 120, 140);
    }

    //设置反射温度
    private void setReflection(float fiputRe) {
        byte[] iputRe = new byte[4];
        mByteUtil.putFloat(iputRe, fiputRe, 0);
        mSendCommand.sendFloatCommand(1 * 4, iputRe[0], iputRe[1], iputRe[2], iputRe[3], 20, 40, 60, 80, 120, 140);
    }

    //设置环境温度
    private void setAmbtemp(float fiputAm) {
        byte[] iputAm = new byte[4];
        mByteUtil.putFloat(iputAm, fiputAm, 0);
        mSendCommand.sendFloatCommand(2 * 4, iputAm[0], iputAm[1], iputAm[2], iputAm[3], 20, 40, 60, 80, 120, 140);
    }

    //设置湿度
    private void setHumidity(float fiputHu) {
        byte[] iputHu = new byte[4];
        mByteUtil.putFloat(iputHu, fiputHu, 0);
        mSendCommand.sendFloatCommand(3 * 4, iputHu[0], iputHu[1], iputHu[2], iputHu[3], 20, 40, 60, 80, 120, 140);
    }

    //设置发射率
    private void setEmissivity(float fiputEm) {
        byte[] iputEm = new byte[4];
        mByteUtil.putFloat(iputEm, fiputEm, 0);
        mSendCommand.sendFloatCommand(4 * 4, iputEm[0], iputEm[1], iputEm[2], iputEm[3], 20, 40, 60, 80, 120, 140);
    }

    //设置距离参数
    private void setDistance(int distance) {
        byte[] bIputDi = new byte[4];
        mByteUtil.putInt(bIputDi, distance, 0);
        mSendCommand.sendShortCommand(5 * 4, bIputDi[0], bIputDi[1], 20, 40, 60, 80);
    }

    public class sendCommand {
        int psitionAndValue0 = 0, psitionAndValue1 = 0, psitionAndValue2 = 0, psitionAndValue3 = 0;

        public void sendFloatCommand(int position, byte value0, byte value1, byte value2, byte value3, int interval0, int interval1, int interval2, int interval3, int interval4, int interval5) {
            psitionAndValue0 = (position << 8) | (0x000000ff & value0);
            Handler handler0 = new Handler();
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.setZoom(psitionAndValue0);
                }
            }, interval0);
            psitionAndValue1 = ((position + 1) << 8) | (0x000000ff & value1);
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.setZoom(psitionAndValue1);
                }
            }, interval1);
            psitionAndValue2 = ((position + 2) << 8) | (0x000000ff & value2);
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.setZoom(psitionAndValue2);
                }
            }, interval2);
            psitionAndValue3 = ((position + 3) << 8) | (0x000000ff & value3);
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.setZoom(psitionAndValue3);
                }
            }, interval3);
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.whenShutRefresh();
                }
            }, interval4);
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.setZoom(0x80ff);
                }
            }, interval5);
        }

        public void sendShortCommand(int position, byte value0, byte value1, int interval0, int interval1, int interval2, int interval3) {
            psitionAndValue0 = (position << 8) | (0x000000ff & value0);
            Handler handler0 = new Handler();
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.setZoom(psitionAndValue0);
                }
            }, interval0);
            psitionAndValue1 = ((position + 1) << 8) | (0x000000ff & value1);
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.setZoom(psitionAndValue1);
                }
            }, interval1);
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.whenShutRefresh();
                }
            }, interval2);
            handler0.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.setZoom(0x80ff);
                }
            }, interval3);
        }
    }

}
