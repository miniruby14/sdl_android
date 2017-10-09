package com.smartdevicelink.encoder;

import android.annotation.TargetApi;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.smartdevicelink.proxy.interfaces.IVideoStreamListener;
import com.smartdevicelink.proxy.rpc.ImageResolution;
import com.smartdevicelink.proxy.rpc.OnTouchEvent;
import com.smartdevicelink.proxy.rpc.ScreenParams;
import com.smartdevicelink.proxy.rpc.TouchCoord;
import com.smartdevicelink.proxy.rpc.TouchEvent;
import com.smartdevicelink.proxy.rpc.VideoStreamingFormat;
import com.smartdevicelink.proxy.rpc.enums.TouchType;
import com.smartdevicelink.streaming.video.SdlRemoteDisplay;
import com.smartdevicelink.streaming.video.VideoStreamingParameters;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static com.smartdevicelink.proxy.constants.Names.screenParams;

@TargetApi(21)
public class VirtualDisplayEncoder {
    private static final String TAG = "VirtualDisplayEncoder";
    private final String videoMimeType = "video/avc";
    private VideoStreamingParameters streamingParams = new VideoStreamingParameters();
    private DisplayManager mDisplayManager;
    private volatile MediaCodec mVideoEncoder = null;
    private volatile MediaCodec.BufferInfo mVideoBufferInfo = null;
    private volatile Surface inputSurface = null;
    private volatile VirtualDisplay virtualDisplay = null;
    private VideoStreamWriterThread streamWriterThread = null;
    private Context mContext;
    private IVideoStreamListener mOutputListener;
    private Boolean initPassed = false;
    private final Object CLOSE_VID_SESSION_LOCK = new Object();
    private final Object START_DISP_LOCK = new Object();
    private final Object STREAMING_LOCK = new Object();



    /**
     * Initialization method for VirtualDisplayEncoder object. MUST be called before start() or shutdown()
     * Will overwrite previously set videoWeight and videoHeight
     * @param context
     * @param outputListener
     * @param streamingParams
     * @throws Exception
     */
    public void init(Context context, IVideoStreamListener outputListener, VideoStreamingParameters streamingParams) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "API level of 21 required for VirtualDisplayEncoder");
            throw new Exception("API level of 21 required");
        }

        if (context == null || outputListener == null || screenParams == null || streamingParams.getResolution() == null || streamingParams.getFormat() == null) {
            Log.e(TAG, "init parameters cannot be null for VirtualDisplayEncoder");
            throw new Exception("init parameters cannot be null");
        }

        this.mDisplayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);

        this.mContext = context;

        this.streamingParams.update(streamingParams);

        mOutputListener = outputListener;

        setupVideoStreamWriter();

        initPassed = true;
    }

    public VideoStreamingParameters getStreamingParams(){
        return this.streamingParams;
    }

    public void setStreamingParams(int displayDensity, ImageResolution resolution, int frameRate, int bitrate, int interval, VideoStreamingFormat format){
        this.streamingParams = new VideoStreamingParameters(displayDensity,frameRate,bitrate,interval,resolution,format);
    }

    public void setStreamingParams(VideoStreamingParameters streamingParams){
        this.streamingParams = streamingParams;
    }

    /**
     * NOTE: Must call init() without error before calling this method.
     * Prepares the encoder and virtual display.
     */
    public void start() throws Exception {
        if(!initPassed){
            Log.e(TAG, "VirtualDisplayEncoder was not properly initialized with the init() method.");
            return;
        }
        if(streamingParams == null || streamingParams.getResolution() == null || streamingParams.getFormat() == null){
            return;
        }

        synchronized (STREAMING_LOCK) {

            try {
                inputSurface = prepareVideoEncoder();

                // Create a virtual display that will output to our encoder.
                virtualDisplay = mDisplayManager.createVirtualDisplay(TAG,
                        streamingParams.getResolution().getResolutionWidth(), streamingParams.getResolution().getResolutionHeight(),
                        streamingParams.getDisplayDensity(), inputSurface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);

                startEncoder();

            } catch (Exception ex) {
                Log.e(TAG, "Unable to create Virtual Display.");
                throw new RuntimeException(ex);
            }
        }
    }

    public void shutDown()
    {
        if(!initPassed){
            Log.e(TAG, "VirtualDisplayEncoder was not properly initialized with the init() method.");
            return;
        }
        try {

            closeVideoSession();
            releaseVideoStreamWriter();

            if (mVideoEncoder != null) {
                mVideoEncoder.stop();
                mVideoEncoder.release();
                mVideoEncoder = null;
            }

            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }

            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
        }
        catch (Exception ex){
            Log.e(TAG, "shutDown() failed");
        }
    }

    private void closeVideoSession() {

        synchronized (CLOSE_VID_SESSION_LOCK) {
            /*if (sdlOutStream != null) {

                try {
                    sdlOutStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                sdlOutStream = null;

                if (streamWriterThread != null) {
                    streamWriterThread.clearOutputStream();
                    streamWriterThread.clearByteBuffer();
                }
            }*/
        }
    }

    private Surface prepareVideoEncoder() {

        if(streamingParams == null || streamingParams.getResolution() == null || streamingParams.getFormat() == null){
            return null;
        }

        if (mVideoBufferInfo == null) {
            mVideoBufferInfo = new MediaCodec.BufferInfo();
        }

        MediaFormat format = MediaFormat.createVideoFormat(videoMimeType, streamingParams.getResolution().getResolutionWidth(), streamingParams.getResolution().getResolutionHeight());

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, streamingParams.getBitrate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, streamingParams.getFrameRate());
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, streamingParams.getInterval()); // seconds between I-frames


        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            mVideoEncoder = MediaCodec.createEncoderByType(videoMimeType);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface surf = mVideoEncoder.createInputSurface();

            mVideoEncoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                    // nothing to do here
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                    try {
                        ByteBuffer encodedData = codec.getOutputBuffer(index);

                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);

                        if (info.size != 0) {
                            byte[] dataToWrite = new byte[info.size];
                            encodedData.get(dataToWrite,
                                    info.offset, info.size);
                            if(mOutputListener!=null){
                                mOutputListener.sendFrame(dataToWrite,0,dataToWrite.length, info.presentationTimeUs);
                            }
                        }

                        codec.releaseOutputBuffer(index, false);
                    } catch (IllegalStateException ex) {
                        ex.printStackTrace();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                    e.printStackTrace();
                }

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                    // nothing to do here
                }
            });

            return surf;

        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }



    private void startEncoder()
    {
        if (mVideoEncoder != null) {
            mVideoEncoder.start();
        }
    }


    public Display getVirtualDisplay(){
        synchronized (START_DISP_LOCK) {
            return virtualDisplay.getDisplay();
        }
    }


    private void setupVideoStreamWriter() {
        if (streamWriterThread == null) {
            // Setup VideoStreamWriterThread thread
            streamWriterThread = new VideoStreamWriterThread();
            streamWriterThread.setName("VideoStreamWriter");
            streamWriterThread.setPriority(Thread.MAX_PRIORITY);
            streamWriterThread.setDaemon(true);
            streamWriterThread.start();
        }
    }

    private void releaseVideoStreamWriter() {
        if (streamWriterThread != null) {

            streamWriterThread.halt();

            try {
                streamWriterThread.interrupt();
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            streamWriterThread.clearOutputStream();
            streamWriterThread.clearByteBuffer();
        }
        streamWriterThread = null;
    }

    private class VideoStreamWriterThread extends Thread {
        private Boolean isHalted = false;
        private Boolean isWaiting = false;
        private byte[] buf = null;
        private Integer size = 0;
        private OutputStream os = null;
        protected final Object BUFFER_LOCK = new Object();

        public OutputStream getOutputStream() {
            return os;
        }

        public byte[] getByteBuffer() {
            return buf;
        }

        public void setOutputStream(OutputStream os) {
            synchronized (STREAMING_LOCK) {
                clearOutputStream();
                this.os = os;
            }
        }

        public void setByteBuffer(byte[] buf, Integer size) {
            synchronized (STREAMING_LOCK) {
                clearByteBuffer();
                this.buf = buf;
                this.size = size;
            }
        }

        private void clearOutputStream() {
            synchronized (STREAMING_LOCK) {
                try {
                    if (os != null) {
                        os.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                os = null;
            }
        }

        private void clearByteBuffer() {
            synchronized (STREAMING_LOCK) {
                try {
                    if (buf != null) {
                        buf = null;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void writeToStream() {
            synchronized (STREAMING_LOCK) {
                if (buf == null || os == null)
                    return;

                try {
                    os.write(buf, 0, size);
                    clearByteBuffer();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        public void run() {
            while (!isHalted) {
                writeToStream();
                if(isWaiting){
                    synchronized(BUFFER_LOCK){
                        BUFFER_LOCK.notify();
                    }
                }
            }
        }

        /**
         * Method that marks thread as halted.
         */
        public void halt() {
            isHalted = true;
        }
    }

}