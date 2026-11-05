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
        text(page,complete?"数据已导入独立版":"将数据带到独立版",23,true);
        text(page,complete?"确认独立版运行正常后，可以移除这个迁移助手，再安装 limbusZhCN。"
                :"安装独立版后，选择导入旧版数据。游戏资源通过本机传输，旧数据会一直保留到你自行移除这个助手。",16,false);
        boolean installed=false;
        try { installed=getPackageManager().checkSignatures(getPackageName(),MigrationArchive.TARGET)==PackageManager.SIGNATURE_MATCH; }
        catch (RuntimeException ignored) { }
        final boolean targetReady=installed;
        button(page,installed?"打开独立版并迁移":"下载独立版",() -> {
            Intent launch=targetReady?getPackageManager().getLaunchIntentForPackage(MigrationArchive.TARGET):null;
            if (launch!=null) launch.setAction(MigrationArchive.IMPORT_ACTION)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (launch==null) launch=new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/gravere/BYBUS/releases/latest/download/BYBUS-Android.apk"));
            try { startActivity(launch); } catch (RuntimeException error) { Toast.makeText(this,"暂时无法打开，请从 GitHub 下载独立版",Toast.LENGTH_LONG).show(); }
        });
        if (complete) button(page,"移除迁移助手",() -> startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+getPackageName()))));
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
