package com.nxp.android.bleaudio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.ParcelUuid;

/**
 * Created by thomasleguevel on 27/01/16.
 */
public class Constants {
    public static final ParcelUuid Service_UUID = ParcelUuid
            .fromString("0000b81d-0000-1000-8000-00805f9b34fb");

    public static final boolean AUDIO_FROM_MIC = true;

    public static final boolean AUDIO_TO_SPEAKER = true;
    public static final boolean AUDIO_LOOPBACK = false;

    public static final int SAMPLE_RATE = 48000;
    public static final int AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    public static final int CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public static final int CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    public static final int AUDIO_STREAM = AudioManager.STREAM_VOICE_CALL;


    public static final String FOLDER = "/Development/NXP_BLE";
    public static final String FILE_INPUT = "/input.pcm";
    public static final String FILE_OUTPUT = "/output.pcm";

    // settings for High Quality Mono with SBC (SBC codec input)
    public static final int BUFFER_SIZE = 768; // should be 768 // 6144
    public static final int PERIOD_SIZE = 8;


    // setting for High Quality Stereo without SBC (SBC codec output)
    //public static final int BUFFER_SIZE = 460;
    //public static final int PERIOD_SIZE = 10;

}
