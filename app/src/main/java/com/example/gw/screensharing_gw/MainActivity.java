package com.example.gw.screensharing_gw;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.widget.Toast;
import android.widget.ToggleButton;
import com.example.gw.screensharing_gw.server.H264Packetizer;
import com.example.gw.screensharing_gw.server.RtspServer;
import com.example.gw.screensharing_gw.server.SessionBuilder;
import com.example.gw.screensharing_gw.stream.VideoStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;

public class MainActivity extends Activity {
    protected static final int PERMISSION_CODE = 1;
    protected static final String START_MEDIARECORDER = "start_mediarecorder";
    protected static int TEST = 0X000000;
    public static final int socket_msg = 0x123;
    protected static MediaRecorder mMediaRecorder;
    private MediaProjectionManager mProjectionManager;
    private MediaProjectionCallback mMediaProjectionCallback;
    public static MediaProjection mMediaProjection;
    private static final String TAG = "MediaProjection_main";
    private VirtualDisplay mVirtualDisplay;
    public int buffersize = 500000;
    public static Context mcontext = null;
    public static int RTSP_PORT = 8866;
    public static boolean storagePermission = false;
    private int WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 0x00;
    private  static String path = null;
    public static String getPath(){
        return path;
    }
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        this.mcontext = this.getApplication().getApplicationContext();
        mMediaRecorder = new MediaRecorder();
        path=getApplicationContext().getFilesDir().getAbsolutePath();
        mProjectionManager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mMediaProjectionCallback=new MediaProjectionCallback();
        shareScreen();
        RtspServer.setContext(getApplicationContext());
        SessionBuilder.getInstance().build();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            //申请STORAGE权限
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission_group.STORAGE},
                    WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        }
        this.startService(new Intent(this, RtspServer.class));
    }

    /*
     * set the control port is 8088
     * start the TCP Server
     */

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        doNext(requestCode,grantResults);
    }
    private void doNext(int requestCode, int[] grantResults) {
        if (requestCode == WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            } else {
                // Permission Denied
            }
        }
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        if(mMediaProjection != null){
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        quitMediaProjection();
    }

    private void quitMediaProjection() {
        this.stopService(new Intent(this,RtspServer.class));
    }

//    public void onToggleScreenShare(View view) throws IOException {
//        if(((ToggleButton) view).isChecked())
//        {
//            shareScreen();
//        } else {
//            mMediaRecorder.stop();
//            mMediaRecorder.reset();
//            mMediaProjection.stop();
//            Log.v(TAG, "Recording Stopped");
//            stopScreenSharing();
//        }
//    }

    public void stopScreenSharing() {
        if (mVirtualDisplay == null) {
            return;
        }
        mVirtualDisplay.release();
    }

    public  void shareScreen()  {
        if(mMediaProjection == null)
        {
            startActivityForResult(mProjectionManager.createScreenCaptureIntent(),PERMISSION_CODE);
            return;
        }
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PERMISSION_CODE) {
            Log.e(TAG, "Unknown request code: " + requestCode);
            return;
        }
        if (resultCode != RESULT_OK) {
            Toast.makeText(this,
                    "Screen Cast Permission Denied", Toast.LENGTH_SHORT).show();
            return;
        }
        mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
        mMediaProjection.registerCallback(mMediaProjectionCallback, null);
    }

//    @Override
//    public boolean onOptionsItemSelected(MenuItem item) {
//        int id = item.getItemId();
//        return super.onOptionsItemSelected(item);
//    }

    public class MediaProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            mMediaProjection = null;
            stopScreenSharing();
            Log.i(TAG, "MediaProjection Stopped");

        }
    }
    /**
     * To process message
     */
    public static MediaProjection getmMediaProjection(){
        return mMediaProjection;
    }

}
