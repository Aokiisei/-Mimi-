package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主题工具集。
 * 负责读取/保存主题偏好、状态栏与底部导航着色、液态玻璃样式套用。
 */

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class ThemeH {
    public static String name = "ocean";
    public static String glass = "0";
    public static String autoCarousel = "0";
    public static int primary = 0xFFC96442;
    public static int primaryDark = 0xFF30302E;
    public static int bg = 0xFFF5F4ED;
    public static int card = 0xFFFAF9F5;
    public static int text = 0xFF141413;
    public static int sub = 0xFF87867F;
    public static int inputBg = 0xFFFAF9F5;
    public static int inputBorder = 0xFFE8E6DC;
    public static int secondary = 0xFF5E5D59;

    private static void applyName() {
        // 全站只保留 Claude 暖色纸张主题：忽略 preferences 里历史遗留的主题名，
        // 避免原生页面拿到旧主题的冷色背景（Web 页面始终使用暖色，导致「我的」页不一致）
        name = "ocean";
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
        // ocean（默认主题）—— Claude 温暖纸张设计系统
        primary = 0xFFC96442;
        primaryDark = 0xFF30302E;
        bg = 0xFFF5F4ED;
        card = 0xFFFAF9F5;
        text = 0xFF141413;
        sub = 0xFF87867F;
        inputBg = 0xFFFAF9F5;
        inputBorder = 0xFFE8E6DC;
        secondary = 0xFF5E5D59;
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
        // 与 Web 端卡片一致：象牙底 + 12dp 圆角 + 暖色淡描边
        childAt.setBackgroundResource(R.drawable.bg_cl_card);
        if (Build.VERSION.SDK_INT >= 21) {
            childAt.setClipToOutline(true);
        }
        ViewGroup viewGroup4 = (ViewGroup) childAt;
        // 按实际子数量遍历：偶数位为行、奇数位为分割线，行数增减时不再越界
        int profileChildCount = viewGroup4.getChildCount();
        for (int i = 0; i < profileChildCount; i++) {
            View rowOrDivider = viewGroup4.getChildAt(i);
            if (rowOrDivider == null) {
                continue;
            }
            if ((i & 1) == 0) {
                if (rowOrDivider instanceof ViewGroup) {
                    tintRow((ViewGroup) rowOrDivider);
                }
            } else {
                rowOrDivider.setBackgroundColor(inputBorder);
            }
        }
        applyProfileGlass(viewGroup);
    }

    public static void applyProfileGlass(View view) {
        if (!"1".equals(glass)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            GlassBgAnim.start(view);
        } else {
            view.setBackgroundColor(bg);
        }
        ViewGroup viewGroup = (ViewGroup) view;
        ViewGroup card = (ViewGroup) ((ViewGroup) viewGroup.getChildAt(0)).getChildAt(1);
        float density = view.getResources().getDisplayMetrics().density;
        // 暖象牙半透明玻璃 + 湿边高光，配合缓慢流动的暖色纸面
        GradientDrawable glassCard = new GradientDrawable();
        glassCard.setColor(0x99FAF7EF);
        glassCard.setCornerRadius(12.0f * density);
        glassCard.setStroke((int) (1.0f * density), 0x59FFFFFF);
        card.setBackground(glassCard);
        if (Build.VERSION.SDK_INT >= 21) {
            card.setClipToOutline(true);
        }
        int cardChildCount = card.getChildCount();
        for (int i = 1; i < cardChildCount; i += 2) {
            View divider = card.getChildAt(i);
            if (divider != null) {
                divider.setBackgroundColor(0x2EC96442);
            }
        }
    }

    public static void applyWeek(Activity activity) {
        Drawable navigationIcon;
        if (Build.VERSION.SDK_INT >= 21) {
            activity.getWindow().setStatusBarColor(bg);
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
        autoCarousel = sharedPreferences.getString("auto_carousel", "0");
    }

    public static void save(Context context, String str) {
        context.getSharedPreferences("app_theme", 0).edit().putString("theme", str).apply();
        name = str;
        applyName();
    }

    public static void statusBar(Activity activity) {
        if (Build.VERSION.SDK_INT >= 21) {
            activity.getWindow().setStatusBarColor(bg);
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
                // 行内文字统一为直立体衬线（部分 ROM 的次要文字样式会带斜体）
                textView.setTypeface(serif(textView.getContext()), Typeface.NORMAL);
                textView.getPaint().setTextSkewX(0f);
                textView.getPaint().setFakeBoldText(true);
            } else if (childAt instanceof ImageView) {
                ((ImageView) childAt).setColorFilter(sub);
            }
        }
    }

    // ===== 全局字体统一：原生界面与 Web 页面使用同一款衬线字体 =====
    private static Typeface miSerif;

    public static Typeface serif(Context context) {
        if (miSerif == null) {
            try {
                miSerif = Typeface.createFromAsset(context.getAssets(), "fonts/mi-serif.otf");
            } catch (Throwable th) {
                miSerif = Typeface.SERIF;
            }
        }
        return miSerif;
    }

    /** 递归把视图中所有文本控件换成衬线字体（保留粗体，清除继承来的斜体） */
    public static void applySerifFont(View view) {
        if (view == null) {
            return;
        }
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            Typeface cur = tv.getTypeface();
            // 只保留粗体，斜体一律清掉：部分机型/主题会把次要文字继承成斜体，看起来是“歪着的”
            int style = (cur != null && cur.isBold()) ? Typeface.BOLD : Typeface.NORMAL;
            tv.setTypeface(serif(view.getContext()), style);
            // 有些系统主题会给 TextPaint 单独设置倾斜角，这里一并复位
            tv.getPaint().setTextSkewX(0f);
            // 内置衬线只有常规字重，笔画偏细；用伪粗体加粗一点，保证阅读清晰
            tv.getPaint().setFakeBoldText(true);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applySerifFont(group.getChildAt(i));
            }
        }
    }
}
