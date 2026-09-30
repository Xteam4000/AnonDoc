package com.anon.doc;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VisualPdfTest {
    Context context;
    @Before public void init() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PDFBoxResourceLoader.init(context);
    }
    private File fixture() throws Exception {
        File file=File.createTempFile("visual_fixture_", ".pdf", context.getCacheDir());
        PdfDocument pdf=new PdfDocument(); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        try {
            for(int i=0;i<2;i++) {
                PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(i==0?600:800,i==0?800:600,i+1).create());
                Canvas c=page.getCanvas(); c.drawColor(Color.WHITE);
                paint.setColor(Color.BLUE); c.drawRect(400,30,500,100,paint); // Unrelated original logo.
                paint.setColor(Color.BLACK); paint.setTextSize(24);
                c.drawText("INFORME DE PRUEBA",40,70,paint);
                c.drawText("DNI: 12345678Z",40,140,paint);
                c.drawText("Correo: juan@example.es",40,210,paint);
                paint.setColor(Color.RED); c.drawRect(50,400,250,470,paint); // Signature/photo region, manually hidden.
                pdf.finishPage(page);
            }
            try(FileOutputStream out=new FileOutputStream(file)) { pdf.writeTo(out); }
        } finally { pdf.close(); }
        return file;
    }
    @Test public void preservesPageLayoutAndLogoButDestroysMaskedPixelsAndOriginalObjects() throws Exception {
        File source=fixture(), output=null; VisualPdf.Session session=null;
        try {
            session=VisualPdf.prepare(context,Uri.fromFile(source),"",null);
            assertEquals(2,session.pages.size());
            for(VisualPdf.Page p:session.pages) {
                assertFalse(p.emptyOcr); assertTrue("OCR must propose sensitive regions",p.automatic>0);
                float sx=(float)p.width/p.pointsWidth,sy=(float)p.height/p.pointsHeight;
                p.masks.add(new RectF(45*sx,395*sy,255*sx,475*sy)); p.reviewed=true;
            }
            output=VisualPdf.export(context,session);
            String visible=PdfOcrReader.readFile(context,output,null).text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            assertFalse(visible.contains("12345678z"));
            assertFalse(visible.contains("juan@example.es"));
            try(PDDocument pdf=PDDocument.load(output)) {
                assertEquals(2,pdf.getNumberOfPages());
                assertTrue("No recoverable original text layer",new PDFTextStripper().getText(pdf).trim().isEmpty());
                assertNull(pdf.getDocumentCatalog().getNames());
                assertNull(pdf.getDocumentCatalog().getAcroForm());
                for(int i=0;i<2;i++) {
                    PDPage p=pdf.getPage(i); VisualPdf.Page reviewed=session.pages.get(i);
                    assertEquals(reviewed.pointsWidth,p.getMediaBox().getWidth(),0.01);
                    assertEquals(reviewed.pointsHeight,p.getMediaBox().getHeight(),0.01);
                    assertTrue(p.getAnnotations().isEmpty());
                    int images=0;
                    for(COSName name:p.getResources().getXObjectNames()) {
                        assertTrue(p.getResources().getXObject(name) instanceof PDImageXObject);
                        Bitmap bitmap=((PDImageXObject)p.getResources().getXObject(name)).getImage(); images++;
                        try {
                            float sx=(float)bitmap.getWidth()/reviewed.pointsWidth,sy=(float)bitmap.getHeight()/reviewed.pointsHeight;
                            assertEquals(Color.BLUE,bitmap.getPixel(Math.round(450*sx),Math.round(60*sy)));
                            assertEquals(Color.BLACK,bitmap.getPixel(Math.round(100*sx),Math.round(430*sy)));
                            for(RectF mask:reviewed.masks) assertEquals(Color.BLACK,bitmap.getPixel((int)mask.centerX(),(int)mask.centerY()));
                        } finally { bitmap.recycle(); }
                    }
                    assertEquals(1,images);
                }
            }
            assertTrue("Original remains readable",source.length()>0);
            File dir=session.directory; session.close(); assertFalse(dir.exists()); session=null;
        } finally { source.delete(); if(output!=null) output.delete(); if(session!=null) session.close(); }
    }
    @Test public void refusesExportUntilEveryPageIsConfirmed() throws Exception {
        File source=fixture(); VisualPdf.Session session=null;
        try {
            session=VisualPdf.prepare(context,Uri.fromFile(source),"",null);
            session.pages.get(0).reviewed=true;
            try { VisualPdf.export(context,session); fail("Unreviewed page must block export"); }
            catch(IOException expected) { assertTrue(expected.getMessage().contains("todas")); }
        } finally { source.delete(); if(session!=null) session.close(); }
    }
    @Test public void malformedPdfLeavesNoWorkingCopies() throws Exception {
        File file=File.createTempFile("broken_visual_", ".pdf",context.getCacheDir());
        int before=countDirectories();
        try {
            try { VisualPdf.prepare(context,Uri.fromFile(file),"",null); fail("Malformed source accepted"); }
            catch(IOException expected) { assertEquals(before,countDirectories()); }
        } finally { file.delete(); }
    }
    int countDirectories() {
        File[] dirs=context.getCacheDir().listFiles(f->f.isDirectory() && f.getName().startsWith("visual_"));
        return dirs==null?0:dirs.length;
    }
}
