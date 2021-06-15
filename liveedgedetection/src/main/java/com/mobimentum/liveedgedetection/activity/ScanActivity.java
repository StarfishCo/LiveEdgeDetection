package com.mobimentum.liveedgedetection.activity;

import android.Manifest;
import android.app.Activity;
import android.app.FragmentManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mobimentum.liveedgedetection.BuildConfig;
import com.mobimentum.liveedgedetection.R;
import com.mobimentum.liveedgedetection.constants.ScanConstants;
import com.mobimentum.liveedgedetection.enums.ScanHint;
import com.mobimentum.liveedgedetection.interfaces.IScanner;
import com.mobimentum.liveedgedetection.util.ScanUtils;
import com.mobimentum.liveedgedetection.view.LimitedArea;
import com.mobimentum.liveedgedetection.view.PolygonView;
import com.mobimentum.liveedgedetection.view.ProgressDialogFragment;
import com.mobimentum.liveedgedetection.view.Quadrilateral;
import com.mobimentum.liveedgedetection.view.ScanSurfaceView;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static android.view.View.GONE;
import static com.mobimentum.liveedgedetection.constants.ScanConstants.MIME_TYPES;
import static com.mobimentum.liveedgedetection.constants.ScanConstants.PDF_EXT;
import static com.mobimentum.liveedgedetection.constants.ScanConstants.SHOW_MANUAL_MODE_INTERVAL;
import static com.mobimentum.liveedgedetection.constants.ScanConstants.START_LIVE_DETECTION;
import static com.mobimentum.liveedgedetection.constants.ScanConstants.WHICH_API;
import static com.mobimentum.liveedgedetection.enums.ScanHint.CAPTURING_IMAGE;
import static com.mobimentum.liveedgedetection.enums.ScanHint.NO_MESSAGE;

/**
 * This class initiates camera and detects edges on live view
 */
public class ScanActivity extends AppCompatActivity implements IScanner, View.OnClickListener, ScanUtils.OnSaveListener {

    private static final String TAG = ScanActivity.class.getSimpleName();

    private static final int MY_PERMISSIONS_REQUEST = 101;
    private static final int SELECTED_FILE_CODE = 102;
    private static final String mOpenCvLibrary = "opencv_java3";
    private static ProgressDialogFragment progressDialogFragment;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ViewGroup containerScan;
    private FrameLayout cameraPreviewLayout;
    private ScanSurfaceView mImageSurfaceView;
    private TextView captureHintText;
    private LinearLayout captureHintLayout;
    private PolygonView polygonView;
    private ImageView cropImageView;
    private Bitmap copyBitmap;
    private FrameLayout cropLayout;
    private ImageButton captureBtn;
    private ImageButton switchModeBtn;
    private ImageButton switchFlashBtn;
    private LimitedArea limitedArea;

    private boolean isPermissionNotGranted;
    private boolean flashIsEnable = false;

    static {
        Log.d(TAG, "LoadLibrary");
        System.loadLibrary(mOpenCvLibrary);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        final int action = getIntent().getExtras() != null ? getIntent().getExtras().getInt(WHICH_API) : -1;
        if (action == START_LIVE_DETECTION) {
            if (!BuildConfig.LIVE_DETECTION_ENABLED) {
                setResult(ScanConstants.API_NOT_ENABLED);
                finish();
            }
            setContentView(R.layout.activity_scan);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            init();
        }
    }

    private void init() {
        Log.d(TAG, "Scan init");
        containerScan = findViewById(R.id.container_scan);
        cameraPreviewLayout = findViewById(R.id.camera_preview);
        captureHintLayout = findViewById(R.id.capture_hint_layout);
        captureHintText = findViewById(R.id.capture_hint_text);
        polygonView = findViewById(R.id.polygon_view);
        cropImageView = findViewById(R.id.crop_image_view);
        cropLayout = findViewById(R.id.crop_layout);
        captureBtn = findViewById(R.id.capture_btn);
        limitedArea = findViewById(R.id.limited_area);
        switchModeBtn = findViewById(R.id.switch_mode);
        switchFlashBtn = findViewById(R.id.flash);

        captureBtn.setOnClickListener(onClickListener);
        switchModeBtn.setOnClickListener(onClickListener);
        switchFlashBtn.setOnClickListener(onClickListener);

        View cropAcceptBtn = findViewById(R.id.crop_accept_btn);
        View cropRejectBtn = findViewById(R.id.crop_reject_btn);
        ImageButton openFileBtn = findViewById(R.id.open_file_btn);
        ImageButton backBtn = findViewById(R.id.back_btn);

        openFileBtn.setOnClickListener(onClickListener);
        backBtn.setOnClickListener(onClickListener);
        cropRejectBtn.setOnClickListener(v -> openCameraView());
        cropAcceptBtn.setOnClickListener(this);

        checkCameraPermissions();
    }

    private void openCameraView() {
        Log.d(TAG, "openCameraView");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            TransitionManager.beginDelayedTransition(containerScan);
        }
        cropLayout.setVisibility(View.GONE);
        if (mImageSurfaceView.getAcquisitionMode() == ScanSurfaceView.AcquisitionMode.FROM_FILESYSTEM) {
            mImageSurfaceView = new ScanSurfaceView(ScanActivity.this, ScanActivity.this);
            cameraPreviewLayout.addView(mImageSurfaceView);
        }
        else {
            mImageSurfaceView.setPreviewCallback();
        }
        goneManualMode();
    }

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            switchModeBtn.setVisibility(View.VISIBLE);
        }
    };

    private void showManualMode() {
        if (mImageSurfaceView != null) {
            mImageSurfaceView.setAcquisitionMode(ScanSurfaceView.AcquisitionMode.MANUAL_MODE);
        }
        captureBtn.setVisibility(View.VISIBLE);
        limitedArea.setVisibility(View.VISIBLE);
        switchModeBtn.setImageResource(R.drawable.ic_detector);
    }

    private void goneManualMode() {
        Log.d(TAG, "Manual Mode");
        if (mImageSurfaceView != null) {
            mImageSurfaceView.setAcquisitionMode(ScanSurfaceView.AcquisitionMode.DETECTION_MODE);
        }
        captureBtn.setVisibility(View.GONE);
        limitedArea.setVisibility(View.GONE);
        switchModeBtn.setImageResource(R.drawable.ic_hand);
        handler.postDelayed(runnable, SHOW_MANUAL_MODE_INTERVAL);
    }

    private final View.OnClickListener onClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (view.getId() == R.id.open_file_btn) {
                mImageSurfaceView.setAcquisitionMode(ScanSurfaceView.AcquisitionMode.FROM_FILESYSTEM);
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, MIME_TYPES);
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select a File to Upload"), SELECTED_FILE_CODE);
                }
                catch (Exception ex) {
                    Log.e(TAG, ex.toString(), ex);
                }
            }
            else if (view.getId() == R.id.capture_btn) {
                mImageSurfaceView.autoCapture(CAPTURING_IMAGE);
            }
            else if (view.getId() == R.id.switch_mode) {
                if (mImageSurfaceView.getAcquisitionMode() == ScanSurfaceView.AcquisitionMode.MANUAL_MODE) {
                    goneManualMode();
                }
                else {
                    showManualMode();
                }
            }
            else if (view.getId() == R.id.flash) {
                flashIsEnable = !flashIsEnable;
                switchFlashBtn.setImageResource(flashIsEnable ? R.drawable.ic_flash_off : R.drawable.ic_flash);
                mImageSurfaceView.setFlash(flashIsEnable);
            }
            else if (view.getId() == R.id.back_btn) {
                finish();
            }
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SELECTED_FILE_CODE && data != null) {
            mImageSurfaceView.surfaceDestroyed();
            try {
                Uri selectedFile = data.getData();
                ContentResolver cR = getApplicationContext().getContentResolver();
                MimeTypeMap mime = MimeTypeMap.getSingleton();
                String type = mime.getExtensionFromMimeType(cR.getType(selectedFile));
                Log.i(TAG, "Caricato file da filesystem di tipo: " + type);
                if (type.equals(PDF_EXT)) {
                    ScanUtils.saveToInternalMemory(getApplicationContext(), selectedFile, this);
                }
                else {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), selectedFile);
                    Bitmap rotatedBitmap = ScanUtils.modifyOrientation(getApplicationContext(), bitmap, selectedFile);
                    if (rotatedBitmap == null) {
                        onPictureClicked(bitmap);
                    }
                    else {
                        onPictureClicked(rotatedBitmap);
                    }
                    displayHint(NO_MESSAGE);
                }
            }
            catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
        else {
            mImageSurfaceView.setAcquisitionMode(ScanSurfaceView.AcquisitionMode.DETECTION_MODE);
        }
    }

    private void checkCameraPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            isPermissionNotGranted = true;
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, MY_PERMISSIONS_REQUEST);
            Log.d(TAG, "permissionsRequested");
        }
        else {
            if (!isPermissionNotGranted) {
                Log.d(TAG, "checkCameraPermissions() called");
                mImageSurfaceView = new ScanSurfaceView(ScanActivity.this, this);
                cameraPreviewLayout.addView(mImageSurfaceView);
            }
            else {
                isPermissionNotGranted = false;
                Log.d(TAG, "permissionsGranted");

            }
        }
        goneManualMode();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == MY_PERMISSIONS_REQUEST) {
            onRequestCamera(grantResults);
            Log.d(TAG, "onPermissionResult")
        }
    }

    private void onRequestCamera(int[] grantResults) {
        Log.d(TAG, "onRequestCamera")
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            new Handler().postDelayed(() -> runOnUiThread(() -> {
                mImageSurfaceView = new ScanSurfaceView(ScanActivity.this, ScanActivity.this);
                cameraPreviewLayout.addView(mImageSurfaceView);
            }), 500);
        }
        else {
            Toast.makeText(this, getString(R.string.camera_activity_permission_denied_toast), Toast.LENGTH_SHORT).show();
            this.finish();
        }
    }

    @Override
    public void displayHint(ScanHint scanHint) {
        captureHintLayout.setVisibility(View.VISIBLE);
        switch (scanHint) {
            case MOVE_CLOSER:
                captureHintText.setText(getResources().getString(R.string.move_closer));
                captureHintLayout.setBackground(getResources().getDrawable(R.drawable.hint_red));
                break;
            case ROTATE:
                captureHintText.setText(getResources().getString(R.string.rotate));
                captureHintLayout.setBackground(getResources().getDrawable(R.drawable.hint_red));
                break;
            case MOVE_AWAY:
                captureHintText.setText(getResources().getString(R.string.move_away));
                captureHintLayout.setBackground(getResources().getDrawable(R.drawable.hint_red));
                break;
            case ADJUST_ANGLE:
                captureHintText.setText(getResources().getString(R.string.adjust_angle));
                captureHintLayout.setBackground(getResources().getDrawable(R.drawable.hint_red));
                break;
            case FIND_RECT:
                captureHintText.setText(getResources().getString(R.string.finding_rect));
                captureHintLayout.setBackground(getResources().getDrawable(R.drawable.hint_white));
                break;
            case CAPTURING_IMAGE:
                captureHintText.setText(getResources().getString(R.string.hold_still));
                captureHintLayout.setBackground(getResources().getDrawable(R.drawable.hint_green));
                break;
            case MANUAL_MODE:
                captureHintText.setText(getResources().getString(R.string.manual_mode));
                captureHintLayout.setBackground(getResources().getDrawable(R.drawable.hint_green));
                break;
            case NO_MESSAGE:
                captureHintLayout.setVisibility(GONE);
                break;
            default:
                break;
        }
    }

    @Override
    public void onPictureClicked(final Bitmap bitmap) {
        handler.removeCallbacks(runnable);
        try {
            copyBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);

            int height = getWindow().findViewById(Window.ID_ANDROID_CONTENT).getHeight();
            int width = getWindow().findViewById(Window.ID_ANDROID_CONTENT).getWidth();

            copyBitmap = ScanUtils.resizeToScreenContentSize(copyBitmap, width, height);
            Mat originalMat = new Mat(copyBitmap.getHeight(), copyBitmap.getWidth(), CvType.CV_8UC1);
            Utils.bitmapToMat(copyBitmap, originalMat);
            ArrayList<PointF> points;
            Map<Integer, PointF> pointFs = new HashMap<>();
            Quadrilateral quad = ScanUtils.detectLargestQuadrilateral(originalMat);
            if (mImageSurfaceView.getAcquisitionMode() != ScanSurfaceView.AcquisitionMode.MANUAL_MODE) {
                if (null != quad) {
                    double resultArea = Math.abs(Imgproc.contourArea(quad.contour));
                    double previewArea = originalMat.rows() * originalMat.cols();
                    if (resultArea > previewArea * 0.08) {
                        points = new ArrayList<>();
                        points.add(new PointF((float) quad.points[0].x, (float) quad.points[0].y));
                        points.add(new PointF((float) quad.points[1].x, (float) quad.points[1].y));
                        points.add(new PointF((float) quad.points[3].x, (float) quad.points[3].y));
                        points.add(new PointF((float) quad.points[2].x, (float) quad.points[2].y));
                    }
                    else {
                        points = ScanUtils.getPolygonDefaultPoints(copyBitmap);
                    }

                }
                else {
                    points = ScanUtils.getPolygonDefaultPoints(copyBitmap);
                }
            }
            else {
                points = ScanUtils.getPolygonFromLimitedArea(limitedArea);
            }

            int index = -1;
            for (PointF pointF : points) {
                pointFs.put(++index, pointF);
            }

            polygonView.setPoints(pointFs);
            int padding = (int) getResources().getDimension(R.dimen.scan_padding);
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                    copyBitmap.getWidth() + 2 * padding, copyBitmap.getHeight() + 2 * padding);
            layoutParams.gravity = Gravity.CENTER;
            polygonView.setLayoutParams(layoutParams);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                TransitionManager.beginDelayedTransition(containerScan);
            cropLayout.setVisibility(View.VISIBLE);

            cropImageView.setImageBitmap(copyBitmap);
            cropImageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
        catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    private synchronized void showProgressDialog(String message) {
        if (progressDialogFragment != null && progressDialogFragment.isVisible()) {
            // Before creating another loading dialog, close all opened loading dialogs (if any)
            progressDialogFragment.dismissAllowingStateLoss();
        }
        progressDialogFragment = null;
        progressDialogFragment = new ProgressDialogFragment(message);
        FragmentManager fm = getFragmentManager();
        progressDialogFragment.show(fm, ProgressDialogFragment.class.toString());
    }

    private synchronized void dismissDialog() {
        progressDialogFragment.dismissAllowingStateLoss();
    }

    @Override
    public void onClick(View view) {
        Map<Integer, PointF> points = polygonView.getPoints();

        Bitmap croppedBitmap;

        if (ScanUtils.isScanPointsValid(points)) {
            Point point1 = new Point(points.get(0).x, points.get(0).y);
            Point point2 = new Point(points.get(1).x, points.get(1).y);
            Point point3 = new Point(points.get(2).x, points.get(2).y);
            Point point4 = new Point(points.get(3).x, points.get(3).y);
            croppedBitmap = ScanUtils.enhanceReceipt(copyBitmap, point1, point2, point3, point4);
        }
        else {
            croppedBitmap = copyBitmap;
        }

        ScanUtils.saveToInternalMemory(getApplicationContext(), croppedBitmap, this);
    }

    @Override
    public void onCompleted(String[] paths) {
        setResult(Activity.RESULT_OK, new Intent()
                .putExtra(ScanConstants.PATH_RESULT, paths[0])
                .putExtra(ScanConstants.TYPE_RESULT, paths[1])
                .putExtra(ScanConstants.ACQUISITION_MODE, mImageSurfaceView.getAcquisitionMode().toString()));
        finish();
    }

    @Override
    public void onError(String message) {
        Log.d(TAG, message)
        /* NOOP */
    }
}
