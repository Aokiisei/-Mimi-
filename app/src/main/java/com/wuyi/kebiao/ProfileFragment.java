package com.wuyi.kebiao;

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
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public class ProfileFragment extends Fragment implements View.OnClickListener {
    private static final String CREATOR_NAME = "星辰的网站";
    private static final String FEEDBACK_EMAIL = "lin_648@qq.com";
    private OkHttpClient client;
    private TextView tvAnnouncementStatus;
    private TextView tvVersionName;

    private static void bindMoreRow(View view) {
        ((ViewGroup) ((ViewGroup) ((ViewGroup) view).getChildAt(0)).getChildAt(1)).getChildAt(8).setOnClickListener(new MoreClickListener());
    }

    private void checkAnnouncement() {
        new Thread(new AnnouncementFetcher(this)).start();
    }

    private void checkVersionUpdate() {
        UpdateChecker.checkManual(requireContext());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyAnnouncement(String str) {
        try {
            if (isAdded()) {
                showAnnouncementDialog("公告", str);
            }
        } catch (Throwable th) {
        }
    }

    private void openCreatorWebsite() {
    }

    private void showFeedbackDialog() {
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
                new AlertDialog.Builder(context).setTitle("问题反馈").setView(linearLayout).setPositiveButton("提交", new FeedbackSubmitListener(context, editText)).setNegativeButton("取消", (DialogInterface.OnClickListener) null).show();
            } catch (Throwable th) {
                CrashLogger.log(context, "showFeedbackDialog", th);
                Toast.makeText(context, th.toString(), 1).show();
            }
        }
    }

    private void showSponsorDialog() {
        FragmentActivity fragmentActivityRequireActivity = requireActivity();
        ImageView imageView = new ImageView(fragmentActivityRequireActivity);
        imageView.setImageResource(R.drawable.wechat_pay);
        imageView.setAdjustViewBounds(true);
        int i = (int) (fragmentActivityRequireActivity.getResources().getDisplayMetrics().density * 16);
        imageView.setPadding(i, i, i, i);
        new AlertDialog.Builder(fragmentActivityRequireActivity).setTitle("赞助支持").setMessage("微信扫码赞助，感谢你的支持").setView(imageView).setPositiveButton("完成", (DialogInterface.OnClickListener) null).show();
    }

    @Override // android.view.View.OnClickListener
    public void onClick(View view) {
        if (view.getId() == R.id.layout_sponsor) {
            showSponsorDialog();
        }
    }

    @Override // androidx.fragment.app.Fragment
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

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        try {
            this.client = new OkHttpClient();
            TextView textView = (TextView) view.findViewById(R.id.tv_version_name);
            this.tvVersionName = textView;
            TextView textView2 = (TextView) view.findViewById(R.id.tv_creator);
            this.tvAnnouncementStatus = (TextView) view.findViewById(R.id.tv_announcement_status);
            if (textView != null) {
                textView.setText("V" + getCurrentVersionName());
            }
            if (textView2 != null) {
                textView2.setText("Aokiisei");
            }
            View viewFindViewById = view.findViewById(R.id.layout_announcement);
            if (viewFindViewById != null) {
                viewFindViewById.setOnClickListener(new View.OnClickListener() { // from class: com.wuyi.kebiao.ProfileFragment$$ExternalSyntheticLambda1
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view2) {
                        m334lambda$onViewCreated$0$comwuyikebiaoProfileFragment(view2);
                    }
                });
            }
            View viewFindViewById2 = view.findViewById(R.id.layout_version);
            if (viewFindViewById2 != null) {
                viewFindViewById2.setOnClickListener(new View.OnClickListener() { // from class: com.wuyi.kebiao.ProfileFragment$$ExternalSyntheticLambda2
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view2) {
                        m335lambda$onViewCreated$1$comwuyikebiaoProfileFragment(view2);
                    }
                });
            }
            View viewFindViewById3 = view.findViewById(R.id.layout_feedback);
            if (viewFindViewById3 != null) {
                viewFindViewById3.setOnClickListener(new View.OnClickListener() { // from class: com.wuyi.kebiao.ProfileFragment$$ExternalSyntheticLambda3
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view2) {
                        m336lambda$onViewCreated$2$comwuyikebiaoProfileFragment(view2);
                    }
                });
            }
            View viewFindViewById4 = view.findViewById(R.id.layout_sponsor);
            if (viewFindViewById4 != null) {
                viewFindViewById4.setOnClickListener(this);
            }
            View viewFindViewById5 = view.findViewById(R.id.layout_creator);
            if (viewFindViewById5 != null) {
                viewFindViewById5.setOnClickListener(new View.OnClickListener() { // from class: com.wuyi.kebiao.ProfileFragment$$ExternalSyntheticLambda4
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view2) {
                        m337lambda$onViewCreated$3$comwuyikebiaoProfileFragment(view2);
                    }
                });
                ThemeH.applyProfile(view);
                bindMoreRow(view);
            }
        } catch (Throwable th) {
            Context contextRequireContext = requireContext();
            CrashLogger.log(contextRequireContext, "ProfileFragment.onViewCreated", th);
            Toast.makeText(contextRequireContext, th.toString(), 1).show();
        }
    }

    /* JADX INFO: renamed from: lambda$onViewCreated$0$com-wuyi-kebiao-ProfileFragment, reason: not valid java name */
    /* synthetic */ void m334lambda$onViewCreated$0$comwuyikebiaoProfileFragment(View view) {
        checkAnnouncement();
    }

    /* JADX INFO: renamed from: lambda$onViewCreated$1$com-wuyi-kebiao-ProfileFragment, reason: not valid java name */
    /* synthetic */ void m335lambda$onViewCreated$1$comwuyikebiaoProfileFragment(View view) {
        checkVersionUpdate();
    }

    /* JADX INFO: renamed from: lambda$onViewCreated$2$com-wuyi-kebiao-ProfileFragment, reason: not valid java name */
    /* synthetic */ void m336lambda$onViewCreated$2$comwuyikebiaoProfileFragment(View view) {
        showFeedbackDialog();
    }

    /* JADX INFO: renamed from: lambda$onViewCreated$3$com-wuyi-kebiao-ProfileFragment, reason: not valid java name */
    /* synthetic */ void m337lambda$onViewCreated$3$comwuyikebiaoProfileFragment(View view) {
        openCreatorWebsite();
    }

    private String getCurrentVersionName() {
        try {
            return requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "1.0";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getCurrentVersionCode() {
        try {
            return requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 1;
        }
    }

    /* JADX INFO: renamed from: com.wuyi.kebiao.ProfileFragment$1, reason: invalid class name */
    class AnonymousClass1 implements Callback {
        AnonymousClass1() {
        }

        @Override // okhttp3.Callback
        public void onFailure(Call call, IOException iOException) {
            ProfileFragment.this.requireActivity().runOnUiThread(new Runnable() { // from class: com.wuyi.kebiao.ProfileFragment$1$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    m339lambda$onFailure$0$comwuyikebiaoProfileFragment$1();
                }
            });
        }

        /* JADX INFO: renamed from: lambda$onFailure$0$com-wuyi-kebiao-ProfileFragment$1, reason: not valid java name */
        /* synthetic */ void m339lambda$onFailure$0$comwuyikebiaoProfileFragment$1() {
            ProfileFragment.this.tvAnnouncementStatus.setText("加载失败");
            ProfileFragment.this.showErrorDialog("公告加载失败\n请检查网络");
        }

        @Override // okhttp3.Callback
        public void onResponse(Call call, Response response) throws IOException {
            try {
                JSONObject jSONObject = new JSONObject(response.body().string());
                final String strOptString = jSONObject.optString("title", "公告");
                final String strOptString2 = jSONObject.optString("content", "暂无公告内容");
                ProfileFragment.this.requireActivity().runOnUiThread(new Runnable() { // from class: com.wuyi.kebiao.ProfileFragment$1$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        m340lambda$onResponse$1$comwuyikebiaoProfileFragment$1(strOptString, strOptString2);
                    }
                });
            } catch (Exception unused) {
                ProfileFragment.this.requireActivity().runOnUiThread(new Runnable() { // from class: com.wuyi.kebiao.ProfileFragment$1$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        m341lambda$onResponse$2$comwuyikebiaoProfileFragment$1();
                    }
                });
            }
        }

        /* JADX INFO: renamed from: lambda$onResponse$1$com-wuyi-kebiao-ProfileFragment$1, reason: not valid java name */
        /* synthetic */ void m340lambda$onResponse$1$comwuyikebiaoProfileFragment$1(String str, String str2) {
            ProfileFragment.this.tvAnnouncementStatus.setText("已更新");
            ProfileFragment.this.showAnnouncementDialog(str, str2);
        }

        /* JADX INFO: renamed from: lambda$onResponse$2$com-wuyi-kebiao-ProfileFragment$1, reason: not valid java name */
        /* synthetic */ void m341lambda$onResponse$2$comwuyikebiaoProfileFragment$1() {
            ProfileFragment.this.tvAnnouncementStatus.setText("解析失败");
            ProfileFragment.this.showErrorDialog("公告解析失败");
        }
    }

    /* JADX INFO: renamed from: com.wuyi.kebiao.ProfileFragment$2, reason: invalid class name */
    class AnonymousClass2 implements Callback {
        AnonymousClass2() {
        }

        @Override // okhttp3.Callback
        public void onFailure(Call call, IOException iOException) {
            ProfileFragment.this.requireActivity().runOnUiThread(new Runnable() { // from class: com.wuyi.kebiao.ProfileFragment$2$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    m342lambda$onFailure$0$comwuyikebiaoProfileFragment$2();
                }
            });
        }

        /* JADX INFO: renamed from: lambda$onFailure$0$com-wuyi-kebiao-ProfileFragment$2, reason: not valid java name */
        /* synthetic */ void m342lambda$onFailure$0$comwuyikebiaoProfileFragment$2() {
            Toast.makeText(ProfileFragment.this.requireContext(), "版本检查失败\n请检查网络", 0).show();
        }

        @Override // okhttp3.Callback
        public void onResponse(Call call, Response response) throws IOException {
            try {
                JSONObject jSONObject = new JSONObject(response.body().string());
                int i = jSONObject.getInt("versionCode");
                final String string = jSONObject.getString("versionName");
                final String strOptString = jSONObject.optString("updateUrl", HttpUrl.FRAGMENT_ENCODE_SET);
                int currentVersionCode = ProfileFragment.this.getCurrentVersionCode();
                ProfileFragment profileFragment = ProfileFragment.this;
                if (i > currentVersionCode) {
                    profileFragment.requireActivity().runOnUiThread(new Runnable() { // from class: com.wuyi.kebiao.ProfileFragment$2$$ExternalSyntheticLambda1
                        @Override // java.lang.Runnable
                        public final void run() {
                            m343lambda$onResponse$1$comwuyikebiaoProfileFragment$2(string, strOptString);
                        }
                    });
                } else {
                    profileFragment.requireActivity().runOnUiThread(new Runnable() { // from class: com.wuyi.kebiao.ProfileFragment$2$$ExternalSyntheticLambda2
                        @Override // java.lang.Runnable
                        public final void run() {
                            m344lambda$onResponse$2$comwuyikebiaoProfileFragment$2();
                        }
                    });
                }
            } catch (Exception unused) {
                ProfileFragment.this.requireActivity().runOnUiThread(new Runnable() { // from class: com.wuyi.kebiao.ProfileFragment$2$$ExternalSyntheticLambda3
                    @Override // java.lang.Runnable
                    public final void run() {
                        m345lambda$onResponse$3$comwuyikebiaoProfileFragment$2();
                    }
                });
            }
        }

        /* JADX INFO: renamed from: lambda$onResponse$1$com-wuyi-kebiao-ProfileFragment$2, reason: not valid java name */
        /* synthetic */ void m343lambda$onResponse$1$comwuyikebiaoProfileFragment$2(String str, String str2) {
            ProfileFragment.this.showUpdateDialog(str, str2);
        }

        /* JADX INFO: renamed from: lambda$onResponse$2$com-wuyi-kebiao-ProfileFragment$2, reason: not valid java name */
        /* synthetic */ void m344lambda$onResponse$2$comwuyikebiaoProfileFragment$2() {
            Toast.makeText(ProfileFragment.this.requireContext(), "当前已是最新版本", 0).show();
        }

        /* JADX INFO: renamed from: lambda$onResponse$3$com-wuyi-kebiao-ProfileFragment$2, reason: not valid java name */
        /* synthetic */ void m345lambda$onResponse$3$comwuyikebiaoProfileFragment$2() {
            Toast.makeText(ProfileFragment.this.requireContext(), "版本检查失败", 0).show();
        }
    }

    private void sendFeedbackEmail() {
        Intent intent = new Intent("android.intent.action.SENDTO");
        intent.setData(Uri.parse("mailto:lin_648@qq.com"));
        intent.putExtra("android.intent.extra.SUBJECT", "武夷学院课表 App 反馈");
        intent.putExtra("android.intent.extra.TEXT", "\n\n\n\n---\nApp版本: V" + getCurrentVersionName() + "\n设备: " + Build.MODEL);
        try {
            startActivity(Intent.createChooser(intent, "选择邮箱应用"));
        } catch (Exception unused) {
            Toast.makeText(requireContext(), "未找到邮箱应用，请手动发送邮件至 lin_648@qq.com", 1).show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showAnnouncementDialog(String str, String str2) {
        new AlertDialog.Builder(requireContext()).setTitle(str).setMessage(str2).setPositiveButton("确定", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showUpdateDialog(String str, final String str2) {
        new AlertDialog.Builder(requireContext()).setTitle("发现新版本 v" + str).setMessage("是否前往下载更新？").setPositiveButton("去更新", new DialogInterface.OnClickListener() { // from class: com.wuyi.kebiao.ProfileFragment$$ExternalSyntheticLambda0
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                m338lambda$showUpdateDialog$4$comwuyikebiaoProfileFragment(str2, dialogInterface, i);
            }
        }).setNegativeButton("稍后", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: renamed from: lambda$showUpdateDialog$4$com-wuyi-kebiao-ProfileFragment, reason: not valid java name */
    /* synthetic */ void m338lambda$showUpdateDialog$4$comwuyikebiaoProfileFragment(String str, DialogInterface dialogInterface, int i) {
        if (!str.isEmpty()) {
            startActivity(new Intent("android.intent.action.VIEW", Uri.parse(str)));
        } else {
            Toast.makeText(requireContext(), "暂无下载链接，请稍后再试", 0).show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showErrorDialog(String str) {
        new AlertDialog.Builder(requireContext()).setTitle("提示").setMessage(str).setPositiveButton("确定", (DialogInterface.OnClickListener) null).show();
    }
}
