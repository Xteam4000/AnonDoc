package com.anon.doc;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import java.io.*;

/** Lightweight input validation. It never renders pages or runs OCR. */
final class DocumentValidator {
    private DocumentValidator() {}

    static void validatePdf(Context context, Uri uri) throws Exception {
        File copy = File.createTempFile("validate_", ".pdf", context.getCacheDir());
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("No se puede abrir el PDF");
                byte[] buffer = new byte[8192];
                long bytes = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelado");
                    bytes += read;
                    if (bytes > PdfOcrReader.MAX_BYTES) throw new IOException("El PDF supera el límite de 25 MB");
                    out.write(buffer, 0, read);
                }
            }
            try (PDDocument document = PDDocument.load(copy)) {
                if (!document.getCurrentAccessPermission().canExtractContent()) {
                    throw new IOException("El PDF no permite extraer su contenido");
                }
                int pages = document.getNumberOfPages();
                if (pages < 1 || pages > PdfOcrReader.MAX_PAGES) {
                    throw new IOException("Solo se admiten PDF de 1 a 30 páginas");
                }
            }
        } catch (OutOfMemoryError lowMemory) {
            throw new IOException("Memoria insuficiente para validar el PDF", lowMemory);
        } finally {
            if (!copy.delete()) copy.deleteOnExit();
        }
    }

    static void validateImage(Context context, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("No se puede abrir la imagen");
            BitmapFactory.decodeStream(in, null, options);
        }
        if (options.outWidth < 1 || options.outHeight < 1) {
            throw new IOException("La imagen no es válida o no puede decodificarse");
        }
    }
}
