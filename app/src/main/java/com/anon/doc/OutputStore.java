package com.anon.doc;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Publishes finished PDFs to shared Downloads and lists generated files. */
final class OutputStore {
    static final String DOWNLOAD_FOLDER = Environment.DIRECTORY_DOWNLOADS + "/AnonDoc/";
    private static final String PREFS = "output_files";

    static final class Entry {
        final String name;
        final Uri uri;
        Entry(String name, Uri uri) { this.name = name; this.uri = uri; }
    }

    private OutputStore() {}

    static Uri publishToDownloads(Context context, File source) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("Selecciona el destino con Guardar como");
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("No se puede crear el archivo en Descargas");
        boolean success = false;
        try {
            copyTo(context, source, uri);
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, ready, null, null) != 1) {
                throw new IOException("No se pudo finalizar el archivo");
            }
            success = true;
            return uri;
        } finally {
            if (!success) context.getContentResolver().delete(uri, null, null);
        }
    }

    static void copyTo(Context context, File source, Uri destination) throws IOException {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("No se puede escribir en la carpeta elegida");
            byte[] bytes = new byte[8192];
            int read;
            while ((read = input.read(bytes)) != -1) output.write(bytes, 0, read);
        }
    }

    static List<Entry> list(Context context) throws IOException {
        List<Entry> files = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME};
            try (Cursor cursor = context.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.MIME_TYPE + "=? AND "
                            + MediaStore.MediaColumns.IS_PENDING + "=0",
                    new String[]{DOWNLOAD_FOLDER, "application/pdf"},
                    MediaStore.MediaColumns.DATE_ADDED + " DESC, "
                            + MediaStore.MediaColumns._ID + " DESC")) {
                if (cursor == null) throw new IOException("No se pueden listar los archivos");
                while (cursor.moveToNext()) {
                    files.add(new Entry(cursor.getString(1), ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))));
                }
            }
        }
        // Files explicitly saved with the Android document picker (older Android).
        try {
            JSONArray saved = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("saved", "[]"));
            for (int index = saved.length() - 1; index >= 0; index--) {
                JSONObject file = saved.getJSONObject(index);
                Uri uri = Uri.parse(file.getString("uri"));
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{"_display_name"}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        files.add(new Entry(cursor.getString(0), uri));
                    }
                } catch (RuntimeException inaccessible) {
                    // Deleted files or revoked grants are not offered as available.
                }
            }
        } catch (JSONException error) {
            throw new IOException("No se pudo leer la lista de archivos guardados", error);
        }
        return files;
    }

    static void rememberPickedFile(Context context, Uri uri, String name) throws IOException {
        try {
            JSONArray saved = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("saved", "[]"));
            JSONObject file = new JSONObject();
            file.put("uri", uri.toString());
            file.put("name", name);
            saved.put(file);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("saved", saved.toString()).apply();
        } catch (JSONException error) {
            throw new IOException("No se pudo registrar el archivo guardado", error);
        }
    }

    static boolean delete(Context context, Uri uri) {
        try { return context.getContentResolver().delete(uri, null, null) > 0; }
        catch (RuntimeException unavailable) { return false; }
    }

    static int deleteAll(Context context) {
        int deleted = 0;
        try {
            for (Entry entry : list(context)) if (delete(context, entry.uri)) deleted++;
        } catch (IOException ignored) { }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("saved").apply();
        return deleted;
    }
}
