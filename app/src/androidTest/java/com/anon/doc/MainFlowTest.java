package com.anon.doc;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import org.hamcrest.Matcher;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
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

    private Uri multiPagePdfFixture() throws Exception {
        File source = File.createTempFile("multipage_fixture_", ".pdf", context.getCacheDir());
        try {
            try (PDDocument pdf = new PDDocument()) {
                for (int number = 1; number <= 2; number++) {
                    PDPage page = new PDPage();
                    pdf.addPage(page);
                    try (PDPageContentStream out = new PDPageContentStream(pdf, page)) {
                        out.beginText();
                        out.setFont(PDType1Font.HELVETICA, 14);
                        out.newLineAtOffset(40, 740);
                        out.showText("PAGINA " + number + " - Nombre: JUAN PEREZ");
                        out.endText();
                    }
                }
                pdf.save(source);
            }
            return OutputStore.publishToDownloads(context, source);
        } finally { source.delete(); }
    }


    private String viewChain(View view) {
        StringBuilder out = new StringBuilder();
        android.view.ViewParent parent = view;
        int depth = 0;
        while (parent instanceof View && depth < 12) {
            View v = (View) parent;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            Rect visible = new Rect();
            boolean has = v.getGlobalVisibleRect(visible);
            out.append("\n#").append(depth).append(" ")
                    .append(v.getClass().getSimpleName())
                    .append(" size=").append(v.getWidth()).append("x").append(v.getHeight())
                    .append(" loc=").append(java.util.Arrays.toString(loc))
                    .append(" visible=").append(has ? visible.toShortString() : "none")
                    .append(" padding=")
                    .append(v.getPaddingLeft()).append(",")
                    .append(v.getPaddingTop()).append(",")
                    .append(v.getPaddingRight()).append(",")
                    .append(v.getPaddingBottom());
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                out.append(" clipChildren=").append(g.getClipChildren())
                        .append(" clipToPadding=").append(g.getClipToPadding());
            }
            parent = v.getParent();
            depth++;
        }
        return out.toString();
    }

    private ViewAction diagnosticClickAtLeast90() {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints() {
                return allOf(isEnabled(), isDisplayed());
            }

            @Override public String getDescription() {
                return "click view after verifying at least 90 percent is globally visible";
            }

            @Override public void perform(UiController uiController, View view) {
                Rect visible = new Rect();
                boolean hasVisibleRect = view.getGlobalVisibleRect(visible);
                int totalArea = Math.max(1, view.getWidth() * view.getHeight());
                int visibleArea = Math.max(0, visible.width()) * Math.max(0, visible.height());
                float fraction = (float) visibleArea / totalArea;
                assertTrue("Visible fraction=" + fraction
                                + " visibleRect=" + visible
                                + " size=" + view.getWidth() + "x" + view.getHeight()
                                + " location=" + java.util.Arrays.toString(location(view))
                                + viewChain(view),
                        hasVisibleRect && fraction >= 0.90f);
                assertTrue("performClick returned false", view.performClick());
                uiController.loopMainThreadUntilIdle();
            }
        };
    }

    private int[] location(View view) {
        int[] out = new int[2];
        view.getLocationOnScreen(out);
        return out;
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

    private String savedText(Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(input);
            return PdfTextReader.read(input);
        }
    }

    private void assertHidden(String text) {
        assertFalse(text.contains("12345678Z"));
        assertFalse(text.contains("JUAN PEREZ"));
        assertFalse(text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT)
                .contains("juan@example.es"));
    }

    @Test public void damagedNewDocumentCannotExportPreviouslyLoadedText() throws Exception {
        select(pdfFixture(false, false));
        onView(withId(R.id.btnAnon)).check(matches(isEnabled()));
        select(pdfFixture(false, true));
        onView(withId(R.id.btnAnon)).check(matches(not(isEnabled())));
        onView(withId(R.id.txtStatus)).check(matches(withText(containsString("No se pudo procesar"))));
    }

    private void waitForVisualText(String prefix) {
        long end=SystemClock.uptimeMillis()+60000;
        while(SystemClock.uptimeMillis()<end) {
            try {
                Matcher<View> expected = prefix.startsWith("Página ")
                        ? withText(prefix) : withText(containsString(prefix));
                onView(allOf(expected, isDisplayed())).check(matches(isDisplayed()));
                return;
            } catch(androidx.test.espresso.NoMatchingViewException | AssertionError notReady) {
                SystemClock.sleep(100);
            }
        }
        fail("Visual review did not reach: "+prefix);
    }

    @Test public void originalLayoutFlowsThroughVisualReviewSaveAndList() throws Exception {
        select(pdfFixture(false,false));
        Set<Uri> before=outputUris();
        onView(withId(R.id.btnAnon)).perform(click());
        waitForVisualText("Página 1 de 1");
        onView(withText("Editar manualmente")).perform(diagnosticClickAtLeast90());
        onView(withText("Tachar")).perform(click());
        onView(withContentDescription("Documento anonimizado ampliable")).perform(
            new androidx.test.espresso.action.GeneralSwipeAction(
                androidx.test.espresso.action.Swipe.SLOW,
                v->{int[] loc=new int[2]; v.getLocationOnScreen(loc); return new float[]{loc[0]+v.getWidth()*0.45f,loc[1]+v.getHeight()*0.55f};},
                v->{int[] loc=new int[2]; v.getLocationOnScreen(loc); return new float[]{loc[0]+v.getWidth()*0.60f,loc[1]+v.getHeight()*0.70f};},
                androidx.test.espresso.action.Press.FINGER));
        onView(withText("Borrar último")).perform(click());
        onView(withText("Completar anonimización")).perform(click());
        waitForVisualText("Documento definitivo preparado");
        onView(withText("Guardar en AnonDoc")).perform(click());
        waitForVisualText("Guardado en Descargas/AnonDoc");
        OutputStore.Entry output=null;
        for(OutputStore.Entry f:OutputStore.list(context)) if(!before.contains(f.uri)) {
            assertNull(output); output=f;
        }
        assertNotNull(output);
        assertTrue(savedText(output.uri).trim().isEmpty());
        String visible=PdfOcrReader.read(context,output.uri,null).text;
        assertHidden(visible);
        assertTrue(visible.contains("INFORME"));
        androidx.test.espresso.Espresso.pressBack();
        onView(withId(R.id.btnViewFiles)).perform(scrollTo(), click());
        waitForVisualText(output.name);
        onView(withText(output.name)).check(matches(isDisplayed()));
        androidx.test.espresso.Espresso.pressBack();
    }

    @Test public void cancellingVisualReviewDoesNotPublishAnything() throws Exception {
        select(pdfFixture(false,false));
        Set<Uri> before=outputUris();
        onView(withId(R.id.btnAnon)).perform(click());
        waitForVisualText("Página 1 de 1");
        androidx.test.espresso.Espresso.pressBack();
        assertEquals(before,outputUris());
        onView(withId(R.id.btnAnon)).check(matches(isEnabled()));
    }

    @Test public void everyVisualStageNavigatesAllPages() throws Exception {
        select(multiPagePdfFixture());
        onView(withId(R.id.btnAnon)).perform(click());
        waitForVisualText("Página 1 de 2");
        onView(withContentDescription("Página siguiente")).perform(click());
        onView(withText("Página 2 de 2")).check(matches(isDisplayed()));
        onView(withText("Editar manualmente")).perform(diagnosticClickAtLeast90());
        onView(withText("Página 2 de 2")).check(matches(isDisplayed()));
        onView(withContentDescription("Página anterior")).perform(click());
        onView(withText("Página 1 de 2")).check(matches(isDisplayed()));
        onView(withText("Completar anonimización")).perform(click());
        waitForVisualText("Documento definitivo preparado");
        onView(withText("Página 1 de 2")).check(matches(isDisplayed()));
        onView(withContentDescription("Página siguiente")).perform(click());
        onView(withText("Página 2 de 2")).check(matches(isDisplayed()));
        onView(withText("Descartar")).perform(click());
    }

    @Test public void pdfWithoutRecognizedTextStillAllowsVisualReview() throws Exception {
        File source=File.createTempFile("blank_fixture_",".pdf",context.getCacheDir());
        Uri uri;
        try {
            try(PDDocument pdf=new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(source); }
            uri=OutputStore.publishToDownloads(context,source);
        } finally { source.delete(); }
        select(uri);
        onView(withId(R.id.txtStatus)).check(matches(withText(containsString("revisa visualmente"))));
        onView(withId(R.id.btnAnon)).check(matches(isEnabled()));
        onView(withId(R.id.btnAnon)).perform(click());
        waitForVisualText("Página 1 de 1");
        onView(withText("Página 1 de 1")).check(matches(isDisplayed()));
        onView(withText(containsString("Sin texto OCR"))).check(matches(isDisplayed()));
        onView(withText("Está bien")).check(matches(isEnabled()));
        androidx.test.espresso.Espresso.pressBack();
    }
}
