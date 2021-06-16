package com.mobimentum.liveedgedetection.view;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.shapes.PathShape;
import android.hardware.Camera;
import android.media.AudioManager;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import com.mobimentum.liveedgedetection.constants.ScanConstants;
import com.mobimentum.liveedgedetection.enums.ScanHint;
import com.mobimentum.liveedgedetection.interfaces.IScanner;
import com.mobimentum.liveedgedetection.util.ImageDetectionProperties;
import com.mobimentum.liveedgedetection.util.ScanUtils;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.List;

import static com.mobimentum.liveedgedetection.constants.ScanConstants.INTERVAL_FRAME;
import static org.opencv.core.CvType.CV_8UC1;

/**
 * This class previews the live images from the camera
 */

public class ScanSurfaceView extends FrameLayout implements SurfaceHolder.Callback {

    private static final String TAG = ScanSurfaceView.class.getSimpleName();

    public SurfaceView surfaceView;
    private Camera camera;
    private CountDownTimer autoCaptureTimer;
    private Camera.Size previewSize;
    private AcquisitionMode acquisitionMode = AcquisitionMode.DETECTION_MODE;
    private Mat mat;
    private Quadrilateral largestQuad;

    private int vWidth = 0;
    private int vHeight = 0;
    private int secondsLeft;

    private final IScanner iScanner;
    private final Context context;
    private final ScanCanvasView scanCanvasView;
    private final Handler processingThread;

    private boolean isCapturing = false;
    private boolean isAutoCaptureScheduled;

    public enum AcquisitionMode {
        FROM_FILESYSTEM,
        MANUAL_MODE,
        DETECTION_MODE
    }

    public ScanSurfaceView(Context context, IScanner iScanner) {
        super(context);
        surfaceView = new SurfaceView(context);
        addView(surfaceView);
        this.context = context;
        this.scanCanvasView = new ScanCanvasView(context);
        addView(scanCanvasView);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        this.iScanner = iScanner;
        HandlerThread handlerThread = new HandlerThread("processing");
        handlerThread.start();
        this.processingThread = new Handler(handlerThread.getLooper());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (acquisitionMode != AcquisitionMode.FROM_FILESYSTEM ) {
            try {
                requestLayout();
                openCamera();
                this.camera.setPreviewDisplay(holder);
                setPreviewCallback();
            }
            catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }

    public void setAcquisitionMode(AcquisitionMode acquisitionMode) {
        this.acquisitionMode = acquisitionMode;
        if (acquisitionMode == AcquisitionMode.MANUAL_MODE) {
            scanCanvasView.clear();
            invalidateCanvas();
            iScanner.displayHint(ScanHint.NO_MESSAGE);
        }
    }

    public AcquisitionMode getAcquisitionMode() {
        return acquisitionMode;
    }

    public void clearAndInvalidateCanvas() {
        scanCanvasView.clear();
        invalidateCanvas();
    }

    public void invalidateCanvas() {
        scanCanvasView.invalidate();
    }

    private void openCamera() {
        if (camera == null) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int defaultCameraId = 0;
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    defaultCameraId = i;
                }
            }
            camera = Camera.open(2);
            Camera.Parameters cameraParams = camera.getParameters();

            List<String> flashModes = cameraParams.getSupportedFlashModes();
            if (null != flashModes && flashModes.contains(Camera.Parameters.FLASH_MODE_AUTO)) {
                cameraParams.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
            }
            camera.setParameters(cameraParams);
        }
    }

    public void setFlash(boolean isEnable) {
        Camera.Parameters cameraParams = camera.getParameters();
        cameraParams.setFlashMode(isEnable ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
        camera.setParameters(cameraParams);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (acquisitionMode == AcquisitionMode.FROM_FILESYSTEM) return;
        if (vWidth == vHeight) {
            return;
        }
        if (previewSize == null)
            previewSize = ScanUtils.getOptimalPreviewSize(camera, vWidth, vHeight);

        Camera.Parameters parameters = camera.getParameters();
        camera.setDisplayOrientation(ScanUtils.configureCameraAngle((Activity) context));
        // TODO trovare soluzione migliore. SetPreviewSize non è supportato da diversi device
//        parameters.setPreviewSize(previewSize.width, previewSize.height);
        if (parameters.getSupportedFocusModes() != null
                && parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        } else if (parameters.getSupportedFocusModes() != null
                && parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        }

        Camera.Size size = ScanUtils.determinePictureSize(camera, parameters.getPreviewSize());
        parameters.setPictureSize(size.width, size.height);
        parameters.setPictureFormat(ImageFormat.JPEG);

        camera.setParameters(parameters);
        requestLayout();
        setPreviewCallback();
    }

    public void surfaceDestroyed() {
        Log.i(TAG, "surfaceDestroyed() called");
        stopPreviewAndFreeCamera();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopPreviewAndFreeCamera();
    }

    private void stopPreviewAndFreeCamera() {
        if (camera != null) {
            camera.stopPreview();
            camera.setPreviewCallback(null);
            camera.release();
            camera = null;
        }
    }

    public void setPreviewCallback() {
        this.camera.startPreview();
        this.camera.setPreviewCallback(previewCallback);
    }

    long lastCall = 0;
    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, final Camera camera) {
            if ((null != camera) && (getAcquisitionMode() != AcquisitionMode.MANUAL_MODE) &&
                    (getAcquisitionMode() != AcquisitionMode.FROM_FILESYSTEM) && (System.currentTimeMillis() - lastCall) > INTERVAL_FRAME) {
                try {
                    final Camera.Size pictureSize = camera.getParameters().getPreviewSize();
                    processingThread.post(new Runnable() {
                        @Override
                        public void run() {
                            Mat yuv = new Mat(new Size(pictureSize.width, pictureSize.height * 1.5), CV_8UC1);
                            yuv.put(0, 0, data);

                            mat = new Mat(new Size(pictureSize.width, pictureSize.height), CvType.CV_8UC4);
                            if (!mat.empty()) {
                                Imgproc.cvtColor(yuv, mat, Imgproc.COLOR_YUV2BGR_NV21, 4);
                                yuv.release();

                                largestQuad = ScanUtils.detectLargestQuadrilateral(mat);
                            }
                        }
                    });
                    clearAndInvalidateCanvas();

                    Size originalPreviewSize = mat.size();
                    int originalPreviewArea = mat.rows() * mat.cols();
                    mat.release();

                    if (largestQuad != null) {
                        drawLargestRect(largestQuad.contour, largestQuad.points, originalPreviewSize, originalPreviewArea);
                    }
                    else {
                        showFindingReceiptHint();
                    }
                }
                catch (Exception e) {
                    showFindingReceiptHint();
                }
                lastCall = System.currentTimeMillis();
            }
        }
    };

    private void drawLargestRect(MatOfPoint2f approx, Point[] points, Size stdSize, int previewArea) {
        Path path = new Path();
        // ATTENTION: axis are swapped
        float previewWidth = (float) stdSize.height;
        float previewHeight = (float) stdSize.width;

        //Points are drawn in anticlockwise direction
        path.moveTo(previewWidth - (float) points[0].y, (float) points[0].x);
        path.lineTo(previewWidth - (float) points[1].y, (float) points[1].x);
        path.lineTo(previewWidth - (float) points[2].y, (float) points[2].x);
        path.lineTo(previewWidth - (float) points[3].y, (float) points[3].x);
        path.close();

        double area = Math.abs(Imgproc.contourArea(approx));

        PathShape newBox = new PathShape(path, previewWidth, previewHeight);
        Paint paint = new Paint();
        Paint border = new Paint();

        //Height calculated on Y axis
        double resultHeight = points[1].x - points[0].x;
        double bottomHeight = points[2].x - points[3].x;
        if (bottomHeight > resultHeight)
            resultHeight = bottomHeight;

        //Width calculated on X axis
        double resultWidth = points[3].y - points[0].y;
        double bottomWidth = points[2].y - points[1].y;
        if (bottomWidth > resultWidth) resultWidth = bottomWidth;

        ImageDetectionProperties imgDetectionPropsObj = new ImageDetectionProperties(previewWidth,
                previewHeight, resultWidth, resultHeight, previewArea, area, points[0], points[1], points[2], points[3]);

        final ScanHint scanHint;

        if (imgDetectionPropsObj.isDetectedAreaBeyondLimits()) {
            scanHint = ScanHint.FIND_RECT;
            cancelAutoCapture();
        }
        else if (imgDetectionPropsObj.isDetectedAreaBelowLimits()) {
            cancelAutoCapture();
            if (imgDetectionPropsObj.isEdgeTouching()) {
                scanHint = ScanHint.MOVE_AWAY;
            }
            else {
                if (imgDetectionPropsObj.rotateSmartphone()) {
                    scanHint = ScanHint.ROTATE;
                }
                else {
                    scanHint = ScanHint.MOVE_CLOSER;
                }
            }
        }
        else if (imgDetectionPropsObj.isDetectedHeightAboveLimit()) {
            cancelAutoCapture();
            if (imgDetectionPropsObj.rotateSmartphone()) {
                scanHint = ScanHint.ROTATE;
            }
            else {
                scanHint = ScanHint.MOVE_AWAY;
            }
        }
        else if (imgDetectionPropsObj.isDetectedWidthAboveLimit() || imgDetectionPropsObj.isDetectedAreaAboveLimit()) {
            cancelAutoCapture();
            if (imgDetectionPropsObj.rotateSmartphone()) {
                scanHint = ScanHint.ROTATE;
            }
            else {
                scanHint = ScanHint.MOVE_AWAY;
            }
        }
        else {
            if (imgDetectionPropsObj.isEdgeTouching()) {
                cancelAutoCapture();
                scanHint = ScanHint.MOVE_AWAY;
            }
            else if (imgDetectionPropsObj.isAngleNotCorrect(approx)) {
                cancelAutoCapture();
                scanHint = ScanHint.ADJUST_ANGLE;
            }
            else if (imgDetectionPropsObj.rotateSmartphone()) {
                cancelAutoCapture();
                scanHint = ScanHint.ROTATE;
            }
            else {
                Log.i(TAG, "GREEN" + "(resultWidth/resultHeight) > 4: " + (resultWidth / resultHeight) +
                        " points[0].x == 0 && points[3].x == 0: " + points[0].x + ": " + points[3].x +
                        " points[2].x == previewHeight && points[1].x == previewHeight: " + points[2].x + ": " + points[1].x +
                        "previewHeight: " + previewHeight);
                scanHint = ScanHint.CAPTURING_IMAGE;
                clearAndInvalidateCanvas();

                if (!isAutoCaptureScheduled) {
                    scheduleAutoCapture(scanHint);
                }
            }
        }
        Log.i(TAG, "Preview Area 95%: " + 0.95 * previewArea +
                " Preview Area 20%: " + 0.20 * previewArea +
                " Area: " + String.valueOf(area) +
                " Label: " + scanHint.toString());

        border.setStrokeWidth(12);
        iScanner.displayHint(scanHint);
        setPaintAndBorder(scanHint, paint, border);
        scanCanvasView.clear();
        scanCanvasView.addShape(newBox, paint, border);
        invalidateCanvas();
    }

    private void scheduleAutoCapture(final ScanHint scanHint) {
        isAutoCaptureScheduled = true;
        secondsLeft = 0;
        autoCaptureTimer = new CountDownTimer(2000, 100) {
            public void onTick(long millisUntilFinished) {
                if (Math.round((float) millisUntilFinished / 1000.0f) != secondsLeft) {
                    secondsLeft = Math.round((float) millisUntilFinished / 1000.0f);
                }
                if (secondsLeft == 1) {
                    autoCapture(scanHint);
                }
            }

            public void onFinish() {
                isAutoCaptureScheduled = false;
            }
        };
        autoCaptureTimer.start();
    }

    public void autoCapture(ScanHint scanHint) {
        if (isCapturing) return;
        if (ScanHint.CAPTURING_IMAGE.equals(scanHint)) {
            try {
                isCapturing = true;
                iScanner.displayHint(ScanHint.CAPTURING_IMAGE);

                camera.takePicture(mShutterCallBack, null, pictureCallback);
                camera.setPreviewCallback(null);
//                iScanner.displayHint(ScanHint.NO_MESSAGE);
//                clearAndInvalidateCanvas();
            }
            catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }

    private void cancelAutoCapture() {
        if (isAutoCaptureScheduled) {
            isAutoCaptureScheduled = false;
            if (null != autoCaptureTimer) {
                autoCaptureTimer.cancel();
            }
        }
    }

    private void showFindingReceiptHint() {
        iScanner.displayHint(ScanHint.FIND_RECT);
        clearAndInvalidateCanvas();
    }

    private void setPaintAndBorder(ScanHint scanHint, Paint paint, Paint border) {
        int paintColor = 0;
        int borderColor = 0;

        switch (scanHint) {
            case MOVE_CLOSER:
            case MOVE_AWAY:
            case ROTATE:
            case ADJUST_ANGLE:
                paintColor = Color.argb(30, 255, 38, 0);
                borderColor = Color.rgb(255, 38, 0);
                break;
            case FIND_RECT:
                paintColor = Color.argb(0, 0, 0, 0);
                borderColor = Color.argb(0, 0, 0, 0);
                break;
            case CAPTURING_IMAGE:
                paintColor = Color.argb(30, 38, 216, 76);
                borderColor = Color.rgb(38, 216, 76);
                break;
        }

        paint.setColor(paintColor);
        border.setColor(borderColor);
    }

    private final Camera.PictureCallback pictureCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            processingThread.removeCallbacksAndMessages(null);
            largestQuad = null;
            camera.stopPreview();
            iScanner.displayHint(ScanHint.NO_MESSAGE);
            clearAndInvalidateCanvas();

            Bitmap bitmap = ScanUtils.decodeBitmapFromByteArray(data,
                    ScanConstants.HIGHER_SAMPLING_THRESHOLD, ScanConstants.HIGHER_SAMPLING_THRESHOLD);

            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            iScanner.onPictureClicked(bitmap);
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    isCapturing = false;
                }
            }, 3000);
        }
    };

    private final Camera.ShutterCallback mShutterCallBack = new Camera.ShutterCallback() {
        @Override
        public void onShutter() {
            if (context != null) {
                AudioManager mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (null != mAudioManager)
                    mAudioManager.playSoundEffect(AudioManager.FLAG_PLAY_SOUND);
            }
        }
    };

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        vWidth = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        vHeight = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(vWidth, vHeight);
        previewSize = ScanUtils.getOptimalPreviewSize(camera, vWidth, vHeight);
    }

    @SuppressWarnings("SuspiciousNameCombination")
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() > 0) {

            int width = r - l;
            int height = b - t;

            int previewWidth = width;
            int previewHeight = height;

            if (previewSize != null) {
                previewWidth = previewSize.width;
                previewHeight = previewSize.height;

                int displayOrientation = ScanUtils.configureCameraAngle((Activity) context);
                if (displayOrientation == 90 || displayOrientation == 270) {
                    previewWidth = previewSize.height;
                    previewHeight = previewSize.width;
                }
            }

            int nW;
            int nH;
            int top;
            int left;

            float scale = 1.0f;

            // Center the child SurfaceView within the parent.
            if (width * previewHeight < height * previewWidth) {
                Log.d(TAG, "center horizontally");
                int scaledChildWidth = (int) ((previewWidth * height / previewHeight) * scale);
                nW = (width + scaledChildWidth) / 2;
                nH = (int) (height * scale);
                top = 0;
                left = (width - scaledChildWidth) / 2;
            }
            else {
                Log.d(TAG, "center vertically");
                int scaledChildHeight = (int) ((previewHeight * width / previewWidth) * scale);
                nW = (int) (width * scale);
                nH = (height + scaledChildHeight) / 2;
                top = (height - scaledChildHeight) / 2;
                left = 0;
            }
            surfaceView.layout(left, top, nW, nH);
            scanCanvasView.layout(left, top, nW, nH);

            Log.d("layout", "left:" + left);
            Log.d("layout", "top:" + top);
            Log.d("layout", "right:" + nW);
            Log.d("layout", "bottom:" + nH);
        }
    }
}