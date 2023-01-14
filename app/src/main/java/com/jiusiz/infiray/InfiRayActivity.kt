package com.jiusiz.infiray

import android.app.Activity
import android.content.ContentValues
import android.graphics.*
import android.hardware.usb.UsbDevice
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.jiusiz.infiray.utils.ByteUtils
import com.jiusiz.infiray.view.SimpleUVCCameraTextureView
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.ITemperatureCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import java.io.OutputStream
import java.util.*
import kotlin.concurrent.thread

@RequiresApi(Build.VERSION_CODES.Q)
class InfiRayActivity : AppCompatActivity() {

    private val tag = "InfiRayActivity"
    private val previewWidth = 384.0
    private val previewHeight = 292.0
    private lateinit var mUVCCameraView: SimpleUVCCameraTextureView
    private lateinit var mUSBMonitor: USBMonitor
    private lateinit var mPreviewSurface: Surface
    private lateinit var mCameraButton: Button
    private lateinit var mShutter: Button
    private lateinit var mColor: Button
    private lateinit var mRange: Button
    private lateinit var mCapture: Button
    private lateinit var activity: Activity
    private lateinit var tempView: TextureView

    // 色板颜色
    private var iColor = 0

    // 色板颜色
    private var temperatureRange = 120

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_infi_ray)
        activity = this
        mUVCCameraView = findViewById(R.id.camera_preview)
        mUVCCameraView.setAspectRatio((previewWidth / previewHeight))
        // 画面选择90度
        //mUVCCameraView.rotation = 90F
        mCameraButton = findViewById(R.id.camera_button)
        mShutter = findViewById(R.id.shut_button)
        mColor = findViewById(R.id.color_button)
        mRange = findViewById(R.id.range_button)
        mCapture = findViewById(R.id.getcapture_button)
        tempView = findViewById(R.id.temp_view)

        tempView.isOpaque = false
        setClickListener()
        mUSBMonitor = USBMonitor(this, mOnDeviceConnectListener)
    }


    private fun setClickListener() {
        mCameraButton.setOnClickListener(mOnClickListener)
        mColor.setOnClickListener(mOnClickListener)
        mRange.setOnClickListener(mOnClickListener)
        mCapture.setOnClickListener(mOnClickListener)
        mShutter.setOnClickListener(mOnClickListener)
    }

    private var isTemp = false

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun takePicture() {
        val bitmap = mUVCCameraView.bitmap
        //拍照
        saveImage(bitmap)
    }

    private val mOnClickListener = View.OnClickListener { view ->
        when (view.id) {
            R.id.camera_button -> synchronized(mSync) {
                //开启测温
                if (!isTemp) turnOnTemp()
                // 关闭测温
                else stopTemp()
            }
            R.id.shut_button -> mUVCCamera.zoom = 0x8000 //打快门
            R.id.getcapture_button -> takePicture()
            R.id.range_button -> {
                if (temperatureRange == 120) {
                    temperatureRange = 400
                    mUVCCamera.setTempRange(temperatureRange)
                    Handler(Looper.getMainLooper()).postDelayed({
                        mUVCCamera.zoom = 0x8021
                    }, 100)
                } else {
                    temperatureRange = 120
                    mUVCCamera.setTempRange(temperatureRange)
                    Handler(Looper.getMainLooper()).postDelayed({
                        mUVCCamera.zoom = 0x8020
                    }, 100)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    mUVCCamera.zoom = 0x8000
                }, 600)
                Handler(Looper.getMainLooper()).postDelayed(
                    { mUVCCamera.whenShutRefresh() }, 1500
                )
            }
            R.id.color_button -> when (iColor) {
                0 -> {
                    // 切换色板
                    mUVCCamera.changePalette(1)
                    iColor = 1
                }
                1 -> {
                    mUVCCamera.changePalette(2)
                    iColor = 2
                }
                2 -> {
                    mUVCCamera.changePalette(3)
                    iColor = 3
                }
                3 -> {
                    mUVCCamera.changePalette(4)
                    iColor = 4
                }
                4 -> {
                    mUVCCamera.changePalette(5)
                    iColor = 5
                    //mUVCCamera.changePalette(0)
                    //iColor = 0
                }
                5 -> {
                    mUVCCamera.changePalette(0)
                    iColor = 0
                }
            }
        }
    }

    private var timerEveryTime = Timer()

    // 每隔三分钟打一次快门
    private fun everyShutter() {
        timerEveryTime.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                // 打快门
                mUVCCamera.zoom = 0x8000
                Log.i(tag, "每隔3分钟执行打快门")
            }
        }, 1000, 1000 * 60 * 3)
    }

    // 保存图片
    private fun saveImage(bmp: Bitmap?) {
        if (bmp == null) {
            runOnUiThread {
                Toast.makeText(this, "保存失败，图片为空", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val xZoom = bmp.width / 256.0f
        val yZoom = bmp.height / 192.0f
        val highX = temperature1[1] * xZoom
        val highY = temperature1[2] * yZoom
        val highT = temperature1[3]
        val lowX = temperature1[4] * xZoom
        val lowY = temperature1[5] * yZoom
        val lowT = temperature1[6]
        Log.i(tag, "bmp.width = ${bmp.width}")
        Log.i(tag, "bmp.height = ${bmp.height}")
        Log.i(tag, "highX = $highX，highY = $highY，highT = $highT")
        Log.i(tag, "lowX = $lowX，lowY = $lowY，lowT = $lowT")
        // 准备数据
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.RED
        paint.strokeWidth = paintStrokeWidth
        paint.textSize = paintTextSize

        val canvas = Canvas(bmp)
        canvas.drawLine(highX - 10, highY, highX + 10, highY, paint)
        canvas.drawLine(highX, highY - 10, highX, highY + 10, paint)
        //canvas.drawPoint(highX, highY, paint)
        canvas.drawText(highT.toString(), highX - 20, highY + 40, paint)
        canvas.drawLine(lowX - 10, lowY, lowX + 10, lowY, paint)
        canvas.drawLine(lowX, lowY - 10, lowX, lowY + 10, paint)
        //canvas.drawPoint(lowX, lowY, paint)
        canvas.drawText(lowT.toString(), lowX - 20, lowY + 40, paint)
        thread {
            //获取要保存的图片的位图,创建一个保存的Uri
            val values = ContentValues()
            //设置图片名称
            values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "infiray-${System.currentTimeMillis()}"
            )
            //设置图片格式
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            //设置图片路径
            values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/infiray"
            )
            val saveUri =
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (saveUri.toString().isBlank()) {
                runOnUiThread {
                    Toast.makeText(this, "保存失败！", Toast.LENGTH_SHORT).show()
                }
                return@thread
            }
            val outputStream: OutputStream? = contentResolver.openOutputStream(saveUri!!)
            if (bmp.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                runOnUiThread {
                    Toast.makeText(this, "保存成功！", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "保存失败！", Toast.LENGTH_SHORT).show()
                }
            }

            // 上传图片
            //val outputStream = ByteArrayOutputStream()
            //bmp.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            //val byteArray = outputStream.toByteArray()
            //val res = HttpUtils.uploadJpg("http://192.168.3.15:13421/up/file", byteArray)
            //Log.i(tag, "okhttp回调的数据为$res")
        }
    }

    override fun onResume() {
        if (!mUSBMonitor.isRegistered) {
            mUSBMonitor.register()
        }
        super.onResume()
    }

    private fun turnOnTemp() {
        // 避免未初始化
        if (this::mUVCCamera.isInitialized) {
            // 设置测温回调函数
            mUVCCamera.setTemperatureCallback(ahITemperatureCallback)
            mUVCCamera.startTemp()
            isTemp = true
        } else {
            runOnUiThread { Toast.makeText(this, "未插入摄像头！", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun stopTemp() {
        if (this::mUVCCamera.isInitialized) {
            mUVCCamera.stopTemp()
            isTemp = false
        }
        // 清除温度显示
        tempView.clean()
    }

    override fun onStop() {
        if (mUSBMonitor.isRegistered) {
            mUSBMonitor.unregister()
        }
        mUVCCameraView.onPause()
        releaseCamera()
        super.onStop()
    }

    override fun onDestroy() {
        mUSBMonitor.destroy()
        //mUSBMonitor = null
        //mUVCCameraView = null
        //mPreviewSurface = null
        super.onDestroy()
    }

    // Activity从后台重新回到前台时被调用
    override fun onRestart() {
        Log.e(tag, "onRestart:")
        mUVCCameraView.onResume()
        super.onRestart()
    }

    private var mWidth = 384
    private var mHeight = 292
    private val mSync = Any()

    fun handleOpen(ctrlBlock: UsbControlBlock?) {
        // 创建UVCCamera
        mUVCCamera = UVCCamera(0)
        // 开启
        mUVCCamera.open(ctrlBlock)
        val mSupportedSize = mUVCCamera.supportedSize
        if (mSupportedSize.contains("384x292")) {
            mWidth = 384
            mHeight = 292
        }
        if (mSupportedSize.contains("240x184")) {
            mWidth = 240
            mHeight = 184
        }
        if (mSupportedSize.contains("256x196")) {
            mWidth = 256
            mHeight = 196
        }
        if (mSupportedSize.contains("640x516")) {
            mWidth = 640
            mHeight = 516
        }
    }

    private lateinit var usbDevice: UsbDevice
    private val mOnDeviceConnectListener = object : OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            if (device.deviceClass == 239 && device.deviceSubclass == 2) {
                Handler(Looper.getMainLooper()).postDelayed({
                    usbDevice = device
                    // 请求权限
                    mUSBMonitor.requestPermission(device)
                }, 100)
            }
        }

        override fun onConnect(
            device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean
        ) {
            handleOpen(ctrlBlock)
            startPreview()
            Handler(Looper.getMainLooper()).postDelayed({
                // 切换数据输出，8004原始，8005yuv,80ff保存
                uvcSetValue(UVCCamera.CTRL_ZOOM_ABS, 0x8004)
            }, 300)
            Handler(Looper.getMainLooper()).postDelayed({
                // 切换色板
                mUVCCamera.changePalette(4)
                // 打开测温
                turnOnTemp()
            }, 1500)

        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
            runOnUiThread {
                Toast.makeText(activity, "设备断开了连接！", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onDettach(device: UsbDevice) {
            runOnUiThread {
                Toast.makeText(activity, "设备分离了！", Toast.LENGTH_SHORT).show()
            }
            runOnUiThread { onPause() }
            if (isTemp) {
                stopTemp()
            }
            runOnUiThread { onStop() }
        }

        override fun onCancel(device: UsbDevice) {
            runOnUiThread {
                Toast.makeText(activity, "取消授权！", Toast.LENGTH_SHORT).show()
            }
        }
    }// 温度校正

    private lateinit var tempPara: ByteArray
    private var Fix = 0f
    private var Refltmp = 0f
    private var Airtmp = 0f
    private var humi = 0f
    private var emiss = 0f
    private var distance: Short = 0
    private lateinit var stFix: String
    private lateinit var stRefltmp: String
    private lateinit var stAirtmp: String
    private lateinit var stHumi: String
    private lateinit var stEmiss: String
    private lateinit var stDistance: String
    private lateinit var stProductSoftVersion: String
    private lateinit var sn: String

    private fun getTempPara() {
        tempPara = mUVCCamera.getByteArrayTemperaturePara(128)
        Fix = getFloat(tempPara, 0)
        Refltmp = getFloat(tempPara, 4)
        Airtmp = getFloat(tempPara, 8)
        humi = getFloat(tempPara, 12)
        emiss = getFloat(tempPara, 16)
        distance = getShort(tempPara, 20)
        stFix = Fix.toString()
        stRefltmp = Refltmp.toString()
        stAirtmp = Airtmp.toString()
        stHumi = humi.toString()
        stEmiss = emiss.toString()
        stDistance = distance.toString()
        stProductSoftVersion = String(tempPara, 128 - 16, 16)
        sn = usbDevice.serialNumber.toString()
        Log.e(
            tag, "校正:" + stFix // 温度校正
                    + ",反射温度:" + stRefltmp // 反射温度
                    + ",环境温度:" + stAirtmp // 环境温度
                    + ",湿度:" + stHumi // 湿度
                    + ",发射率:" + stEmiss // 发射率
                    + ",距离:" + stDistance // 距离
                    + ",SN:" + sn // 产品序列号
                    + ",产品固件版本:" + stProductSoftVersion // 产品固件版本
        )
    }

    // 释放相机
    @Synchronized
    private fun releaseCamera() {
        synchronized(mSync) {
            try {
                // mUVCCameraView = null;
                // mCameraButton = null;
                // mUVCCamera.close();
                if (this::mUVCCamera.isInitialized) mUVCCamera.destroy()
            } catch (e: Exception) {
                //
            }
            // 避免未初始化
            if (this::mPreviewSurface.isInitialized) mPreviewSurface.release()

            //mPreviewSurface = null
            runOnUiThread {
                Toast.makeText(this, "关闭红外相机！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 用于访问 UVCCamera
     */
    private lateinit var mUVCCamera: UVCCamera
    private fun handleStartPreview(surface: Any?) {
        try {
            mUVCCamera.setPreviewSize(
                mWidth,
                mHeight,
                1,
                26,
                UVCCamera.DEFAULT_PREVIEW_MODE,
                UVCCamera.DEFAULT_BANDWIDTH,
                0
            )
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            //try {
            //    // fallback to YUV mode
            //    mUVCCamera.setPreviewSize(
            //        mWidth, mHeight, 1, 26,
            //        UVCCamera.DEFAULT_PREVIEW_MODE, UVCCamera.DEFAULT_BANDWIDTH, 0
            //    )
            //} catch (e1: IllegalArgumentException) {
            //    previewError(e1)
            //    return
            //}
        }
        when (surface) {
            is SurfaceHolder -> {
                mUVCCamera.setPreviewDisplay(surface)
            }
            is Surface -> {
                mUVCCamera.setPreviewDisplay(surface) // 设置预览
            }
            is SurfaceTexture -> {
                mUVCCamera.setPreviewTexture(surface)
            }
        }
        // 开始预览
        mUVCCamera.startPreview()
        // 设置帧回调
        mUVCCamera.setFrameCallback(
            mIFrameCallback, UVCCamera.PIXEL_FORMAT_RGBX
        )
        mUVCCamera.startCapture() // 开始捕捉
        Toast.makeText(this, "成功打开红外相机!", Toast.LENGTH_SHORT).show()
        // 每隔三分钟打一次快门
        everyShutter()
    }

    private val frameData = ByteArray(640 * 512 * 4)
    private val mIFrameCallback = IFrameCallback { frameData ->
        frameData[this.frameData, 0, frameData.capacity()]
    }

    // 开始预览
    private fun startPreview() {
        val st = mUVCCameraView.surfaceTexture
        mPreviewSurface = Surface(st)
        // 开始处理预览
        handleStartPreview(mPreviewSurface)
    }

    fun uvcSetValue(flag: Int, value: Int): Int {
        return when (flag) {
            UVCCamera.PU_BRIGHTNESS -> {
                mUVCCamera.brightness = value
                mUVCCamera.brightness
            }
            UVCCamera.PU_CONTRAST -> {
                mUVCCamera.contrast = value
                mUVCCamera.contrast
            }
            UVCCamera.CTRL_ZOOM_ABS -> {
                mUVCCamera.zoom = value
                1
            }
            else -> 100
        }
    }

    // 测温数据返回
    private var temperature1 = FloatArray(640 * 512 + 10)

    //temperature1[1]//MAXX1，最高温点X坐标
    //temperature1[2]//MAXY1，最高温点Y坐标
    //temperature1[4]//MINX1，最低温点X坐标
    //temperature1[5] //MIXY1，最低温点Y坐标
    private val ahITemperatureCallback = ITemperatureCallback { temperature ->
        val xZoom = mUVCCameraView.width / 256f
        val yZoom = mUVCCameraView.height / 192f
        runOnUiThread {
            tempView.drawTemp(
                temperature[1], temperature[2], temperature[3],
                temperature[4], temperature[5], temperature[6],
                xZoom, yZoom
            )
        }
        System.arraycopy(temperature, 0, temperature1, 0, (mHeight - 4) * mWidth + 10)
    }

    private val mSendCommand = SendCommand()

    //设置校正
    private fun setCorrection(fiputCo: Float) {
        val iputCo = ByteArray(4)
        ByteUtils.putFloat(iputCo, fiputCo, 0)
        mSendCommand.sendFloatCommand(
            0 * 4, iputCo[0], iputCo[1], iputCo[2], iputCo[3], 20, 40, 60, 80, 120, 140
        )
    }

    //设置反射温度
    private fun setReflection(fiputRe: Float) {
        val iputRe = ByteArray(4)
        ByteUtils.putFloat(iputRe, fiputRe, 0)
        mSendCommand.sendFloatCommand(
            1 * 4, iputRe[0], iputRe[1], iputRe[2], iputRe[3], 20, 40, 60, 80, 120, 140
        )
    }

    //设置环境温度
    private fun setAmbtemp(fiputAm: Float) {
        val iputAm = ByteArray(4)
        ByteUtils.putFloat(iputAm, fiputAm, 0)
        mSendCommand.sendFloatCommand(
            2 * 4, iputAm[0], iputAm[1], iputAm[2], iputAm[3], 20, 40, 60, 80, 120, 140
        )
    }

    //设置湿度
    private fun setHumidity(fiputHu: Float) {
        val iputHu = ByteArray(4)
        ByteUtils.putFloat(iputHu, fiputHu, 0)
        mSendCommand.sendFloatCommand(
            3 * 4, iputHu[0], iputHu[1], iputHu[2], iputHu[3], 20, 40, 60, 80, 120, 140
        )
    }

    //设置发射率
    private fun setEmissivity(fiputEm: Float) {
        val iputEm = ByteArray(4)
        ByteUtils.putFloat(iputEm, fiputEm, 0)
        mSendCommand.sendFloatCommand(
            4 * 4, iputEm[0], iputEm[1], iputEm[2], iputEm[3], 20, 40, 60, 80, 120, 140
        )
    }

    //设置距离参数
    private fun setDistance(distance: Int) {
        val bIputDi = ByteArray(4)
        ByteUtils.putInt(bIputDi, distance, 0)
        mSendCommand.sendShortCommand(5 * 4, bIputDi[0], bIputDi[1], 20, 40, 60, 80)
    }

    inner class SendCommand {
        var psitionAndValue0 = 0
        var psitionAndValue1 = 0
        var psitionAndValue2 = 0
        var psitionAndValue3 = 0
        fun sendFloatCommand(
            position: Int,
            value0: Byte,
            value1: Byte,
            value2: Byte,
            value3: Byte,
            interval0: Int,
            interval1: Int,
            interval2: Int,
            interval3: Int,
            interval4: Int,
            interval5: Int
        ) {
            psitionAndValue0 = position shl 8 or (0x000000ff and value0.toInt())
            val handler0 = Handler(Looper.getMainLooper())
            handler0.postDelayed({ mUVCCamera.zoom = psitionAndValue0 }, interval0.toLong())
            psitionAndValue1 = position + 1 shl 8 or (0x000000ff and value1.toInt())
            handler0.postDelayed({ mUVCCamera.zoom = psitionAndValue1 }, interval1.toLong())
            psitionAndValue2 = position + 2 shl 8 or (0x000000ff and value2.toInt())
            handler0.postDelayed({ mUVCCamera.zoom = psitionAndValue2 }, interval2.toLong())
            psitionAndValue3 = position + 3 shl 8 or (0x000000ff and value3.toInt())
            handler0.postDelayed({ mUVCCamera.zoom = psitionAndValue3 }, interval3.toLong())
            handler0.postDelayed({ mUVCCamera.whenShutRefresh() }, interval4.toLong())
            handler0.postDelayed({ mUVCCamera.zoom = 0x80ff }, interval5.toLong())
        }

        fun sendShortCommand(
            position: Int,
            value0: Byte,
            value1: Byte,
            interval0: Int,
            interval1: Int,
            interval2: Int,
            interval3: Int
        ) {
            psitionAndValue0 = position shl 8 or (0x000000ff and value0.toInt())
            val handler0 = Handler(Looper.getMainLooper())
            handler0.postDelayed({ mUVCCamera.zoom = psitionAndValue0 }, interval0.toLong())
            psitionAndValue1 = position + 1 shl 8 or (0x000000ff and value1.toInt())
            handler0.postDelayed({ mUVCCamera.zoom = psitionAndValue1 }, interval1.toLong())
            handler0.postDelayed({ mUVCCamera.whenShutRefresh() }, interval2.toLong())
            handler0.postDelayed({ mUVCCamera.zoom = 0x80ff }, interval3.toLong())
        }
    }

    companion object {
        private const val paintTextSize = 30f
        private const val paintStrokeWidth = 3.4f

        /**
         * 通过byte数组取到short
         */
        fun getShort(b: ByteArray, index: Int): Short {
            val b1 = b[index + 1].toInt()
            val b0 = b[index].toInt()
            return ((b1 shl 8) or (b0 and 0xff)).toShort()
        }

        fun TextureView.clean() {
            val canvas = this.lockCanvas()
            if (canvas != null) {
                //绘制透明色
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                unlockCanvasAndPost(canvas)
            }
        }

        fun TextureView.drawTemp(
            highX: Float,
            highY: Float,
            highT: Float,
            lowX: Float,
            lowY: Float,
            lowT: Float,
            xZoom: Float,
            yZoom: Float
        ) {
            val mHighX = highX * xZoom
            val mHighY = highY * yZoom
            val mLowX = lowX * xZoom
            val mLowY = lowY * yZoom
            val canvas = this.lockCanvas()
            if (canvas != null) {
                //绘制透明色
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                // 准备数据
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.RED
                paint.strokeWidth = paintStrokeWidth
                paint.textSize = paintTextSize

                canvas.drawLine(mHighX - 10, mHighY, mHighX + 10, mHighY, paint)
                canvas.drawLine(mHighX, mHighY - 10, mHighX, mHighY + 10, paint)
                canvas.drawText(highT.toString(), mHighX - 20, mHighY + 40, paint)
                canvas.drawLine(mLowX - 10, mLowY, mLowX + 10, mLowY, paint)
                canvas.drawLine(mLowX, mLowY - 10, mLowX, mLowY + 10, paint)
                canvas.drawText(lowT.toString(), mLowX - 20, mLowY + 40, paint)
                unlockCanvasAndPost(canvas)
            }
        }

        /**
         * 通过byte数组取得float
         */
        fun getFloat(b: ByteArray, index: Int): Float {
            var l: Int = b[index].toInt()
            l = l and 0xff
            l = l or (b[index + 1].toLong() shl 8).toInt()
            l = l and 0xffff
            l = l or (b[index + 2].toLong() shl 16).toInt()
            l = l and 0xffffff
            l = l or (b[index + 3].toLong() shl 24).toInt()
            return java.lang.Float.intBitsToFloat(l)
        }
    }
}