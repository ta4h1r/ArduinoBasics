package com.ctrl.arduinobasics;

// https://www.digikey.be/en/maker/projects/how-to-use-your-android-to-communicate-with-your-arduino/aed1f8e3fa044264a4310c6b3b2a4364
// https://developer.android.com/guide/topics/connectivity/usb/host

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.felhr.usbserial.SerialInputStream;
import com.felhr.usbserial.SerialPortBuilder;
import com.felhr.usbserial.SerialPortCallback;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;


public class MainActivity extends AppCompatActivity {

    String TAG = "Tai_MainActivity";

    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";
    UsbManager usbManager;
    UsbDeviceConnection connection;
    UsbDevice device;
    UsbSerialDevice serialPort;


    List<UsbDevice> deviceMap;
    UsbDevice devices[];
    private SerialPortBuilder builder;
    private List<UsbSerialDevice> serialPorts;
    private WriteThread writeThread;
    private Handler writeHandler;
    private ReadThreadCOM readThreadCOM1, readThreadCOM2;
    private MyHandler mHandler;
    private TextView display1, display2;
    static List<Integer> indices;

    public static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";
    public static final String ACTION_USB_DISCONNECTED = "com.felhr.usbservice.USB_DISCONNECTED";


    Button startButton;
    Button button1, button2;
    Button stopButton;
    Button clearButton;
    EditText editText1, editText2;

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);


        setFilter();
        builder = SerialPortBuilder.createSerialPortBuilder(mSerialPortCallback);
        boolean ret = builder.openSerialPorts(getApplicationContext(), 9600,
                UsbSerialInterface.DATA_BITS_8,
                UsbSerialInterface.STOP_BITS_1,
                UsbSerialInterface.PARITY_NONE,
                UsbSerialInterface.FLOW_CONTROL_OFF);
        if (!ret)
            Toast.makeText(this, "No Usb serial ports available", Toast.LENGTH_SHORT).show();

        mHandler = new MyHandler(this);


        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(broadcastReceiver, filter);

        startButton = findViewById(R.id.buttonStart);
        button1 = findViewById(R.id.buttonSend);
        button2 = findViewById(R.id.buttonSend2);
        stopButton = findViewById(R.id.buttonStop);
        clearButton = findViewById(R.id.buttonClear);
        display1 = findViewById(R.id.display1);
        display2 = findViewById(R.id.display2);
        editText1 = findViewById(R.id.editText);
        editText2 = findViewById(R.id.editText2);

        button1.setOnClickListener((View v) -> {
            byte[] data = editText1.getText().toString().getBytes();
            write(data, indices.get(0));
        });

        button2.setOnClickListener((View v) -> {
            byte[] data = editText2.getText().toString().getBytes();
            write(data, indices.get(1));
        });

    }


    public static final int SYNC_READ = 3;

    private static class MyHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        public MyHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SYNC_READ:
                    String buffer = (String) msg.obj;
                    if (msg.arg1 == indices.get(0)) {
                        mActivity.get().display1.append(buffer);
                    } else if (msg.arg1 == indices.get(1)) {
                        mActivity.get().display2.append(buffer);
                    }
                    break;
            }
        }
    }

    private class WriteThread extends Thread {

        @Override
        @SuppressLint("HandlerLeak")
        public void run() {
            Looper.prepare();
            writeHandler = new Handler() {
                @RequiresApi(api = Build.VERSION_CODES.KITKAT)
                @Override
                public void handleMessage(Message msg) {
                    int port = msg.arg1;
                    byte[] data = (byte[]) msg.obj;
                    if (port <= serialPorts.size() - 1) {
                        UsbSerialDevice serialDevice = serialPorts.get(port);
                        serialDevice.syncOpen();
                        Log.d(TAG, "HERE: " + serialDevice.getPortName());
                        Log.d(TAG, "HERE: " + new String(data, StandardCharsets.UTF_8));
//                        serialDevice.getOutputStream().setTimeout(100);
//                        serialDevice.getOutputStream().write(1);
                        serialDevice.syncWrite(data, 100);
                    }
                }
            };
            Looper.loop();
        }
    }

    private class ReadThreadCOM extends Thread {
        private int port;
        private AtomicBoolean keep = new AtomicBoolean(true);
        private SerialInputStream inputStream;

        public ReadThreadCOM(int port, SerialInputStream serialInputStream) {
            this.port = port;
            this.inputStream = serialInputStream;
        }

        @Override
        public void run() {
            while (keep.get()) {
                if (inputStream == null)
                    return;
                int value = inputStream.read();
                Log.v(TAG, "inputStreamRead: " + value);
                if (value != -1) {
                    String str = toASCII(value);
                    mHandler.obtainMessage(SYNC_READ, port, 0, str).sendToTarget();
                }
            }
        }


        public void setKeep(boolean keep) {
            this.keep.set(keep);
        }
    }

    private static String toASCII(int value) {
        int length = 4;
        StringBuilder builder = new StringBuilder(length);
        for (int i = length - 1; i >= 0; i--) {
            builder.append((char) ((value >> (8 * i)) & 0xFF));
        }
        return builder.toString();
    }

    public void write(byte[] data, int port) {
        if (writeThread != null)
            writeHandler.obtainMessage(0, port, 0, data).sendToTarget();
    }

    private void setFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_ATTACHED);
        registerReceiver(usbReceiver, filter);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            Log.d(TAG, "Received usbEvent");
            if (arg1.getAction().equals(ACTION_USB_ATTACHED)) {
                Log.d(TAG, "USB_ATTACHED");
                boolean ret = builder.openSerialPorts(getApplicationContext(), 9600,
                        UsbSerialInterface.DATA_BITS_8,
                        UsbSerialInterface.STOP_BITS_1,
                        UsbSerialInterface.PARITY_NONE,
                        UsbSerialInterface.FLOW_CONTROL_OFF);
                if (!ret) {
                    Toast.makeText(getApplicationContext(), "Couldn't open the device", Toast.LENGTH_SHORT).show();
                }
            } else if (arg1.getAction().equals(ACTION_USB_DETACHED)) {
                Log.d(TAG, "USB_DETTACHED");

                UsbDevice usbDevice = arg1.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean ret = builder.disconnectDevice(usbDevice);

                if (ret)
                    Toast.makeText(getApplicationContext(), "Usb device disconnected", Toast.LENGTH_SHORT).show();
                else
                    Toast.makeText(getApplicationContext(), "Usb device wasn't a serial port", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent(ACTION_USB_DISCONNECTED);
                arg0.sendBroadcast(intent);

            }
        }
    };

    private SerialPortCallback mSerialPortCallback = new SerialPortCallback() {
        @Override
        public void onSerialPortsDetected(List<UsbSerialDevice> s) {
            serialPorts = s;

            if (serialPorts.size() == 0) {
                return;
            }

            if (writeThread == null) {
                writeThread = new WriteThread();
                writeThread.start();
            }

            Log.d(TAG, "SERIAL PORTS DETECTED: " + serialPorts.size());

            indices = new ArrayList<>();
            ListIterator<UsbSerialDevice> it = serialPorts.listIterator();
            int i = 0;
            while (it.hasNext()) {
                UsbSerialDevice d = it.next();
                int deviceVID = d.getVid();
                if (deviceVID == 9025) //Arduino Vendor ID
                {
                    if (!d.getPortName().isEmpty()) {
                        indices.add(i);
                        Log.d(TAG, "INDICES ADD: " + d.getPortName());
                    }
                }
                i++;
            }
            Log.d(TAG, "INDICES SIZE: " + indices.size());

            int index = 0;

            int aIndex = indices.get(index);

            if (readThreadCOM1 == null && aIndex <= serialPorts.size() - 1
                    && serialPorts.get(aIndex).isOpen()) {
                Log.d(TAG, "START SERIAL THREAD: " + serialPorts.get(aIndex).getPortName());
                readThreadCOM1 = new ReadThreadCOM(aIndex,
                        serialPorts.get(aIndex).getInputStream());
                readThreadCOM1.start();
            }

            index++;
            aIndex = indices.get(index);
            if (readThreadCOM2 == null && aIndex <= serialPorts.size() - 1
                    && serialPorts.get(aIndex).isOpen()) {
                Log.d(TAG, "START SERIAL THREAD: " + serialPorts.get(aIndex).getPortName());
                readThreadCOM2 = new ReadThreadCOM(aIndex,
                        serialPorts.get(aIndex).getInputStream());
                readThreadCOM2.start();
            }

        }
    };


    private UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {

        @Override
        public void onReceivedData(byte[] arg0) {
            // Code here :)
            Log.d(TAG, "OH WOOOOOOW!");

            String data = null;
            try {
                data = new String(arg0, "UTF-8");
                data.concat("/n");
                tvAppend(display1, data);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }

    };

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { //Broadcast Receiver to automatically start and stop the Serial connection.
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                boolean granted =
                        intent.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
                if (granted) {
                    connection = usbManager.openDevice(device);
                    serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
                    Log.d(TAG, "SERIAL: " + serialPort.getPortName());
                    if (serialPort != null) {
                        if (serialPort.open()) { //Set Serial Connection Parameters.
                            setUiEnabled(true); //Enable Buttons in UI
                            serialPort.setBaudRate(9600);
                            serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                            serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                            serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                            serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                            serialPort.read(mCallback); //
                            tvAppend(display1, "Serial Connection Opened!\n");
                        } else {
                            Log.d(TAG, "SERIAL" + "PORT NOT OPEN");
                        }
                    } else {
                        Log.d(TAG, "SERIAL" + "PORT IS NULL");
                    }
                } else {
                    Log.d(TAG, "SERIAL" + "PERM NOT GRANTED");
                }
            } else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
//                onClickStart(startButton);
            } else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                onClickStop(stopButton);
            }
        }
    };

    private void setUiEnabled(boolean b) {
    }

    private void tvAppend(TextView tv, CharSequence text) {
        final TextView ftv = tv;
        final CharSequence ftext = text;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ftv.append(ftext);
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void onClickStart(View view) {
        /**
         * Search for all connected devices and then check if the vendor ID of the Arduino matches
         * that of a connected device. If devices are found, permission is requested from the user.
         * All USB slave devices have a vendor and product ID, used to identify what drivers should
         * be used for it. An Arduino’s vendor ID is always 0x2341 or 9025.
         * */
        HashMap usbDevices = usbManager.getDeviceList();
        Log.v(TAG, "DEVICES:" + usbDevices.toString());

//        deviceMap = new ArrayList<>();
//
//        for (UsbDevice d : (Iterable<UsbDevice>) usbDevices.values()) {
//            int deviceVID = d.getVendorId();
//            if (deviceVID == 9025)//Arduino Vendor ID
//            {
//                Log.v(TAG, "DEVICE ID:" + d.getProductId());
//                Log.v(TAG, "MAN: " + d.getProductName());
//                Log.v(TAG, "MAN: " + d.getDeviceName());
//                deviceMap.add(d);
//            }
//        }
//        int comPortNos[] = new int[deviceMap.size()];
//        devices = new UsbDevice[deviceMap.size()];
//        int i = 0;
//        for (UsbDevice d : (Iterable<UsbDevice>) deviceMap) {
//            String[] dSplit = d.getDeviceName().split("/");
//            int lastIndex = dSplit.length - 1;
//            int dSplitLast = Integer.valueOf(dSplit[lastIndex]);
//            comPortNos[i] = dSplitLast;
//            devices[i] = d;
//            i++;
//        }
//        Arrays.sort(comPortNos);       // Ascending order
//        Arrays.sort(devices);       // Ascending order
//
//        Log.d(TAG, "DEVMAP: " + Arrays.toString(comPortNos));
//        Log.d(TAG, "DEVMAP: " + Arrays.toString(devices));
//
//        for (int j = 0, n = comPortNos.length; i < n; j++) {
//            PendingIntent pi = PendingIntent.getBroadcast(getApplicationContext(), 0,
//                    new Intent(ACTION_USB_PERMISSION), 0);
//            usbManager.requestPermission(devices[i], pi);
//
//        }

        boolean keep = true;
        for (UsbDevice d : (Iterable<UsbDevice>) usbDevices.values()) {
            //your code

            int deviceVID = d.getVendorId();
            if (deviceVID == 9025)//Arduino Vendor ID
            {
                PendingIntent pi = PendingIntent.getBroadcast(getApplicationContext(), 0,
                        new Intent(ACTION_USB_PERMISSION), 0);
                device = d;
                usbManager.requestPermission(d, pi);
                keep = false;
            } else {
                connection = null;
                device = null;
            }

            if (!keep)
                break;

        }

    }

    public void onClickClear(View view) {
        display1.setText("");
    }

    public void onClickStop(View view) {
        serialPort.close();
    }

    public void onClickSend(View view) {
        serialPort.write(editText1.getText().toString().getBytes());
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (builder != null)
            builder.unregisterListeners(this);
    }
}