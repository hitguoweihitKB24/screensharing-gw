package com.example.gw.screensharing_gw.stream;

import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;


import com.example.gw.screensharing_gw.MP4.Config;
import com.example.gw.screensharing_gw.MP4.MP4Config;
import com.example.gw.screensharing_gw.MP4.VideoQuality;
import com.example.gw.screensharing_gw.MainActivity;
import com.example.gw.screensharing_gw.exceptions.ConfNotSupportedException;
import com.example.gw.screensharing_gw.exceptions.StorageUnavailableException;
import com.example.gw.screensharing_gw.server.H264Packetizer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;

/**
 * Created by Administrator on 2015/12/2.
 */
public class VideoStream extends MediaStream
{
    private VirtualDisplay mVirtualDisplay;
    private int mScreenDensity =560;
    private MediaProjection mMediaProjection = MainActivity.getmMediaProjection();
    private MP4Config mConfig;
    private DisplayManager displayManager;
    protected int mRtpPort = 0, mRtcpPort = 0;
    protected String mMimeType;
    protected String mEncoderName;
    protected int mEncoderColorFormat;
    protected int mMaxFps = 0;
    protected int mVideoEncoder = 0;
    protected SharedPreferences mSettings = null;
    protected VideoQuality mRequestedQuality = VideoQuality.DEFAULT_VIDEO_QUALITY.clone();
    protected boolean mUpdated = false;
    protected int mRequestedOrientation = 0, mOrientation = 0;
    protected VideoQuality mQuality = mRequestedQuality.clone();
    private boolean flag = false;
    private MediaCodec mediaCodec;
    public synchronized String getSessionDescription() throws IllegalStateException{
        if (mConfig == null) throw new IllegalStateException("You need to call configure() first !");

        String result = "m=video "+String.valueOf(getDestinationPorts()[0])+" RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 packetization-mode=1;profile-level-id="+mConfig.getProfileLevel()+";sprop-parameter-sets="+mConfig.getB64SPS()+","+mConfig.getB64PPS()+";\r\n";
        Log.d(TAG,result);
        return result;
    }

    public VideoStream() {
        mMimeType = "video/avc";
        mVideoEncoder = MediaRecorder.VideoEncoder.H264;
        mPacketizer = new H264Packetizer();
    }

    @Override
    protected void encodeWithMediaRecorder() throws IOException {

            Log.d(TAG, "Video encoded using the MediaRecorder API");
            // We need a local socket to forward data output by the camera to the packetizer
            createSockets();
            try {

                mMediaRecorder = new MediaRecorder();
                mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                mMediaRecorder.setVideoSize(1080, 1920);
//                mMediaRecorder.setVideoFrameRate(30);
                // The bandwidth actually consumed is often above what was requested
                mMediaRecorder.setVideoEncodingBitRate(5000000);

                // We write the output of the camera in a local socket instead of a file !
                // This one little trick makes streaming feasible quiet simply: data from the camera
                // can then be manipulated at the other end of the socket
                FileDescriptor fd;
//                if (sPipeApi == PIPE_API_PFD) {
                    fd = mParcelWrite.getFileDescriptor();
//                } else  {
//                    fd = mSender.getFileDescriptor();
//                }
                mMediaRecorder.setOutputFile(fd);
                mMediaRecorder.prepare();
               createVirtualDisplay();
                mMediaRecorder.start();

            } catch (Exception e) {
                e.printStackTrace();
                throw new ConfNotSupportedException(e.getMessage());
            }
            InputStream is;
            if (sPipeApi == PIPE_API_PFD) {
                is = new ParcelFileDescriptor.AutoCloseInputStream(mParcelRead);         //这里条件成立了，is流是这里的
            } else  {
                is = mReceiver.getInputStream();
            }
            // This will skip the MPEG4 header if this step fails we can't stream anything :(
            try {
                byte buffer[] = new byte[4];
                // Skip all atoms preceding mdat atom
                while (!Thread.interrupted()) {
                    while (is.read() != 'm');
                    is.read(buffer,0,3);
                    if (buffer[0] == 'd' && buffer[1] == 'a' && buffer[2] == 't') break;
                }
            } catch (IOException e) {
                Log.e(TAG, "Couldn't skip mp4 header :/");
                stop();
                throw e;
            }
            // The packetizer encapsulates the bit stream in an RTP stream and send it over the network
            if(mRtpPort ==0 || mRtcpPort ==0){
                mRtpPort = Config.rtpport;
                mRtcpPort = Config.rtcpport;
            }

            mPacketizer.setDestination(mDestination,mRtpPort,mRtcpPort);
            mPacketizer.setInputStream(is);
            mPacketizer.start();
            mStreaming = true;
    }
    public synchronized void configure() throws IllegalStateException, IOException {
        super.configure();
        mMode = MODE_MEDIARECORDER_API;
        mQuality = mRequestedQuality.clone();
        mConfig = testH264();
    }

    private MP4Config testH264() throws IOException {
        String key = PREF_PREFIX + "h264-mr-" + mRequestedQuality.framerate + "," + mRequestedQuality.resX + "," + mRequestedQuality.resY;
        Log.d("RtspServer", "in the test the key is " + key);
        final String TESTFILE = MainActivity.getPath() + "/spydroid-test.mp4";
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
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoSize(MP4Config.DISPLAY_WIDTH, MP4Config.DISPLAY_HEIGHT);
            mMediaRecorder.setVideoFrameRate(mRequestedQuality.framerate);
            mMediaRecorder.setVideoEncodingBitRate(5000000);
            mMediaRecorder.setOutputFile(TESTFILE);
            // We wait a little and stop recording
            mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                public void onInfo(MediaRecorder mr, int what, int extra) {
                    Log.d(TAG, "MediaRecorder callback called !");
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.d(TAG, "MediaRecorder: MAX_DURATION_REACHED");
                    } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        Log.d(TAG, "MediaRecorder: MAX_FILESIZE_REACHED");
                    } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) {
                        Log.d(TAG, "MediaRecorder: INFO_UNKNOWN");
                    } else {
                        Log.d(TAG, "WTF ?");
                    }
                }
            });
            mMediaRecorder.prepare();
            showimage();
            mMediaRecorder.start();
        } catch (IOException e) {
            throw new ConfNotSupportedException(e.getMessage());
        } catch (RuntimeException e) {
            throw new ConfNotSupportedException(e.getMessage());
        } finally {
            try {
                if (mMediaRecorder != null) {
                    mMediaRecorder.setOnErrorListener(null);
                    mMediaRecorder.setPreviewDisplay(null);
                    Log.d("RtspServer","服务器开始进行设置！");
                }
                wait(1000);//TODO：时间过短导致stop方法错误，后续需要优化；
                mMediaRecorder.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
            Log.d("RtspServer",""+mVirtualDisplay);
        }

        // Retrieve SPS & PPS & ProfileId with MP4Config
        MP4Config config = new MP4Config(TESTFILE);
        byte[] sps= Base64.decode(config.getB64SPS(),Base64.NO_WRAP);
        byte[] pps=Base64.decode(config.getB64PPS(),Base64.NO_WRAP);
        mPacketizer.setStreamParameters(pps,sps);
        Config.pps=config.getB64PPS();
        // Delete dummy video
        File file = new File(TESTFILE);
        if (!file.delete()) Log.e(TAG, "Temp file could not be erased");
        Log.i(TAG, "H264 Test succeded...");

        // Save test result
        if (mSettings != null) {
            Log.d(TAG,"save the test result");
            SharedPreferences.Editor editor = mSettings.edit();
            editor.putString(key, config.getProfileLevel() + "," + config.getB64SPS() + "," + config.getB64PPS());
            editor.commit();
        }

        return config;
    }
    private void showimage() {
        //TODO create Virtual Display
        mVirtualDisplay = createVirtualDisplay();
    }
    private VirtualDisplay createVirtualDisplay() {
        if(mMediaProjection == null)
        {
            Log.d(TAG,"mMediaProjection is null");
        }
        return mMediaProjection.createVirtualDisplay("VideoStream",
                MP4Config.DISPLAY_WIDTH, MP4Config.DISPLAY_HEIGHT, mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mMediaRecorder.getSurface(), null /*Callbacks*/, null /*Handler*/);
    }
    public void setPreferences(SharedPreferences prefs) {
        mSettings = prefs;
        Log.d(TAG,"set the mSetting");
    }
    public void setVideoQuality(VideoQuality videoQuality) {
        if (!mRequestedQuality.equals(videoQuality)) {
            mRequestedQuality = videoQuality.clone();
            mUpdated = false;
        }
    }
    public void setPreviewOrientation(int orientation) {
        mRequestedOrientation = orientation;
        mUpdated = false;
    }

//    class MyThread extends Thread{
//        public void run(){
//            Looper.prepare();
//            Looper looper=Looper.getMainLooper();
//            handler=new MainActivity.Myhandler(looper);
//        }
//    }
}
