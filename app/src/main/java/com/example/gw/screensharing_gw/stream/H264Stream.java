package com.example.gw.screensharing_gw.stream;


import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import com.example.gw.screensharing_gw.MP4.Config;
import com.example.gw.screensharing_gw.MP4.EncoderDebugger;
import com.example.gw.screensharing_gw.MP4.MP4Config;
import com.example.gw.screensharing_gw.exceptions.ConfNotSupportedException;
import com.example.gw.screensharing_gw.exceptions.StorageUnavailableException;

import java.io.File;
import java.io.IOException;

/**
 * Created by Administrator on 2015/12/2.
 */
public class H264Stream extends VideoStream {
    private MP4Config mConfig;
    protected boolean mFlashEnabled = false;
    public final static String TAG = "H264Stream";
//  Constructs ths H.264 stream.
    public H264Stream() {
        mMimeType = "video/avc";
//        mCameraImageFormat = ImageFormat.NV21;
        mVideoEncoder = MediaRecorder.VideoEncoder.H264;

    }
    /**
     * Returns a description of the stream using SDP. It can then be included in an SDP file.
     */
    public synchronized String getSessionDescription() throws IllegalStateException {
        if (mConfig == null) throw new IllegalStateException("You need to call configure() first !");
        return "m=video "+String.valueOf(getDestinationPorts()[0])+" RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 packetization-mode=1;profile-level-id="+mConfig.getProfileLevel()+";sprop-parameter-sets="+mConfig.getB64SPS()+","+mConfig.getB64PPS()+";\r\n";
    }
    /**
     * Starts the stream.
     */
    public synchronized void start() throws IllegalStateException, IOException {
        if (!mStreaming) {
            configure();
            byte[] pps = Base64.decode(Config.pps, Base64.NO_WRAP);
            byte[] sps = Base64.decode(Config.sps, Base64.NO_WRAP);
            mPacketizer.setStreamParameters(pps, sps);
            super.start();
        }
    }
    /**
     * Configures the stream. You need to call this before calling {@link #getSessionDescription()} to apply
     * your configuration of the stream.
     */
    public synchronized void configure() throws IllegalStateException, IOException {
        super.configure();
      //  mMode = mRequestedMode;
        mQuality = mRequestedQuality.clone();
        mConfig = testH264();
    }
    private MP4Config testH264() throws IllegalStateException, IOException {
         return testMediaCodecAPI();
    }

    @SuppressLint("NewApi")
    private MP4Config testMediaCodecAPI() throws RuntimeException, IOException {
        try {
            EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);
            return new MP4Config(debugger.getB64SPS(), debugger.getB64PPS());
        } catch (Exception e) {
            // Fallback on the old streaming method using the MediaRecorder API
            Log.e(TAG,"Resolution not supported with the MediaCodec API, we fallback on the old streamign method.");
            return testH264();
        }
    }

    // Should not be called by the UI thread
    private MP4Config testMediaRecorderAPI() throws RuntimeException, IOException {
        String key = PREF_PREFIX+"h264-mr-"+mRequestedQuality.framerate+","+mRequestedQuality.resX+","+mRequestedQuality.resY;

        if (mSettings != null) {
            if (mSettings.contains(key)) {
                String[] s = mSettings.getString(key, "").split(",");
                return new MP4Config(s[0],s[1],s[2]);
            }
        }

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            throw new StorageUnavailableException("No external storage or external storage not ready !");
        }

        final String TESTFILE = Environment.getExternalStorageDirectory().getPath()+"/spydroid-test.mp4";

        Log.i(TAG, "Testing H264 support... Test file saved at: " + TESTFILE);

        try {
            File file = new File(TESTFILE);
            file.createNewFile();
        } catch (IOException e) {
            throw new StorageUnavailableException(e.getMessage());
        }
        try {

            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mMediaRecorder.setVideoEncoder(mVideoEncoder);
            mMediaRecorder.setVideoSize(mRequestedQuality.resX,mRequestedQuality.resY);
            mMediaRecorder.setVideoFrameRate(mRequestedQuality.framerate);
            mMediaRecorder.setVideoEncodingBitRate((int)(mRequestedQuality.bitrate*0.8));
            mMediaRecorder.setOutputFile(TESTFILE);
            mMediaRecorder.setMaxDuration(3000);

            // We wait a little and stop recording
            mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                public void onInfo(MediaRecorder mr, int what, int extra) {
                    Log.d(TAG, "MediaRecorder callback called !");
                    if (what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.d(TAG,"MediaRecorder: MAX_DURATION_REACHED");
                    } else if (what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        Log.d(TAG,"MediaRecorder: MAX_FILESIZE_REACHED");
                    } else if (what==MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) {
                        Log.d(TAG,"MediaRecorder: INFO_UNKNOWN");
                    } else {
                        Log.d(TAG,"WTF ?");
                    }
                }
            });

            // Start recording
            mMediaRecorder.prepare();
            mMediaRecorder.start();
        } catch (IOException e) {
            throw new ConfNotSupportedException(e.getMessage());
        } catch (RuntimeException e) {
            throw new ConfNotSupportedException(e.getMessage());
        } finally {
            try {
                mMediaRecorder.stop();
            } catch (Exception e) {}
            mMediaRecorder.release();
            mMediaRecorder = null;
        }

        // Retrieve SPS & PPS & ProfileId with MP4Config
        MP4Config config = new MP4Config(TESTFILE);

        // Delete dummy video
        File file = new File(TESTFILE);
        if (!file.delete()) Log.e(TAG, "Temp file could not be erased");

        Log.i(TAG,"H264 Test succeded...");

        // Save test result
        if (mSettings != null) {
            SharedPreferences.Editor editor = mSettings.edit();
            editor.putString(key, config.getProfileLevel()+","+config.getB64SPS()+","+config.getB64PPS());
            editor.commit();
        }

        return config;

    }
}
