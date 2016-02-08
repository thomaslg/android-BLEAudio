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
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private File fileToPlay;
    private File fileToWrite;

    // Member fields
    //private final Handler mHandler;
    private AcceptThread mAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device


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

        mState = STATE_NONE;

        return true;
    }

    /**
     * Set the current state of the connection
     *
     * @param state An integer defining the current connection state
     */
    private synchronized void setState(int state) {
        Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;
    }

    /**
     * Return the current connection state.
     */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Start the Bluetooth service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume()
     */
    public synchronized void start() {
        Log.d(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(STATE_LISTEN);

        // Start the thread to listen on a BluetoothServerSocket
        if (mAcceptThread == null) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        }
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device_address The address of the BluetoothDevice to connect
     */
    public synchronized void connect(String device_address, boolean sendToSocket) {

        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(device_address);
        Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device, sendToSocket);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     */
    public synchronized void connected(BluetoothSocket socket, boolean sendToSocket) {
        Log.d(TAG, "connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (mAcceptThread != null) {
            Log.d(TAG, "Calling mAcceptThread.cancel");
            mAcceptThread.cancel();
            Log.d(TAG, "Setting mAcceptThread to null");
            mAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket, sendToSocket);
        mConnectedThread.start();

        setState(STATE_CONNECTED);
    }

    public void write() {
        if (mConnectedThread != null) {
            Log.d(TAG, "ConnectThread reading from file and sending to socket");
            mConnectedThread.write();
        } else {
            Log.d(TAG, "ConnectThread null CANNOT read from file and send to socket");
        }
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        Log.d(TAG, "stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        setState(STATE_NONE);
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailedOrLost() {

        // Start the service over to restart listening mode
        BluetoothLeService.this.start();
    }


    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;


        public AcceptThread() {
            BluetoothServerSocket tmp;

            /* Use the Reflection method to access hidden java function
             * into BluetoothAdapter class.
             */
            final Class BTAClass;
            final Method BTAClassMethod;

            try {
                BTAClass = Class.forName(mBluetoothAdapter.getClass().getName());
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "ERROR setting up the Reflection for listenUsingL2capOn");
                mmServerSocket = null;
                return;
            }

            try {
                Class[] cArg = new Class[1];
                cArg[0] = int.class;
                BTAClassMethod = BTAClass.getDeclaredMethod("listenUsingL2capOn", cArg);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "ERROR setting up the Reflection for listenUsingL2capOn");
                mmServerSocket = null;
                return;
            }

            BTAClassMethod.setAccessible(true);

            try {
                tmp = (BluetoothServerSocket) BTAClassMethod.invoke(mBluetoothAdapter, 0x20025);
                Log.d(TAG, "listenUsingL2capOn");
            } catch (InvocationTargetException|IllegalAccessException e) {
                Log.e(TAG, "ERROR setting up the Reflection for listenUsingL2capOn");
                mmServerSocket = null;
                return;
            }

            mmServerSocket = tmp;
        }

        public void run() {
            Log.d(TAG, "BEGIN mAcceptThread "+this);
            setName("AcceptThread");

            BluetoothSocket socket;

            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed "+this, e);
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BluetoothLeService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                // Situation normal. Start the connected thread.
                                connected(socket, false);
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                }
            }
            Log.i(TAG, "END mAcceptThread");
        }

        public void cancel() {
            Log.d(TAG, "cancel " + this);
            try {
                mmServerSocket.close();
                Log.d(TAG, "cancel ServerSocket closed");
            } catch (IOException e) {
                Log.e(TAG, "close() of server failed", e);
            }
        }
    }



    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private final boolean mmSendToSocket;

        public ConnectThread(BluetoothDevice device, boolean sendToSocket) {
            mmDevice = device;
            mmSendToSocket = sendToSocket;

            BluetoothSocket tmp;

            final Class BTDClass;
            final Method BTDClassMethod;

            try {
                BTDClass = Class.forName(mmDevice.getClass().getName());
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "ERROR setting up the Reflection for createL2capSocket");
                mmSocket = null;
                return;
            }
            try {
                Class[] cArg = new Class[1];
                cArg[0] = int.class;
                BTDClassMethod = BTDClass.getDeclaredMethod("createL2capSocket", cArg);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "ERROR setting up the Reflection for createL2capSocket");
                mmSocket = null;
                return;
            }
            BTDClassMethod.setAccessible(true);

            try {
                tmp = (BluetoothSocket) BTDClassMethod.invoke(device, 0x20025);
            } catch (IllegalAccessException|InvocationTargetException e) {
                Log.e(TAG, "ERROR setting up the Reflection for createL2capSocket");
                mmSocket = null;
                return;
            }
            mmSocket = tmp;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectThread");
            setName("ConnectThread");

            // Always cancel discovery because it will slow down a connection
            mBluetoothAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() during connection failure", e2);
                }
                connectionFailedOrLost();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothLeService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmSendToSocket);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }


    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final boolean mmSendToSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket, boolean sendToSocket) {
            Log.d(TAG, "create ConnectedThread");
            mmSocket = socket;
            mmSendToSocket = sendToSocket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;
            BufferedOutputStream bufOutStr;

            final File sdcard = Environment.getExternalStorageDirectory();
            fileToWrite = new File(sdcard.getAbsolutePath() + Constants.FOLDER + Constants.FILE_OUTPUT);
            try {
                bufOutStr = new BufferedOutputStream(new FileOutputStream(fileToWrite));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "TLG --------- File cannot be opened -----------");
                return;
            }

            if (mmSendToSocket) {
                // Keep listening to the InputStream while connected
                while (true) {
                    try {
                        // Read from the InputStream
                        Log.i(TAG, "prepare to read from the buffer");
                        bytes = mmInStream.read(buffer);
                        Log.i(TAG, "read from the buffer " + bytes);
                        bufOutStr.write(buffer);
                        Log.i(TAG, "write buffer to the file from the buffer ");
                    } catch (IOException e) {
                        Log.e(TAG, "disconnected", e);
                        connectionFailedOrLost();
                        // Start the service over to restart listening mode
                        BluetoothLeService.this.start();
                        break;
                    }
                }
            } else {
                write();
            }
        }

        /**
         * Write to the connected OutStream.
         */
        public void write() {

            // Open the file
            byte[] buffer = new byte[1024];
            BufferedInputStream bufInStr;

            final File sdcard = Environment.getExternalStorageDirectory();
            fileToPlay = new File(sdcard.getAbsolutePath() + Constants.FOLDER + Constants.FILE_INPUT);
            try {
                bufInStr = new BufferedInputStream(new FileInputStream(fileToPlay));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "TLG --------- File cannot be opened -----------");
                return;
            }
            try {
                Log.d(TAG, "TLG --------- File Opened: available " + bufInStr.available() + "-----------");
            } catch (IOException e) {
                Log.e(TAG, "TLG --------- File read issue with available() -----------");
            }

            // read from the file till EOF
            try {
                while (!(bufInStr.read(buffer) < 0))
                {
                    mmOutStream.write(buffer);
                    Log.d(TAG, "TLG --------- Written " + buffer.length + " ; Remaining available " + bufInStr.available() + "-----------");
                }
            } catch (IOException e) {
                Log.e(TAG, "TLG --------- File cannot read -----------");
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
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
