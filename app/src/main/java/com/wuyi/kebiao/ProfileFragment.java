package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「我的」页面。
 * 负责公告展示、版本更新检查、意见反馈与赞助支持。
 */

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import okhttp3.OkHttpClient;

public class ProfileFragment extends Fragment {
    // 开发者反馈邮箱
    private static final String FEEDBACK_EMAIL = "lin_648@qq.com";
    // 彩蛋：连续点击「开发者」右侧蓝色名字的次数
    private int creatorClickCount = 0;
    private static final int EGG_CLICK_TARGET = 10;
    private OkHttpClient client;
    private TextView tvAnnouncementStatus;
    private TextView tvVersionName;

    private void bindMoreRow(View view) {
        View moreRow = view.findViewById(R.id.layout_more);
        if (moreRow != null) {
            moreRow.setOnClickListener(new MoreClickListener(this));
        }
    }

    private void checkAnnouncement() {
        new Thread(new AnnouncementFetcher(this)).start();
    }

    private void checkVersionUpdate() {
        UpdateChecker.checkManual(requireContext());
    }

    public void notifyAnnouncement(String str) {
        try {
            if (isAdded()) {
                showAnnouncementDialog("公告", str);
            }
        } catch (Throwable th) {
        }
    }

    // 开发者个人网站（原方法为空，导致按钮点了没反应）
    private static final String CREATOR_WEBSITE = "https://aokiisei.vxni.ink";

    private void openCreatorWebsite() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CREATOR_WEBSITE)));
        } catch (Throwable th) {
            Toast.makeText(requireContext(), "未找到可打开网页的应用", Toast.LENGTH_SHORT).show();
        }
    }

    // 通过系统邮件客户端向开发者邮箱发送反馈
    private void sendFeedbackEmail() {
        Intent intent = new Intent("android.intent.action.SENDTO");
        intent.setData(Uri.parse("mailto:" + FEEDBACK_EMAIL));
        intent.putExtra("android.intent.extra.SUBJECT", "Mimi课表 反馈");
        intent.putExtra("android.intent.extra.TEXT", "\n\n\n\n---\nApp版本: V" + getCurrentVersionName() + "\n设备: " + Build.MODEL);
        try {
            startActivity(Intent.createChooser(intent, "选择邮箱应用"));
        } catch (Exception unused) {
            Toast.makeText(requireContext(), "未找到邮箱应用，请手动发送邮件至 " + FEEDBACK_EMAIL, 1).show();
        }
    }

    void showFeedbackDialog() {
        Context context = getContext();
        if (context != null) {
            try {
                EditText editText = new EditText(context);
                editText.setHint("请描述遇到的问题或建议…");
                editText.setMinLines(5);
                editText.setGravity(0);
                editText.setInputType(131073);
                LinearLayout linearLayout = new LinearLayout(context);
                linearLayout.setOrientation(1);
                float f = context.getResources().getDisplayMetrics().density;
                int i = (int) ((20.0f * f) + 0.5f);
                int i2 = (int) ((8.0f * f) + 0.5f);
                linearLayout.setPadding(i, i2, i, i2);
                linearLayout.addView(editText);
                new MimiDialogBuilder(context).setTitle("问题反馈").setView(linearLayout).setPositiveButton("提交", new FeedbackSubmitListener(context, editText)).setNeutralButton("邮件反馈", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        sendFeedbackEmail();
                    }
                }).setNegativeButton("取消", (DialogInterface.OnClickListener) null).show();
            } catch (Throwable th) {
                CrashLogger.log(context, "showFeedbackDialog", th);
                Toast.makeText(context, th.toString(), 1).show();
            }
        }
    }

    void showSponsorDialog() {
        if (!isAdded()) {
            return;
        }
        FragmentActivity fragmentActivityRequireActivity = requireActivity();
        ImageView imageView = new ImageView(fragmentActivityRequireActivity);
        imageView.setImageResource(R.drawable.wechat_pay);
        imageView.setAdjustViewBounds(true);
        int i = (int) (fragmentActivityRequireActivity.getResources().getDisplayMetrics().density * 16);
        imageView.setPadding(i, i, i, i);
        new MimiDialogBuilder(fragmentActivityRequireActivity).setTitle("赞助支持").setMessage("微信扫码赞助，感谢你的支持").setView(imageView).setPositiveButton("完成", (DialogInterface.OnClickListener) null).show();
    }

    @Override
    public View onCreateView(LayoutInflater layoutInflater, ViewGroup viewGroup, Bundle bundle) {
        try {
            return layoutInflater.inflate(R.layout.fragment_profile, viewGroup, false);
        } catch (Throwable th) {
            Context context = getContext();
            if (context != null) {
                return new View(layoutInflater.getContext());
            }
            CrashLogger.log(context, "ProfileFragment.onCreateView", th);
            TextView textView = new TextView(context);
            textView.setText("我的页面加载失败，请反馈开发者");
            return textView;
        }
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        try {
            TextView pageTitle = (TextView) view.findViewById(R.id.tv_page_title);
            if (pageTitle != null && getContext() != null) {
                pageTitle.setTypeface(android.graphics.Typeface.createFromAsset(getContext().getAssets(), "fonts/mi-serif.otf"));
            }
            // 原生页面所有文本统一衬线字体
            ThemeH.applySerifFont(view);
            this.client = new OkHttpClient();
            TextView textView = (TextView) view.findViewById(R.id.tv_version_name);
            this.tvVersionName = textView;
            TextView textView2 = (TextView) view.findViewById(R.id.tv_creator);
            this.tvAnnouncementStatus = (TextView) view.findViewById(R.id.tv_announcement_status);
            if (textView != null) {
                textView.setText("V" + getCurrentVersionName());
                textView.setTypeface(ThemeH.serif(getContext()), android.graphics.Typeface.NORMAL);
                textView.getPaint().setTextSkewX(0f);
                textView.getPaint().setFakeBoldText(true);
            }
            if (textView2 != null) {
                textView2.setText("Aokiisei");
            }
            if (this.tvAnnouncementStatus != null) {
                this.tvAnnouncementStatus.setTypeface(ThemeH.serif(getContext()), android.graphics.Typeface.NORMAL);
                this.tvAnnouncementStatus.getPaint().setTextSkewX(0f);
                this.tvAnnouncementStatus.getPaint().setFakeBoldText(true);
            }
            View viewFindViewById = view.findViewById(R.id.layout_announcement);
            if (viewFindViewById != null) {
                viewFindViewById.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public final void onClick(View view2) {
                        onAnnouncementClicked(view2);
                    }
                });
            }
            View viewFindViewById2 = view.findViewById(R.id.layout_version);
            if (viewFindViewById2 != null) {
                viewFindViewById2.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public final void onClick(View view2) {
                        onVersionClicked(view2);
                    }
                });
            }
            View viewFindViewByIdThanks = view.findViewById(R.id.layout_thanks);
            if (viewFindViewByIdThanks != null) {
                viewFindViewByIdThanks.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public final void onClick(View view2) {
                        showThanksDialog();
                    }
                });
            }
            View viewFindViewById5 = view.findViewById(R.id.layout_creator);
            if (viewFindViewById5 != null) {
                viewFindViewById5.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public final void onClick(View view2) {
                        onCreatorClicked(view2);
                    }
                });
                ThemeH.applyProfile(view);
                // 主题着色完成后再补一次字体，确保次要文字不会被主题样式改回斜体
                ThemeH.applySerifFont(view);
                bindMoreRow(view);
            }
        } catch (Throwable th) {
            Context contextRequireContext = requireContext();
            CrashLogger.log(contextRequireContext, "ProfileFragment.onViewCreated", th);
            Toast.makeText(contextRequireContext, th.toString(), 1).show();
        }
    }

    void onAnnouncementClicked(View view) {
        checkAnnouncement();
    }

    void onVersionClicked(View view) {
        checkVersionUpdate();
    }

    // 鸣谢弹窗：列出本项目用到的开源代码
    private void showThanksDialog() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        new MimiDialogBuilder(context)
                .setTitle("鸣谢")
                .setMessage("感谢以下开源项目与代码：\n\n"
                        + "· 课表查询与连接教务处：GitHub「Horizon UI」的开源代码")
                .setPositiveButton("知道了", (DialogInterface.OnClickListener) null)
                .show();
    }

    void onCreatorClicked(View view) {
        // 彩蛋：连续点击「开发者」右侧蓝色名字 10 次，弹出隐藏彩蛋
        creatorClickCount++;
        if (creatorClickCount >= EGG_CLICK_TARGET) {
            creatorClickCount = 0;
            showEasterEgg();
            return;
        }
        if (creatorClickCount >= 3) {
            Toast.makeText(requireContext(), "再点 " + (EGG_CLICK_TARGET - creatorClickCount) + " 次有惊喜", Toast.LENGTH_SHORT).show();
        }
    }

    // 隐藏彩蛋：奶茶图片 + 警告文案
    private void showEasterEgg() {
        try {
            Context context = requireContext();
            float density = context.getResources().getDisplayMetrics().density;
            int pad = (int) (16 * density + 0.5f);
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad, pad, 0);
            ImageView imageView = new ImageView(context);
            imageView.setImageResource(R.drawable.easter_egg);
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(imageView, new LinearLayout.LayoutParams(-1, (int) (200 * density + 0.5f)));
            TextView warn = new TextView(context);
            warn.setText("⚠️ 警告\n\n"
                    + "系统检测到您连续点击了 10 次，本次操作已被记录。\n\n"
                    + "隐藏彩蛋已解锁：犒劳自己一杯吧");
            warn.setTextSize(13.0f);
            warn.setTextColor(-6710887);
            warn.setLineSpacing(0.0f, 1.25f);
            LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(-1, -2);
            warnLp.topMargin = (int) (6 * density + 0.5f);
            box.addView(warn, warnLp);
            new MimiDialogBuilder(context)
                    .setTitle("🥚 你发现了隐藏彩蛋")
                    .setView(box)
                    .setPositiveButton("我现在就去", (DialogInterface.OnClickListener) null)
                    .setNegativeButton("我的个人网站", new DialogInterface.OnClickListener() {
                        @Override
                        public final void onClick(DialogInterface dialogInterface, int i) {
                            openCreatorWebsite();
                        }
                    })
                    .show();
        } catch (Throwable th) {
            CrashLogger.log(requireContext(), "ProfileFragment.showEasterEgg", th);
        }
    }

    private String getCurrentVersionName() {
        try {
            return requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "1.0";
        }
    }

    public int getCurrentVersionCode() {
        try {
            return requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 1;
        }
    }

    public void showAnnouncementDialog(String str, String str2) {
        new MimiDialogBuilder(requireContext()).setTitle(str).setMessage(str2).setPositiveButton("确定", (DialogInterface.OnClickListener) null).show();
    }

    public void showUpdateDialog(String str, final String str2) {
        new MimiDialogBuilder(requireContext()).setTitle("发现新版本 v" + str).setMessage("是否前往下载更新？").setPositiveButton("去更新", new DialogInterface.OnClickListener() {
            @Override
            public final void onClick(DialogInterface dialogInterface, int i) {
                openUpdateUrl(str2, dialogInterface, i);
            }
        }).setNegativeButton("稍后", (DialogInterface.OnClickListener) null).show();
    }

    void openUpdateUrl(String str, DialogInterface dialogInterface, int i) {
        if (!str.isEmpty()) {
            startActivity(new Intent("android.intent.action.VIEW", Uri.parse(str)));
        } else {
            Toast.makeText(requireContext(), "暂无下载链接，请稍后再试", 0).show();
        }
    }

    public void showErrorDialog(String str) {
        new MimiDialogBuilder(requireContext()).setTitle("提示").setMessage(str).setPositiveButton("确定", (DialogInterface.OnClickListener) null).show();
    }
}
