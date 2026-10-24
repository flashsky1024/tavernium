package io.github.flashsky1024.tavernium;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 APK 提供者：把下载到 {@code cacheDir/updates/} 的安装包以 {@code content://} 形式交给系统安装器。
 *
 * <p>Android 7.0 起禁止把 {@code file://} URI 传给别的应用（FileUriExposedException），
 * 而 FileProvider 需要 androidx 依赖。本工程坚持零第三方依赖，所以手写一个只读、
 * 不导出的 ContentProvider：只暴露 updates 目录下的单个文件名，且拒绝任何路径穿越。</p>
 */
public class ApkProvider extends ContentProvider {

    /** authority 后缀，与 AndroidManifest 中的 {@code ${applicationId}.updates} 对应。 */
    public static final String AUTHORITY_SUFFIX = ".updates";
    /** APK 存放目录名（相对 cacheDir）。 */
    public static final String DIR_NAME = "updates";
    private static final String MIME_APK = "application/vnd.android.package-archive";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        if (file == null || !file.exists()) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_APK;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("no such apk: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 只允许 updates 目录下的单层文件名，拒绝 {@code ..} 与子路径。 */
    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        if (getContext() == null) {
            return null;
        }
        return new File(new File(getContext().getCacheDir(), DIR_NAME), name);
    }
}
