package io.github.gravere.bybus.installprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Button;
import java.io.File;
import java.io.FileNotFoundException;

/** Disposable external installer caller; never included in a production APK. */
public final class InstallProbe extends Activity {
    private static final Uri APK = Uri.parse("content://io.github.gravere.bybus.installprobe.apk/update");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getExternalFilesDir(null).mkdirs();
        Button install = new Button(this);
        install.setText("Install BYBUS");
        install.setOnClickListener(view -> {
            Intent request = new Intent(Intent.ACTION_VIEW).setDataAndType(APK, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            request.setClipData(ClipData.newRawUri("BYBUS", APK));
            startActivityForResult(request, 1);
        });
        setContentView(install);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        // Do not launch the returned intent: inspect the system installer's own Open behavior.
        Log.i("BYBUSInstallProbe", "result=" + result + " launchReturned=" +
                (data != null && Intent.ACTION_MAIN.equals(data.getAction()) && data.hasCategory(Intent.CATEGORY_LAUNCHER)));
    }

    public static final class ApkProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        private File checkedFile(Uri uri) {
            if (!APK.equals(uri)) throw new IllegalArgumentException("Unknown file");
            return new File(getContext().getExternalFilesDir(null), "update.apk");
        }
        @Override public String getType(Uri uri) { checkedFile(uri); return "application/vnd.android.package-archive"; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
            return ParcelFileDescriptor.open(checkedFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            File file = checkedFile(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "BYBUS.apk";
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            result.addRow(row);
            return result;
        }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
