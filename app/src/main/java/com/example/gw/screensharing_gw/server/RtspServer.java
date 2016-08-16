package com.example.gw.screensharing_gw.server;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.example.gw.screensharing_gw.MP4.Config;
import com.example.gw.screensharing_gw.MP4.UriParser;
import com.example.gw.screensharing_gw.MainActivity;
import com.example.gw.screensharing_gw.stream.VideoStream;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class RtspServer extends Service{

    public final static String TAG = "RtspServer";

    /** The server name that will appear in responses. */
    public static String SERVER_NAME = "guowei RTSP Server";
    private static Context mcontext;
    /** Port used by default. */
    public static final int DEFAULT_RTSP_PORT = 8086;

    /** Port already in use. */
    public final static int ERROR_BIND_FAILED = 0x00;

    /** A stream could not be started. */
    public final static int ERROR_START_FAILED = 0x01;

    /** Streaming started. */
    public final static int MESSAGE_STREAMING_STARTED = 0X00;

    /** Streaming stopped. */
    public final static int MESSAGE_STREAMING_STOPPED = 0X01;

    /** Key used in the SharedPreferences to store whether the RTSP server is enabled or not. */
    public final static String KEY_ENABLED = "rtsp_enabled";

    /** Key used in the SharedPreferences for the port used by the RTSP server. */
    public final static String KEY_PORT = "rtsp_port";

    protected SessionBuilder mSessionBuilder;
    protected SharedPreferences mSharedPreferences;
    protected boolean mEnabled = true;
    protected int mPort = MainActivity.RTSP_PORT;
    protected WeakHashMap<Session,Object> mSessions = new WeakHashMap<Session,Object>(2);

    private RequestListener mListenerThread;
    private final IBinder mBinder = new LocalBinder();
    private boolean mRestart = false;
    private final LinkedList<CallbackListener> mListeners = new LinkedList<CallbackListener>();

    /** Credentials for Basic Auth */
    private String mUsername;
    private String mPassword;

    public RtspServer() {
    }

    public static void setContext(Context context) {
        mcontext = context;
    }

    public interface CallbackListener {

        /** Called when an error occurs. */
        void onError(RtspServer server, Exception e, int error);

        /** Called when streaming starts/stops. */
        void onMessage(RtspServer server, int message);

    }
    public void addCallbackListener(CallbackListener listener) {
        synchronized (mListeners) {
            if (mListeners.size() > 0) {
                for (CallbackListener cl : mListeners) {
                    if (cl == listener) return;
                }
            }
            mListeners.add(listener);
        }
    }
    public void removeCallbackListener(CallbackListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }
    public void start() {
        if (!mEnabled || mRestart) stop();
        if (mEnabled && mListenerThread == null) {
            try {
                mListenerThread = new RequestListener();
            } catch (Exception e) {
                mListenerThread = null;
            }
        }
        mRestart = false;
    }
    public void stop() {
        if (mListenerThread != null) {
            try {
                mListenerThread.kill();
                for ( Session session : mSessions.keySet() ) {
                    if ( session != null ) {
                        if (session.isStreaming()) session.stop();
                    }
                }
            } catch (Exception e) {
            } finally {
                mListenerThread = null;
            }
        }
    }
    /** Returns whether or not the RTSP server is streaming to some client(s). */
    public boolean isStreaming() {
        for ( Session session : mSessions.keySet() ) {
            if ( session != null ) {
                if (session.isStreaming()) return true;
            }
        }
        return false;
    }

    public boolean isEnabled() {
        return mEnabled;
    }
    public long getBitrate() {
        long bitrate = 0;
        for ( Session session : mSessions.keySet() ) {
            if ( session != null ) {
                if (session.isStreaming()) bitrate += session.getBitrate();
            }
        }
        return bitrate;
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Return the communication channel to the service.  May return null if
     * clients can not bind to the service.  The returned
     * {@link IBinder} is usually for a complex interface
     * that has been <a href="{@docRoot}guide/components/aidl.html">described using
     * aidl</a>.
     * <p/>
     * <p><em>Note that unlike other application components, calls on to the
     * IBinder interface returned here may not happen on the main thread
     * of the process</em>.  More information about the main thread can be found in
     * <a href="{@docRoot}guide/topics/fundamentals/processes-and-threads.html">Processes and
     * Threads</a>.</p>
     *
     * @param intent The Intent that was used to bind to this service,
     *               as given to {@link Context#bindService
     *               Context.bindService}.  Note that any extras that were included with
     *               the Intent at that point will <em>not</em> be seen here.
     * @return Return an IBinder through which clients can call on to the
     * service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {

        // Let's restore the state of the service
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mPort = Integer.parseInt(mSharedPreferences.getString(KEY_PORT, String.valueOf(mPort)));
        mEnabled = mSharedPreferences.getBoolean(KEY_ENABLED, mEnabled);

        // If the configuration is modified, the server will adjust
        mSharedPreferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        start();
    }
    @Override
    public void onDestroy() {
        stop();
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
    }
    private SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {

            if (key.equals(KEY_PORT)) {
                int port = Integer.parseInt(sharedPreferences.getString(KEY_PORT, String.valueOf(mPort)));
                if (port != mPort) {
                    mPort = port;
                    mRestart = true;
                    start();
                }
            }
            else if (key.equals(KEY_ENABLED)) {
                mEnabled = sharedPreferences.getBoolean(KEY_ENABLED, mEnabled);
                start();
            }
        }
    };
    /** The Binder you obtain when a connection with the Service is established. */
    public class LocalBinder extends Binder {
        public RtspServer getService() {
            return RtspServer.this;
        }
    }
    protected void postMessage(int id) {
        synchronized (mListeners) {
            if (mListeners.size() > 0) {
                for (CallbackListener cl : mListeners) {
                    cl.onMessage(this, id);
                }
            }
        }
    }
    protected void postError(Exception exception, int id) {
        synchronized (mListeners) {
            if (mListeners.size() > 0) {
                for (CallbackListener cl : mListeners) {
                    cl.onError(this, exception, id);
                }
            }
        }
    }
    /**
     * By default the RTSP uses {@link UriParser} to parse the URI requested by the client
     * but you can change that behavior by override this method.
     * @param uri The uri that the client has requested
     * @param client The socket associated to the client
     * @return A proper session
     */
    protected Session handleRequest(String uri, Socket client) throws IllegalStateException, IOException {
        Log.d(TAG,"Client uri is " + uri);

        Session session = UriParser.parse(uri);
        session.setOrigin(client.getLocalAddress().getHostAddress());
        if (session.getDestination()==null) {
            session.setDestination(client.getInetAddress().getHostAddress());
        }
        return session;
    }
    class RequestListener extends Thread implements Runnable {

        private final ServerSocket mServer;

        public RequestListener() throws IOException {
            try {
                mPort = MainActivity.RTSP_PORT;
                Log.d(TAG,"Port is "+mPort);
                mServer = new ServerSocket(mPort);
                start();
            } catch (BindException e) {
                Log.e(TAG,"Port already in use !");
                postError(e, ERROR_BIND_FAILED);
                throw e;
            }
        }

        public void run() {
            Log.i(TAG,"RTSP server listening on port "+mServer.getLocalPort());
            while (!Thread.interrupted()) {
                try {
                    new WorkerThread(mServer.accept()).start();
                } catch (SocketException e) {
                    break;
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage());
                    continue;
                }
            }
            Log.i(TAG,"RTSP server stopped !");
        }

        public void kill() {
            try {
                mServer.close();
            } catch (IOException e) {}
            try {
                this.join();
            } catch (InterruptedException ignore) {}
        }
    }
    // One thread per client
    class WorkerThread extends Thread implements Runnable {


        private final Socket mClient;
        private final OutputStream mOutput;
        private final BufferedReader mInput;

        // Each client has an associated session
        private Session mSession;

        public WorkerThread(final Socket client) throws IOException {
            mInput = new BufferedReader(new InputStreamReader(client.getInputStream()));
            mOutput = client.getOutputStream();
            mClient = client;
            mSession = new Session();
        }

        public void run() {
            Request request;
            Response response;

            Log.i(TAG, "Connection from "+mClient.getInetAddress().getHostAddress());

            while (!Thread.interrupted()) {

                request = null;
                response = null;

                // Parse the request
                try {
                    request = Request.parseRequest(mInput);
                } catch (SocketException e) {
                    // Client has left
                    break;
                } catch (Exception e) {
                    // We don't understand the request :/
                    response = new Response();
                    response.status = Response.STATUS_BAD_REQUEST;
                }

                // Do something accordingly like starting the streams, sending a session description
                if (request != null) {
                    try {
                        response = processRequest(request);
                    }
                    catch (Exception e) {
                        // This alerts the main thread that something has gone wrong in this thread
                        postError(e, ERROR_START_FAILED);
                        Log.e(TAG,e.getMessage()!=null?e.getMessage():"An error occurred");
                        e.printStackTrace();
                        response = new Response(request);
                    }
                }

                // We always send a response
                // The client will receive an "INTERNAL SERVER ERROR" if an exception has been thrown at some point
                try {
                    response.send(mOutput);
                } catch (IOException e) {
                    Log.e(TAG,"Response was not sent properly");
                    break;
                }
            }

            // Streaming stops when client disconnects
            boolean streaming = isStreaming();
            mSession.syncStop();
            if (streaming && !isStreaming()) {
                postMessage(MESSAGE_STREAMING_STOPPED);
            }
  //          mSession.release();
            Log.e(TAG,"shut down session");
            try {
                mClient.close();
            } catch (IOException ignore) {}
            Log.i(TAG, "Client disconnected");
        }

        public Response processRequest(Request request) throws IllegalStateException, IOException {
            Response response = new Response(request);

            //Ask for authorization unless this is an OPTIONS request
            if(!isAuthorized(request) && !request.method.equalsIgnoreCase("OPTIONS"))
            {
                response.attributes = "WWW-Authenticate: Basic realm=\""+SERVER_NAME+"\"\r\n";
                response.status = Response.STATUS_UNAUTHORIZED;
            }
            else
            {
			    /* ********************************************************************************** */
			    /* ********************************* Method DESCRIBE ******************************** */
			    /* ********************************************************************************** */
                if (request.method.equalsIgnoreCase("DESCRIBE")) {
                    // Parse the requested URI and configure the session
                    mSession = handleRequest(request.uri, mClient);
                    mSessions.put(mSession, null);
                    VideoStream stream = new VideoStream();
                    stream.setPreferences(PreferenceManager.getDefaultSharedPreferences(mcontext));
                    stream.setDestinationAddress(mClient.getInetAddress());
                    mSession.addVideoTrack(stream);
                    mSession.syncConfigure();
                    String requestContent = mSession.getSessionDescription();
                    String requestAttributes =
                            "Content-Base: " + mClient.getLocalAddress().getHostAddress() + ":" + mClient.getLocalPort() + "/\r\n" +
                                    "Content-Type: application/sdp\r\n";
                    Log.d(TAG,requestAttributes);
                    response.attributes = requestAttributes;
                    response.content = requestContent;

                    // If no exception has been thrown, we reply with OK
                    response.status = Response.STATUS_OK;

                }

                /* ********************************************************************************** */
                /* ********************************* Method OPTIONS ********************************* */
                /* ********************************************************************************** */
                else if (request.method.equalsIgnoreCase("OPTIONS")) {
                    response.status = Response.STATUS_OK;
                    response.attributes = "Public: DESCRIBE,SETUP,TEARDOWN,PLAY,PAUSE\r\n";
                    response.status = Response.STATUS_OK;
                }

                /* ********************************************************************************** */
                /* ********************************** Method SETUP ********************************** */
                /* ********************************************************************************** */
                else if (request.method.equalsIgnoreCase("SETUP")) {
                    Log.d(TAG,"in the SRTUP method");
                    Pattern p;
                    Matcher m;
                    int p2, p1, ssrc, trackId, src[];
                    String destination;

                    p = Pattern.compile("trackID=(\\w+)", Pattern.CASE_INSENSITIVE);
                    m = p.matcher(request.uri);

                    if (!m.find()) {
                        response.status = Response.STATUS_BAD_REQUEST;
                        return response;
                    }

                    trackId = Integer.parseInt(m.group(1));
                    Log.v(TAG,"trackId is "+trackId);
                    if (!mSession.trackExists(trackId)) {
                        response.status = Response.STATUS_NOT_FOUND;
                        return response;
                    }

                    p = Pattern.compile("client_port=(\\d+)-(\\d+)", Pattern.CASE_INSENSITIVE);
                    m = p.matcher(request.headers.get("transport"));

                    if (!m.find()) {
                        int[] ports = mSession.getTrack(trackId).getDestinationPorts();
                        p1 = ports[0];
                        p2 = ports[1];
                    } else {
                        p1 = Integer.parseInt(m.group(1));
                        p2 = Integer.parseInt(m.group(2));
                    }
                    if(p1 == 0 || p2 == 0){
                        p1 = Config.rtpport;
                        p2 = Config.rtcpport;
                    }else{
                        Config.rtpport = p1;
                        Config.rtcpport = p2;
                    }
                    ssrc = mSession.getTrack(trackId).getSSRC();
                    src = mSession.getTrack(trackId).getLocalPorts();
                    destination = mSession.getDestination();
                    Log.d(TAG,"dest should be " + destination);
                    Log.d(TAG, "first the rtpport is " + p1 + " the rtcpport is " + p2);
                    if(p1 ==0 || p2==0){

                    }else {
                        mSession.getTrack(trackId).setDestinationPorts(p1, p2);
                    }
                    Log.d(TAG,"in the server,the ports are set to"+String.valueOf(mSession.getTrack(trackId).getDestinationPorts()[0]));
                    boolean streaming = isStreaming();
                    mSession.syncStart(trackId);
                    if (!streaming && isStreaming()) {
                        postMessage(MESSAGE_STREAMING_STARTED);
                    }
                    Log.d(TAG, "the rtpport is " + p1 + " the rtcpport is " + p2);
                    response.attributes = "Transport: RTP/AVP/UDP;" + (InetAddress.getByName(destination).isMulticastAddress() ? "multicast" : "unicast") +
                            ";destination=" + mSession.getDestination() +
                            ";client_port=" + p1 + "-" + p2 +
                            ";server_port=" + src[0] + "-" + src[1] +
                            ";ssrc=" + Integer.toHexString(ssrc) +
                            ";mode=play\r\n" +
                            "Session: " + "1185d20035702ca" + "\r\n" +
                            "Cache-Control: no-cache\r\n";
                    response.status = Response.STATUS_OK;

                    // If no exception has been thrown, we reply with OK
                    response.status = Response.STATUS_OK;

                }

                /* ********************************************************************************** */
                /* ********************************** Method PLAY *********************************** */
                /* ********************************************************************************** */
                else if (request.method.equalsIgnoreCase("PLAY")) {
                    String requestAttributes = "RTP-Info: ";
                    if (mSession.trackExists(0))
                        requestAttributes += "url=rtsp://" + mClient.getLocalAddress().getHostAddress() + ":" + mClient.getLocalPort() + "/trackID=" + 0 + ";seq=0,";
                    if (mSession.trackExists(1))
                        requestAttributes += "url=rtsp://" + mClient.getLocalAddress().getHostAddress() + ":" + mClient.getLocalPort() + "/trackID=" + 1 + ";seq=0,";
                    requestAttributes = requestAttributes.substring(0, requestAttributes.length() - 1) + "\r\nSession: 1185d20035702ca\r\n";

                    response.attributes = requestAttributes;

                    // If no exception has been thrown, we reply with OK
                    response.status = Response.STATUS_OK;

                }

                /* ********************************************************************************** */
                /* ********************************** Method PAUSE ********************************** */
                /* ********************************************************************************** */
                else if (request.method.equalsIgnoreCase("PAUSE")) {
                    response.status = Response.STATUS_OK;
                }

                /* ********************************************************************************** */
                /* ********************************* Method TEARDOWN ******************************** */
                /* ********************************************************************************** */
                else if (request.method.equalsIgnoreCase("TEARDOWN")) {
                    response.status = Response.STATUS_OK;
                    Log.e(TAG,"in the TEARDOWN ");
                }

                /* ********************************************************************************** */
                /* ********************************* Unknown method ? ******************************* */
                /* ********************************************************************************** */
                else {
                    Log.e(TAG, "Command unknown: " + request);
                    response.status = Response.STATUS_BAD_REQUEST;
                }
            }
            return response;

        }

        /**
         * Check if the request is authorized
         * @param request
         * @return true or false
         */
        private boolean isAuthorized(Request request)
        {
            String auth = request.headers.get("authorization");
            if(mUsername == null || mPassword == null || mUsername.isEmpty())
                return true;

            if(auth != null && !auth.isEmpty())
            {
                String received = auth.substring(auth.lastIndexOf(" ")+1);
                String local = mUsername+":"+mPassword;
                String localEncoded = Base64.encodeToString(local.getBytes(), Base64.NO_WRAP);
                if(localEncoded.equals(received))
                    return true;
            }

            return false;
        }
    }
    static class Request {

        // Parse method & uri
        public static final Pattern regexMethod = Pattern.compile("(\\w+) (\\S+) RTSP",Pattern.CASE_INSENSITIVE);
        // Parse a request header
        public static final Pattern rexegHeader = Pattern.compile("(\\S+):(.+)",Pattern.CASE_INSENSITIVE);

        public String method;
        public String uri;
        public HashMap<String,String> headers = new HashMap<String,String>();

        /** Parse the method, uri & headers of a RTSP request */
        public static Request parseRequest(BufferedReader input) throws IOException, IllegalStateException, SocketException {
            Request request = new Request();
            String line;
            Matcher matcher;

            // Parsing request method & uri
            if ((line = input.readLine())==null) throw new SocketException("Client disconnected");
            matcher = regexMethod.matcher(line);
            matcher.find();
            request.method = matcher.group(1);
            request.uri = matcher.group(2);

            // Parsing headers of the request
            while ( (line = input.readLine()) != null && line.length()>3 ) {
                matcher = rexegHeader.matcher(line);
                matcher.find();
                request.headers.put(matcher.group(1).toLowerCase(Locale.US),matcher.group(2));
            }
            if (line==null) throw new SocketException("Client disconnected");

            // It's not an error, it's just easier to follow what's happening in logcat with the request in red
            Log.e(TAG,request.method+" "+request.uri);
            return request;
        }
    }
    static class Response {

        // Status code definitions
        public static final String STATUS_OK = "200 OK";
        public static final String STATUS_BAD_REQUEST = "400 Bad Request";
        public static final String STATUS_UNAUTHORIZED = "401 Unauthorized";
        public static final String STATUS_NOT_FOUND = "404 Not Found";
        public static final String STATUS_INTERNAL_SERVER_ERROR = "500 Internal Server Error";

        public String status = STATUS_INTERNAL_SERVER_ERROR;
        public String content = "";
        public String attributes = "";

        private final Request mRequest;

        public Response(Request request) {
            this.mRequest = request;
        }

        public Response() {
            // Be careful if you modify the send() method because request might be null !
            mRequest = null;
        }

        public void send(OutputStream output) throws IOException {
            int seqid = -1;
            try {
                seqid = Integer.parseInt(mRequest.headers.get("cseq").replace(" ",""));
            } catch (Exception e) {
                Log.e(TAG,"Error parsing CSeq: "+(e.getMessage()!=null?e.getMessage():""));
            }

            String response = 	"RTSP/1.0 "+status+"\r\n" +
                    "Server: "+SERVER_NAME+"\r\n" +
                    (seqid>=0?("Cseq: " + seqid + "\r\n"):"") +
                    "Content-Length: " + content.length() + "\r\n" +
                    attributes +
                    "\r\n" +
                    content;
            Log.d(TAG,response.replace("\r", ""));
            output.write(response.getBytes());
        }
    }

}
