package com.wuyi.kebiao;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/* JADX INFO: loaded from: classes.dex */
public class FeedbackUrlClick implements DialogInterface.OnClickListener {
    private Context ctx;

    public FeedbackUrlClick(Context context) {
        this.ctx = context;
    }

    @Override // android.content.DialogInterface.OnClickListener
    public void onClick(DialogInterface dialogInterface, int i) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://aokiisei.vxni.ink/"));
            this.ctx.startActivity(intent);
        } catch (Throwable th) {
            Toast.makeText(this.ctx, "无法打开浏览器，请手动访问 https://aokiisei.vxni.ink/", 1).show();
        }
    }
}
