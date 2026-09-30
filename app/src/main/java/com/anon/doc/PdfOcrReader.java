package com.anon.doc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Worker-thread reader: combines embedded text with local OCR of every page. */
final class PdfOcrReader {
    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final int MAX_PAGES = 30;
    private static final int MAX_EDGE = 1600;

    interface Progress { void onPage(int page, int total); }

    static final class Result {
        final String text;
        final int pages;
        final int emptyPages;
        Result(String text, int pages, int emptyPages) {
            this.text = text;
            this.pages = pages;
            this.emptyPages = emptyPages;
        }
    }

    private PdfOcrReader() {}

    static Result read(Context context, Uri uri, Progress progress) throws Exception {
        File copy = File.createTempFile("pdf_ocr_", ".pdf", context.getCacheDir());
        try {
            try (InputStream source = context.getContentResolver().openInputStream(uri);
                 FileOutputStream target = new FileOutputStream(copy)) {
                if (source == null) throw new IOException("No se puede abrir el PDF");
                byte[] buffer = new byte[8192];
                long bytes = 0;
                int count;
                while ((count = source.read(buffer)) != -1) {
                    checkInterrupted();
                    bytes += count;
                    if (bytes > MAX_BYTES) throw new IOException("El PDF supera el límite de 25 MB");
                    target.write(buffer, 0, count);
                }
            }
            return readFile(context, copy, progress);
        } finally {
            // Originals are never modified. Delete the private working copy.
            if (!copy.delete()) copy.deleteOnExit();
        }
    }

    static Result readFile(Context context, File file, Progress progress) throws Exception {
        if (file.length() > MAX_BYTES) throw new IOException("El PDF supera el límite de 25 MB");
        try (PDDocument document = PDDocument.load(file)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new IOException("El PDF no permite extraer su contenido");
            }
            int pages = document.getNumberOfPages();
            if (pages == 0 || pages > MAX_PAGES) {
                throw new IOException("Solo se admiten PDF de 1 a 30 páginas; no se ha procesado parcialmente");
            }
            // PdfRenderer requires a seekable descriptor; the private copy supplies one.
            try (ParcelFileDescriptor descriptor =
                         ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(descriptor)) {
                if (renderer.getPageCount() != pages) throw new IOException("Número de páginas incoherente");
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                StringBuilder output = new StringBuilder();
                int emptyPages = 0;
                for (int index = 0; index < pages; index++) {
                    checkInterrupted();
                    if (progress != null) progress.onPage(index + 1, pages);
                    stripper.setStartPage(index + 1);
                    stripper.setEndPage(index + 1);
                    String embedded = stripper.getText(document);
                    Bitmap bitmap;
                    try (PdfRenderer.Page page = renderer.openPage(index)) {
                        int width = page.getWidth();
                        int height = page.getHeight();
                        if (width < 1 || height < 1) throw new IOException("Dimensiones de página inválidas");
                        float scale = Math.min(3f, (float) MAX_EDGE / Math.max(width, height));
                        bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } catch (RuntimeException error) {
                            bitmap.recycle();
                            throw error;
                        }
                    }
                    String recognized = recognize(bitmap);
                    String pageText = mergePage(embedded, recognized);
                    if (pageText.trim().isEmpty()) {
                        emptyPages++;
                    } else {
                        if (output.length() > 0) output.append("\n\n");
                        output.append(pageText);
                    }
                    checkInterrupted();
                }
                return new Result(output.toString(), pages, emptyPages);
            }
        }
    }

    private static String recognize(Bitmap bitmap) throws Exception {
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Task<Text> task;
        try {
            task = recognizer.process(InputImage.fromBitmap(bitmap, 0));
        } catch (RuntimeException error) {
            recognizer.close();
            bitmap.recycle();
            throw error;
        }
        try {
            return Tasks.await(task).getText();
        } finally {
            // Even when interrupted, retain the bitmap until ML Kit has finished with it.
            task.addOnCompleteListener(Runnable::run, completed -> {
                recognizer.close();
                bitmap.recycle();
            });
        }
    }

    static String mergePage(String embedded, String recognized) {
        String nativeText = embedded == null ? "" : embedded.trim();
        String ocrText = recognized == null ? "" : recognized.trim();
        if (nativeText.isEmpty()) return ocrText;
        if (ocrText.isEmpty()) return nativeText;
        StringBuilder combined = new StringBuilder(nativeText);
        Set<String> lines = new HashSet<>();
        String nativeNormalized = normalize(nativeText);
        for (String line : nativeText.split("\\r?\\n")) lines.add(normalize(line));
        for (String line : ocrText.split("\\r?\\n")) {
            String normalized = normalize(line);
            if (!normalized.isEmpty() && !lines.contains(normalized)
                    && !nativeNormalized.contains(normalized)) {
                combined.append("\n").append(line);
                lines.add(normalized);
            }
        }
        return combined.toString();
    }

    private static String normalize(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Procesamiento cancelado");
    }
}
