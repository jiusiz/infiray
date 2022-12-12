package com.serenegiant.usb;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.serenegiant.utils.BuildCheck;
import com.serenegiant.utils.HandlerThreadHandler;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class USBMonitor {

    private static boolean DEBUG = true;    // TODO set false on production
    private static String TAG = "USBMonitor";

    private static String ACTION_USB_PERMISSION_BASE = "com.serenegiant.USB_PERMISSION.";
    private String ACTION_USB_PERMISSION = ACTION_USB_PERMISSION_BASE + hashCode();

    public static String ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";

    /**
     * openしているUsbControlBlock
     */
    private ConcurrentHashMap<UsbDevice, UsbControlBlock> mCtrlBlocks = new ConcurrentHashMap<>();
    private SparseArray<WeakReference<UsbDevice>> mHasPermissions = new SparseArray<>();

    private WeakReference<Context> mWeakContext;
    private UsbManager mUsbManager;
    private OnDeviceConnectListener mOnDeviceConnectListener;
    private PendingIntent mPermissionIntent = null;
    private List<DeviceFilter> mDeviceFilters = new ArrayList<>();

    /**
     * コールバックをワーカースレッドで呼び出すためのハンドラー
     */
    private Handler mAsyncHandler;
    private volatile boolean destroyed;

    /**
     * USB 设备状态改变时的回调监听器
     * USB機器の状態変更時のコールバックリスナー
     */
    public interface OnDeviceConnectListener {
        /**
         * 连接设备时调用
         * called when device attached
         */
        void onAttach(UsbDevice device);

        /**
         * 设备分离时调用（在 onDisconnect 之后）
         * called when device dettach(after onDisconnect)
         */
        void onDettach(UsbDevice device);

        /**
         * 设备打开后调用
         * called after device opend
         */
        void onConnect(UsbDevice device, UsbControlBlock ctrlBlock, boolean createNew);

        /**
         * USB 设备移除或关闭时调用（设备关闭后调用此回调）
         * called when USB device removed or its power off (this callback is called after device closing)
         */
        void onDisconnect(UsbDevice device, UsbControlBlock ctrlBlock);

        /**
         * 取消或无法获得用户许可时调用
         * called when canceled or could not get permission from user
         */
        void onCancel(UsbDevice device);
    }

    public USBMonitor(Context context, OnDeviceConnectListener listener) {
        if (DEBUG) Log.v(TAG, "USBMonitor:Constructor");
        if (listener == null)
            throw new IllegalArgumentException("OnDeviceConnectListener should not null.");
        mWeakContext = new WeakReference<Context>(context);
        mUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        mOnDeviceConnectListener = listener;
        mAsyncHandler = HandlerThreadHandler.createHandler(TAG);
        destroyed = false;
        if (DEBUG) Log.v(TAG, "USBMonitor:mUsbManager=" + mUsbManager);
    }

    /**
     * Release all related resources,
     * never reuse again
     */
    public void destroy() {
        if (DEBUG) Log.i(TAG, "destroy:");
        //unregister();
        if (!destroyed) {
            destroyed = true;
            // 关闭所有受监控的 USB 设备 モニターしているUSB機器を全てcloseする
            Set<UsbDevice> keys = mCtrlBlocks.keySet();
            if (keys != null) {
                UsbControlBlock ctrlBlock;
                try {
                    for (UsbDevice key : keys) {
                        ctrlBlock = mCtrlBlocks.remove(key);
                        if (ctrlBlock != null) {
                            ctrlBlock.close();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "destroy:", e);
                }
            }
            mCtrlBlocks.clear();
            try {
                mAsyncHandler.getLooper().quit();
            } catch (Exception e) {
                Log.e(TAG, "destroy:", e);
            }
        }
    }

    /**
     * 注册 BroadcastReceiver 以监听 USB 事件。register BroadcastReceiver to monitor USB events
     */
    public synchronized void register() throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        if (mPermissionIntent == null) {
            if (DEBUG) Log.i(TAG, "register:");
            Context context = mWeakContext.get();
            if (context != null) {
                mPermissionIntent = PendingIntent.getBroadcast(context, 0,
                        new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_CANCEL_CURRENT);
                IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                // ACTION_USB_DEVICE_ATTACHED never comes on some devices so it should not be added here
                filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                context.registerReceiver(mUsbReceiver, filter);
            }
            // start connection check
            mDeviceCounts = 0;
            mAsyncHandler.postDelayed(mDeviceCheckRunnable, 1000);
        }
    }

    /**
     * 注销 BroadcastReceiver
     */
    public synchronized void unregister() throws IllegalStateException {
        // 接続チェック用Runnableを削除
        mDeviceCounts = 0;
        if (!destroyed) {
            mAsyncHandler.removeCallbacks(mDeviceCheckRunnable);
        }
        if (mPermissionIntent != null) {
            // if (DEBUG) Log.i(TAG, "unregister:");
            Context context = mWeakContext.get();
            try {
                if (context != null) {
                    context.unregisterReceiver(mUsbReceiver);
                }
            } catch (Exception e) {
                Log.w(TAG, e);
            }
            mPermissionIntent = null;
        }
    }

    public synchronized boolean isRegistered() {
        return !destroyed && (mPermissionIntent != null);
    }

    /**
     * set device filter
     */
    public void setDeviceFilter(DeviceFilter filter) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        mDeviceFilters.clear();
        mDeviceFilters.add(filter);
    }

    /**
     * デバイスフィルターを追加
     */
    public void addDeviceFilter(DeviceFilter filter) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        mDeviceFilters.add(filter);
    }

    /**
     * 移除设备过滤器
     */
    public void removeDeviceFilter(DeviceFilter filter) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        mDeviceFilters.remove(filter);
    }

    /**
     * set device filters
     */
    public void setDeviceFilter(List<DeviceFilter> filters) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        mDeviceFilters.clear();
        mDeviceFilters.addAll(filters);
    }

    /**
     * add device filters
     */
    public void addDeviceFilter(List<DeviceFilter> filters) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        mDeviceFilters.addAll(filters);
    }

    /**
     * remove device filters
     */
    public void removeDeviceFilter(List<DeviceFilter> filters) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        mDeviceFilters.removeAll(filters);
    }

    /**
     * return the number of connected USB devices that matched device filter
     */
    public int getDeviceCount() throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        return getDeviceList().size();
    }

    /**
     * return device list, return empty list if no device matched
     */
    public List<UsbDevice> getDeviceList() throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        return getDeviceList(mDeviceFilters);
    }

    /**
     * 是否为Xtherm的设备
     * @param device usb设备
     * @return 是否为Xtherm的设备
     */
    private boolean isMyDevice(UsbDevice device) {
        return device.getProductName().contains("T2") ||
                device.getProductName().contains("T3") ||
                device.getProductName().contains("T5") ||
                device.getProductName().contains("S0") ||
                device.getProductName().contains("S1") ||
                device.getProductName().contains("Xtherm") ||
                device.getProductName().contains("Xmodule") ||
                device.getProductName().contains("FX3") ||
                device.getProductName().contains("PNS");
    }

    /**
     * return device list, return empty list if no device matched
     */
    public List<UsbDevice> getDeviceList(List<DeviceFilter> filters) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
        List<UsbDevice> result = new ArrayList<>();
        if (deviceList != null) {
            if ((filters == null) || filters.isEmpty()) {
                result.addAll(deviceList.values());
            } else {
                for (UsbDevice device : deviceList.values()) {
                    for (DeviceFilter filter : filters) {
                        if (isMyDevice(device)) {
                            if ((filter != null) && filter.matches(device)) {
                                // when filter matches
                                if (!filter.isExclude) {
                                    result.add(device);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * return device list, return empty list if no device matched
     */
    public List<UsbDevice> getDeviceList(DeviceFilter filter) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
        List<UsbDevice> result = new ArrayList<>();
        if (deviceList != null) {
            for (UsbDevice device : deviceList.values()) {
                if ((filter == null) || (filter.matches(device) && !filter.isExclude)) {
                    result.add(device);
                }
            }
        }
        return result;
    }

    /**
     * get USB device list, without filter
     */
    public Iterator<UsbDevice> getDevices() throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        Iterator<UsbDevice> iterator = null;
        HashMap<String, UsbDevice> list = mUsbManager.getDeviceList();
        if (list != null)
            iterator = list.values().iterator();
        return iterator;
    }

    /**
     * output device list to LogCat
     */
    public void dumpDevices() {
        HashMap<String, UsbDevice> list = mUsbManager.getDeviceList();
        if (list != null) {
            Set<String> keys = list.keySet();
            if (keys != null && keys.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (String key : keys) {
                    UsbDevice device = list.get(key);
                    int num_interface = device != null ? device.getInterfaceCount() : 0;
                    sb.setLength(0);
                    for (int i = 0; i < num_interface; i++) {
                        sb.append(String.format(Locale.US, "interface%d:%s", i, device.getInterface(i).toString()));
                    }
                    Log.i(TAG, "key=" + key + ":" + device + ":" + sb.toString());
                }
            } else {
                Log.i(TAG, "no device");
            }
        } else {
            Log.i(TAG, "no device");
        }
    }

    /**
     * return whether the specific Usb device has permission
     * @return true: 指定したUsbDeviceにパーミッションがある
     */
    public boolean hasPermission(UsbDevice device) throws IllegalStateException {
        if (destroyed) throw new IllegalStateException("already destroyed");
        return updatePermission(device, device != null && mUsbManager.hasPermission(device));
    }

    /**
     * 更新内部持有的权限状态 内部で保持しているパーミッション状態を更新
     * @return hasPermission
     */
    private boolean updatePermission(UsbDevice device, boolean hasPermission) {
        int deviceKey = getDeviceKey(device, true);
        synchronized (mHasPermissions) {
            if (hasPermission) {
                if (mHasPermissions.get(deviceKey) == null) {
                    mHasPermissions.put(deviceKey, new WeakReference<UsbDevice>(device));
                }
            } else {
                mHasPermissions.remove(deviceKey);
            }
        }
        return hasPermission;
    }

    /**
     * 请求访问 USB 设备的权限
     * request permission to access to USB device
     * @return true if fail to request permission
     */
    public synchronized boolean requestPermission(UsbDevice device) {
        // if (DEBUG) Log.v(TAG, "requestPermission:device=" + device);
        boolean result = false;
        if (isRegistered()) {
            if (device != null) {
                if (mUsbManager.hasPermission(device)) {
                    // 如果应用程序已经具有权限，则调用 onConnect。call onConnect if app already has permission
                    processConnect(device);
                } else {
                    try {
                        // 如果没有权限就请求 パーミッションがなければ要求する
                        mUsbManager.requestPermission(device, mPermissionIntent);
                    } catch (Exception e) {
                        // Android5.1.xのGALAXY系でandroid.permission.sec.MDM_APP_MGMTという意味不明の例外生成するみたい
                        Log.w(TAG, e);
                        processCancel(device);
                        result = true;
                    }
                }
            } else {
                processCancel(device);
                result = true;
            }
        } else {
            processCancel(device);
            result = true;
        }
        return result;
    }

    /**
     * 指定したUsbDeviceをopenする
     * @throws SecurityException パーミッションがなければSecurityExceptionを投げる
     */
    public UsbControlBlock openDevice(UsbDevice device) throws SecurityException {
        if (hasPermission(device)) {
            UsbControlBlock result = mCtrlBlocks.get(device);
            if (result == null) {
                result = new UsbControlBlock(USBMonitor.this, device);    // この中でopenDeviceする
                mCtrlBlocks.put(device, result);
            }
            return result;
        } else {
            throw new SecurityException("has no permission");
        }
    }

    /**
     * BroadcastReceiver for USB permission
     */
    private BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (destroyed) return;
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                // when received the result of requesting USB permission
                synchronized (USBMonitor.this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (DEBUG)
                        Log.v(TAG, "processCancel:" + intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            // get permission, call onConnect
                            processConnect(device);
                        }
                    } else {
                        // failed to get permission
                        processCancel(device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                updatePermission(device, hasPermission(device));
                processAttach(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // when device removed
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    if (isMyDevice(device)) {
                        UsbControlBlock ctrlBlock = mCtrlBlocks.remove(device);
                        if (ctrlBlock != null) {
                            // cleanup
                            ctrlBlock.close();
                        }
                        mDeviceCounts = 0;
                        processDettach(device);
                    }
                }
            }
        }
    };

    /**
     * number of connected & detected devices
     */
    private volatile int mDeviceCounts = 0;

    /**
     * 定期检查连接的设备，如果发生变化，请调用 onAttach
     * periodically check connected devices and if it changed, call onAttach
     */
    private Runnable mDeviceCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            List<UsbDevice> devices = getDeviceList();
            int n = devices.size();
            int hasPermissionCounts;
            int m;
            synchronized (mHasPermissions) {
                hasPermissionCounts = mHasPermissions.size();
                mHasPermissions.clear();
                for (UsbDevice device : devices) {
                    hasPermission(device);
                }
                m = mHasPermissions.size();
            }
            if ((n > mDeviceCounts) || (m > hasPermissionCounts)) {
                mDeviceCounts = n;
                if (mOnDeviceConnectListener != null) {
                    for (int i = 0; i < n; i++) {
                        final UsbDevice device = devices.get(i);
                        if (device.getProductName() != null) {
                            if (isMyDevice(device)) {
                                mAsyncHandler.post(() -> mOnDeviceConnectListener.onAttach(device));
                            }
                        }
                    }
                }
            }
            mAsyncHandler.postDelayed(this, 2000);    // confirm every 2 seconds
        }
    };

    /**
     * open specific USB device
     */
    private void processConnect(final UsbDevice device) {
        if (destroyed) return;
        updatePermission(device, true);
        mAsyncHandler.post(new Runnable() {
            @Override
            public void run() {
                if (DEBUG) Log.v(TAG, "processConnect:device=" + device);
                UsbControlBlock ctrlBlock;
                boolean createNew;
                ctrlBlock = mCtrlBlocks.get(device);
                if (ctrlBlock == null) {
                    ctrlBlock = new UsbControlBlock(USBMonitor.this, device);
                    mCtrlBlocks.put(device, ctrlBlock);
                    createNew = true;
                } else {
                    createNew = false;
                }
                if (mOnDeviceConnectListener != null) {
                    mOnDeviceConnectListener.onConnect(device, ctrlBlock, createNew);
                }
            }
        });
    }

    private void processCancel(final UsbDevice device) {
        if (destroyed) return;
        if (DEBUG) Log.v(TAG, "processCancel:");
        updatePermission(device, false);
        if (mOnDeviceConnectListener != null) {
            mAsyncHandler.post(new Runnable() {
                @Override
                public void run() {
                    mOnDeviceConnectListener.onCancel(device);
                }
            });
        }
    }

    private void processAttach(final UsbDevice device) {
        if (destroyed) return;
        if (DEBUG) Log.v(TAG, "processAttach:");
        if (mOnDeviceConnectListener != null) {
            mAsyncHandler.post(() -> mOnDeviceConnectListener.onAttach(device));
        }
    }

    private void processDettach(final UsbDevice device) {
        if (destroyed) return;
        if (DEBUG) Log.v(TAG, "processDettach:");
        if (mOnDeviceConnectListener != null) {
            mAsyncHandler.post(() -> mOnDeviceConnectListener.onDettach(device));
        }
    }

    /**
     * USB機器毎の設定保存用にデバイスキー名を生成する。
     * ベンダーID, プロダクトID, デバイスクラス, デバイスサブクラス, デバイスプロトコルから生成
     * 同種の製品だと同じキー名になるので注意
     * @param device nullなら空文字列を返す
     */
    public static String getDeviceKeyName(UsbDevice device) {
        return getDeviceKeyName(device, null, false);
    }

    /**
     * USB機器毎の設定保存用にデバイスキー名を生成する。
     * useNewAPI=falseで同種の製品だと同じデバイスキーになるので注意
     */
    public static String getDeviceKeyName(UsbDevice device, boolean useNewAPI) {
        return getDeviceKeyName(device, null, useNewAPI);
    }

    /**
     * 为每个 USB 设备生成用于保存设置的设备密钥名称。
     * 将此设备名称用作 HashMap 键
     * 仅当 UsbDevice 打开时有效
     * 由供应商 ID、产品 ID、设备类、设备子类和设备协议生成level 满足，还使用制造商名称、版本和配置计数
     * USB機器毎の設定保存用にデバイスキー名を生成する。この機器名をHashMapのキーにする
     * UsbDeviceがopenしている時のみ有効
     * ベンダーID, プロダクトID, デバイスクラス, デバイスサブクラス, デバイスプロトコルから生成
     * serialがnullや空文字でなければserialを含めたデバイスキー名を生成する
     * useNewAPI=trueでAPIレベルを満たしていればマニュファクチャ名, バージョン, コンフィギュレーションカウントも使う
     * @param device    nullなら空文字列を返す
     * @param serial    UsbDeviceConnection#getSerialで取得したシリアル番号を渡す, nullでuseNewAPI=trueでAPI>=21なら内部で取得
     * @param useNewAPI API>=21またはAPI>=23のみで使用可能なメソッドも使用する(ただし機器によってはnullが返ってくるので有効かどうかは機器による)
     */
    @SuppressLint("NewApi")
    public static String getDeviceKeyName(UsbDevice device, String serial, boolean useNewAPI) {
        //Log.i(":getDeviceKeyName", device.toString());
        if (device == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(device.getVendorId());
        sb.append("#");    // API >= 12
        sb.append(device.getProductId());
        sb.append("#");    // API >= 12
        sb.append(device.getDeviceClass());
        sb.append("#");    // API >= 12
        sb.append(device.getDeviceSubclass());
        sb.append("#");    // API >= 12
        sb.append(device.getDeviceProtocol());   // API >= 12
        if (!TextUtils.isEmpty(serial)) {
            sb.append("#");
            sb.append(serial);
        }
        if (useNewAPI && BuildCheck.isAndroid5()) {
            sb.append("#");
            // 报错为缺少权限，先注释
            //if (TextUtils.isEmpty(serial)) {
            //    sb.append(device.getSerialNumber());
            //    sb.append("#");    // API >= 21
            //}
            sb.append(device.getManufacturerName());
            sb.append("#");    // API >= 21
            sb.append(device.getConfigurationCount());
            sb.append("#");    // API >= 21
            if (BuildCheck.isMarshmallow()) {
                sb.append(device.getVersion());
                sb.append("#");    // API >= 23
            }
        }
        return sb.toString();
    }

    /**
     * 获取整型的device key 获取getDeviceKeyName获取的字符串的hasCode 由vendor ID、product ID、
     * device class、device subclass、device protocol生成 注意相同类型的产品会有相同的device key
     * デバイスキーを整数として取得
     * getDeviceKeyNameで得られる文字列のhasCodeを取得
     * ベンダーID, プロダクトID, デバイスクラス, デバイスサブクラス, デバイスプロトコルから生成
     * 同種の製品だと同じデバイスキーになるので注意
     * @param device nullなら0を返す
     */
    public static int getDeviceKey(UsbDevice device) {
        return device != null ? getDeviceKeyName(device, null, false).hashCode() : 0;
    }

    /**
     * デバイスキーを整数として取得
     * getDeviceKeyNameで得られる文字列のhasCodeを取得
     * useNewAPI=falseで同種の製品だと同じデバイスキーになるので注意
     */
    public static int getDeviceKey(UsbDevice device, boolean useNewAPI) {
        return device != null ? getDeviceKeyName(device, null, useNewAPI).hashCode() : 0;
    }

    /**
     * デバイスキーを整数として取得
     * getDeviceKeyNameで得られる文字列のhasCodeを取得
     * serialがnullでuseNewAPI=falseで同種の製品だと同じデバイスキーになるので注意
     * @param device    nullなら0を返す
     * @param serial    UsbDeviceConnection#getSerialで取得したシリアル番号を渡す, nullでuseNewAPI=trueでAPI>=21なら内部で取得
     * @param useNewAPI API>=21またはAPI>=23のみで使用可能なメソッドも使用する(ただし機器によってはnullが返ってくるので有効かどうかは機器による)
     */
    public static int getDeviceKey(UsbDevice device, String serial, boolean useNewAPI) {
        return device != null ? getDeviceKeyName(device, serial, useNewAPI).hashCode() : 0;
    }

    public static class UsbDeviceInfo {
        public String usb_version;
        public String manufacturer;
        public String product;
        public String version;
        public String serial;

        private void clear() {
            usb_version = manufacturer = product = version = serial = null;
        }

        @Override
        public String toString() {
            return String.format("UsbDevice:usb_version=%s,manufacturer=%s,product=%s,version=%s,serial=%s",
                    usb_version != null ? usb_version : "",
                    manufacturer != null ? manufacturer : "",
                    product != null ? product : "",
                    version != null ? version : "",
                    serial != null ? serial : "");
        }
    }

    private static int USB_DIR_OUT = 0;
    private static int USB_DIR_IN = 0x80;
    private static int USB_TYPE_MASK = (0x03 << 5);
    private static int USB_TYPE_STANDARD = (0x00 << 5);
    private static int USB_TYPE_CLASS = (0x01 << 5);
    private static int USB_TYPE_VENDOR = (0x02 << 5);
    private static int USB_TYPE_RESERVED = (0x03 << 5);
    private static int USB_RECIP_MASK = 0x1f;
    private static int USB_RECIP_DEVICE = 0x00;
    private static int USB_RECIP_INTERFACE = 0x01;
    private static int USB_RECIP_ENDPOINT = 0x02;
    private static int USB_RECIP_OTHER = 0x03;
    private static int USB_RECIP_PORT = 0x04;
    private static int USB_RECIP_RPIPE = 0x05;
    private static int USB_REQ_GET_STATUS = 0x00;
    private static int USB_REQ_CLEAR_FEATURE = 0x01;
    private static int USB_REQ_SET_FEATURE = 0x03;
    private static int USB_REQ_SET_ADDRESS = 0x05;
    private static int USB_REQ_GET_DESCRIPTOR = 0x06;
    private static int USB_REQ_SET_DESCRIPTOR = 0x07;
    private static int USB_REQ_GET_CONFIGURATION = 0x08;
    private static int USB_REQ_SET_CONFIGURATION = 0x09;
    private static int USB_REQ_GET_INTERFACE = 0x0A;
    private static int USB_REQ_SET_INTERFACE = 0x0B;
    private static int USB_REQ_SYNCH_FRAME = 0x0C;
    private static int USB_REQ_SET_SEL = 0x30;
    private static int USB_REQ_SET_ISOCH_DELAY = 0x31;
    private static int USB_REQ_SET_ENCRYPTION = 0x0D;
    private static int USB_REQ_GET_ENCRYPTION = 0x0E;
    private static int USB_REQ_RPIPE_ABORT = 0x0E;
    private static int USB_REQ_SET_HANDSHAKE = 0x0F;
    private static int USB_REQ_RPIPE_RESET = 0x0F;
    private static int USB_REQ_GET_HANDSHAKE = 0x10;
    private static int USB_REQ_SET_CONNECTION = 0x11;
    private static int USB_REQ_SET_SECURITY_DATA = 0x12;
    private static int USB_REQ_GET_SECURITY_DATA = 0x13;
    private static int USB_REQ_SET_WUSB_DATA = 0x14;
    private static int USB_REQ_LOOPBACK_DATA_WRITE = 0x15;
    private static int USB_REQ_LOOPBACK_DATA_READ = 0x16;
    private static int USB_REQ_SET_INTERFACE_DS = 0x17;

    private static int USB_REQ_STANDARD_DEVICE_SET = (USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_DEVICE);        // 0x10
    private static int USB_REQ_STANDARD_DEVICE_GET = (USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE);            // 0x90
    private static int USB_REQ_STANDARD_INTERFACE_SET = (USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_INTERFACE);    // 0x11
    private static int USB_REQ_STANDARD_INTERFACE_GET = (USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_INTERFACE);    // 0x91
    private static int USB_REQ_STANDARD_ENDPOINT_SET = (USB_DIR_OUT | USB_TYPE_STANDARD | USB_RECIP_ENDPOINT);    // 0x12
    private static int USB_REQ_STANDARD_ENDPOINT_GET = (USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_ENDPOINT);        // 0x92

    private static int USB_REQ_CS_DEVICE_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_DEVICE);                // 0x20
    private static int USB_REQ_CS_DEVICE_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_DEVICE);                    // 0xa0
    private static int USB_REQ_CS_INTERFACE_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE);            // 0x21
    private static int USB_REQ_CS_INTERFACE_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE);            // 0xa1
    private static int USB_REQ_CS_ENDPOINT_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_ENDPOINT);                // 0x22
    private static int USB_REQ_CS_ENDPOINT_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_ENDPOINT);                // 0xa2

    private static int USB_REQ_VENDER_DEVICE_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_DEVICE);                // 0x40
    private static int USB_REQ_VENDER_DEVICE_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_DEVICE);                // 0xc0
    private static int USB_REQ_VENDER_INTERFACE_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE);        // 0x41
    private static int USB_REQ_VENDER_INTERFACE_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_INTERFACE);        // 0xc1
    private static int USB_REQ_VENDER_ENDPOINT_SET = (USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_ENDPOINT);            // 0x42
    private static int USB_REQ_VENDER_ENDPOINT_GET = (USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_ENDPOINT);            // 0xc2

    private static int USB_DT_DEVICE = 0x01;
    private static int USB_DT_CONFIG = 0x02;
    private static int USB_DT_STRING = 0x03;
    private static int USB_DT_INTERFACE = 0x04;
    private static int USB_DT_ENDPOINT = 0x05;
    private static int USB_DT_DEVICE_QUALIFIER = 0x06;
    private static int USB_DT_OTHER_SPEED_CONFIG = 0x07;
    private static int USB_DT_INTERFACE_POWER = 0x08;
    private static int USB_DT_OTG = 0x09;
    private static int USB_DT_DEBUG = 0x0a;
    private static int USB_DT_INTERFACE_ASSOCIATION = 0x0b;
    private static int USB_DT_SECURITY = 0x0c;
    private static int USB_DT_KEY = 0x0d;
    private static int USB_DT_ENCRYPTION_TYPE = 0x0e;
    private static int USB_DT_BOS = 0x0f;
    private static int USB_DT_DEVICE_CAPABILITY = 0x10;
    private static int USB_DT_WIRELESS_ENDPOINT_COMP = 0x11;
    private static int USB_DT_WIRE_ADAPTER = 0x21;
    private static int USB_DT_RPIPE = 0x22;
    private static int USB_DT_CS_RADIO_CONTROL = 0x23;
    private static int USB_DT_PIPE_USAGE = 0x24;
    private static int USB_DT_SS_ENDPOINT_COMP = 0x30;
    private static int USB_DT_CS_DEVICE = (USB_TYPE_CLASS | USB_DT_DEVICE);
    private static int USB_DT_CS_CONFIG = (USB_TYPE_CLASS | USB_DT_CONFIG);
    private static int USB_DT_CS_STRING = (USB_TYPE_CLASS | USB_DT_STRING);
    private static int USB_DT_CS_INTERFACE = (USB_TYPE_CLASS | USB_DT_INTERFACE);
    private static int USB_DT_CS_ENDPOINT = (USB_TYPE_CLASS | USB_DT_ENDPOINT);
    private static int USB_DT_DEVICE_SIZE = 18;

    /**
     * 指定したIDのStringディスクリプタから文字列を取得する。取得できなければnull
     */
    private static String getString(UsbDeviceConnection connection, int id, int languageCount, byte[] languages) {
        byte[] work = new byte[256];
        String result = null;
        for (int i = 1; i <= languageCount; i++) {
            int ret = connection.controlTransfer(
                    USB_REQ_STANDARD_DEVICE_GET, // USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE
                    USB_REQ_GET_DESCRIPTOR,
                    (USB_DT_STRING << 8) | id, languages[i], work, 256, 0);
            if ((ret > 2) && (work[0] == ret) && (work[1] == USB_DT_STRING)) {
                // skip first two bytes(bLength & bDescriptorType), and copy the rest to the string
                try {
                    result = new String(work, 2, ret - 2, "UTF-16LE");
                    if (!"Љ".equals(result)) {    // 変なゴミが返ってくる時がある
                        break;
                    } else {
                        result = null;
                    }
                } catch (UnsupportedEncodingException e) {
                    // ignore
                }
            }
        }
        return result;
    }

    /**
     * ベンダー名・製品名・バージョン・シリアルを取得する
     */
    public UsbDeviceInfo getDeviceInfo(UsbDevice device) {
        return updateDeviceInfo(mUsbManager, device, null);
    }

    /**
     * ベンダー名・製品名・バージョン・シリアルを取得する
     * #updateDeviceInfo( UsbManager,  UsbDevice,  UsbDeviceInfo)のヘルパーメソッド
     */
    public static UsbDeviceInfo getDeviceInfo(Context context, UsbDevice device) {
        return updateDeviceInfo((UsbManager) context.getSystemService(Context.USB_SERVICE), device, new UsbDeviceInfo());
    }

    /**
     * ベンダー名・製品名・バージョン・シリアルを取得する
     */
    public static UsbDeviceInfo updateDeviceInfo(UsbManager manager, UsbDevice device, UsbDeviceInfo _info) {
        UsbDeviceInfo info = _info != null ? _info : new UsbDeviceInfo();
        info.clear();

        if (device != null) {
            if (BuildCheck.isLollipop()) {
                info.manufacturer = device.getManufacturerName();
                info.product = device.getProductName();
                info.serial = device.getSerialNumber();
            }
            if (BuildCheck.isMarshmallow()) {
                info.usb_version = device.getVersion();
            }
            if ((manager != null) && manager.hasPermission(device)) {
                UsbDeviceConnection connection = manager.openDevice(device);
                byte[] desc = connection.getRawDescriptors();

                if (TextUtils.isEmpty(info.usb_version)) {
                    info.usb_version = String.format("%x.%02x", ((int) desc[3] & 0xff), ((int) desc[2] & 0xff));
                }
                if (TextUtils.isEmpty(info.version)) {
                    info.version = String.format("%x.%02x", ((int) desc[13] & 0xff), ((int) desc[12] & 0xff));
                }
                if (TextUtils.isEmpty(info.serial)) {
                    info.serial = connection.getSerial();
                }

                byte[] languages = new byte[256];
                int languageCount = 0;
                // controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int length, int timeout)
                try {
                    int result = connection.controlTransfer(
                            USB_REQ_STANDARD_DEVICE_GET, // USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE
                            USB_REQ_GET_DESCRIPTOR,
                            (USB_DT_STRING << 8) | 0, 0, languages, 256, 0);
                    if (result > 0) {
                        languageCount = (result - 2) / 2;
                    }
                    if (languageCount > 0) {
                        if (TextUtils.isEmpty(info.manufacturer)) {
                            info.manufacturer = getString(connection, desc[14], languageCount, languages);
                        }
                        if (TextUtils.isEmpty(info.product)) {
                            info.product = getString(connection, desc[15], languageCount, languages);
                        }
                        if (TextUtils.isEmpty(info.serial)) {
                            info.serial = getString(connection, desc[16], languageCount, languages);
                        }
                    }
                } finally {
                    connection.close();
                }
            }
            if (TextUtils.isEmpty(info.manufacturer)) {
                info.manufacturer = USBVendorId.vendorName(device.getVendorId());
            }
            if (TextUtils.isEmpty(info.manufacturer)) {
                info.manufacturer = String.format("%04x", device.getVendorId());
            }
            if (TextUtils.isEmpty(info.product)) {
                info.product = String.format("%04x", device.getProductId());
            }
        }
        return info;
    }

    /**
     * control class
     * never reuse the instance when it closed
     */
    public static class UsbControlBlock implements Cloneable {
        private WeakReference<USBMonitor> mWeakMonitor;
        private WeakReference<UsbDevice> mWeakDevice;
        protected UsbDeviceConnection mConnection;
        protected UsbDeviceInfo mInfo;
        private int mBusNum;
        private int mDevNum;
        private SparseArray<SparseArray<UsbInterface>> mInterfaces = new SparseArray<SparseArray<UsbInterface>>();

        /**
         * this class needs permission to access USB device before constructing
         */
        private UsbControlBlock(USBMonitor monitor, UsbDevice device) {
            if (DEBUG) Log.i(TAG, "UsbControlBlock:constructor");
            mWeakMonitor = new WeakReference<USBMonitor>(monitor);
            mWeakDevice = new WeakReference<UsbDevice>(device);
            mConnection = monitor.mUsbManager.openDevice(device);
            mInfo = updateDeviceInfo(monitor.mUsbManager, device, null);
            String name = device.getDeviceName();
            String[] v = !TextUtils.isEmpty(name) ? name.split("/") : null;
            int busnum = 0;
            int devnum = 0;
            if (v != null) {
                busnum = Integer.parseInt(v[v.length - 2]);
                devnum = Integer.parseInt(v[v.length - 1]);
            }
            mBusNum = busnum;
            mDevNum = devnum;
//			if (DEBUG) {
            if (mConnection != null) {
                int desc = mConnection.getFileDescriptor();
                byte[] rawDesc = mConnection.getRawDescriptors();
                Log.i(TAG, String.format(Locale.US, "name=%s,desc=%d,busnum=%d,devnum=%d,rawDesc=", name, desc, busnum, devnum) + rawDesc);
            } else {
                Log.e(TAG, "could not connect to device " + name);
            }
//			}
        }

        /**
         * copy constructor
         */
        private UsbControlBlock(UsbControlBlock src) throws IllegalStateException {
            USBMonitor monitor = src.getUSBMonitor();
            UsbDevice device = src.getDevice();
            if (device == null) {
                throw new IllegalStateException("device may already be removed");
            }
            mConnection = monitor.mUsbManager.openDevice(device);
            if (mConnection == null) {
                throw new IllegalStateException("device may already be removed or have no permission");
            }
            mInfo = updateDeviceInfo(monitor.mUsbManager, device, null);
            mWeakMonitor = new WeakReference<USBMonitor>(monitor);
            mWeakDevice = new WeakReference<UsbDevice>(device);
            mBusNum = src.mBusNum;
            mDevNum = src.mDevNum;
            // FIXME USBMonitor.mCtrlBlocksに追加する
            //  (今はHashMapなので追加すると置き換わってしまうのでだめ, ListかHashMapにListをぶら下げる?)
        }

        /**
         * duplicate by clone
         * need permission
         * USBMonitor never handle cloned UsbControlBlock, you should release it after using it.
         */
        @Override
        public UsbControlBlock clone() throws CloneNotSupportedException {
            UsbControlBlock ctrlblock;
            try {
                ctrlblock = new UsbControlBlock(this);
            } catch (IllegalStateException e) {
                throw new CloneNotSupportedException(e.getMessage());
            }
            return ctrlblock;
        }

        public USBMonitor getUSBMonitor() {
            return mWeakMonitor.get();
        }

        public UsbDevice getDevice() {
            return mWeakDevice.get();
        }

        /**
         * get device name
         */
        public String getDeviceName() {
            UsbDevice device = mWeakDevice.get();
            return device != null ? device.getDeviceName() : "";
        }

        /**
         * get device id
         */
        public int getDeviceId() {
            UsbDevice device = mWeakDevice.get();
            return device != null ? device.getDeviceId() : 0;
        }

        /**
         * get device key string
         * @return same value if the devices has same vendor id, product id, device class, device subclass and device protocol
         */
        public String getDeviceKeyName() {
            return USBMonitor.getDeviceKeyName(mWeakDevice.get());
        }

        /**
         * get device key string
         * @param useNewAPI if true, try to use serial number
         */
        public String getDeviceKeyName(boolean useNewAPI) throws IllegalStateException {
            if (useNewAPI) checkConnection();
            return USBMonitor.getDeviceKeyName(mWeakDevice.get(), mInfo.serial, useNewAPI);
        }

        /**
         * get device key
         */
        public int getDeviceKey() throws IllegalStateException {
            checkConnection();
            return USBMonitor.getDeviceKey(mWeakDevice.get());
        }

        /**
         * get device key
         * @param useNewAPI if true, try to use serial number
         */
        public int getDeviceKey(boolean useNewAPI) throws IllegalStateException {
            if (useNewAPI) checkConnection();
            return USBMonitor.getDeviceKey(mWeakDevice.get(), mInfo.serial, useNewAPI);
        }

        /**
         * get device key string
         * if device has serial number, use it
         */
        public String getDeviceKeyNameWithSerial() {
            return USBMonitor.getDeviceKeyName(mWeakDevice.get(), mInfo.serial, false);
        }

        /**
         * get device key
         * if device has serial number, use it
         */
        public int getDeviceKeyWithSerial() {
            return getDeviceKeyNameWithSerial().hashCode();
        }

        /**
         * get UsbDeviceConnection
         */
        public synchronized UsbDeviceConnection getConnection() {
            return mConnection;
        }

        /**
         * get file descriptor to access USB device
         */
        public synchronized int getFileDescriptor() throws IllegalStateException {
            checkConnection();
            return mConnection.getFileDescriptor();
        }

        /**
         * get raw descriptor for the USB device
         */
        public synchronized byte[] getRawDescriptors() throws IllegalStateException {
            checkConnection();
            return mConnection.getRawDescriptors();
        }

        /**
         * get vendor id
         */
        public int getVenderId() {
            UsbDevice device = mWeakDevice.get();
            return device != null ? device.getVendorId() : 0;
        }

        /**
         * get product id
         */
        public int getProductId() {
            UsbDevice device = mWeakDevice.get();
            return device != null ? device.getProductId() : 0;
        }

        /**
         * get version string of USB
         */
        public String getUsbVersion() {
            return mInfo.usb_version;
        }

        /**
         * get manufacture
         */
        public String getManufacture() {
            return mInfo.manufacturer;
        }

        /**
         * get product name
         */
        public String getProductName() {
            return mInfo.product;
        }

        /**
         * get version
         */
        public String getVersion() {
            return mInfo.version;
        }

        /**
         * get serial number
         */
        public String getSerial() {
            return mInfo.serial;
        }

        public int getBusNum() {
            return mBusNum;
        }

        public int getDevNum() {
            return mDevNum;
        }

        /**
         * get interface
         */
        public synchronized UsbInterface getInterface(int interface_id) throws IllegalStateException {
            return getInterface(interface_id, 0);
        }

        /**
         * get interface
         */
        public synchronized UsbInterface getInterface(int interface_id, int altsetting) throws IllegalStateException {
            checkConnection();
            SparseArray<UsbInterface> intfs = mInterfaces.get(interface_id);
            if (intfs == null) {
                intfs = new SparseArray<UsbInterface>();
                mInterfaces.put(interface_id, intfs);
            }
            UsbInterface intf = intfs.get(altsetting);
            if (intf == null) {
                UsbDevice device = mWeakDevice.get();
                int n = device.getInterfaceCount();
                for (int i = 0; i < n; i++) {
                    UsbInterface temp = device.getInterface(i);
                    if ((temp.getId() == interface_id) && (temp.getAlternateSetting() == altsetting)) {
                        intf = temp;
                        break;
                    }
                }
                if (intf != null) {
                    intfs.append(altsetting, intf);
                }
            }
            return intf;
        }

        /**
         * open specific interface
         */
        public synchronized void claimInterface(UsbInterface intf) {
            claimInterface(intf, true);
        }

        public synchronized void claimInterface(UsbInterface intf, boolean force) {
            checkConnection();
            mConnection.claimInterface(intf, force);
        }

        /**
         * close interface
         */
        public synchronized void releaseInterface(UsbInterface intf) throws IllegalStateException {
            checkConnection();
            SparseArray<UsbInterface> intfs = mInterfaces.get(intf.getId());
            if (intfs != null) {
                int index = intfs.indexOfValue(intf);
                intfs.removeAt(index);
                if (intfs.size() == 0) {
                    mInterfaces.remove(intf.getId());
                }
            }
            mConnection.releaseInterface(intf);
        }

        /**
         * Close device
         * This also close interfaces if they are opened in Java side
         */
        public synchronized void close() {
            if (DEBUG) Log.i(TAG, "UsbControlBlock#close:");

            if (mConnection != null) {
                int n = mInterfaces.size();
                for (int i = 0; i < n; i++) {
                    SparseArray<UsbInterface> intfs = mInterfaces.valueAt(i);
                    if (intfs != null) {
                        int m = intfs.size();
                        for (int j = 0; j < m; j++) {
                            UsbInterface intf = intfs.valueAt(j);
                            mConnection.releaseInterface(intf);
                        }
                        intfs.clear();
                    }
                }
                mInterfaces.clear();
                mConnection.close();
                mConnection = null;
                USBMonitor monitor = mWeakMonitor.get();
                if (monitor != null) {
                    if (monitor.mOnDeviceConnectListener != null) {
                        monitor.mOnDeviceConnectListener.onDisconnect(mWeakDevice.get(), UsbControlBlock.this);
                    }
                    monitor.mCtrlBlocks.remove(getDevice());
                }
            }
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) return false;
            if (o instanceof UsbControlBlock) {
                UsbDevice device = ((UsbControlBlock) o).getDevice();
                return device == null ? mWeakDevice.get() == null
                        : device.equals(mWeakDevice.get());
            } else if (o instanceof UsbDevice) {
                return o.equals(mWeakDevice.get());
            }
            return super.equals(o);
        }

//		@Override
//		protected void ize() throws Throwable {
///			close();
//			super.ize();
//		}

        private synchronized void checkConnection() throws IllegalStateException {
            if (mConnection == null) {
                throw new IllegalStateException("already closed");
            }
        }
    }

}
