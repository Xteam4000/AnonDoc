package com.anon.doc;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.exifinterface.media.ExifInterface;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import java.io.*;
import java.util.*;

/** Creates a new image-only PDF. Original PDF objects never enter the output. */
final class VisualPdf {
    interface Progress { void page(int current, int total); }
    static final class Page {
        final File image;
        final int width, height, pointsWidth, pointsHeight;
        final List<RectF> masks = new ArrayList<>();
        final int automatic;
        final boolean emptyOcr;
        boolean reviewed;
        Page(File image, int w, int h, int pw, int ph, List<RectF> boxes, boolean empty) {
            this.image=image; width=w; height=h; pointsWidth=pw; pointsHeight=ph;
            masks.addAll(boxes); automatic=boxes.size(); emptyOcr=empty;
        }
        Bitmap bitmap() throws IOException {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = true;
            Bitmap b = BitmapFactory.decodeFile(image.getAbsolutePath(), options);
            if(b==null) throw new IOException("No se puede leer la página");
            return b;
        }
    }
    static final class Session implements AutoCloseable {
        final File directory;
        final List<Page> pages=new ArrayList<>();
        Session(File directory) { this.directory=directory; }
        @Override public void close() {
            File[] files=directory.listFiles();
            if(files!=null) for(File f:files) f.delete();
            directory.delete();
        }
    }

    static Session prepare(Context context, Uri uri, String additional, Progress progress) throws Exception {
        File directory=File.createTempFile("visual_", "", context.getCacheDir());
        if(!directory.delete() || !directory.mkdir()) throw new IOException("No se puede preparar la revisión");
        Session session=new Session(directory);
        boolean success=false;
        String mime=context.getContentResolver().getType(uri);
        String path=uri.getPath()==null?"":uri.getPath().toLowerCase(Locale.ROOT);
        boolean imageSource=(mime!=null && mime.toLowerCase(Locale.ROOT).startsWith("image/"))
                || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                || path.endsWith(".webp") || path.endsWith(".heic") || path.endsWith(".heif");
        if(imageSource) {
            try { prepareImage(context,uri,additional,session,progress); success=true; return session; }
            catch(OutOfMemoryError lowMemory) { throw new IOException("Memoria insuficiente para conservar la imagen",lowMemory); }
            finally { if(!success) session.close(); }
        }
        File source=new File(directory,"source.pdf");
        try {
            try(InputStream in=context.getContentResolver().openInputStream(uri);
                OutputStream out=new FileOutputStream(source)) {
                if(in==null) throw new IOException("No se puede abrir el PDF");
                byte[] buffer=new byte[8192]; int n; long bytes=0;
                while((n=in.read(buffer))!=-1) {
                    interrupted(); bytes+=n;
                    if(bytes>PdfOcrReader.MAX_BYTES) throw new IOException("El PDF supera 25 MB");
                    out.write(buffer,0,n);
                }
            }
            try(PDDocument doc=PDDocument.load(source)) {
                if(!doc.getCurrentAccessPermission().canExtractContent())
                    throw new IOException("El PDF no permite extraer contenido");
            }
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer renderer=new PdfRenderer(fd)) {
                int count=renderer.getPageCount(); long pixels=0;
                if(count<1 || count>PdfOcrReader.MAX_PAGES) throw new IOException("Solo se admiten de 1 a 30 páginas");
                for(int i=0;i<count;i++) {
                    interrupted(); if(progress!=null) progress.page(i+1,count);
                    Bitmap bitmap=null;
                    try(PdfRenderer.Page page=renderer.openPage(i)) {
                        int pw=page.getWidth(),ph=page.getHeight();
                        if(pw<1 || ph<1) throw new IOException("Dimensiones inválidas");
                        float scale=Math.min(3f,2000f/Math.max(pw,ph));
                        int w=Math.max(1,Math.round(pw*scale)), h=Math.max(1,Math.round(ph*scale));
                        pixels+=(long)w*h;
                        if(pixels>24000000L) throw new IOException("El PDF supera el límite de resolución total; no se exportará parcialmente");
                        bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        Text recognized=recognize(bitmap);
                        List<RectF> boxes=detectBoxes(recognized,additional,w,h);
                        File image=new File(directory,"page_"+i+".png");
                        try(OutputStream out=new FileOutputStream(image)) {
                            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("No se puede preparar la página");
                        }
                        session.pages.add(new Page(image,w,h,pw,ph,boxes,recognized.getText().trim().isEmpty()));
                    } finally { if(bitmap!=null) bitmap.recycle(); }
                }
            }
            source.delete(); success=true; return session;
        } catch(OutOfMemoryError lowMemory) {
            throw new IOException("Memoria insuficiente para conservar el diseño; no se ha exportado",lowMemory);
        } finally { if(!success) session.close(); }
    }

    private static void prepareImage(Context context,Uri uri,String additional,Session session,Progress progress) throws Exception {
        if(progress!=null) progress.page(1,1);
        Bitmap bitmap=decodeOrientedImage(context,uri);
        try {
            if(bitmap==null) throw new IOException("No se puede leer la imagen");
            long pixels=(long)bitmap.getWidth()*bitmap.getHeight();
            if(pixels>24000000L) {
                float scale=(float)Math.sqrt(24000000d/pixels);
                Bitmap reduced=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),
                        Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
                if(reduced!=bitmap) { bitmap.recycle(); bitmap=reduced; }
            }
            Text recognized=recognize(bitmap);
            List<RectF> boxes=detectBoxes(recognized,additional,bitmap.getWidth(),bitmap.getHeight());
            File image=new File(session.directory,"page_0.png");
            try(OutputStream out=new FileOutputStream(image)) {
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("No se puede preparar la imagen");
            }
            // Use a normal PDF scale while preserving the exact aspect ratio.
            int pw=612, ph=Math.max(1,Math.round(612f*bitmap.getHeight()/bitmap.getWidth()));
            session.pages.add(new Page(image,bitmap.getWidth(),bitmap.getHeight(),pw,ph,boxes,
                    recognized.getText().trim().isEmpty()));
        } finally { if(bitmap!=null && !bitmap.isRecycled()) bitmap.recycle(); }
    }

    private static Bitmap decodeOrientedImage(Context context,Uri uri) throws Exception {
        Bitmap bitmap;
        try(InputStream in=context.getContentResolver().openInputStream(uri)) {
            if(in==null) throw new IOException("No se puede abrir la imagen");
            bitmap=BitmapFactory.decodeStream(in);
        }
        if(bitmap==null) return null;
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream in=context.getContentResolver().openInputStream(uri)) {
            if(in!=null) orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
        } catch(IOException ignored) {}
        Matrix matrix=new Matrix();
        switch(orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1,1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1,-1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1,1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1,1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: return bitmap;
        }
        Bitmap oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
        if(oriented!=bitmap) bitmap.recycle();
        return oriented;
    }

    private static List<RectF> detectBoxes(Text recognized,String additional,int w,int h) throws IOException {
        List<Text.Line> allLines=new ArrayList<>();
        for(Text.TextBlock block:recognized.getTextBlocks()) allLines.addAll(block.getLines());
        List<String> values=new ArrayList<>();
        for(Text.Line line:allLines) values.add(line.getText());
        boolean[] sensitive=TextAnonymizer.sensitiveLines(values,additional);
        List<RectF> boxes=new ArrayList<>();
        for(int i=0;i<allLines.size();i++) if(sensitive[i]) addBox(boxes,allLines.get(i).getBoundingBox(),w,h);
        return boxes;
    }
    private static void addBox(List<RectF> boxes,Rect r,int w,int h) throws IOException {
        if(r==null || r.isEmpty()) throw new IOException("Dato detectado sin posición; revisa el original");
        RectF padded=new RectF(Math.max(0,r.left-5),Math.max(0,r.top-5),Math.min(w,r.right+5),Math.min(h,r.bottom+5));
        if(padded.isEmpty()) throw new IOException("Posición OCR inválida");
        boxes.add(padded);
    }
    private static Text recognize(Bitmap bitmap) throws Exception {
        TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Task<Text> task=null;
        try {
            task=recognizer.process(InputImage.fromBitmap(bitmap,0));
            // Finish the OCR before releasing its bitmap, even if cancelled.
            for(;;) try { return Tasks.await(task); }
            catch(InterruptedException cancelled) {
                while(!task.isComplete()) { try { Thread.sleep(20); } catch(InterruptedException ignored) {} }
                Thread.currentThread().interrupt(); throw cancelled;
            }
        } finally { recognizer.close(); }
    }
    static void paintMasks(Canvas canvas,List<RectF> masks) {
        Paint paint=new Paint(); paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.FILL);
        for(RectF r:masks) canvas.drawRect((float)Math.floor(r.left),(float)Math.floor(r.top),
                (float)Math.ceil(r.right),(float)Math.ceil(r.bottom),paint);
    }
    static File export(Context context,Session session) throws Exception {
        for(Page page:session.pages) if(!page.reviewed) throw new IOException("Revisa y confirma todas las páginas");
        File file=File.createTempFile("anonimizado_visual_", ".pdf",context.getCacheDir());
        boolean success=false;
        try(PDDocument pdf=new PDDocument()) {
            for(Page page:session.pages) {
                interrupted();
                Bitmap censored = page.bitmap();
                try {
                    paintMasks(new Canvas(censored), page.masks);
                    PDPage output=new PDPage(new PDRectangle(page.pointsWidth,page.pointsHeight));
                    pdf.addPage(output);
                    try(PDPageContentStream stream=new PDPageContentStream(pdf,output)) {
                        stream.drawImage(LosslessFactory.createFromImage(pdf,censored),0,0,page.pointsWidth,page.pointsHeight);
                    }
                } finally {
                    censored.recycle();
                }
            }
            pdf.save(file); success=true; return file;
        } catch(OutOfMemoryError lowMemory) {
            throw new IOException("Memoria insuficiente para generar el PDF; no se ha publicado",lowMemory);
        } finally { if(!success) file.delete(); }
    }
    private static void interrupted() throws InterruptedException {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Cancelado");
    }
}
