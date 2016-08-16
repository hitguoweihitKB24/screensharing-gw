package com.example.gw.screensharing_gw.server;

import android.net.rtp.AudioStream;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;


import com.example.gw.screensharing_gw.exceptions.CameraInUseException;
import com.example.gw.screensharing_gw.exceptions.ConfNotSupportedException;
import com.example.gw.screensharing_gw.exceptions.InvalidSurfaceException;
import com.example.gw.screensharing_gw.exceptions.StorageUnavailableException;
import com.example.gw.screensharing_gw.stream.Stream;
import com.example.gw.screensharing_gw.stream.VideoStream;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;

/**
 * Created by Administrator on 2015/11/28.
 */
public class Session {
    public final static String TAG = "Session";

    public final static int STREAM_VIDEO = 0x01;

    public final static int STREAM_AUDIO = 0x00;
    /** The phone may not support some streaming parameters that you are trying to use (bit rate, frame rate, resolution...). */
    public final static int ERROR_CONFIGURATION_NOT_SUPPORTED = 0x01;

    public final static int ERROR_STORAGE_NOT_READY = 0x02;
    /** The supplied SurfaceView is not a valid surface, or has not been created yet. */
    public final static int ERROR_INVALID_SURFACE = 0x04;
    /** The phone has no flash. */
    public final static int ERROR_UNKNOWN_HOST = 0x05;
    private AudioStream mAudioStream = null;

    /**
     * Some other error occurred !
     */
    public final static int ERROR_OTHER = 0x06;
    private String mOrigin;
    private String mDestination;
    private int mTimeToLive = 64;
    private long mTimestamp;
    private Callback mCallback;
    private Handler mMainHandler;
    private VideoStream mVideoStream;
    private Handler mHandler;
    private static CountDownLatch sSignal;
    private static Handler sHandler;

    static {
        // Creates the Thread that will be used when asynchronous methods of a Session are called
        sSignal = new CountDownLatch(1);
        new HandlerThread("com.example.zzr.mediaprojection.Session"){
            @Override
            protected void onLooperPrepared() {
                sHandler = new Handler();
                sSignal.countDown();
            }
        }.start();
    }

    public Session() {
        long uptime = System.currentTimeMillis();
        HandlerThread thread = new HandlerThread("com.example.zzr.mediaprojection.Session");
        thread.start();

        mHandler = new Handler(thread.getLooper());
        mMainHandler = new Handler(Looper.getMainLooper());
        mTimestamp = (uptime/1000)<<32 & (((uptime-((uptime/1000)*1000))>>32)/1000); // NTP timestamp
        mOrigin = "127.0.0.1";
    }
    /**
     * The callback interface you need to implement to get some feedback
     * Those will be called from the UI thread.
     */
    public interface Callback {

        /**
         * Called periodically to inform you on the bandwidth
         * consumption of the streams when streaming.
         */
        void onBitrateUpdate(long bitrate);

        /** Called when some error occurs. */
        void onSessionError(int reason, int streamType, Exception e);


        /**
         * Called when the session has correctly been configured
         * after calling {@link Session#configure()}.
         * If an error occurs while configuring the {@link Session},
         * {@link Callback#onSessionError(int, int, Exception)} will be
         * called instead of  {@link Callback#onSessionConfigured()}.
         */
        void onSessionConfigured();

        /**
         * Called when the streams of the session have correctly been started.
         * If an error occurs while starting the {@link Session},
         * {@link Callback#onSessionError(int, int, Exception)} will be
         * called instead of  {@link Callback#onSessionStarted()}.
         */
        void onSessionStarted();

        /** Called when the stream of the session have been stopped. */
        void onSessionStopped();

    }

    void addVideoTrack(VideoStream track) {
        removeVideoTrack();
        mVideoStream = track;
    }
    public boolean isStreaming()
    {
        if ( (mVideoStream!=null && mVideoStream.isStreaming()) )
            return true;
        else
            return false;

    }
    public String getSessionDescription() {
        StringBuilder sessionDescription = new StringBuilder();
        if (mDestination==null) {
            throw new IllegalStateException("setDestination() has not been called !");
        }
        sessionDescription.append("v=0\r\n");
        // TODO: Add IPV6 support
        sessionDescription.append("o=- "+mTimestamp+" "+mTimestamp+" IN IP4 "+mOrigin+"\r\n");
        sessionDescription.append("s=Unnamed\r\n");
        sessionDescription.append("i=N/A\r\n");
        sessionDescription.append("c=IN IP4 "+mDestination+"\r\n");
        // t=0 0 means the session is permanent (we don't know when it will stop)
        sessionDescription.append("t=0 0\r\n");
        sessionDescription.append("a=recvonly\r\n");
        // Prevents two different sessions from using the same peripheral at the same time
        if (mVideoStream != null) {
            sessionDescription.append(mVideoStream.getSessionDescription());
            sessionDescription.append("a=control:trackID="+1+"\r\n");
        }
        return sessionDescription.toString();
    }
    void removeVideoTrack() {
        if (mVideoStream != null) {
            mVideoStream = null;
        }
    }
    public void configure() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    syncConfigure();
                } catch (Exception ignored) {}
            }
        });
    }
    public void syncConfigure()
            throws
            RuntimeException,
            IOException {
        Log.d(TAG,"in the syncConfigure");
        for (int id=0;id<2;id++) {
            Stream stream = id==0 ? (Stream) mAudioStream : mVideoStream;
            if (stream!=null && !stream.isStreaming()) {
                try {
                    Log.d("RtspServer","in the syncConfigure ,id = "+id);
                    stream.configure();
                    Log.d("RtspServer","in the syncConfigure ,id = "+id + "is over");
                }  catch (StorageUnavailableException e) {
                    postError(ERROR_STORAGE_NOT_READY , id, e);
                    throw e;
                } catch (ConfNotSupportedException e) {
                    postError(ERROR_CONFIGURATION_NOT_SUPPORTED , id, e);
                    throw e;
                } catch (InvalidSurfaceException e) {
                    postError(ERROR_INVALID_SURFACE , id, e);
                    throw e;
                } catch (IOException | RuntimeException e) {
                    postError(ERROR_OTHER, id, e);
                    throw e;
                }
            }
        }
        postSessionConfigured();
    }

    private void postError(final int reason, final int streamType,final Exception e) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCallback != null) {
                    mCallback.onSessionError(reason, streamType, e);
                }
            }
        });
    }
    private void postSessionConfigured() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCallback != null) {
                    mCallback.onSessionConfigured();
                }
            }
        });
    }
    public void setOrigin(String origin) {
        mOrigin = origin;
    }
    public void setDestination(String destination) {
        mDestination =  destination;
    }
    public void setTimeToLive(int ttl) {
        mTimeToLive = ttl;
    }
    public void setCallback(Callback callback) {
        mCallback = callback;
    }
    public VideoStream getVideoTrack() {
        return mVideoStream;
    }
    public long getBitrate() {
        long sum = 0;
        if (mVideoStream != null) sum += mVideoStream.getBitrate();
        return sum;
    }
    public String getDestination() {
        return mDestination;
    }
    public void syncStop() {
        syncStop(1);
        postSessionStopped();
    }
    private void syncStop(final int id) {
        Stream stream = id==0 ? (Stream) mAudioStream : mVideoStream;
        if (stream != null) {
            stream.stop();
        }
    }

    private void postSessionStopped() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCallback != null) {
                    mCallback.onSessionStopped();
                }
            }
        });
    }
    public void release() {
        mVideoStream.stop();
        removeVideoTrack();
        sHandler.getLooper().quit();
    }
    public boolean trackExists(int id) {
        if (id==0)
            return mAudioStream!=null;
        else
            return mVideoStream!=null;
    }
    public Stream getTrack(int id) {
        if (id==0)
            return null;
        else
            return mVideoStream;
    }
    /**
     * Asyncronously starts all streams of the session.
     **/
    public void start() {
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    syncStart();
                } catch (Exception ignored) {}
            }
        });
    }
    /**
     * Does the same thing as {@link #start()}, but in a synchronous manner.
     * Throws exceptions in addition to calling a callback.
     **/
    public void syncStart()
            throws CameraInUseException,
            ConfNotSupportedException,
            InvalidSurfaceException,
            IOException {

        syncStart(1);
        try {
            syncStart(0);
        } catch (RuntimeException | IOException e) {
            syncStop(1);
            throw e;
        }

    }
    public void syncStart(int id)
            throws CameraInUseException,
            ConfNotSupportedException,
            InvalidSurfaceException,
            IOException {

        Stream stream = id==0 ? (Stream) mAudioStream : mVideoStream;
        if (stream!=null && !stream.isStreaming()) {
            Log.d(TAG,"in the syncStart");
            try {
                InetAddress destination =  InetAddress.getByName(mDestination);
                stream.setTimeToLive(mTimeToLive);
                stream.setDestinationAddress(destination);
                stream.start();
                if (getTrack(1-id) == null || getTrack(1-id).isStreaming()) {
                    postSessionStarted();
                }
                if (getTrack(1-id) == null || !getTrack(1-id).isStreaming()) {
//                    sHandler.post(mUpdateBitrate);
                }
            } catch (UnknownHostException e) {
                postError(ERROR_UNKNOWN_HOST, id, e);
                throw e;
            }  catch (StorageUnavailableException e) {
                postError(ERROR_STORAGE_NOT_READY , id, e);
                throw e;
            } catch (ConfNotSupportedException e) {
                postError(ERROR_CONFIGURATION_NOT_SUPPORTED , id, e);
                throw e;
            } catch (InvalidSurfaceException e) {
                postError(ERROR_INVALID_SURFACE , id, e);
                throw e;
            } catch (IOException | RuntimeException e) {
                postError(ERROR_OTHER, id, e);
                throw e;
            }
        }
    }
    private void postSessionStarted() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCallback != null) {
                    mCallback.onSessionStarted();
                }
            }
        });
    }
    private Runnable mUpdateBitrate = new Runnable() {
        @Override
        public void run() {
            if (isStreaming()) {
                postBitRate(getBitrate());
                sHandler.postDelayed(mUpdateBitrate, 500);
            } else {
                postBitRate(0);
            }
        }
    };

    private void postBitRate(final long bitrate) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCallback != null) {
                    mCallback.onBitrateUpdate(bitrate);
                }
            }
        });
    }
    public void stop() {
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                syncStop();
            }
        });
    }
}
