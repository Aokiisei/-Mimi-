package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 二维码图片识别工具：把相册/相机得到的图片降采样后用 ZXing 本地解码，
 * 解码失败时依次尝试旋转 90 / 180 / 270 度，兼容竖拍或倒置的图片。
 */

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class XxtQrUtil {

    private static final int MAX_SIDE = 1600;

    private XxtQrUtil() {
    }

    public static String decodeUri(Context context, Uri uri) {
        Bitmap bitmap = loadSampled(context, uri);
        String text = decodeBitmap(bitmap);
        if (bitmap != null) {
            bitmap.recycle();
        }
        return text;
    }

    public static String decodeFile(String path) {
        Bitmap bitmap = loadSampled(null, Uri.fromFile(new File(path)));
        String text = decodeBitmap(bitmap);
        if (bitmap != null) {
            bitmap.recycle();
        }
        return text;
    }

    private static Bitmap loadSampled(Context context, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in1 = open(context, uri);
            BitmapFactory.decodeStream(in1, null, bounds);
            close(in1);
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max > 0 && max / sample > MAX_SIDE) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            InputStream in2 = open(context, uri);
            Bitmap bitmap = BitmapFactory.decodeStream(in2, null, opts);
            close(in2);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream open(Context context, Uri uri) throws Exception {
        if (context != null) {
            return context.getContentResolver().openInputStream(uri);
        }
        return new FileInputStream(new File(uri.getPath()));
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static String decodeBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return "";
        }
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

        int[] degrees = new int[]{0, 90, 180, 270};
        for (int degree : degrees) {
            Bitmap current = degree == 0 ? bitmap : rotate(bitmap, degree);
            try {
                String text = tryDecode(current, hints);
                if (!text.isEmpty()) {
                    return text;
                }
            } finally {
                if (degree != 0 && current != null && current != bitmap) {
                    current.recycle();
                }
            }
        }
        return "";
    }

    private static String tryDecode(Bitmap bitmap, Map<DecodeHintType, Object> hints) {
        if (bitmap == null) {
            return "";
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) {
            return "";
        }
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        try {
            RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
            BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));
            return new MultiFormatReader().decode(binary, hints).getText();
        } catch (Exception first) {
            // 二维码图片带透明通道时 RGB 亮度可能全黑，再按 YUV 亮度解一次
            try {
                byte[] yuv = new byte[w * h];
                for (int i = 0; i < pixels.length; i++) {
                    int c = pixels[i];
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    yuv[i] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
                }
                PlanarYUVLuminanceSource ySource = new PlanarYUVLuminanceSource(
                        yuv, w, h, 0, 0, w, h, false);
                BinaryBitmap yBinary = new BinaryBitmap(new HybridBinarizer(ySource));
                return new MultiFormatReader().decode(yBinary, hints).getText();
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private static Bitmap rotate(Bitmap source, int degree) {
        try {
            Matrix matrix = new Matrix();
            matrix.postRotate(degree);
            return Bitmap.createBitmap(source, 0, 0,
                    source.getWidth(), source.getHeight(), matrix, true);
        } catch (Exception e) {
            return null;
        }
    }
}
