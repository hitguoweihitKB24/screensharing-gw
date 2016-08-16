package com.example.gw.screensharing_gw.server;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Random;

/**
 * Created by Administrator on 2015/11/27.
 */
public class H264Packetizer implements Runnable{

    public final static String TAG = "H264Packetizer";
    private Statistics stats = new Statistics();
    private Thread t = null;
    private int naluLength = 0;
    private long delay = 0, oldtime = 0;
    private byte[] sps =  null, pps = null, stapa = null;
    byte[] header = new byte[5];
    private int count = 0;
    private int streamType = 1;
    protected RtpSocket socket = null;
    protected long ts = 0;
    protected InputStream is = null;
    protected byte[] buffer;
    protected static final int rtphl = RtpSocket.RTP_HEADER_LENGTH;
    protected final static int MAXPACKETSIZE = RtpSocket.MTU-28;

    public H264Packetizer() {
        int ssrc = new Random().nextInt();
        ts = new Random().nextInt();
        socket = new RtpSocket();
        Log.d(TAG,"in the pack SSRC is "+ssrc);
        socket.setSSRC(ssrc);
        socket.setClockFrequency(90000);
    }

    public void start() {
        if (t == null) {
            t = new Thread(this);
            t.start();
        }
    }

    public void setInputStream(InputStream is) {
        this.is = is;
    }
    public RtpSocket getRtpSocket() {
        return socket;
    }

    public void setSSRC(int ssrc) {
        socket.setSSRC(ssrc);
    }

    public int getSSRC() {
        return socket.getSSRC();
    }
    public void setDestination(InetAddress dest, int rtpPort, int rtcpPort) {
        socket.setDestination(dest, rtpPort, rtcpPort);
    }
    public void setTimeToLive(int ttl) throws IOException {
        socket.setTimeToLive(ttl);
    }
    protected static class Statistics {

        public final static String TAG = "Statistics";

        private int count = 700, c = 0;
        private float m = 0, q = 0;
        private long elapsed = 0;
        private long start = 0;
        private long duration = 0;
        private long period = 10000000000L;
        private boolean initoffset = false;

        public Statistics() {
        }

        public Statistics(int count, int period) {
            this.count = count;
            this.period = period;
        }
        public void reset() {
            initoffset = false;
            q = 0; m = 0; c = 0;
            elapsed = 0;
            start = 0;
            duration = 0;
        }

        public void push(long value) {
            elapsed += value;
            if (elapsed>period) {
                elapsed = 0;
                long now = System.nanoTime();
                if (!initoffset || (now - start < 0)) {
                    start = now;
                    duration = 0;
                    initoffset = true;
                }
                // Prevents drifting issues by comparing the real duration of the
                // stream with the sum of all temporal lengths of RTP packets.
                value += (now - start) - duration;
                //Log.d(TAG, "sum1: "+duration/1000000+" sum2: "+(now-start)/1000000+" drift: "+((now-start)-duration)/1000000+" v: "+value/1000000);
            }
            if (c<5) {
                // We ignore the first 20 measured values because they may not be accurate
                c++;
                m = value;
            } else {
                m = (m*q+value)/(q+1);
                if (q<count) q++;
            }
        }

        public long average() {
            long l = (long)m;
            duration += l;
            return l;
        }
    }

    public void stop() {
        if (t != null) {
            try {
                is.close();
            } catch (IOException e) {}
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {}
            t = null;
        }
    }
    public void setStreamParameters(byte[] pps, byte[] sps) {
        this.pps = pps;
        this.sps = sps;
      //  Log.d(TAG,"sps is "+sps+"PPs "+ pps);
//        // A STAP-A NAL (NAL type 24) containing the sps and pps of the stream
        if (pps != null && sps != null) {
            // STAP-A NAL header + NALU 1 (SPS) size + NALU 2 (PPS) size = 5 bytes
            stapa = new byte[sps.length + pps.length + 5];

            // STAP-A NAL header is 24
            stapa[0] = 24;

            // Write NALU 1 size into the array (NALU 1 is the SPS).
            stapa[1] = (byte) (sps.length >> 8);
            stapa[2] = (byte) (sps.length & 0xFF);

            // Write NALU 2 size into the array (NALU 2 is the PPS).
            stapa[sps.length + 3] = (byte) (pps.length >> 8);
            stapa[sps.length + 4] = (byte) (pps.length & 0xFF);

            // Write NALU 1 into the array, then write NALU 2 into the array.
            System.arraycopy(sps, 0, stapa, 3, sps.length);
            System.arraycopy(pps, 0, stapa, 5 + sps.length, pps.length);
        }
    }

    /**
     * Starts executing the active part of the class' code. This method is
     * called when a thread is started that has been created with a class which
     * implements {@code Runnable}.
     */
    @Override
    public void run() {
//        sps = Base64.decode(Config.sps,Base64.NO_WRAP);
//        pps = Base64.decode(Config.pps,Base64.NO_WRAP);
        long duration = 0;
        Log.d(TAG, "H264 packetizer started !");
        stats.reset();
        count = 0;


            streamType = 0;
            socket.setCacheSize(1200);
        try {
            while (!Thread.interrupted()) {
                oldtime = System.nanoTime();
                // We read a NAL units from the input stream and we send them
                send();
                // We measure how long it took to receive NAL units from the phone
                duration = System.nanoTime() - oldtime;

                stats.push(duration);
                // Computes the average duration of a NAL unit
                delay = stats.average();
                //Log.d(TAG,"duration: "+duration/1000000+" delay: "+delay/1000000);
            }
        } catch (IOException e) {
        } catch (InterruptedException e) {}

        Log.d(TAG,"H264 packetizer stopped !");
    }

    private void send() throws IOException, InterruptedException {
        int sum = 1, len = 0, type = 0;
    //    Log.d(TAG,"streamType is "+ streamType + "sps" + sps + "pps" + pps);
            // NAL units are preceeded by their length, we parse the length
        if(streamType == 0) {
            fill(header, 0, 5);
            ts += delay;
            naluLength = header[3] & 0xFF | (header[2] & 0xFF) << 8 | (header[1] & 0xFF) << 16 | (header[0] & 0xFF) << 24;
         //   Log.d(TAG,"nalu的长度!"+naluLength);
            if (naluLength > 100000 || naluLength < 0) resync();
        }else
        // Parses the NAL unit type
        type = header[4]&0x1F;
        // The stream already contains NAL unit type 7 or 8, we don't need
        // to add them to the stream ourselves
        if (type == 7 || type == 8) {
            Log.v(TAG,"SPS or PPS present in the stream.");
            count++;
            if (count>4) {
                sps = null;
                pps = null;
            }
        }

        // We send two packets containing NALU type 7 (SPS) and 8 (PPS)
        // Those should allow the H264 stream to be decoded even if no SDP was sent to the decoder.
        if (type == 5 && sps != null && pps != null) {
            buffer = socket.requestBuffer();
            socket.markNextPacket();
            socket.updateTimestamp(ts);
            System.arraycopy(stapa, 0, buffer, rtphl, stapa.length);
            socket.commitBuffer(rtphl + stapa.length);
        }

    //    Log.d(TAG,"- Nal unit length: " + naluLength + " delay: "+delay/1000000+" type: "+type);

        // Small NAL unit => Single NAL unit
        if (naluLength<=MAXPACKETSIZE-rtphl-2) {
            buffer = socket.requestBuffer();
            buffer[rtphl] = header[4];
            len = fill(buffer, rtphl+1,  naluLength-1);
            socket.updateTimestamp(ts);
            socket.markNextPacket();
            socket.commitBuffer(naluLength+rtphl);
            Log.d(TAG,"----- Single NAL unit - len:"+len+" delay: "+delay );
        }
        // Large NAL unit => Split nal unit
        else {

            // Set FU-A header
            header[1] = (byte) (header[4] & 0x1F);  // FU header type
            header[1] += 0x80; // Start bit
            // Set FU-A indicator
            header[0] = (byte) ((header[4] & 0x60) & 0xFF); // FU indicator NRI
            header[0] += 28;

            while (sum < naluLength) {
                buffer = socket.requestBuffer();
                buffer[rtphl] = header[0];
                buffer[rtphl+1] = header[1];
                socket.updateTimestamp(ts);
                if ((len = fill(buffer, rtphl+2,  naluLength-sum > MAXPACKETSIZE-rtphl-2 ? MAXPACKETSIZE-rtphl-2 : naluLength-sum  ))<0) return; sum += len;
                // Last packet before next NAL
                if (sum >= naluLength) {
                    // End bit on
                    buffer[rtphl+1] += 0x40;
                    socket.markNextPacket();
                }
                socket.commitBuffer(len + rtphl+2);
                // Switch start bit
                header[1] = (byte) (header[1] & 0x7F);
                //Log.d(TAG,"----- FU-A unit, sum:"+sum);
            }
        }
    }
    private int fill(byte[] buffer, int offset,int length) throws IOException {
        int sum = 0, len;
        while (sum<length) {
            len = is.read(buffer, offset+sum, length-sum);
            if (len<0) {
                throw new IOException("End of stream");
            }
            else sum+=len;
        }
        return sum;
    }
    private void resync() throws IOException {
        int type;
//        Log.e(TAG,"Packetizer out of sync ! Let's try to fix that...(NAL length: "+naluLength+")");
//        while (true) {
//
//            header[0] = header[1];
//            header[1] = header[2];
//            header[2] = header[3];
//            header[3] = header[4];
//            header[4] = (byte) is.read();
//
//            type = header[4]&0x1F;
//
//            if (type == 5 || type == 1) {
//                naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
//                if (naluLength>0 && naluLength<100000) {
//                    oldtime = System.nanoTime();
//                    Log.e(TAG,"A NAL unit may have been found in the bit stream !");
//                    break;
//                }
//                if (naluLength==0) {
//                    Log.e(TAG,"NAL unit with NULL size found...");
//                } else if (header[3]==0xFF && header[2]==0xFF && header[1]==0xFF && header[0]==0xFF) {
//                    Log.e(TAG,"NAL unit with 0xFFFFFFFF size found...");
//                }
//            }
//        }

    }
}
