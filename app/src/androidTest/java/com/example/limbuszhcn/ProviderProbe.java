package com.example.limbuszhcn;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** No game, account or personal data; checks the real platform's calling-package validation. */
public class ProviderProbe extends ContentProvider {
    public static final class Denied extends ProviderProbe { }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        result.putString("caller", getCallingPackage());
        result.putString("method", method);
        result.putString("arg", arg);
        return result;
    }
    @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return "application/x-bybus-fixture"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
