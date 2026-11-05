package io.github.gravere.bybus.migration;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import io.github.gravere.bybus.migration.core.MigrationArchive;

/** A one-time, same-signed replacement for the old host. Its private data is never erased here. */
public final class LegacyMigrationActivity extends Activity {
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+.5f); }
    @Override public void onCreate(Bundle state) { super.onCreate(state); render(); }
    @Override protected void onResume() { super.onResume(); render(); }
    private void render() {
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL); page.setPadding(dp(28),dp(32),dp(28),dp(32));
        page.setBackgroundColor(Color.rgb(247,248,245));
        page.setOnApplyWindowInsetsListener((view,insets) -> {
            page.setPadding(dp(28)+insets.getSystemWindowInsetLeft(),dp(32)+insets.getSystemWindowInsetTop(),
                    dp(28)+insets.getSystemWindowInsetRight(),dp(32)+insets.getSystemWindowInsetBottom()); return insets;
        });
        ImageView icon=new ImageView(this); icon.setImageResource(R.drawable.app_icon);
        page.addView(icon,new LinearLayout.LayoutParams(dp(72),dp(72)));
        text(page,"BYBUS",32,true);
        boolean complete=getSharedPreferences("bybus-migration",MODE_PRIVATE).getBoolean("complete",false);
        text(page,complete?"数据已迁入新版":"旧数据已保留",23,true);
        text(page,complete?"请先确认新版 BYBUS 运行正常，再卸载这个旧版。之后可同时使用 limbusZhCN。"
                :"请保留这个旧版，从 GitHub 下载新版 BYBUS。在新版的“设置与工具 → 数据迁移”中，一键导入已有数据。资源无需更新到最新，旧数据不会自动删除。",16,false);
        boolean installed=false;
        try { installed=getPackageManager().checkSignatures(getPackageName(),MigrationArchive.TARGET)==PackageManager.SIGNATURE_MATCH; }
        catch (RuntimeException ignored) { }
        final boolean targetReady=installed;
        button(page,installed?"打开新版 BYBUS":"前往 GitHub 下载新版",() -> {
            Intent launch=targetReady?getPackageManager().getLaunchIntentForPackage(MigrationArchive.TARGET):null;
            if (launch!=null) launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (launch==null) launch=new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/gravere/BYBUS/releases/latest"));
            try { startActivity(launch); } catch (RuntimeException error) { Toast.makeText(this,"暂时无法打开，请从 GitHub 下载新版 BYBUS",Toast.LENGTH_LONG).show(); }
        });
        if (complete) button(page,"卸载旧版 BYBUS",() -> startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+getPackageName()))));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        setContentView(scroll);
    }
    private void text(LinearLayout page,String value,int size,boolean bold) {
        TextView label=new TextView(this); label.setText(value); label.setTextSize(size); label.setTextColor(Color.rgb(32,44,39));
        label.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL)); label.setLineSpacing(dp(5),1f);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(18); page.addView(label,params);
    }
    private void button(LinearLayout page,String title,Runnable action) {
        Button button=new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(16); button.setTextColor(Color.WHITE);
        GradientDrawable fill=new GradientDrawable(); fill.setColor(Color.rgb(37,75,64)); fill.setCornerRadius(dp(20)); button.setBackground(fill);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(56)); params.topMargin=dp(24); page.addView(button,params);
    }
}
