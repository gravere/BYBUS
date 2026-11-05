package io.github.gravere.bybus.migration;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import io.github.gravere.bybus.migration.core.MigrationArchive;

/** Only the new, same-signed BYBUS can read. No arbitrary paths, account APIs, or delete operations. */
public final class LegacyMigrationProvider extends ContentProvider {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean streaming=new java.util.concurrent.atomic.AtomicBoolean();
    @Override public boolean onCreate() { return true; }
    private Path root() { return new File(getContext().getApplicationInfo().dataDir).toPath(); }
    private void verifyCaller() {
        Context context=getContext(); int caller=Binder.getCallingUid();
        try {
            int target=context.getPackageManager().getApplicationInfo(MigrationArchive.TARGET,0).uid;
            if (caller!=target || context.getPackageManager().checkSignatures(android.os.Process.myUid(),caller)!=PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("Migration caller not authorized");
        } catch (PackageManager.NameNotFoundException absent) { throw new SecurityException("Migration target absent"); }
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        verifyCaller(); Bundle result=new Bundle();
        try {
            if ("summary".equals(method)) {
                MigrationArchive.Snapshot snapshot=MigrationArchive.snapshot(root());
                result.putLong("bytes",snapshot.bytes); result.putInt("files",snapshot.entries.size()); result.putInt("schema",1);
            } else if ("complete".equals(method)) {
                getContext().getSharedPreferences("bybus-migration",Context.MODE_PRIVATE).edit().putBoolean("complete",true).commit();
                result.putBoolean("ok",true);
            } else throw new IllegalArgumentException("Unsupported migration request");
            return result;
        } catch (IOException error) { throw new IllegalStateException("无法读取旧版迁移清单",error); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        verifyCaller();
        boolean inventory="/inventory".equals(uri.getPath());
        if (!"r".equals(mode) || !(inventory || "/archive".equals(uri.getPath()))) throw new FileNotFoundException("Read-only migration endpoint");
        if (!inventory && !streaming.compareAndSet(false,true)) throw new FileNotFoundException("Migration already active");
        try {
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();
            worker.execute(() -> {
                try (OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    MigrationArchive.Snapshot snapshot=MigrationArchive.snapshot(root());
                    if (inventory) MigrationArchive.writeInventory(snapshot,output);
                    else MigrationArchive.write(root(),snapshot,output);
                } catch (IOException error) {
                    try { pipe[1].closeWithError("Migration interrupted; original data retained"); } catch(IOException ignored) { }
                } finally { if (!inventory) streaming.set(false); }
            });
            return pipe[0];
        } catch (IOException|RuntimeException error) {
            if (!inventory) streaming.set(false); throw new FileNotFoundException("Unable to open migration stream");
        }
    }
    @Override public String getType(Uri uri) { verifyCaller(); return "application/octet-stream"; }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
}
