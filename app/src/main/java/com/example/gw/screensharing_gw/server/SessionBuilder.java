package com.example.gw.screensharing_gw.server;

import android.content.Context;
import android.preference.PreferenceManager;
import android.view.SurfaceView;


import com.example.gw.screensharing_gw.MP4.VideoQuality;
import com.example.gw.screensharing_gw.stream.H264Stream;
import com.example.gw.screensharing_gw.stream.VideoStream;

import java.io.IOException;

/**
 * Created by Administrator on 2015/11/29.
 */
public class SessionBuilder
{

    public final static String TAG = "SessionBuilder";

    /** Can be used with {@link #setVideoEncoder}. */
    public final static int VIDEO_NONE = 0;

    /** Can be used with {@link #setVideoEncoder}. */
    public final static int VIDEO_H264 = 1;

    /** Can be used with {@link #setVideoEncoder}. */
    public final static int VIDEO_H263 = 2;
    // Default configuration
    private VideoQuality mVideoQuality = VideoQuality.DEFAULT_VIDEO_QUALITY;
    private Context mContext;
    private int mVideoEncoder = VIDEO_H264;
    private int mTimeToLive = 64;
    private int mOrientation = 0;
    private boolean mFlash = false;
    private SurfaceView mSurfaceView = null;
    private String mOrigin = null;
    private String mDestination = null;
    private Session.Callback mCallback = null;

    // Removes the default public constructor
    private SessionBuilder() {}

    // The SessionManager implements the singleton pattern
    private static volatile SessionBuilder sInstance = null;

    /**
     * Returns a reference to the {@link SessionBuilder}.
     * @return The reference to the {@link SessionBuilder}
     */
    public final static SessionBuilder getInstance() {
        if (sInstance == null) {
            synchronized (SessionBuilder.class) {
                if (sInstance == null) {
                    SessionBuilder.sInstance = new SessionBuilder();
                }
            }
        }
        return sInstance;
    }

    /**
     * Creates a new {@link Session}.
     * @return The new Session
     * @throws IOException
     */
    public Session build() {
        Session session;

        session = new Session();
        session.setOrigin(mOrigin);
        session.setDestination(mDestination);
        session.setTimeToLive(mTimeToLive);
        session.setCallback(mCallback);
        switch (mVideoEncoder) {
            case VIDEO_H264:
                H264Stream stream = new H264Stream();
                if (mContext!=null)
                    stream.setPreferences(PreferenceManager.getDefaultSharedPreferences(mContext));
                session.addVideoTrack(stream);
                break;
        }

        if (session.getVideoTrack()!=null) {
            VideoStream video = session.getVideoTrack();
            video.setVideoQuality(mVideoQuality);
            video.setPreviewOrientation(mOrientation);
            video.setDestinationPorts(5006);
        }



        return session;

    }

    /**
     * Access to the context is needed for the H264Stream class to store some stuff in the SharedPreferences.
     * Note that you should pass the Application context, not the context of an Activity.
     **/
    public SessionBuilder setContext(Context context) {
        mContext = context;
        return this;
    }

    /** Sets the destination of the session. */
    public SessionBuilder setDestination(String destination) {
        mDestination = destination;
        return this;
    }

    /** Sets the origin of the session. It appears in the SDP of the session. */
    public SessionBuilder setOrigin(String origin) {
        mOrigin = origin;
        return this;
    }

    /** Sets the video stream quality. */
    public SessionBuilder setVideoQuality(VideoQuality quality) {
        mVideoQuality = quality.clone();
        return this;
    }


    /** Sets the default video encoder. */
    public SessionBuilder setVideoEncoder(int encoder) {
        mVideoEncoder = encoder;
        return this;
    }

    public SessionBuilder setFlashEnabled(boolean enabled) {
        mFlash = enabled;
        return this;
    }


    public SessionBuilder setTimeToLive(int ttl) {
        mTimeToLive = ttl;
        return this;
    }

    /**
     * Sets the SurfaceView required to preview the video stream.
     **/
    public SessionBuilder setSurfaceView(SurfaceView surfaceView) {
        mSurfaceView = surfaceView;
        return this;
    }

    /**
     * Sets the orientation of the preview.
     * @param orientation The orientation of the preview
     */
    public SessionBuilder setPreviewOrientation(int orientation) {
        mOrientation = orientation;
        return this;
    }

    public SessionBuilder setCallback(Session.Callback callback) {
        mCallback = callback;
        return this;
    }

    /** Returns the context set with {@link #setContext(Context)}*/
    public Context getContext() {
        return mContext;
    }

    /** Returns the destination ip address set with {@link #setDestination(String)}. */
    public String getDestination() {
        return mDestination;
    }

    /** Returns the origin ip address set with {@link #setOrigin(String)}. */
    public String getOrigin() {
        return mOrigin;
    }

    /** Returns the audio encoder set with {@link #setAudioEncoder(int)}. */


    /** Returns the id of the {@link android.hardware.Camera} set with {@link #setCamera(int)}. */


    /** Returns the video encoder set with {@link #setVideoEncoder(int)}. */
    public int getVideoEncoder() {
        return mVideoEncoder;
    }

    /** Returns the VideoQuality set with {@link #setVideoQuality(VideoQuality)}. */
    public VideoQuality getVideoQuality() {
        return mVideoQuality;
    }

    /** Returns the AudioQuality set with {@link #setAudioQuality(AudioQuality)}. */

    /** Returns the flash state set with {@link #setFlashEnabled(boolean)}. */
    public boolean getFlashState() {
        return mFlash;
    }

    /** Returns the SurfaceView set with {@link #setSurfaceView(SurfaceView)}. */
    public SurfaceView getSurfaceView() {
        return mSurfaceView;
    }


    /** Returns the time to live set with {@link #setTimeToLive(int)}. */
    public int getTimeToLive() {
        return mTimeToLive;
    }

    /** Returns a new {@link SessionBuilder} with the same configuration. */
    public SessionBuilder clone() {
        return new SessionBuilder()
                .setDestination(mDestination)
                .setOrigin(mOrigin)
                .setSurfaceView(mSurfaceView)
                .setPreviewOrientation(mOrientation)
                .setVideoQuality(mVideoQuality)
                .setVideoEncoder(mVideoEncoder)
                .setFlashEnabled(mFlash)
                .setTimeToLive(mTimeToLive)
                .setContext(mContext)
                .setCallback(mCallback);
    }
}
