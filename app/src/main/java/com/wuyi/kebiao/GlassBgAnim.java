package com.wuyi.kebiao;

import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.LinearInterpolator;
import java.lang.ref.WeakReference;

/* JADX INFO: loaded from: classes.dex */
public class GlassBgAnim implements ValueAnimator.AnimatorUpdateListener {
    private static boolean applied;
    private static ValueAnimator current;
    private static int lastOff;
    private final WeakReference viewRef;

    public GlassBgAnim(WeakReference weakReference) {
        this.viewRef = weakReference;
    }

    private static Drawable build(int i, int i2, float f) {
        int[] iArr = {-2758667, -2101785, -1054245, -1972493};
        float f2 = f * 4.0f;
        int iSample = sample(iArr, f2);
        float f3 = f2 + 1.0f;
        int iSample2 = sample(iArr, f3);
        float f4 = f3 + 1.0f;
        int iSample3 = sample(iArr, f4);
        int iSample4 = sample(iArr, f4 + 1.0f);
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColors(new int[]{iSample, iSample2, iSample3, iSample4});
        return gradientDrawable;
    }

    public static void cancel() {
        ValueAnimator valueAnimator = current;
        if (valueAnimator != null) {
            valueAnimator.cancel();
        }
        current = null;
        applied = false;
    }

    private static int lerp(int i, int i2, float f) {
        int i3 = i >>> 24;
        int i4 = (i >> 16) & 255;
        int i5 = (i >> 8) & 255;
        int i6 = i & 255;
        return ((i3 + ((int) (((i2 >>> 24) - i3) * f))) << 24) | ((i4 + ((int) ((((i2 >> 16) & 255) - i4) * f))) << 16) | ((i5 + ((int) ((((i2 >> 8) & 255) - i5) * f))) << 8) | (i6 + ((int) (((i2 & 255) - i6) * f)));
    }

    private static int sample(int[] iArr, float f) {
        int i = (int) f;
        int i2 = i % 4;
        return lerp(iArr[i2], iArr[(i2 + 1) % 4], f - i);
    }

    public static void start(View view) {
        cancel();
        ValueAnimator valueAnimatorOfFloat = ValueAnimator.ofFloat(0.0f, 1.0f);
        valueAnimatorOfFloat.addUpdateListener(new GlassBgAnim(new WeakReference(view)));
        valueAnimatorOfFloat.setDuration(36000L);
        valueAnimatorOfFloat.setRepeatCount(-1);
        valueAnimatorOfFloat.setRepeatMode(1);
        valueAnimatorOfFloat.setInterpolator(new LinearInterpolator());
        current = valueAnimatorOfFloat;
        valueAnimatorOfFloat.start();
    }

    @Override // android.animation.ValueAnimator.AnimatorUpdateListener
    public void onAnimationUpdate(ValueAnimator valueAnimator) {
        View view;
        WeakReference weakReference = this.viewRef;
        if (weakReference == null || (view = (View) weakReference.get()) == null) {
            cancel();
            return;
        }
        if (view.isAttachedToWindow()) {
            int width = view.getWidth();
            if (width <= 0) {
                width = 1080;
            }
            int height = view.getHeight();
            if (height <= 0) {
                height = 1920;
            }
            view.setBackground(build(width, height, ((Float) valueAnimator.getAnimatedValue()).floatValue()));
        }
    }
}
