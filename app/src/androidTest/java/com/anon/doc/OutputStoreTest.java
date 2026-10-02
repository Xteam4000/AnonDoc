package com.anon.doc;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import java.io.File;
import java.io.InputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class OutputStoreTest {
    private Context context;
    @Before public void initialize() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PDFBoxResourceLoader.init(context);
    }

    @Test public void savesCompletePdfToPublicDownloadsAndListsIt() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 29);
        File source = TestPdfFactory.export(context, "Documento revisado sin datos personales");
        Uri uri = null;
        try {
            uri = OutputStore.publishToDownloads(context, source);
            assertEquals("application/pdf", context.getContentResolver().getType(uri));
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
                assertNotNull(cursor);
                assertTrue(cursor.moveToFirst());
                assertEquals(OutputStore.DOWNLOAD_FOLDER, cursor.getString(0));
                assertEquals(0, cursor.getInt(1));
            }
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                assertNotNull(input);
                assertTrue(PdfTextReader.read(input).contains("Documento revisado"));
            }
            boolean listed = false;
            for (OutputStore.Entry file : OutputStore.list(context)) {
                if (file.uri.equals(uri)) {
                    listed = true;
                    assertEquals(source.getName(), file.name);
                }
            }
            assertTrue(listed);
            context.getContentResolver().delete(uri, null, null);
            for (OutputStore.Entry file : OutputStore.list(context)) assertNotEquals(uri, file.uri);
            uri = null;
        } finally {
            if (uri != null) context.getContentResolver().delete(uri, null, null);
            source.delete();
        }
    }

    @Test public void failedCopyDoesNotLeaveAPartialFileInDownloads() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 29);
        File source = File.createTempFile("anonimizado_missing_", ".pdf", context.getCacheDir());
        String name = source.getName();
        assertTrue(source.delete());
        try {
            OutputStore.publishToDownloads(context, source);
            fail("Missing source must not publish a file");
        } catch (java.io.IOException expected) {
            for (OutputStore.Entry file : OutputStore.list(context)) assertNotEquals(name, file.name);
        }
    }
}
