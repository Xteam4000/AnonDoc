package com.anon.doc;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;

/** Explicit page confirmation, manual rectangles, zoom and image-only export. */
public class VisualReviewActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private volatile VisualPdf.Session session;
    private int index;
    private TextView status;
    private PageView preview;
    private Button previous,next,confirm,undo,save;
    private File pending;
    private boolean busy=true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12,12,12,12);
        status=new TextView(this); root.addView(status);
        TextView help=new TextView(this);
        help.setText("Revisa cada página. Arrastra un dedo para ocultar firmas, fotos o datos. Usa dos dedos para ampliar y desplazar. Las zonas negras se eliminarán de la imagen exportada.");
        root.addView(help);
        preview=new PageView(this); preview.setContentDescription("Página original con zonas ocultas");
        root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this);
        previous=button(nav,"Anterior",()->showPage(index-1));
        next=button(nav,"Siguiente",()->showPage(index+1));
        undo=button(nav,"Deshacer",()->{
            VisualPdf.Page p=session.pages.get(index);
            if(p.masks.size()>p.automatic) { p.masks.remove(p.masks.size()-1); p.reviewed=false; preview.invalidate(); update(); }
        }); root.addView(nav);
        confirm=new Button(this); confirm.setText("Confirmar página revisada");
        confirm.setOnClickListener(v->{session.pages.get(index).reviewed=true; update();}); root.addView(confirm);
        save=new Button(this); save.setText("Guardar PDF con diseño");
        save.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Guardar PDF revisado")
                .setMessage("¿Has comprobado todas las zonas, incluidos firmas, fotos y códigos? El OCR puede omitir datos. El PDF conservará el aspecto como imágenes, sin texto seleccionable ni firmas digitales válidas.")
                .setNegativeButton("Volver",null).setPositiveButton("Guardar",(d,w)->export()).show());
        root.addView(save); setContentView(root); update();
        status.setText("Preparando páginas y propuestas de ocultación...");
        Uri source=getIntent().getData(); String additional=getIntent().getStringExtra("additional");
        if(source==null || state!=null) {
            status.setText("Revisión interrumpida. Vuelve al documento y comienza de nuevo."); return;
        }
        worker.execute(()->{
            try {
                session=VisualPdf.prepare(getApplicationContext(),source,additional,(current,total)->{
                    if(destroyed) throw new CancellationException();
                    runOnUiThread(()->{if(!destroyed) status.setText("Preparando página "+current+" de "+total);});
                });
                runOnUiThread(()->{if(!destroyed) { busy=false; showPage(0); }});
            } catch(Exception error) {
                runOnUiThread(()->{if(!destroyed) status.setText("No se pudo preparar: "+error.getMessage());});
            }
        });
    }
    private Button button(LinearLayout row,String text,Runnable action) {
        Button b=new Button(this); b.setText(text); b.setOnClickListener(v->action.run());
        row.addView(b,new LinearLayout.LayoutParams(0,-2,1)); return b;
    }
    private void showPage(int requested) {
        if(session==null || requested<0 || requested>=session.pages.size()) return;
        index=requested;
        try { preview.setPage(session.pages.get(index)); update(); }
        catch(Exception e) { busy=true; update(); status.setText("No se puede mostrar la página: "+e.getMessage()); }
    }
    private void update() {
        boolean available=session!=null && !busy;
        previous.setEnabled(available && index>0);
        next.setEnabled(available && index+1<session.pages.size());
        confirm.setEnabled(available); undo.setEnabled(available);
        preview.setEnabled(available);
        boolean all=available;
        if(session!=null) for(VisualPdf.Page p:session.pages) all&=p.reviewed;
        save.setEnabled(all);
        if(available) {
            VisualPdf.Page p=session.pages.get(index);
            status.setText("Página "+(index+1)+" de "+session.pages.size()+" · "+(p.reviewed?"Confirmada":"Pendiente de revisión")
                    +(p.emptyOcr?" · Sin texto OCR: inspecciona toda la imagen":""));
        }
    }
    private void export() {
        busy=true; update(); status.setText("Generando PDF con las zonas eliminadas...");
        worker.execute(()->{
            File file=null;
            try {
                file=VisualPdf.export(getApplicationContext(),session);
                if(destroyed) return;
                if(Build.VERSION.SDK_INT>=29) {
                    OutputStore.publishToDownloads(getApplicationContext(),file);
                    runOnUiThread(()->{if(!destroyed) {
                        busy=false; update(); status.setText("Guardado en Descargas/AnonDoc. Vuelve y pulsa Ver archivos anonimizados.");
                    }});
                } else {
                    File prepared=file; file=null;
                    runOnUiThread(()->{
                        if(destroyed) { prepared.delete(); return; }
                        pending=prepared;
                        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,prepared.getName())
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try { startActivityForResult(intent,2); }
                        catch(ActivityNotFoundException error) { pending.delete(); pending=null; busy=false; update(); status.setText("No hay selector de guardado disponible"); }
                    });
                }
            } catch(Exception error) {
                runOnUiThread(()->{if(!destroyed) { busy=false; update(); status.setText("No se pudo guardar: "+error.getMessage()); }});
            } finally { if(file!=null) file.delete(); }
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=2 || pending==null) return;
        File source=pending; pending=null;
        if(result!=RESULT_OK || data==null || data.getData()==null) {
            source.delete(); busy=false; update(); status.setText("Guardado cancelado"); return;
        }
        Uri uri=data.getData();
        try { getContentResolver().takePersistableUriPermission(uri,data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
        catch(SecurityException ignored) {}
        worker.execute(()->{
            try {
                OutputStore.copyTo(getApplicationContext(),source,uri);
                OutputStore.rememberPickedFile(getApplicationContext(),uri,source.getName());
                runOnUiThread(()->{if(!destroyed) { busy=false; update(); status.setText("PDF guardado en la ubicación elegida"); }});
            } catch(Exception e) {
                try { getContentResolver().delete(uri,null,null); } catch(Exception ignored) {}
                runOnUiThread(()->{if(!destroyed) { busy=false; update(); status.setText("Error al guardar: "+e.getMessage()); }});
            } finally { source.delete(); }
        });
    }
    @Override protected void onDestroy() {
        destroyed=true; if(pending!=null) pending.delete(); preview.release();
        // Cleanup is queued after preparation/export; never delete pages underneath the worker.
        worker.execute(()->{if(session!=null) session.close();}); worker.shutdown(); super.onDestroy();
    }

    final class PageView extends View {
        Bitmap bitmap; VisualPdf.Page page;
        float zoom=1,panX,panY,startX,startY,lastX,lastY;
        boolean drawing,multiple;
        RectF draft;
        final Matrix transform=new Matrix(),inverse=new Matrix();
        final ScaleGestureDetector scaling;
        PageView(Context c) {
            super(c);
            scaling=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d) {
                    zoom=Math.max(1,Math.min(5,zoom*d.getScaleFactor())); invalidate(); return true;
                }
            });
        }
        void release() { if(bitmap!=null) { bitmap.recycle(); bitmap=null; } }
        void setPage(VisualPdf.Page p) throws Exception {
            release(); page=p; bitmap=p.bitmap(); zoom=1; panX=panY=0; draft=null; drawing=false; invalidate();
        }
        void matrix() {
            if(bitmap==null) return;
            float fit=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
            float scale=fit*zoom;
            panX=Math.max(-getWidth()*zoom,Math.min(getWidth()*zoom,panX));
            panY=Math.max(-getHeight()*zoom,Math.min(getHeight()*zoom,panY));
            transform.reset(); transform.postScale(scale,scale);
            transform.postTranslate((getWidth()-bitmap.getWidth()*scale)/2+panX,(getHeight()-bitmap.getHeight()*scale)/2+panY);
            transform.invert(inverse);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.drawColor(Color.LTGRAY); if(bitmap==null) return;
            matrix(); c.save(); c.concat(transform); c.drawBitmap(bitmap,0,0,null);
            VisualPdf.paintMasks(c,page.masks);
            if(draft!=null) VisualPdf.paintMasks(c,java.util.Collections.singletonList(draft));
            c.restore();
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if(!isEnabled() || bitmap==null) return false;
            scaling.onTouchEvent(e); matrix();
            if(e.getPointerCount()>1) {
                drawing=false; draft=null;
                float x=(e.getX(0)+e.getX(1))/2,y=(e.getY(0)+e.getY(1))/2;
                if(multiple && e.getActionMasked()==MotionEvent.ACTION_MOVE) { panX+=x-lastX; panY+=y-lastY; }
                lastX=x; lastY=y; multiple=true; invalidate(); return true;
            }
            float[] pt={e.getX(),e.getY()}; inverse.mapPoints(pt);
            float x=Math.max(0,Math.min(bitmap.getWidth(),pt[0])),y=Math.max(0,Math.min(bitmap.getHeight(),pt[1]));
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    multiple=false; drawing=pt[0]>=0 && pt[0]<=bitmap.getWidth() && pt[1]>=0 && pt[1]<=bitmap.getHeight();
                    startX=x; startY=y; break;
                case MotionEvent.ACTION_MOVE:
                    if(drawing && !multiple) draft=new RectF(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y)); break;
                case MotionEvent.ACTION_UP:
                    if(drawing && !multiple && draft!=null && draft.width()>=3 && draft.height()>=3) {
                        page.masks.add(new RectF(draft)); page.reviewed=false; update();
                    }
                    drawing=false; multiple=false; draft=null; performClick(); break;
                case MotionEvent.ACTION_CANCEL: drawing=false; multiple=false; draft=null; break;
            }
            invalidate(); return true;
        }
        @Override public boolean performClick() { return super.performClick(); }
    }
}
