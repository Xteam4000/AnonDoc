package com.anon.doc;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;

/** Extracts embedded text only. The caller owns and closes the input stream. */
final class PdfTextReader {
    private PdfTextReader() {}

    static String read(InputStream stream) throws IOException {
        try (PDDocument document = PDDocument.load(stream)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new IOException("El PDF no permite extraer su contenido");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }
}
