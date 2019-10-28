package com.adityaarora.liveedgedetection.activity;

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

import com.adityaarora.liveedgedetection.R;
import com.adityaarora.liveedgedetection.constants.ScanConstants;
import com.adityaarora.liveedgedetection.enums.ScanHint;
import com.adityaarora.liveedgedetection.interfaces.IScanner;
import com.adityaarora.liveedgedetection.util.ScanUtils;
import com.adityaarora.liveedgedetection.view.PolygonPoints;
import com.adityaarora.liveedgedetection.view.PolygonView;
import com.adityaarora.liveedgedetection.view.ProgressDialogFragment;
import com.adityaarora.liveedgedetection.view.Quadrilateral;
import com.adityaarora.liveedgedetection.view.ScanSurfaceView;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import static android.view.View.GONE;
import static com.adityaarora.liveedgedetection.constants.ScanConstants.PDF_EXT;

/**
 * This class initiates camera and detects edges on live view
 */
public class ScanActivity extends AppCompatActivity implements IScanner, View.OnClickListener, ScanUtils.OnSaveListener {
    private static final String TAG = ScanActivity.class.getSimpleName();

    private static final int MY_PERMISSIONS_REQUEST_CAMERA = 101;
    private static final int SELECTED_FILE_CODE = 102;

    private ViewGroup containerScan;
    private FrameLayout cameraPreviewLayout;
    private ScanSurfaceView mImageSurfaceView;
    private boolean isPermissionNotGranted;
    private static final String mOpenCvLibrary = "opencv_java3";
    private static ProgressDialogFragment progressDialogFragment;
    private TextView captureHintText;
    private LinearLayout captureHintLayout;

    public final static Stack<PolygonPoints> allDraggedPointsStack = new Stack<>();
    private PolygonView polygonView;
    private ImageView cropImageView;
    private View cropAcceptBtn;
    private View cropRejectBtn;
    private Bitmap copyBitmap;
    private FrameLayout cropLayout;

    private ImageButton backBtn;
    private ImageButton openFileBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        init();
    }

    private void init() {
        containerScan = findViewById(R.id.container_scan);
        cameraPreviewLayout = findViewById(R.id.camera_preview);
        captureHintLayout = findViewById(R.id.capture_hint_layout);
        captureHintText = findViewById(R.id.capture_hint_text);
        polygonView = findViewById(R.id.polygon_view);
        cropImageView = findViewById(R.id.crop_image_view);
        cropAcceptBtn = findViewById(R.id.crop_accept_btn);
        cropRejectBtn = findViewById(R.id.crop_reject_btn);
        cropLayout = findViewById(R.id.crop_layout);
        openFileBtn = findViewById(R.id.open_file_btn);
        openFileBtn.setOnClickListener(onClickListener);
        backBtn = findViewById(R.id.back_btn);
        backBtn.setOnClickListener(onClickListener);

        cropAcceptBtn.setOnClickListener(this);
        cropRejectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    TransitionManager.beginDelayedTransition(containerScan);
                }
                cropLayout.setVisibility(View.GONE);
                if (mImageSurfaceView.getFromFilesystem()) {
                    mImageSurfaceView = new ScanSurfaceView(ScanActivity.this, ScanActivity.this);
                    cameraPreviewLayout.addView(mImageSurfaceView);
                }
                else {
                    mImageSurfaceView.setPreviewCallback();
                }
            }
        });
        checkCameraPermissions();
    }

    private View.OnClickListener onClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (view.getId() == R.id.open_file_btn) {
                mImageSurfaceView.setFromFilesystem(true);
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select a File to Upload"), SELECTED_FILE_CODE);
                } catch (Exception ex) {
                    Log.e(TAG, "onClick: ", ex);
                }
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
                if (type.equals(PDF_EXT)) {
                    ScanUtils.saveToInternalMemory(getApplicationContext(), selectedFile, this);
                }
                else {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), selectedFile);
                    onPictureClicked(bitmap);
                    displayHint(ScanHint.NO_MESSAGE);
                }
            }
            catch (IOException e) {
                Log.e(TAG, "onActivityResult: ", e);
            }
        }
        else {
            mImageSurfaceView.setFromFilesystem(false);
        }
    }

    private void checkCameraPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            isPermissionNotGranted = true;
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, MY_PERMISSIONS_REQUEST_CAMERA);
        }
        else {
            if (!isPermissionNotGranted) {
                Log.d(TAG, "checkCameraPermissions() called");
                mImageSurfaceView = new ScanSurfaceView(ScanActivity.this, this);
                cameraPreviewLayout.addView(mImageSurfaceView);
            } else {
                isPermissionNotGranted = false;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_CAMERA:
                onRequestCamera(grantResults);
                break;
            default:
                break;
        }
    }

    private void onRequestCamera(int[] grantResults) {
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(TAG, "run() called");
                            mImageSurfaceView = new ScanSurfaceView(ScanActivity.this, ScanActivity.this);
                            cameraPreviewLayout.addView(mImageSurfaceView);
                        }
                    });
                }
            }, 500);

        } else {
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
            case NO_MESSAGE:
                captureHintLayout.setVisibility(GONE);
                break;
            default:
                break;
        }
    }

    @Override
    public void onPictureClicked(final Bitmap bitmap) {
        try {
            copyBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);

            int height = getWindow().findViewById(Window.ID_ANDROID_CONTENT).getHeight();
            int width = getWindow().findViewById(Window.ID_ANDROID_CONTENT).getWidth();

            copyBitmap = ScanUtils.resizeToScreenContentSize(copyBitmap, width, height);
            Mat originalMat = new Mat(copyBitmap.getHeight(), copyBitmap.getWidth(), CvType.CV_8UC1);
            Utils.bitmapToMat(copyBitmap, originalMat);
            ArrayList<PointF> points;
            Map<Integer, PointF> pointFs = new HashMap<>();
            try {
                Quadrilateral quad = ScanUtils.detectLargestQuadrilateral(originalMat);
                if (null != quad) {
                    double resultArea = Math.abs(Imgproc.contourArea(quad.contour));
                    double previewArea = originalMat.rows() * originalMat.cols();
                    if (resultArea > previewArea * 0.08) {
                        points = new ArrayList<>();
                        points.add(new PointF((float) quad.points[0].x, (float) quad.points[0].y));
                        points.add(new PointF((float) quad.points[1].x, (float) quad.points[1].y));
                        points.add(new PointF((float) quad.points[3].x, (float) quad.points[3].y));
                        points.add(new PointF((float) quad.points[2].x, (float) quad.points[2].y));
                    } else {
                        points = ScanUtils.getPolygonDefaultPoints(copyBitmap);
                    }

                } else {
                    points = ScanUtils.getPolygonDefaultPoints(copyBitmap);
                }

                int index = -1;
                for (PointF pointF : points) {
                    pointFs.put(++index, pointF);
                }

                polygonView.setPoints(pointFs);
                int padding = (int) getResources().getDimension(R.dimen.scan_padding);
                FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(copyBitmap.getWidth() + 2 * padding, copyBitmap.getHeight() + 2 * padding);
                layoutParams.gravity = Gravity.CENTER;
                polygonView.setLayoutParams(layoutParams);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                    TransitionManager.beginDelayedTransition(containerScan);
                cropLayout.setVisibility(View.VISIBLE);

                cropImageView.setImageBitmap(copyBitmap);
                cropImageView.setScaleType(ImageView.ScaleType.FIT_XY);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
            }
        } catch (Exception e) {
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

    static {
        System.loadLibrary(mOpenCvLibrary);
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
                .putExtra(ScanConstants.PATH_RESULT, paths[1])
                .putExtra(ScanConstants.TYPE_RESULT, paths[2]));
        finish();
    }
}
