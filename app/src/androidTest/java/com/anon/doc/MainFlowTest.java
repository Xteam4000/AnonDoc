package com.anon.doc;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.*;
import static androidx.test.espresso.intent.matcher.IntentMatchers.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class MainFlowTest {
    private Context context;
    private ActivityScenario<MainActivity> scenario;
    private Set<Uri> existingOutputs;
    private final Set<Uri> imageFixtures = new HashSet<>();

    @Before public void start() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 29);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PDFBoxResourceLoader.init(context);
        existingOutputs = outputUris();
        Intents.init();
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(
                new Instrumentation.ActivityResult(Activity.RESULT_OK, null));
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After public void finish() throws Exception {
        if (scenario != null) scenario.close();
        if (existingOutputs != null) {
            for (Uri uri : outputUris()) {
                if (!existingOutputs.contains(uri)) context.getContentResolver().delete(uri, null, null);
            }
        }
        for (Uri uri : imageFixtures) context.getContentResolver().delete(uri, null, null);
        if (existingOutputs != null) Intents.release();
    }

    private Set<Uri> outputUris() throws Exception {
        Set<Uri> uris = new HashSet<>();
        for (OutputStore.Entry file : OutputStore.list(context)) uris.add(file.uri);
        return uris;
    }

    private Bitmap scanBitmap() {
        Bitmap image = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(42);
        canvas.drawText("INFORME FICTICIO", 40, 80, paint);
        canvas.drawText("DNI: 12345678Z", 40, 160, paint);
        canvas.drawText("Nombre: JUAN PEREZ", 40, 240, paint);
        canvas.drawText("Correo: juan@example.es", 40, 320, paint);
        canvas.drawText("REFERENCIA RESERVADA", 40, 400, paint);
        return image;
    }

    private Uri pdfFixture(boolean scanned, boolean damaged) throws Exception {
        File source = File.createTempFile("fixture_", ".pdf", context.getCacheDir());
        try {
            if (damaged) {
                try (FileOutputStream out = new FileOutputStream(source)) {
                    out.write(new byte[]{1, 2, 3});
                }
            } else if (scanned) {
                Bitmap image = scanBitmap();
                PdfDocument pdf = new PdfDocument();
                try {
                    PdfDocument.Page page = pdf.startPage(
                            new PdfDocument.PageInfo.Builder(1000, 600, 1).create());
                    page.getCanvas().drawBitmap(image, 0, 0, null);
                    pdf.finishPage(page);
                    try (FileOutputStream out = new FileOutputStream(source)) { pdf.writeTo(out); }
                } finally { pdf.close(); image.recycle(); }
            } else {
                try (PDDocument pdf = new PDDocument()) {
                    PDPage page = new PDPage();
                    pdf.addPage(page);
                    try (PDPageContentStream out = new PDPageContentStream(pdf, page)) {
                        out.beginText();
                        out.setFont(PDType1Font.HELVETICA, 14);
                        out.newLineAtOffset(40, 740);
                        for (String line : new String[]{"INFORME FICTICIO", "DNI: 12345678Z",
                                "Nombre: JUAN PEREZ", "Correo: juan@example.es",
                                "REFERENCIA RESERVADA"}) {
                            out.showText(line);
                            out.newLineAtOffset(0, -35);
                        }
                        out.endText();
                    }
                    pdf.save(source);
                }
            }
            return OutputStore.publishToDownloads(context, source);
        } finally { source.delete(); }
    }

    private Uri imageFixture() throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "fixture_" + System.nanoTime() + ".png");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AnonDocTests/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        imageFixtures.add(uri);
        Bitmap image = scanBitmap();
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull(out);
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(uri, ready, null, null);
        return uri;
    }

    private void select(Uri uri) {
        // Only the external picker is stubbed: the app receives an actual stored file.
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                new Instrumentation.ActivityResult(Activity.RESULT_OK,
                        new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
        onView(withId(R.id.btnSelect)).perform(click());
        waitForWorker();
    }

    private void waitForWorker() {
        long end = SystemClock.uptimeMillis() + 60000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicBoolean idle = new AtomicBoolean();
            scenario.onActivity(activity -> idle.set(activity.findViewById(R.id.btnSelect).isEnabled()));
            if (idle.get()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }
            SystemClock.sleep(100);
        }
        fail("Document worker did not finish within 60 seconds");
    }

    private String review(String additional) {
        onView(withId(R.id.btnAnon)).perform(click());
        onView(isAssignableFrom(EditText.class)).perform(replaceText(additional), closeSoftKeyboard());
        onView(withText("Revisar resultado")).perform(click());
        AtomicReference<String> result = new AtomicReference<>();
        onView(isAssignableFrom(EditText.class)).check((view, error) -> {
            if (error != null) throw error;
            result.set(((EditText) view).getText().toString());
        });
        return result.get();
    }

    private OutputStore.Entry exportReviewed(Set<Uri> before, String edited) throws Exception {
        if (edited != null) onView(isAssignableFrom(EditText.class)).perform(
                replaceText(edited), closeSoftKeyboard());
        onView(withText("Exportar texto revisado")).perform(click());
        waitForWorker();
        onView(withId(R.id.txtStatus)).check(matches(withText(containsString("Descargas/AnonDoc"))));
        OutputStore.Entry saved = null;
        for (OutputStore.Entry file : OutputStore.list(context)) {
            if (!before.contains(file.uri)) {
                assertNull("Export should create exactly one new PDF", saved);
                saved = file;
            }
        }
        assertNotNull("No exported PDF found", saved);
        return saved;
    }

    private String savedText(Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(input);
            return PdfTextReader.read(input);
        }
    }

    private void assertHidden(String text) {
        assertFalse(text.contains("12345678Z"));
        assertFalse(text.contains("JUAN PEREZ"));
        assertFalse(text.contains("juan@example.es"));
    }

    @Test public void digitalPdfFlowsThroughReviewSaveListAndViewerIntent() throws Exception {
        select(pdfFixture(false, false));
        onView(withId(R.id.btnAnon)).check(matches(isEnabled()));
        String preview = review("REFERENCIA RESERVADA");
        assertHidden(preview);
        assertFalse(preview.contains("REFERENCIA RESERVADA"));
        assertTrue(preview.contains("[DATO]"));
        Set<Uri> before = outputUris();
        OutputStore.Entry output = exportReviewed(before, preview + "\nREVISION CONFIRMADA");
        String stored = savedText(output.uri);
        assertHidden(stored);
        assertTrue(stored.contains("REVISION CONFIRMADA"));
        onView(withId(R.id.btnViewFiles)).perform(click());
        waitForWorker();
        onView(withText(output.name)).perform(click());
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasType("application/pdf"), hasData(output.uri)));
        boolean grant = false;
        for (Intent intent : Intents.getIntents()) {
            if (Intent.ACTION_VIEW.equals(intent.getAction()) && output.uri.equals(intent.getData())) {
                grant = (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
            }
        }
        assertTrue("Viewer must receive temporary read access", grant);
    }

    @Test public void scannedPdfFlowsThroughOcrAnonymizationAndPublicExport() throws Exception {
        select(pdfFixture(true, false));
        String preview = review("");
        assertHidden(preview);
        assertTrue(preview.contains("[DNI]"));
        OutputStore.Entry output = exportReviewed(outputUris(), null);
        assertHidden(savedText(output.uri));
    }

    @Test public void imageFlowsThroughOcrAnonymizationAndPublicExport() throws Exception {
        select(imageFixture());
        String preview = review("");
        assertHidden(preview);
        assertTrue(preview.contains("[DNI]"));
        OutputStore.Entry output = exportReviewed(outputUris(), null);
        assertHidden(savedText(output.uri));
    }

    @Test public void cancellingReviewNeverExports() throws Exception {
        select(pdfFixture(false, false));
        Set<Uri> before = outputUris();
        review("");
        onView(withText("Cancelar")).perform(click());
        assertEquals(before, outputUris());
        onView(withId(R.id.btnAnon)).check(matches(isEnabled()));
    }

    @Test public void damagedNewDocumentCannotExportPreviouslyLoadedText() throws Exception {
        select(pdfFixture(false, false));
        onView(withId(R.id.btnAnon)).check(matches(isEnabled()));
        select(pdfFixture(false, true));
        onView(withId(R.id.btnAnon)).check(matches(not(isEnabled())));
        onView(withId(R.id.txtStatus)).check(matches(withText(containsString("No se pudo procesar"))));
    }

    @Test public void recreatingScreenClearsWorkButKeepsSavedFilesAccessible() throws Exception {
        select(pdfFixture(false, false));
        review("");
        OutputStore.Entry output = exportReviewed(outputUris(), null);
        scenario.recreate();
        onView(withId(R.id.btnAnon)).check(matches(not(isEnabled())));
        onView(withId(R.id.btnViewFiles)).perform(click());
        waitForWorker();
        onView(withText(output.name)).check(matches(isDisplayed()));
        onView(withText("Cerrar")).perform(click());
    }
}
