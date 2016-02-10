package com.example.android.bluetoothlegatt;

import android.os.ParcelUuid;

/**
 * Created by thomasleguevel on 27/01/16.
 */
public class Constants {
    public static final ParcelUuid Service_UUID = ParcelUuid
            .fromString("0000b81d-0000-1000-8000-00805f9b34fb");

    public static final String FOLDER = "/Development/NXP_BLE";
    public static final String FILE_INPUT = "/input.txt";
    public static final String FILE_OUTPUT = "/output.txt";
    public static final int BUFFER_SIZE = 988;
    public static final int PERIOD_SIZE = 10;


}
