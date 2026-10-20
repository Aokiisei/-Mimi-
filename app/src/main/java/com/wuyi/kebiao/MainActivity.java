package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 主界面 Activity。
 * 安装崩溃处理器、初始化主题、绑定底部导航与 NavHostFragment，并触发自动更新检查。
 * 版本更新后首次启动弹出更新日志。
 */

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {
    private static final int[] TAB_IDS = {
            R.id.navigation_home,
            R.id.navigation_schedule,
            R.id.navigation_assistant,
            R.id.navigation_grade,
            R.id.navigation_profile
    };

    private ViewPager2 pager;
    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        CrashHandler.install(this);
        setContentView(R.layout.activity_main);
        ThemeH.init(this);
        ThemeH.statusBar(this);
        bottomNav = (BottomNavigationView) findViewById(R.id.bottom_navigation);
        ThemeH.tintBottomNav(bottomNav);
        pager = (ViewPager2) findViewById(R.id.pager);
        // 五页全部常驻：切换时不再重建 Fragment/WebView，消除卡顿与闪屏
        pager.setOffscreenPageLimit(TAB_IDS.length - 1);
        pager.setAdapter(new TabAdapter(this));
        // 禁用左右滑动翻页（避免与 WebView 竖直滚动冲突），仅通过底部导航切换
        pager.setUserInputEnabled(false);
        bindBottomNav();
        UpdateChecker.checkAuto(this);
        // 全 App 字体统一：原生控件（含底部导航标签）与 Web 页面使用同一款衬线字体
        ThemeH.applySerifFont(getWindow().getDecorView());
        bottomNav.post(new Runnable() {
            @Override
            public void run() {
                ThemeH.applySerifFont(bottomNav);
            }
        });
        showChangelogIfNeeded();
    }

    /** ViewPager2 适配器：按底部导航顺序提供五个页面 */
    private static class TabAdapter extends FragmentStateAdapter {
        TabAdapter(FragmentActivity activity) {
            super(activity);
        }

        @Override
        public int getItemCount() {
            return TAB_IDS.length;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 1:
                    return new ScheduleFragment();
                case 2:
                    return new AssistantFragment();
                case 3:
                    return new GradeFragment();
                case 4:
                    return new ProfileFragment();
                default:
                    return new HomeFragment();
            }
        }
    }

    /** 底部导航与左右滑动翻页双向联动 */
    private void bindBottomNav() {
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position >= 0 && position < TAB_IDS.length && bottomNav != null) {
                    bottomNav.getMenu().getItem(position).setChecked(true);
                }
            }
        });
        bottomNav.setOnItemSelectedListener(item -> {
            int index = indexOfTab(item.getItemId());
            if (index < 0) {
                return false;
            }
            switchTab(index);
            return true;
        });
        bottomNav.setOnItemReselectedListener(item -> {
        });
        // 在导航栏上横向拖动也能切换，松手后落到对应标签
        bottomNav.post(this::attachSwipeToAllTabs);
    }

    private void attachSwipeToAllTabs() {
        for (int i = 0; i < TAB_IDS.length; i++) {
            View itemView = bottomNav.findViewById(TAB_IDS[i]);
            if (itemView == null && bottomNav.getChildCount() > 0
                    && bottomNav.getChildAt(0) instanceof android.view.ViewGroup) {
                android.view.ViewGroup menuContainer = (android.view.ViewGroup) bottomNav.getChildAt(0);
                if (i < menuContainer.getChildCount()) {
                    itemView = menuContainer.getChildAt(i);
                }
            }
            if (itemView != null) {
                attachSwipeGesture(itemView);
            }
        }
    }

    private void attachSwipeGesture(final View itemView) {
        itemView.setOnTouchListener(new View.OnTouchListener() {
            private float downX = 0f;
            private boolean dragging = false;
            private final int[] navLocation = new int[2];

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                int navWidth = bottomNav.getWidth();
                if (navWidth <= 0) {
                    return false;
                }
                float itemWidth = navWidth / (float) TAB_IDS.length;
                bottomNav.getLocationOnScreen(navLocation);
                float x = event.getRawX() - navLocation[0];
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = x;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && Math.abs(x - downX) > itemWidth * 0.16f) {
                            dragging = true;
                        }
                        if (dragging) {
                            // 拖动过程中直接跟手跳页，避免叠加多个滑动动画
                            jumpTab(tabIndexAt(x, itemWidth));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            switchTab(tabIndexAt(x, itemWidth));
                        }
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private int tabIndexAt(float x, float itemWidth) {
        int target = (int) (x / itemWidth);
        if (target < 0) {
            return 0;
        }
        return target >= TAB_IDS.length ? TAB_IDS.length - 1 : target;
    }

    private int indexOfTab(int id) {
        for (int i = 0; i < TAB_IDS.length; i++) {
            if (TAB_IDS[i] == id) {
                return i;
            }
        }
        return -1;
    }

    /** 供 Web 页面调用：左右翻页已彻底移除，此方法不再响应（保留仅为兼容页内旧 JS 调用，防止报错） */
    public void setPagerSwipe(boolean enabled) {
        // 不再响应：页面切换仅通过底部导航点击，ViewPager2 用户输入保持永久关闭
    }

    /** 平滑滑到目标页 */
    private void switchTab(int target) {
        if (pager == null || target < 0 || target >= TAB_IDS.length || pager.getCurrentItem() == target) {
            return;
        }
        try {
            pager.setCurrentItem(target, true);
            bottomNav.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } catch (Throwable th) {
            android.util.Log.e("MainActivity", "switch tab failed: " + target, th);
            Toast.makeText(this, "页面切换失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 拖动切换：不加动画，直接落到目标页 */
    private void jumpTab(int target) {
        if (pager == null || target < 0 || target >= TAB_IDS.length || pager.getCurrentItem() == target) {
            return;
        }
        pager.setCurrentItem(target, false);
        bottomNav.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void showChangelogIfNeeded() {
        try {
            String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            SharedPreferences sp = getSharedPreferences("mimi_changelog", Context.MODE_PRIVATE);
            String last = sp.getString("last_version", "");
            if (!cur.equals(last)) {
                sp.edit().putString("last_version", cur).apply();
                String notes = "V3.2 更新日志（3.1 → 3.2）\n\n"
                        + "本次重点：全新暖纸界面、日程右滑完成、武夷山公交查询上线、手势冲突彻底修复、课表节次时间与「我的」页优化。\n\n"
                        + "【界面】全新设计\n"
                        + "· 全局改为暖纸风：米黄纸底、赤陶主色、细描边卡片，去除冷色渐变与厚重圆角\n"
                        + "· 全 App 统一衬线字体（含数字、按钮与所有弹窗）\n"
                        + "· 五个页面常驻内存，切回主界面不再卡顿、闪屏或字体回退\n"
                        + "· 液态玻璃效果重做（默认关闭，可在「我的 → 更多」开启）\n\n"
                        + "【手势】\n"
                        + "· 彻底移除页面左右滑动切换，仅通过底部导航点击切换页面\n"
                        + "· 主界面上下滚动不再被翻页手势抢占，跟手顺滑\n"
                        + "· 日程页的日程卡片右滑标记完成功能不受影响\n\n"
                        + "【课表】\n"
                        + "· 课程卡片上的「（早八 / 早十 / 午二 / 午四 / 晚七）」可点击，弹窗查看五个大节的具体起止时间\n"
                        + "· 登录后标题下方新增细信息条，显示时间、问候、天气与名言，点击展开完整信息卡\n"
                        + "· 移除课表页右下角「＋」悬浮按钮，新建日程统一在日程页操作\n\n"
                        + "【日程】\n"
                        + "· 倒计日：预置英语四六级、普通话、计算机二级、考研、期末考试，支持自定义；7 天内醒目提示\n"
                        + "· 单点提醒：课表内以格子左侧小圆点显示，点击查看待办\n"
                        + "· 日程卡片从左向右滑即可标记完成：卡片变灰并显示「已完成」，课表内同步移除；再次右滑或点「恢复」可撤销\n"
                        + "· 跨节次日程自动横跨多个格子；与课程冲突时只显示外框，点击展开\n"
                        + "· 同格多张卡片右上角显示页数角标，可手动翻页；「更多」中可开启每 5 秒自动轮播\n"
                        + "· 日程支持自定义颜色；「＋ 新建」合并新建日程与添加倒计日\n"
                        + "· 空状态提示合并为一句，不再重复显示\n\n"
                        + "【我的】\n"
                        + "· 页面精简为：公告 / 版本更新 / 更多 / 鸣谢 / 开发者\n"
                        + "· 「问题反馈」「赞助支持」移入「更多」，更多弹窗支持上下滑动并按「设置 / 工具 / 帮助与支持」分组\n"
                        + "· 新增「鸣谢」入口，点击查看本项目所用的开源代码\n\n"
                        + "【天气】\n"
                        + "· 根据当天天气自动判断是否有降水，有雨时提示「记得带伞」\n\n"
                        + "【小助手 · 武夷山公交】\n"
                        + "· 新增公交查询，覆盖 2、6、7、8、9、10 路，横向直线站序、竖排站名\n"
                        + "· 支持选择起点站与终点站，区间站点高亮并显示站数\n"
                        + "· 7 路、8 路提供双向发车时刻表，按早晚高峰与平峰分段展示\n\n"
                        + "【其他】\n"
                        + "· 新增隐藏彩蛋，可在「我的」页面自行探索";
                new MimiSystemDialogBuilder(this)
                        .setTitle("更新公告")
                        .setMessage(notes)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        } catch (Throwable ignored) {
        }
    }
}
