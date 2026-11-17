package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 学习通章节测验的字体反解。页面把真实汉字映射到私有字体的错误码位上，
 * 这里把密文字体渲染成小尺寸灰度特征，与设备上的参考中文字体逐字比对，
 * 取相似度最高的候选字。识别置信度低的字保留原样，避免把题干改错。
 */

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class XxtFontDecoder {

    /** 一次反解的结果 */
    public static final class Decoded {
        public String text = "";
        public float min = 1f;
        public int weak = 0;

        public boolean trustworthy() {
            return weak == 0 && min >= 0.86f;
        }
    }

    private static final int VEC = 16;
    private static final int CANVAS = 72;
    private static final float THRESHOLD = 0.86f;

    private static final Map<String, List<float[]>> REF_CACHE = new HashMap<>();
    private static char[] candidates;

    private final Typeface secret;
    private final List<Typeface> refs = new ArrayList<>();
    private final List<String> refKeys = new ArrayList<>();
    private final List<float[]> refSignatures = new ArrayList<>();

    public XxtFontDecoder(Context context, String base64) {
        this.secret = loadSecret(context, base64);
        buildReferences(context);
    }

    public boolean ready() {
        return secret != null && !refs.isEmpty();
    }

    public Decoded decode(String input) {
        Decoded out = new Decoded();
        String text = input == null ? "" : input;
        out.text = text;
        if (!ready() || text.isEmpty()) {
            out.weak = countCjk(text);
            return out;
        }
        StringBuilder sb = new StringBuilder(text.length());
        float min = 1f;
        int weak = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!isCjk(ch)) {
                sb.append(ch);
                continue;
            }
            float[] sv = render(secret, ch);
            if (sv == null) {
                sb.append(ch);
                weak++;
                continue;
            }
            char best = ch;
            float bestSim = -1f;
            for (int r = 0; r < refs.size(); r++) {
                List<float[]> vectors = refVectors(refKeys.get(r), refs.get(r));
                if (vectors == null) {
                    continue;
                }
                for (int c = 0; c < candidates.length; c++) {
                    float[] rv = c < vectors.size() ? vectors.get(c) : null;
                    if (rv == null) {
                        continue;
                    }
                    float sim = cosine(sv, rv);
                    if (sim > bestSim) {
                        bestSim = sim;
                        best = candidates[c];
                    }
                }
            }
            if (bestSim >= THRESHOLD) {
                sb.append(best);
                if (bestSim < min) {
                    min = bestSim;
                }
            } else {
                sb.append(ch);
                weak++;
            }
        }
        out.text = sb.toString();
        out.min = min;
        out.weak = weak;
        return out;
    }

    // -------------------------------------------------------------- 字体加载
    private static Typeface loadSecret(Context context, String base64) {
        if (base64 == null || base64.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File dir = new File(context.getCacheDir(), "quizfont");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File file = new File(dir, "secret-" + bytes.length + "-" + bytes.hashCode() + ".ttf");
            if (!file.exists() || file.length() != bytes.length) {
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
            }
            Typeface tf = Typeface.createFromFile(file);
            return usable(tf) ? tf : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void buildReferences(Context context) {
        Typeface asset = loadAssetFont(context, "fonts/mi-serif.otf", "mi-serif");
        addRef("asset-mi-serif", asset);
        addRef("sans", Typeface.SANS_SERIF);
        addRef("serif", Typeface.SERIF);
        addRef("default", Typeface.DEFAULT);
    }

    private void addRef(String key, Typeface tf) {
        if (tf == null) {
            return;
        }
        for (Typeface existing : refs) {
            if (existing.equals(tf)) {
                return;
            }
        }
        if (!usable(tf)) {
            return;
        }
        float[] signature = render(tf, '中');
        if (signature == null) {
            return;
        }
        for (float[] existing : refSignatures) {
            if (cosine(signature, existing) > 0.999f) {
                return;
            }
        }
        refSignatures.add(signature);
        refs.add(tf);
        refKeys.add(key);
    }

    private static Typeface loadAssetFont(Context context, String path, String name) {
        try {
            File dir = new File(context.getCacheDir(), "reffonts");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File file = new File(dir, name + ".otf");
            if (!file.exists() || file.length() == 0) {
                InputStream in = context.getAssets().open(path);
                FileOutputStream out = new FileOutputStream(file);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.close();
                in.close();
            }
            return Typeface.createFromFile(file);
        } catch (Exception e) {
            return null;
        }
    }

    /** 能画出「中」「国」且两者形状不同，才当作有效中文字体 */
    private static boolean usable(Typeface tf) {
        float[] a = render(tf, '中');
        float[] b = render(tf, '国');
        if (a == null || b == null) {
            return false;
        }
        return cosine(a, b) < 0.995f;
    }

    // -------------------------------------------------------------- 参考字表
    private static synchronized List<float[]> refVectors(String key, Typeface tf) {
        List<float[]> cached = REF_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        char[] chars = candidates();
        List<float[]> list = new ArrayList<>(chars.length);
        for (char ch : chars) {
            list.add(render(tf, ch));
        }
        REF_CACHE.put(key, list);
        return list;
    }

    private static synchronized char[] candidates() {
        if (candidates != null) {
            return candidates;
        }
        StringBuilder sb = new StringBuilder(4000);
        for (int hi = 0xB0; hi <= 0xF7; hi++) {
            for (int lo = 0xA1; lo <= 0xFE; lo++) {
                try {
                    String s = new String(new byte[]{(byte) hi, (byte) lo}, "GB2312");
                    if (s.length() == 1) {
                        sb.append(s.charAt(0));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        candidates = sb.toString().toCharArray();
        return candidates;
    }

    // -------------------------------------------------------------- 渲染
    private static float[] render(Typeface tf, char ch) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(tf);
            paint.setTextSize(64f);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(String.valueOf(ch), 5f, 68f, paint);

            int[] pixels = new int[CANVAS * CANVAS];
            bitmap.getPixels(pixels, 0, CANVAS, 0, 0, CANVAS, CANVAS);
            int left = CANVAS, top = CANVAS, right = -1, bottom = -1;
            for (int y = 0; y < CANVAS; y++) {
                for (int x = 0; x < CANVAS; x++) {
                    int alpha = Color.alpha(pixels[y * CANVAS + x]);
                    if (alpha > 8) {
                        if (x < left) {
                            left = x;
                        }
                        if (x > right) {
                            right = x;
                        }
                        if (y < top) {
                            top = y;
                        }
                        if (y > bottom) {
                            bottom = y;
                        }
                    }
                }
            }
            if (right <= left || bottom <= top) {
                bitmap.recycle();
                return null;
            }
            int pad = Math.max(1, Math.round(Math.min(right - left, bottom - top) * 0.02f));
            left = Math.max(0, left - pad);
            top = Math.max(0, top - pad);
            right = Math.min(CANVAS - 1, right + pad);
            bottom = Math.min(CANVAS - 1, bottom + pad);

            Bitmap scaled = Bitmap.createBitmap(VEC, VEC, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(scaled);
            Paint sp = new Paint(Paint.FILTER_BITMAP_FLAG);
            sc.drawBitmap(bitmap, new Rect(left, top, right + 1, bottom + 1),
                    new Rect(0, 0, VEC, VEC), sp);
            int[] small = new int[VEC * VEC];
            scaled.getPixels(small, 0, VEC, 0, 0, VEC, VEC);
            float[] vec = new float[VEC * VEC];
            double norm = 0d;
            for (int i = 0; i < vec.length; i++) {
                vec[i] = Color.alpha(small[i]) / 255f;
                norm += vec[i] * vec[i];
            }
            scaled.recycle();
            bitmap.recycle();
            if (norm <= 0d) {
                return null;
            }
            float len = (float) Math.sqrt(norm);
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= len;
            }
            return vec;
        } catch (Exception e) {
            return null;
        }
    }

    private static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0f;
        }
        float dot = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private static boolean isCjk(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FFF;
    }

    private static int countCjk(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }
}
