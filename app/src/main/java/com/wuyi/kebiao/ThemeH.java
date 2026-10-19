package com.wuyi.kebiao;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/* JADX INFO: loaded from: classes.dex */
public class ThemeH {
    public static String name = "ocean";
    public static String glass = "0";
    public static int primary = -16028725;
    public static int primaryDark = -16299377;
    public static int bg = -1117192;
    public static int card = -1;
    public static int text = -15322545;
    public static int sub = -10784378;
    public static int inputBg = -722692;
    public static int inputBorder = -2891540;
    public static int secondary = -9669251;

    private static void applyName() {
        String str = name;
        if ("classic".equals(str)) {
            primary = -16560479;
            primaryDark = -16631693;
            bg = -986379;
            card = -1;
            text = -15061954;
            sub = -10066330;
            inputBg = -328966;
            inputBorder = -2236963;
            secondary = -9669251;
            return;
        }
        if ("green".equals(str)) {
            primary = -13656466;
            primaryDark = -14716848;
            bg = -1378834;
            card = -1;
            text = -14795474;
            sub = -10716568;
            inputBg = -919308;
            inputBorder = -3546413;
            secondary = -9669251;
            return;
        }
        if ("orange".equals(str)) {
            primary = -1016542;
            primaryDark = -4695536;
            bg = -133908;
            card = -1;
            text = -11915755;
            sub = -7705003;
            inputBg = -133134;
            inputBorder = -992830;
            secondary = -9669251;
            return;
        }
        if ("pink".equals(str)) {
            primary = -1545582;
            primaryDark = -4962706;
            bg = -134923;
            card = -1;
            text = -11919824;
            sub = -7709584;
            inputBg = -133639;
            inputBorder = -864290;
            secondary = -9669251;
            return;
        }
        if ("purple".equals(str)) {
            primary = -9675545;
            primaryDark = -11845696;
            bg = -855812;
            card = -1;
            text = -14015408;
            sub = -10133874;
            inputBg = -592642;
            inputBorder = -2962190;
            secondary = -9669251;
            return;
        }
        if ("red".equals(str)) {
            primary = -4179669;
            primaryDark = -7591393;
            bg = -266516;
            card = -1;
            text = -11919848;
            sub = -7710384;
            inputBg = -199438;
            inputBorder = -1259070;
            secondary = -9669251;
            return;
        }
        if ("cyan".equals(str)) {
            primary = -16215630;
            primaryDark = -16356734;
            bg = -1313030;
            card = -1;
            text = -15582907;
            sub = -11240824;
            inputBg = -919044;
            inputBorder = -4006167;
            secondary = -9669251;
            return;
        }
        if ("mint".equals(str)) {
            primary = -15419226;
            primaryDark = -15824259;
            bg = -1443081;
            card = -1;
            text = -15581896;
            sub = -11173254;
            inputBg = -984071;
            inputBorder = -4134687;
            secondary = -9669251;
            return;
        }
        if ("dark".equals(str)) {
            primary = -12877066;
            primaryDark = -14796150;
            bg = -15592425;
            card = -14934236;
            text = -1512723;
            sub = -6643284;
            inputBg = -14473426;
            inputBorder = -13288893;
            secondary = -12959929;
            return;
        }
        primary = -16028725;
        primaryDark = -16299377;
        bg = -1117192;
        card = -1;
        text = -15322545;
        sub = -10784378;
        inputBg = -722692;
        inputBorder = -2891540;
        secondary = -9669251;
    }

    public static void applyProfile(View view) {
        view.setBackgroundColor(bg);
        ViewGroup viewGroup = (ViewGroup) view;
        ViewGroup viewGroup2 = (ViewGroup) viewGroup.getChildAt(0);
        ViewGroup viewGroup3 = (ViewGroup) viewGroup2.getChildAt(0);
        Drawable background = viewGroup3.getChildAt(0).getBackground();
        if (background != null) {
            background.setColorFilter(primary, PorterDuff.Mode.SRC_IN);
        }
        ((TextView) viewGroup3.getChildAt(1)).setTextColor(text);
        View childAt = viewGroup2.getChildAt(1);
        childAt.setBackgroundColor(card);
        ViewGroup viewGroup4 = (ViewGroup) childAt;
        for (int i = 0; i < 11; i++) {
            if ((i & 1) == 0) {
                tintRow((ViewGroup) viewGroup4.getChildAt(i));
            } else {
                viewGroup4.getChildAt(i).setBackgroundColor(inputBorder);
            }
        }
        applyProfileGlass(viewGroup);
    }

    public static void applyProfileGlass(View view) {
        if ("1".equals(glass)) {
            if (Build.VERSION.SDK_INT >= 29) {
                GlassBgAnim.start(view);
            } else {
                view.setBackgroundColor(primary);
            }
            ViewGroup viewGroup = (ViewGroup) view;
            ViewGroup viewGroup2 = (ViewGroup) ((ViewGroup) viewGroup.getChildAt(0)).getChildAt(1);
            GradientDrawable gradientDrawable = new GradientDrawable();
            gradientDrawable.setColor(-855638017);
            gradientDrawable.setCornerRadius(22.0f * viewGroup.getResources().getDisplayMetrics().density);
            gradientDrawable.setStroke(1, 1728053247);
            viewGroup2.setBackground(gradientDrawable);
            for (int i = 1; i < 10; i += 2) {
                viewGroup2.getChildAt(i).setBackgroundColor(335544320);
            }
        }
    }

    public static void applyWeek(Activity activity) {
        Drawable navigationIcon;
        if (Build.VERSION.SDK_INT >= 21) {
            activity.getWindow().setStatusBarColor(primaryDark);
        }
        ViewGroup viewGroup = (ViewGroup) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        View childAt = viewGroup.getChildAt(0);
        childAt.setBackgroundColor(primary);
        if ((childAt instanceof Toolbar) && (navigationIcon = ((Toolbar) childAt).getNavigationIcon()) != null) {
            navigationIcon.setColorFilter(-1, PorterDuff.Mode.SRC_IN);
        }
        viewGroup.getChildAt(1).setBackgroundColor(primary);
        ViewGroup viewGroup2 = (ViewGroup) activity.findViewById(R.id.table_content);
        int childCount = viewGroup2.getChildCount();
        for (int i = 0; i < childCount; i++) {
            ViewGroup viewGroup3 = (ViewGroup) viewGroup2.getChildAt(i);
            for (int i2 = 0; i2 < viewGroup3.getChildCount(); i2++) {
                View childAt2 = viewGroup3.getChildAt(i2);
                childAt2.setBackgroundColor(card);
                ((TextView) ((ViewGroup) childAt2).getChildAt(0)).setTextColor(text);
            }
        }
    }

    public static void init(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("app_theme", 0);
        name = sharedPreferences.getString("theme", "ocean");
        applyName();
        glass = sharedPreferences.getString("glass_ui", "0");
    }

    public static void save(Context context, String str) {
        context.getSharedPreferences("app_theme", 0).edit().putString("theme", str).apply();
        name = str;
        applyName();
    }

    public static void statusBar(Activity activity) {
        if (Build.VERSION.SDK_INT >= 21) {
            activity.getWindow().setStatusBarColor(primaryDark);
        }
    }

    public static void tintBottomNav(BottomNavigationView bottomNavigationView) {
        ColorStateList colorStateList = new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]}, new int[]{primary, sub});
        bottomNavigationView.setItemTextColor(colorStateList);
        bottomNavigationView.setItemIconTintList(colorStateList);
    }

    private static void tintRow(ViewGroup viewGroup) {
        int childCount = viewGroup.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View childAt = viewGroup.getChildAt(i);
            if (childAt instanceof TextView) {
                TextView textView = (TextView) childAt;
                textView.setTextColor(text);
                int id = textView.getId();
                if (id == R.id.tv_announcement_status) {
                    textView.setTextColor(sub);
                } else if (id == R.id.tv_version_name) {
                    textView.setTextColor(sub);
                } else if (id == R.id.tv_creator) {
                    textView.setTextColor(primary);
                }
            } else if (childAt instanceof ImageView) {
                ((ImageView) childAt).setColorFilter(sub);
            }
        }
    }
}
