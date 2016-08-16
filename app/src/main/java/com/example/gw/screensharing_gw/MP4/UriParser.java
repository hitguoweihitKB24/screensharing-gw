/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package com.example.gw.screensharing_gw.MP4;


import android.content.ContentValues;
import android.util.Log;

import com.example.gw.screensharing_gw.server.Session;
import com.example.gw.screensharing_gw.server.SessionBuilder;
import com.example.gw.screensharing_gw.stream.MediaStream;


import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * This class parses URIs received by the RTSP server and configures a Session accordingly.
 */
public class UriParser {

	public final static String TAG = "UriParser";
	
	/**
	 * Configures a Session according to the given URI.
	 * Here are some examples of URIs that can be used to configure a Session:
	 * <ul><li>rtsp://xxx.xxx.xxx.xxx:8086?h264&flash=on</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?h263&camera=front&flash=on</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?h264=200-20-320-240</li>
	 * <li>rtsp://xxx.xxx.xxx.xxx:8086?aac</li></ul>
	 * @param uri The URI
	 * @throws IllegalStateException
	 * @throws IOException
	 * @return A Session configured according to the URI
	 */
	public static Session parse(String uri) throws IllegalStateException, IOException {
		SessionBuilder builder = SessionBuilder.getInstance().clone();
		byte audioApi = 0, videoApi = 0;
		Log.d(TAG, "in the parse : the uri is "+uri);
//        String[] queryParams = URI.create(uri).getQuery().split("&");
        ContentValues params = new ContentValues();
//        for(String param:queryParams)
//        {
//            String[] keyValue = param.split("=");
//			String value = "";
//			try {
//				value = keyValue[1];
//			}catch(ArrayIndexOutOfBoundsException e){}
//
//            params.put(
//                    URLEncoder.encode(keyValue[0], "UTF-8"), // Name
//                    URLEncoder.encode(value, "UTF-8")  // Value
//            );
//
//        }

		if (params.size()>0) {
			builder.setVideoEncoder(SessionBuilder.VIDEO_NONE);
            Set<String> paramKeys=params.keySet();
			// Those parameters must be parsed first or else they won't necessarily be taken into account
            for(String paramName: paramKeys) {
                String paramValue = params.getAsString(paramName);

				// FLASH ON/OFF
				if (paramName.equalsIgnoreCase("flash")) {
					if (paramValue.equalsIgnoreCase("on")) 
						builder.setFlashEnabled(true);
					else 
						builder.setFlashEnabled(false);
				}



				// MULTICAST -> the stream will be sent to a multicast group
				// The default mutlicast address is 228.5.6.7, but the client can specify another
				else if (paramName.equalsIgnoreCase("multicast")) {
					if (paramValue!=null) {
						try {
							InetAddress addr = InetAddress.getByName(paramValue);
							if (!addr.isMulticastAddress()) {
								throw new IllegalStateException("Invalid multicast address !");
							}
							builder.setDestination(paramValue);
						} catch (UnknownHostException e) {
							throw new IllegalStateException("Invalid multicast address !");
						}
					}
					else {
						// Default multicast address
						builder.setDestination("228.5.6.7");
					}
				}

				// UNICAST -> the client can use this to specify where he wants the stream to be sent
				else if (paramName.equalsIgnoreCase("unicast")) {
					if (paramValue!=null) {
						builder.setDestination(paramValue);
					}					
				}
				
				// VIDEOAPI -> can be used to specify what api will be used to encode video (the MediaRecorder API or the MediaCodec API)
				else if (paramName.equalsIgnoreCase("videoapi")) {
					if (paramValue!=null) {
						if (paramValue.equalsIgnoreCase("mr")) {
							videoApi = MediaStream.MODE_MEDIARECORDER_API;
						} else if (paramValue.equalsIgnoreCase("mc")) {
							videoApi = MediaStream.MODE_MEDIACODEC_API;
						}
					}					
				}
				
				// AUDIOAPI -> can be used to specify what api will be used to encode audio (the MediaRecorder API or the MediaCodec API)
				else if (paramName.equalsIgnoreCase("audioapi")) {
					if (paramValue!=null) {
						if (paramValue.equalsIgnoreCase("mr")) {
							audioApi = MediaStream.MODE_MEDIARECORDER_API;
						} else if (paramValue.equalsIgnoreCase("mc")) {
							audioApi = MediaStream.MODE_MEDIACODEC_API;
						}
					}					
				}		

				// TTL -> the client can modify the time to live of packets
				// By default ttl=64
				else if (paramName.equalsIgnoreCase("ttl")) {
					if (paramValue!=null) {
						try {
							int ttl = Integer.parseInt(paramValue);
							if (ttl<0) throw new IllegalStateException();
							builder.setTimeToLive(ttl);
						} catch (Exception e) {
							throw new IllegalStateException("The TTL must be a positive integer !");
						}
					}
				}

				// H.264
				else if (paramName.equalsIgnoreCase("h264")) {
					VideoQuality quality = VideoQuality.parseQuality(paramValue);
					builder.setVideoQuality(quality).setVideoEncoder(SessionBuilder.VIDEO_H264);
				}

				// H.263
				else if (paramName.equalsIgnoreCase("h263")) {
					VideoQuality quality = VideoQuality.parseQuality(paramValue);
					builder.setVideoQuality(quality).setVideoEncoder(SessionBuilder.VIDEO_H263);
				}

			}

		}

		if (builder.getVideoEncoder()==SessionBuilder.VIDEO_NONE  ) {
			SessionBuilder b = SessionBuilder.getInstance();
			builder.setVideoEncoder(b.getVideoEncoder());
		}

		Session session = builder.build();
		
		if (videoApi>0 && session.getVideoTrack() != null) {
			session.getVideoTrack().setStreamingMethod(videoApi);
		}
		
		return session;

	}

}
