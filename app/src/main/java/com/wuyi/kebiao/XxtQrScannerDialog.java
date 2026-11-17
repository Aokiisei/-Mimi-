package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 实时取景二维码扫描弹窗。用系统 Camera 预览 + 本地 ZXing 逐帧解码，
 * 识别成功后把原始文本回传给签到弹窗，用于二维码签到。
 */

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.List;

public class XxtQrScannerDialog extends Dialog implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public interface Listener {
        void onQr(String raw);
    }

    private static final long DECODE_GAP = 220L;

    private final Listener listener;
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SurfaceView surface;
    private Camera camera;
    private HandlerThread thread;
    private Handler worker;
    private volatile boolean decoding = false;
    private volatile boolean finished = false;
    private long lastDecodeAt = 0L;

    public XxtQrScannerDialog(Context context, Listener listener) {
        super(context, android.R.style.Theme_Translucent_NoTitleBar);
        this.listener = listener;
        setup();
    }

    private void setup() {
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        }
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(getContext());
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(getContext());
        hint.setText("把大屏二维码放进画面中央，识别后自动签到");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(46));
        hintParams.gravity = Gravity.BOTTOM;
        root.addView(hint, hintParams);

        TextView close = new TextView(getContext());
        close.setText("×");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0x66000000);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams((int) dp(44), (int) dp(44));
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.topMargin = (int) dp(16);
        closeParams.rightMargin = (int) dp(16);
        root.addView(close, closeParams);

        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getContext().getResources().getDisplayMetrics());
    }

    @Override
    public void show() {
        super.show();
        Window window = getWindow();
        if (window == null) {
            return;
        }
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        window.setLayout((int) (dm.widthPixels * 0.92f), (int) (dm.heightPixels * 0.7f));
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
    }

    // ------------------------------------------------------------ 预览回调
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.setDisplayOrientation(90);
                camera.startPreview();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void openCamera(SurfaceHolder holder) {
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int index = -1;
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    index = i;
                    break;
                }
            }
            if (index < 0 && Camera.getNumberOfCameras() > 0) {
                index = 0;
            }
            if (index < 0) {
                return;
            }
            camera = Camera.open(index);
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            Camera.Size size = pickSize(params.getSupportedPreviewSizes());
            if (size != null) {
                params.setPreviewSize(size.width, size.height);
            }
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);

            if (thread == null) {
                thread = new HandlerThread("xxt-qr-scan");
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            camera.startPreview();
        } catch (Exception e) {
            releaseCamera();
        }
    }

    private Camera.Size pickSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = sizes.get(0);
        for (Camera.Size size : sizes) {
            if (size.width >= 960 && size.height >= 540 && size.width < best.width) {
                best = size;
            }
        }
        return best;
    }

    @Override
    public void onPreviewFrame(final byte[] data, final Camera cam) {
        if (finished || data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDecodeAt < DECODE_GAP || decoding) {
            return;
        }
        lastDecodeAt = now;
        decoding = true;
        final Camera.Size size = cam.getParameters().getPreviewSize();
        final Handler target = worker;
        if (target == null) {
            decoding = false;
            return;
        }
        target.post(new Runnable() {
            @Override
            public void run() {
                decode(data, size.width, size.height);
            }
        });
    }

    private void decode(byte[] data, int width, int height) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    data, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            final Result result = reader.decodeWithState(bitmap);
            if (result != null && result.getText() != null && !result.getText().isEmpty()) {
                finished = true;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        String raw = result.getText();
                        dismiss();
                        if (listener != null) {
                            listener.onQr(raw);
                        }
                    }
                });
            }
        } catch (NotFoundException ignored) {
        } catch (Exception ignored) {
        } finally {
            reader.reset();
            decoding = false;
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
        if (thread != null) {
            try {
                thread.quitSafely();
            } catch (Exception ignored) {
            }
            thread = null;
            worker = null;
        }
    }

    @Override
    public void dismiss() {
        finished = true;
        releaseCamera();
        super.dismiss();
    }
}
