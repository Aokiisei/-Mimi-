package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 弹出「使用说明」对话框，说明各按钮与三个勾选框的组合逻辑。
 */

import android.content.DialogInterface;
import android.view.View;
import androidx.appcompat.app.AlertDialog;

public class UsageGuideClickListener implements View.OnClickListener {
    @Override
    public void onClick(View view) {
        try {
            new MimiDialogBuilder(view.getContext()).setTitle("使用说明").setMessage("【首页按钮】\n今天：查看今日课程\n明天：查看明日课程\n本周：查看本周完整课表（周一至周日）\n选周：输入周数查看指定周课表\n更新：重新登录教务系统刷新课表\n\n【三勾选框逻辑】记住密码＝总开关，不勾选时后两项自动失效\n\n① 记住密码√ 自动登录√ 课表记忆√\n   → 显示缓存课表，并后台自动更新\n② 记住密码√ 自动登录√ 课表记忆×\n   → 不显示缓存，自动登录获取最新课表\n③ 记住密码√ 自动登录× 课表记忆√\n   → 显示缓存课表，不自动登录\n④ 记住密码√ 自动登录× 课表记忆×\n   → 显示登录页（账号密码已填好），不自动登录\n⑤ 记住密码×（后两项置灰失效）\n   → 空登录页，不填充、不登录、不显示缓存\n\n【返回主界面】\n只回到登录页：绝不自动登录、不加载缓存；\n勾了记住密码则保留已填账号，没勾则清空。").setPositiveButton("知道了", (DialogInterface.OnClickListener) null).show();
        } catch (Throwable th) {
        }
    }
}
