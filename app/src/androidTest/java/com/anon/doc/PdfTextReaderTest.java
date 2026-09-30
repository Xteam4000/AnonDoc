package com.anon.doc;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfTextReaderTest {
    @Before
    public void initializeResources() {
        PDFBoxResourceLoader.init(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    private byte[] document(boolean withText, boolean protectedPdf) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            if (withText) {
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(40, 750);
                    content.showText("Documento ficticio: DNI 12345678Z");
                    content.endText();
                }
                PDPage second = new PDPage();
                pdf.addPage(second);
                try (PDPageContentStream content = new PDPageContentStream(pdf, second)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(40, 750);
                    content.showText("Segunda pagina: prueba sin datos reales");
                    content.endText();
                }
            }
            if (protectedPdf) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(
                        "owner-test", "reader-test", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                pdf.protect(policy);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pdf.save(bytes);
            return bytes.toByteArray();
        }
    }

    @Test
    public void extractsTextFromBothPages() throws IOException {
        String text = PdfTextReader.read(new ByteArrayInputStream(document(true, false)));
        assertTrue(text.contains("12345678Z"));
        assertTrue(text.contains("Segunda pagina"));
    }

    @Test
    public void pdfWithoutEmbeddedTextReturnsEmptyText() throws IOException {
        String text = PdfTextReader.read(new ByteArrayInputStream(document(false, false)));
        assertTrue(text.trim().isEmpty());
    }

    @Test
    public void damagedPdfFailsExplicitly() {
        try {
            PdfTextReader.read(new ByteArrayInputStream(new byte[]{1, 2, 3}));
            fail("A damaged PDF must not produce a successful result");
        } catch (IOException expected) {
            // No successful extraction.
        }
    }

    @Test
    public void passwordProtectedPdfFailsWithoutPassword() throws IOException {
        byte[] bytes = document(true, true);
        try {
            PdfTextReader.read(new ByteArrayInputStream(bytes));
            fail("Password-protected PDF must not silently return text");
        } catch (IOException expected) {
            // Password entry is outside this change's scope.
        }
    }
}
