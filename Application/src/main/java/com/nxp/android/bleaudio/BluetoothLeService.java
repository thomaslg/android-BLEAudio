/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * This application has been inspired by the project
 * BluetoothLeGatt and BluetoothChat from the Google Android sample code.
 * Adaptation made by NXP Semiconductors.
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

package com.nxp.android.bleaudio;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
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
 * Service for managing connection and data communication with a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    // Member fields
    //private final Handler mHandler;
    private ListeningThread mListeningThread;
    private ConnectingThread mConnectingThread;
    private AudioTxThread mAudioTxThread;
    private AudioRxThread mAudioRxThread;


    private int mState;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTENING = 1;     // now listening for incoming connections
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
     * Start the Bluetooth service. Specifically start ListeningThread to begin a
     * session in listening (server) mode. Called by the Activity onResume()
     */
    public synchronized void start() {
        Log.d(TAG, "start");

        // Cancel any thread attempting to make a connection
        if (mConnectingThread != null) {
            mConnectingThread.cancel();
            mConnectingThread = null;
        }

        // Cancel any Tx thread currently running a connection
        if (mAudioTxThread != null) {
            mAudioTxThread.cancel();
            mAudioTxThread = null;
        }
        // Cancel any Rx thread currently running a connection
        if (mAudioRxThread != null) {
            mAudioRxThread.cancel();
            mAudioRxThread = null;
        }

        setState(STATE_LISTENING);

        // Start the thread to listen on a BluetoothServerSocket
        if (mListeningThread == null) {
            mListeningThread = new ListeningThread();
            mListeningThread.start();
        }
    }

    /**
     * Start the ConnectingThread to initiate a connection to a remote device.
     *
     * @param device_address The address of the BluetoothDevice to connect
     */
    public synchronized void connect(String device_address, boolean sendToSocket) {

        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(device_address);
        Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectingThread != null) {
                mConnectingThread.cancel();
                mConnectingThread = null;
            }
        }

        // Cancel any Tx thread currently running a connection
        if (mAudioTxThread != null) {
            mAudioTxThread.cancel();
            mAudioTxThread = null;
        }
        // Cancel any Rx thread currently running a connection
        if (mAudioRxThread != null) {
            mAudioRxThread.cancel();
            mAudioRxThread = null;
        }

        // Start the thread to connect with the given device
        mConnectingThread = new ConnectingThread(device, sendToSocket);
        mConnectingThread.start();
        setState(STATE_CONNECTING);
    }

    /**
     * Start the AudioTxThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     */
    public synchronized void connected(BluetoothSocket socket, boolean sendToSocket) {
        Log.d(TAG, "connected");

        // Cancel the thread that completed the connection
        if (mConnectingThread != null) {
            mConnectingThread.cancel();
            mConnectingThread = null;
        }

        // Cancel any Tx thread currently running a connection
        if (mAudioTxThread != null) {
            mAudioTxThread.cancel();
            mAudioTxThread = null;
        }
        // Cancel any Rx thread currently running a connection
        if (mAudioRxThread != null) {
            mAudioRxThread.cancel();
            mAudioRxThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (mListeningThread != null) {
            Log.d(TAG, "Calling mListeningThread.cancel");
            mListeningThread.cancel();
            Log.d(TAG, "Setting mListeningThread to null");
            mListeningThread = null;
        }

        if (sendToSocket) {
            // Start the thread to manage the connection and perform transmissions
            mAudioTxThread = new AudioTxThread(socket);
            mAudioTxThread.start();
        }

        // Start the thread to manage the connection and perform transmissions
        mAudioRxThread = new AudioRxThread(socket, sendToSocket);
        mAudioRxThread.start();

        setState(STATE_CONNECTED);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        Log.d(TAG, "stop");

        if (mConnectingThread != null) {
            mConnectingThread.cancel();
            mConnectingThread = null;
        }

        if (mAudioTxThread != null) {
            mAudioTxThread.cancel();
            mAudioTxThread = null;
        }

        if (mListeningThread != null) {
            mListeningThread.cancel();
            mListeningThread = null;
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
    private class ListeningThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;


        public ListeningThread() {
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
            Log.d(TAG, "BEGIN mListeningThread "+this);
            setName("ListeningThread");

            BluetoothSocket socket;

            // Listen to the server socket if we're not connected
            while ((mState != STATE_CONNECTED) && (mmServerSocket != null)) {
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
                            case STATE_LISTENING:
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
            Log.i(TAG, "END mListeningThread");
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
    private class ConnectingThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private final boolean mmSendToSocket;

        public ConnectingThread(BluetoothDevice device, boolean sendToSocket) {
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
            Log.i(TAG, "BEGIN mConnectingThread");
            setName("ConnectingThread");

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

            // Reset the ConnectingThread because we're done
            synchronized (BluetoothLeService.this) {
                mConnectingThread = null;
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
    private class AudioTxThread extends Thread {
        private final BluetoothSocket mmSocket;
        private OutputStream mmOutStream;

        protected int minRecordBuffSizeInBytes;
        protected AudioRecord audioRecord;
        protected byte[] recordingByteArray;


        public AudioTxThread(BluetoothSocket socket) {
            Log.d(TAG, "create AudioTxThread");
            mmSocket = socket;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
                mmOutStream = null;
                return;
            }
            mmOutStream = tmpOut;

            // Get minimum buffer size returned for the format
            minRecordBuffSizeInBytes = AudioRecord.getMinBufferSize(Constants.SAMPLE_RATE,
                    Constants.CHANNEL_IN_CONFIG, Constants.AUDIO_FORMAT);

            // Allocate the byte array to read the audio data
            recordingByteArray = new byte[minRecordBuffSizeInBytes];

            if (Constants.AUDIO_FROM_MIC) {
                Log.d(TAG, "Writing from microphone selected");

                // Instantiate the Recorder
                audioRecord = new AudioRecord(Constants.AUDIO_SOURCE, Constants.SAMPLE_RATE,
                        Constants.CHANNEL_IN_CONFIG, Constants.AUDIO_FORMAT,
                        4 * minRecordBuffSizeInBytes);
                Log.d(TAG, "microphone reader / AudioRecord initialized");

            }
        }

        public void run() {
            Log.i(TAG, "BEGIN mAudioTxThread");
            // Input are exclusive. Either from MIC or from file
            if (Constants.AUDIO_FROM_MIC) {
                write_from_mic();
            } else {
                write_from_file();
            }
        }

        /**
         * Write to the connected OutStream.
         */
        public void write_from_mic() {
            int byteRead = 0;
            // read from the file till EOF
            try {
                audioRecord.startRecording();
                while (true) {
                    Log.i(TAG, "Prepare to read from the microphone");
                    while ((byteRead = audioRecord.read(recordingByteArray, 0, minRecordBuffSizeInBytes)) > 0) {
                        mmOutStream.write(recordingByteArray);
                        Log.i(TAG, "read from microphone " + byteRead + " bytes and write them to HCI.");
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "TLG --------- Mic cannot read -----------");
            }
        }

        /**
         * Write to the connected OutStream.
         */
        public void write_from_file() {

            // Open the file
            File fileToPlay;
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
                while (!(bufInStr.read(recordingByteArray, 0, minRecordBuffSizeInBytes) < 0))
                {
                    mmOutStream.write(recordingByteArray);
                    Log.d(TAG, "TLG --------- Written " + minRecordBuffSizeInBytes + " bytes and "+ bufInStr.available() + " bytes remaining --------");
                    int channelNb = (Constants.CHANNEL_IN_CONFIG == AudioFormat.CHANNEL_IN_STEREO? 2 : 1);
                    int bytePerMSec = (Constants.SAMPLE_RATE/1000) * 2 * channelNb;
                    int MsPerFrame = minRecordBuffSizeInBytes/bytePerMSec;
                    Log.d(TAG, "Sleeping now " + MsPerFrame + "ms.");
                    sleep(MsPerFrame, 0);
                }
            } catch (IOException e) {
                Log.e(TAG, "TLG --------- File cannot read -----------");
            } catch (InterruptedException e) {
                Log.e(TAG, "TLG --------- Insomnia issue -----------");
            }
        }


        public void cancel() {
            try {
                mmSocket.close();
                mmOutStream = null;
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }



    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class AudioRxThread extends Thread {
        private final BluetoothSocket mmSocket;
        private InputStream mmInStream;
        private OutputStream mmOutStream;
        private final boolean mmTxActive;

        private AudioManager mAudioManager;
        private int minTrackBuffSizeInBytes;
        private AudioTrack audioTrack;
        private byte[] trackByteArray;

        private File fileToWrite;
        BufferedOutputStream bufOutStr;

        public AudioRxThread(BluetoothSocket socket, boolean txActive) {
            Log.d(TAG, "create AudioRxThread");
            mmSocket = socket;
            mmTxActive = txActive;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
                mmInStream = null;
                mmOutStream = null;
                return;
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;

            // Get minimum buffer size returned for the format
            minTrackBuffSizeInBytes = AudioTrack.getMinBufferSize(Constants.SAMPLE_RATE,
                    Constants.CHANNEL_OUT_CONFIG, Constants.AUDIO_FORMAT);

            // Allocate the byte array to write the audio data to the track
            trackByteArray = new byte[minTrackBuffSizeInBytes];

            if (Constants.AUDIO_TO_SPEAKER) {

                // Instantiate the native player
                audioTrack = new AudioTrack(Constants.AUDIO_STREAM, Constants.SAMPLE_RATE,
                        Constants.CHANNEL_OUT_CONFIG, Constants.AUDIO_FORMAT, 4 * minTrackBuffSizeInBytes,
                        AudioTrack.MODE_STREAM);
                mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                mAudioManager.setSpeakerphoneOn(true);
            }

            if (Constants.AUDIO_TO_FILE) {

                // Opening the file on FileSystem
                final File sdcard = Environment.getExternalStorageDirectory();
                fileToWrite = new File(sdcard.getAbsolutePath() + Constants.FOLDER + Constants.FILE_OUTPUT);
                try {
                    bufOutStr = new BufferedOutputStream(new FileOutputStream(fileToWrite));
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "TLG --------- File cannot be opened -----------");
                }
            }
        }

        public void run() {
            int bytesRead = 0;
            Log.i(TAG, "BEGIN mAudioRxThread");

            if (Constants.AUDIO_TO_SPEAKER)
                audioTrack.play();

            while (mmInStream != null) {
                try {
                    // sync reading with reloading of input stream
                    synchronized (this) {
                        bytesRead = mmInStream.read(trackByteArray, 0, minTrackBuffSizeInBytes);
                        Log.i(TAG, "read from HCI " + bytesRead + " bytes...");
                    }
                } catch (final IOException ioe) {
                    ioe.printStackTrace();
                }

                if (Constants.AUDIO_TO_SPEAKER) {
                    push_to_speaker(bytesRead);
                }
                if ((Constants.AUDIO_LOOPBACK) && (mmTxActive == false)) {
                    push_back_to_sender(bytesRead);
                }
                if (Constants.AUDIO_TO_FILE) {
                    push_to_file(bytesRead);
                }
            }
        }

        public void push_to_speaker(int bytesToWrite) {
            if (bytesToWrite > 0) {
                audioTrack.write(trackByteArray, 0, bytesToWrite);
                Log.i(TAG, "...and write " + bytesToWrite + " bytes to speaker.");
            }
        }

        public void push_to_file(int bytesToWrite) {
            try {
                // Read from the InputStream
                if (bytesToWrite > 0) {
                    bufOutStr.write(trackByteArray, 0, bytesToWrite);
                    Log.i(TAG, "...and write " + bytesToWrite + " bytes to file.");
                }
            } catch (IOException e) {
                Log.e(TAG, "disconnected", e);
                connectionFailedOrLost();
                // Start the service over to restart listening mode
                BluetoothLeService.this.start();
            }
        }

        public void push_back_to_sender(int bytesToWrite) {
            try {
                if (bytesToWrite > 0) {
                    mmOutStream.write(trackByteArray, 0, bytesToWrite);
                    Log.i(TAG, "...and write " + bytesToWrite + " bytes back to sender.");
                }
            } catch (IOException e) {
                Log.e(TAG, "Loopback mode: issue with looping back streams");
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
                mmInStream = null;
                mmOutStream = null;
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


