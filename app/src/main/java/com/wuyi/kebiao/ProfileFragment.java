package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 「我的」页面。
 * 负责公告展示、版本更新检查、意见反馈与赞助支持。
 */

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import java.io.InputStream;
import java.io.OutputStream;
import okhttp3.OkHttpClient;

public class ProfileFragment extends Fragment {
    // 开发者反馈邮箱
    private static final String FEEDBACK_EMAIL = "lin_648@qq.com";
    // QQ 交流群
    private static final String QQ_GROUP_NUMBER = "961401372";
    private static final String QQ_GROUP_NAME = "Mimi课表交流群";
    private static final int RC_SAVE_QQ_QR = 7501;
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
                showAnnouncementDialog("更新公告", str);
            }
        } catch (Throwable th) {
        }
    }

    // 开发者个人网站（原方法为空，导致按钮点了没反应）
    private static final String CREATOR_WEBSITE = "https://aokiisei.xyz";

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

    // ------------------------------------------------------------ QQ 交流群
    void showQqGroupDialog() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int padH = (int) (24 * density + 0.5f);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(padH, (int) (8 * density + 0.5f), padH, 0);

        ImageView qr = new ImageView(context);
        qr.setImageResource(R.drawable.qq_group);
        qr.setAdjustViewBounds(true);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                saveQqQr();
                return true;
            }
        });
        box.addView(qr, new LinearLayout.LayoutParams((int) (220 * density + 0.5f), -2));

        TextView number = new TextView(context);
        number.setText("群号 961401372");
        number.setTextSize(15.0f);
        number.setTextColor(0xFF141413);
        number.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(-2, -2);
        numberLp.topMargin = (int) (14 * density + 0.5f);
        box.addView(number, numberLp);

        TextView tip = new TextView(context);
        tip.setText("扫二维码进群 · 长按图片可保存到相册");
        tip.setTextSize(12.0f);
        tip.setTextColor(0xFF87867F);
        tip.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-2, -2);
        tipLp.topMargin = (int) (6 * density + 0.5f);
        box.addView(tip, tipLp);

        new MimiDialogBuilder(context)
                .setTitle(QQ_GROUP_NAME)
                .setView(box)
                .setNeutralButton("复制群号", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        copyQqGroupNumber();
                    }
                })
                .setPositiveButton("完成", (DialogInterface.OnClickListener) null)
                .show();
    }

    private void copyQqGroupNumber() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("QQ群号", QQ_GROUP_NUMBER));
                Toast.makeText(context, "群号已复制：" + QQ_GROUP_NUMBER, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable th) {
            Toast.makeText(context, "复制失败，请手动记下群号 " + QQ_GROUP_NUMBER, Toast.LENGTH_LONG).show();
        }
    }

    // 长按图片保存：Android 10+ 直接写入相册；旧系统走系统保存对话框（无需存储权限）
    private void saveQqQr() {
        final Context context = getContext();
        final FragmentActivity host = getActivity();
        if (context == null || host == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        Toast.makeText(context, "正在保存图片…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Mimi课表交流群.jpg");
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/Mimi课表");
                        Uri uri = app.getContentResolver().insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) {
                            throw new IllegalStateException("无法创建相册文件");
                        }
                        OutputStream out = app.getContentResolver().openOutputStream(uri);
                        if (out == null) {
                            throw new IllegalStateException("无法写入相册");
                        }
                        copyQqQr(app, out);
                        out.close();
                        toastOnUi(app, "已保存到相册：Pictures/Mimi课表");
                    } catch (Throwable th) {
                        toastOnUi(app, "保存失败：" + th.getMessage());
                    }
                    return;
                }
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("image/jpeg");
                            intent.putExtra(Intent.EXTRA_TITLE, "Mimi课表交流群.jpg");
                            startActivityForResult(intent, RC_SAVE_QQ_QR);
                        } catch (Throwable th) {
                            Toast.makeText(app, "保存失败：" + th.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void copyQqQr(Context context, OutputStream out) throws Exception {
        InputStream in = context.getResources().openRawResource(R.drawable.qq_group);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            in.close();
        }
    }

    private void toastOnUi(final Context context, final String text) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_SAVE_QQ_QR || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream out = app.getContentResolver().openOutputStream(uri);
                    if (out == null) {
                        throw new IllegalStateException("无法写入文件");
                    }
                    copyQqQr(app, out);
                    out.close();
                    toastOnUi(app, "图片已保存");
                } catch (Throwable th) {
                    toastOnUi(app, "保存失败：" + th.getMessage());
                }
            }
        }).start();
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
            View viewFindViewByIdQq = view.findViewById(R.id.layout_qq_group);
            if (viewFindViewByIdQq != null) {
                viewFindViewByIdQq.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public final void onClick(View view2) {
                        showQqGroupDialog();
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
    void showThanksDialog() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        new MimiDialogBuilder(context)
                .setTitle("鸣谢")
                .setMessage("感谢以下开源项目与代码：\n\n"
                        + "· 课表查询与连接教务处：GitHub「Horizon UI」的开源代码\n\n"
                        + "· 快递身份码（淘宝 / 拼多多 / 京东 / 菜鸟）：GitHub 用户「xianhe51920」的开源项目 HandyCode（MIT 协议）\n"
                        + "   https://github.com/xianhe51920/handycode")
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
        new MimiDialogBuilder(requireContext()).setTitle("发现新版本 v" + str)
                .setMessage("可直接在应用内下载安装，也可用浏览器下载。")
                .setPositiveButton("在应用内更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                        if (str2.isEmpty()) {
                            Toast.makeText(requireContext(), "暂无下载链接，请稍后再试", 0).show();
                            return;
                        }
                        UpdateChecker.startDownload(requireContext(), str2);
                    }
                })
                .setNeutralButton("去浏览器", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                        openUpdateUrl(str2, dialogInterface, i);
                    }
                })
                .setNegativeButton("取消", (DialogInterface.OnClickListener) null).show();
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
