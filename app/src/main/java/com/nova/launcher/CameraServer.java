package com.nova.launcher;

import fi.iki.elonen.NanoHTTPD;

import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import android.graphics.SurfaceTexture;

public class CameraServer extends NanoHTTPD {

    private static final String TAG = "CameraServer";
    private Camera camera;
    private volatile byte[] latestFrame;
    private boolean cameraRunning = false;
    private SurfaceTexture dummySurfaceTexture;

    public CameraServer(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        if (uri.equals("/on")) {
            startCamera();
            return newFixedLengthResponse("Camera Started");
        }

        if (uri.equals("/off")) {
            stopCamera();
            return newFixedLengthResponse("Camera Stopped");
        }

        if (uri.equals("/restart")) {
            stopCamera();
            try { Thread.sleep(500); } catch (Exception ignored) {}
            startCamera();
            return newFixedLengthResponse("Camera Restarted");
        }

        if (uri.equals("/stream")) {
            if (!cameraRunning) {
                startCamera();
            }
            return newChunkedResponse(Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame",
                    new MJPEGInputStream());
        }

        return newFixedLengthResponse("Server Running. Visit /on to start camera and /stream to see the feed.");
    }

    private synchronized void startCamera() {
        if (cameraRunning) return;

        try {
            Log.d(TAG, "Opening front camera...");
            int cameraId = findFrontFacingCamera();
            if (cameraId == -1) {
                Log.w(TAG, "Front camera not found, falling back to default");
                camera = Camera.open();
            } else {
                camera = Camera.open(cameraId);
            }

            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = parameters.getPreviewSize();
            
            Log.d(TAG, "Camera preview size: " + size.width + "x" + size.height);

            // Important: On many devices, setPreviewCallback only works if a surface is set.
            dummySurfaceTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(dummySurfaceTexture);

            camera.setPreviewCallback((data, cam) -> {
                try {
                    YuvImage yuvImage = new YuvImage(data, parameters.getPreviewFormat(), size.width, size.height, null);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    yuvImage.compressToJpeg(new Rect(0, 0, size.width, size.height), 50, out);
                    latestFrame = out.toByteArray();
                } catch (Exception e) {
                    Log.e(TAG, "Error processing camera frame", e);
                }
            });

            camera.startPreview();
            cameraRunning = true;
            Log.d(TAG, "Camera started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error starting camera", e);
            if (camera != null) {
                camera.release();
                camera = null;
            }
            cameraRunning = false;
        }
    }

    private int findFrontFacingCamera() {
        int cameraId = -1;
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                cameraId = i;
                break;
            }
        }
        return cameraId;
    }

    public synchronized void stopCamera() {
        cameraRunning = false;
        latestFrame = null;
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
            Log.d(TAG, "Camera stopped and released");
        }
    }

    private class MJPEGInputStream extends InputStream {
        private byte[] currentChunk;
        private int chunkPos = 0;

        @Override
        public int read() throws IOException {
            if (currentChunk == null || chunkPos >= currentChunk.length) {
                if (!getNextChunk()) return -1;
            }
            return currentChunk[chunkPos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (currentChunk == null || chunkPos >= currentChunk.length) {
                if (!getNextChunk()) return -1;
            }
            int bytesToCopy = Math.min(len, currentChunk.length - chunkPos);
            System.arraycopy(currentChunk, chunkPos, b, off, bytesToCopy);
            chunkPos += bytesToCopy;
            return bytesToCopy;
        }

        private boolean getNextChunk() {
            // Wait for a frame if not available yet
            while (latestFrame == null && cameraRunning) {
                try {
                    Thread.sleep(50); // Small delay to avoid busy waiting
                } catch (InterruptedException e) {
                    return false;
                }
            }

            if (!cameraRunning) return false;
            if (latestFrame == null) return false;

            byte[] frame = latestFrame;
            String header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            try {
                stream.write(header.getBytes());
                stream.write(frame);
                stream.write("\r\n".getBytes());
                currentChunk = stream.toByteArray();
                chunkPos = 0;
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Error preparing MJPEG chunk", e);
                return false;
            }
        }
    }
}
