/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothlegatt;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private File fileToPlay;


    private int mConnectionState = STATE_DISCONNECTED;

    private BluetoothSocket mBTSocket;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTED = 1;

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        Log.d(TAG, "TLG --------- Trying to create a new connection -----------");


        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }

        final Class BTDClass;
        final Method BTDClassMethod;

        try {
            BTDClass = Class.forName(device.getClass().getName());
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "ERROR setting up the Reflection for createL2capSocket");
            return false;
        }
        try {
            Class[] cArg = new Class[1];
            cArg[0] = int.class;
            BTDClassMethod = BTDClass.getDeclaredMethod("createL2capSocket", cArg);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "ERROR setting up the Reflection for createL2capSocket");
            return false;
        }
        BTDClassMethod.setAccessible(true);

        try {
            mBTSocket = (BluetoothSocket) BTDClassMethod.invoke(device, 0x20025);
        } catch (IllegalAccessException e) {
            Log.e(TAG, "ERROR setting up the Reflection for createL2capSocket");
            return false;
        } catch (InvocationTargetException e) {
            Log.e(TAG, "ERROR setting up the Reflection for createL2capSocket");
            return false;
        }

        new ConnectBluetooth().execute();

        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTED;

        return true;
    }

    private class ConnectBluetooth extends AsyncTask<Void, Void, Void> {

        private boolean mStatus = true;

        @Override
        protected Void doInBackground(Void... params) {
            try {
                mBTSocket.connect();
            } catch (IOException e) {
                Log.e(TAG, "ERROR connecting the BluetoothSocket");
            }
            Log.d(TAG, "TLG --------- connection successfully created -----------");
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if (mStatus) {
                Context context = getApplicationContext();
                CharSequence text = "Bluetooth Connected";
                int duration = Toast.LENGTH_SHORT;

                Toast toast = Toast.makeText(context, text, duration);
                toast.show();

                new SendDataOverBluetooth().execute();
            }

        }
    }

    private class SendDataOverBluetooth extends AsyncTask<Void, Void, Void> {

        private boolean mStatus = true;

        @Override
        protected Void doInBackground(Void... params) {
            try {
                byte[] contents = new byte[1024];
                int byte_written = 0;
                final File sdcard = Environment.getExternalStorageDirectory();
                fileToPlay = new File(sdcard.getAbsolutePath() + Constants.FOLDER + Constants.FILE);

                FileInputStream fileinputstr = new FileInputStream(fileToPlay);
                BufferedInputStream buf = new BufferedInputStream(fileinputstr);
                Log.d(TAG, "TLG --------- File Opened -----------");

                if(mBTSocket.isConnected()) {
                    OutputStream outputStream = mBTSocket.getOutputStream();
                    while ( ((byte_written = buf.read(contents, 0, 1024 )) > 0 ) && (mBTSocket.isConnected()) ) {
                        Log.d(TAG, "TLG --------- Writing " + byte_written + " bytes Master -> Slave ----------- ");
                        outputStream.write(contents, 0, byte_written);
                        SystemClock.sleep(10);
                        Log.d(TAG, "TLG --------- Writing completed ----------- ");
                    }
                    buf.close();
                } else {
                    mStatus = false;
                }


            } catch (IOException e) {
                Log.e(TAG, "ERROR connect the BluetoothSocket");
                mStatus = false;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if (mStatus) {
                Context context = getApplicationContext();
                CharSequence text = "Data Sent... closing the Socket";
                int duration = Toast.LENGTH_SHORT;

                Toast toast = Toast.makeText(context, text, duration);
                toast.show();
            }

            try {
                mBTSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "ERROR closing the BluetoothSocket");
            }
        }
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        Log.d(TAG, "Close called");

    }

}
