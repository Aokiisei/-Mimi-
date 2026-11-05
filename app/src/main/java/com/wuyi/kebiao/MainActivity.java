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
import com.wuyi.kebiao.reminder.CourseReminderScheduler;

public class MainActivity extends AppCompatActivity {
    private static final int[] TAB_IDS = {
            R.id.navigation_home,
            R.id.navigation_schedule,
            R.id.navigation_assistant,
            R.id.navigation_grade,
            R.id.navigation_profile
    };

    public ViewPager2 pager;
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
        initCourseReminders();
        showChangelogIfNeeded();
    }

    /** 启动时恢复课表提醒的闹钟；通知权限在「我的 → 更多 → 通知设置」中按需申请 */
    private void initCourseReminders() {
        try {
            CourseReminderScheduler.reschedule(this);
        } catch (Throwable ignored) {
        }
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
                String notes = "V3.3 更新日志（3.2 → 3.3）\n\n"
                        + "本次重点：修复晚上 19:00 的课程不显示，小助手新增校园地图标注。\n\n"
                        + "【课表】晚课修复\n"
                        + "· 第九、十节（19:00-20:40）的课程现在能正常显示在「晚七」行\n"
                        + "· 课表解析改为按节次编号识别，兼容「第九、十节」「第9-10节」「晚上」等不同写法\n"
                        + "· 教务课表分成多张表格时自动合并，不再整段漏抓\n"
                        + "· 课程卡片的「晚七」别名、点击时间弹窗与日视图排序同步修正\n\n"
                        + "【小助手 · 校园地图标注】\n"
                        + "· 新增校园地图：接入你自己的高德地图 Key（Web端 JS API），打开即定位到武夷学院\n"
                        + "· 点地图任意位置即可标注，写下「这里是什么」，标注会同步给所有同学\n"
                        + "· 点地图上的标记可查看地点名称、补充说明与添加者，支持拖动微调位置\n"
                        + "· 自己加的标注可随时删除；觉得别人标错了可以举报，超过 5 票自动删除（每人只计一票）\n"
                        + "· Key 只保存在本机，随时可在页面右上角更换或清除";
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
