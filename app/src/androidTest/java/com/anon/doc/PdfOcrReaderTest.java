package com.anon.doc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfOcrReaderTest {
    private Context context;
    @Before public void initialize() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PDFBoxResourceLoader.init(context);
    }

    private Bitmap scannedText() {
        Bitmap bitmap = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(50);
        canvas.drawText("DOCUMENTO FICTICIO", 40, 80, paint);
        canvas.drawText("DNI: 12345678Z", 40, 160, paint);
        canvas.drawText("Nombre: JUAN PEREZ", 40, 240, paint);
        return bitmap;
    }

    @Test public void readsScannedPdfAndCleansPrivateWorkingCopy() throws Exception {
        File file = File.createTempFile("scan_test_", ".pdf", context.getCacheDir());
        Bitmap bitmap = scannedText();
        PdfDocument pdf = new PdfDocument();
        try {
            PdfDocument.Page page = pdf.startPage(
                    new PdfDocument.PageInfo.Builder(1000, 400, 1).create());
            page.getCanvas().drawBitmap(bitmap, 0, 0, null);
            pdf.finishPage(page);
            try (FileOutputStream stream = new FileOutputStream(file)) { pdf.writeTo(stream); }
            int before = workingCopies();
            PdfOcrReader.Result result = PdfOcrReader.read(context, Uri.fromFile(file), null);
            assertTrue(result.text.replaceAll("\\s", "").contains("12345678Z"));
            assertEquals(1, result.pages);
            assertEquals(0, result.emptyPages);
            assertEquals(before, workingCopies());
            assertFalse(TextAnonymizer.anonymize(result.text, "").contains("12345678Z"));
        } finally {
            pdf.close();
            bitmap.recycle();
            file.delete();
        }
    }

    private int workingCopies() {
        File[] files = context.getCacheDir().listFiles(
                (directory, name) -> name.startsWith("pdf_ocr_"));
        return files == null ? 0 : files.length;
    }

    @Test public void readsImageContentOnPageThatAlsoHasEmbeddedText() throws Exception {
        File file = File.createTempFile("mixed_test_", ".pdf", context.getCacheDir());
        Bitmap bitmap = scannedText();
        try {
            try (PDDocument pdf = new PDDocument()) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(40, 750);
                    content.showText("INFORME DE PRUEBA");
                    content.endText();
                    content.drawImage(LosslessFactory.createFromImage(pdf, bitmap), 40, 400, 500, 200);
                }
                pdf.save(file);
            }
            PdfOcrReader.Result result = PdfOcrReader.read(context, Uri.fromFile(file), null);
            assertTrue(result.text.contains("INFORME DE PRUEBA"));
            assertTrue(result.text.replaceAll("\\s", "").contains("12345678Z"));
        } finally {
            bitmap.recycle();
            file.delete();
        }
    }

    @Test public void rejectsTooManyPagesRatherThanReturningPartialText() throws Exception {
        File file = File.createTempFile("pages_test_", ".pdf", context.getCacheDir());
        try {
            try (PDDocument pdf = new PDDocument()) {
                for (int i = 0; i <= PdfOcrReader.MAX_PAGES; i++) pdf.addPage(new PDPage());
                pdf.save(file);
            }
            try {
                PdfOcrReader.read(context, Uri.fromFile(file), null);
                fail("Oversized page count must fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("30"));
            }
        } finally { file.delete(); }
    }

    @Test public void rejectsOversizedFileBeforeParsing() throws Exception {
        File file = File.createTempFile("size_test_", ".pdf", context.getCacheDir());
        try {
            try (RandomAccessFile data = new RandomAccessFile(file, "rw")) {
                data.setLength(PdfOcrReader.MAX_BYTES + 1);
            }
            try {
                PdfOcrReader.readFile(context, file, null);
                fail("Oversized file must fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("25 MB"));
            }
        } finally { file.delete(); }
    }

    @Test public void mergeKeepsOcrOnlyDataAndAvoidsIdenticalLines() {
        assertEquals("Cabecera\nDNI 12345678Z",
                PdfOcrReader.mergePage("Cabecera", "Cabecera\nDNI 12345678Z"));
    }

    @Test public void exportsUniqueFilesAndWrapsLongReviewedText() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1500; i++) text.append("Texto revisado sin datos personales. ");
        File first = TestPdfFactory.export(context, text.toString());
        File second = null;
        try {
            second = TestPdfFactory.export(context, "Otra salida");
            assertNotEquals(first.getName(), second.getName());
            try (PDDocument pdf = PDDocument.load(first)) {
                assertTrue(pdf.getNumberOfPages() > 1);
            }
        } finally {
            first.delete();
            if (second != null) second.delete();
        }
    }
}
