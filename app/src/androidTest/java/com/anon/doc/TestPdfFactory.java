package com.anon.doc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test-only factory for temporary text PDFs used by instrumentation fixtures. */
final class TestPdfFactory {
    private TestPdfFactory() {}

    static File export(Context context, String text) throws IOException {
        File directory = context.getCacheDir();
        if (directory == null) throw new IOException("No se puede acceder al directorio de salida");
        File file = File.createTempFile("anonimizado_", ".pdf", directory);
        PdfDocument pdf = new PdfDocument();
        boolean success = false;
        try {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(12);
            int pageNumber = 1;
            PdfDocument.Page page = pdf.startPage(
                    new PdfDocument.PageInfo.Builder(595, 842, pageNumber).create());
            Canvas canvas = page.getCanvas();
            int y = 35;
            for (String paragraph : text.split("\\r?\\n", -1)) {
                String remaining = paragraph.replace('\t', ' ');
                do {
                    if (y > 800) {
                        pdf.finishPage(page);
                        page = pdf.startPage(new PdfDocument.PageInfo.Builder(
                                595, 842, ++pageNumber).create());
                        canvas = page.getCanvas();
                        y = 35;
                    }
                    int count = remaining.isEmpty() ? 0
                            : paint.breakText(remaining, true, 535, null);
                    count = Math.min(remaining.length(), Math.max(remaining.isEmpty() ? 0 : 1, count));
                    if (count < remaining.length() && count > 1
                            && Character.isHighSurrogate(remaining.charAt(count - 1))) count--;
                    if (count < remaining.length()) {
                        int space = remaining.lastIndexOf(' ', count - 1);
                        if (space > 0) count = space + 1;
                    }
                    canvas.drawText(remaining.substring(0, count), 30, y, paint);
                    y += 18;
                    remaining = remaining.substring(count);
                } while (!remaining.isEmpty());
            }
            pdf.finishPage(page);
            try (FileOutputStream output = new FileOutputStream(file)) {
                pdf.writeTo(output);
            }
            success = true;
            return file;
        } finally {
            pdf.close();
            if (!success && !file.delete()) file.deleteOnExit();
        }
    }
}
