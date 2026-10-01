package com.anon.doc;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Automatic preview, explicit manual editing and final preview share one safe session. */
public class VisualReviewActivity extends Activity {
    private enum Screen { AUTOMATIC, MANUAL, FINAL }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private volatile VisualPdf.Session session;
    private Screen screen=Screen.AUTOMATIC;
    private int index;
    private TextView title,status,help;
    private PageView preview;
    private LinearLayout controls;
    private File finalPdf,pending;
    private boolean busy=true,marking;
    private final int primary=Color.rgb(23,107,114),primaryDark=Color.rgb(15,82,88);
    private final int background=Color.rgb(244,247,248),surface=Color.WHITE;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext()); buildShell();
        Uri source=getIntent().getData(); String additional=getIntent().getStringExtra("additional");
        if(source==null || state!=null){status.setText("Revisión interrumpida. Vuelve al documento y comienza de nuevo.");return;}
        status.setText("Preparando la anonimización automática...");
        worker.execute(()->{try{
            session=VisualPdf.prepare(getApplicationContext(),source,additional,(current,total)->{
                if(destroyed)throw new CancellationException();
                runOnUiThread(()->{if(!destroyed)status.setText("Preparando página "+current+" de "+total);});});
            runOnUiThread(()->{if(!destroyed){busy=false;showAutomatic();}});
        }catch(Exception e){runOnUiThread(()->{if(!destroyed)status.setText("No se pudo preparar: "+e.getMessage());});}});
    }

    private void buildShell(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(12),dp(16),dp(14));root.setBackgroundColor(background);
        title=new TextView(this);title.setTextColor(Color.rgb(24,32,34));title.setTextSize(24);title.setTypeface(null,1);root.addView(title);
        status=new TextView(this);status.setTextColor(Color.rgb(93,105,108));status.setTextSize(14);root.addView(status);
        help=new TextView(this);help.setTextColor(primaryDark);help.setTextSize(13);help.setPadding(0,dp(5),0,dp(7));root.addView(help);
        FrameLayout card=new FrameLayout(this);card.setBackgroundColor(surface);card.setPadding(dp(4),dp(4),dp(4),dp(4));
        preview=new PageView(this);preview.setContentDescription("Documento anonimizado ampliable");card.addView(preview,new FrameLayout.LayoutParams(-1,-1));root.addView(card,new LinearLayout.LayoutParams(-1,0,1));
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(0,dp(8),0,0);root.addView(controls);setContentView(root);
    }

    private void showAutomatic(){
        screen=Screen.AUTOMATIC;marking=false;preview.setMarking(false);title.setText("Resultado automático");
        help.setText("Revisa todas las páginas. Pellizca para ampliar y arrastra para desplazarte.");showPage(index);controls.removeAllViews();
        controls.addView(pageNavigation());
        LinearLayout row=row();row.addView(button("Me vale",true,this::complete),weight());row.addView(button("Lo hago yo",false,this::showManual),weight());controls.addView(row);
    }
    private void showManual(){
        screen=Screen.MANUAL;preview.setMarking(marking);title.setText("Censura manual");
        help.setText("Amplía normalmente. Pulsa Tachar solo cuando quieras dibujar una zona negra.");controls.removeAllViews();
        controls.addView(pageNavigation());LinearLayout tools=row();
        tools.addView(button("Tachar",marking,()->{marking=!marking;preview.setMarking(marking);showManual();}),weight());tools.addView(button("Borrar último",false,this::undo),weight());controls.addView(tools);
        controls.addView(button("Completar anonimización",true,this::complete),new LinearLayout.LayoutParams(-1,dp(54)));updateStatus();
    }
    private void showFinal(){
        screen=Screen.FINAL;marking=false;preview.setMarking(false);title.setText("Documento definitivo");help.setText("Esta es la versión final. Puedes guardarla, consultar los archivos o descartarla.");
        showPage(index);controls.removeAllViews();controls.addView(pageNavigation());
        controls.addView(button("Guardar en AnonDoc",true,this::saveFinal),new LinearLayout.LayoutParams(-1,dp(54)));
        LinearLayout row=row();row.setPadding(0,dp(7),0,0);row.addView(button("Mis archivos",false,this::showFiles),weight());row.addView(button("Descartar",false,this::discard),weight());controls.addView(row);
        status.setText("Documento definitivo preparado · todavía no guardado");
    }

    private void complete(){
        if(busy||session==null)return;busy=true;marking=false;preview.setMarking(false);status.setText("Generando documento definitivo...");for(VisualPdf.Page p:session.pages)p.reviewed=true;
        worker.execute(()->{try{File generated=VisualPdf.export(getApplicationContext(),session);runOnUiThread(()->{if(!destroyed){finalPdf=generated;busy=false;showFinal();}});}
        catch(Exception e){runOnUiThread(()->{if(!destroyed){busy=false;status.setText("No se pudo completar: "+e.getMessage());}});}});
    }
    private void saveFinal(){
        if(busy||finalPdf==null)return;busy=true;status.setText("Guardando documento definitivo...");
        if(Build.VERSION.SDK_INT>=29)worker.execute(()->{try{OutputStore.publishToDownloads(getApplicationContext(),finalPdf);runOnUiThread(()->{if(!destroyed){busy=false;status.setText("Guardado en Descargas/AnonDoc");}});}
        catch(Exception e){runOnUiThread(()->{if(!destroyed){busy=false;status.setText("No se pudo guardar: "+e.getMessage());}});}});
        else{pending=finalPdf;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,finalPdf.getName()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try{startActivityForResult(i,2);}catch(ActivityNotFoundException e){pending=null;busy=false;status.setText("No hay selector de guardado disponible");}}
    }

    private void showFiles(){startActivity(new Intent(this,OutputFilesActivity.class));}
    private void discard(){if(finalPdf!=null){finalPdf.delete();finalPdf=null;}finish();}
    private void undo(){if(session==null)return;VisualPdf.Page p=session.pages.get(index);if(p.masks.size()>p.automatic){p.masks.remove(p.masks.size()-1);preview.invalidate();}updateStatus();}
    private void showPage(int requested){if(session==null||requested<0||requested>=session.pages.size())return;index=requested;try{preview.setPage(session.pages.get(index));updateStatus();}catch(Exception e){busy=true;status.setText("No se puede mostrar la página: "+e.getMessage());}}
    private void updateStatus(){if(session==null||busy)return;VisualPdf.Page p=session.pages.get(index);status.setText("Página "+(index+1)+" de "+session.pages.size()+(marking?" · Modo Tachar activo":" · Navegación y zoom")+(p.emptyOcr?" · Sin texto OCR: revísala completa":""));}

    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=2||pending==null)return;File source=pending;pending=null;if(result!=RESULT_OK||data==null||data.getData()==null){busy=false;status.setText("Guardado cancelado");return;}Uri destination=data.getData();
        try{getContentResolver().takePersistableUriPermission(destination,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(SecurityException ignored){}
        worker.execute(()->{try{OutputStore.copyTo(getApplicationContext(),source,destination);OutputStore.rememberPickedFile(getApplicationContext(),destination,source.getName());runOnUiThread(()->{if(!destroyed){busy=false;status.setText("PDF guardado en la ubicación elegida");}});}catch(Exception e){runOnUiThread(()->{if(!destroyed){busy=false;status.setText("Error al guardar: "+e.getMessage());}});}});}
    @Override public void onBackPressed(){if(screen==Screen.MANUAL){showAutomatic();return;}if(screen==Screen.FINAL){finish();return;}super.onBackPressed();}
    @Override protected void onDestroy(){destroyed=true;preview.release();if(finalPdf!=null)finalPdf.delete();worker.execute(()->{if(session!=null)session.close();});worker.shutdown();super.onDestroy();}

    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setPadding(0,0,0,dp(7));return r;}
    private LinearLayout pageNavigation(){LinearLayout r=row();r.addView(button("‹",false,()->navigate(index-1)),weight());TextView page=new TextView(this);page.setGravity(Gravity.CENTER);page.setText("Página "+(index+1)+" de "+session.pages.size());page.setTextColor(primaryDark);r.addView(page,new LinearLayout.LayoutParams(0,dp(48),2));r.addView(button("›",false,()->navigate(index+1)),weight());return r;}
    private void navigate(int page){if(page<0||session==null||page>=session.pages.size())return;index=page;if(screen==Screen.AUTOMATIC)showAutomatic();else if(screen==Screen.MANUAL){marking=false;showManual();}else showFinal();}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(3),0,dp(3),0);return p;}
    private MaterialButton button(String text,boolean strong,Runnable action){MaterialButton b=new MaterialButton(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setCornerRadius(dp(15));b.setBackgroundTintList(ColorStateList.valueOf(strong?primary:surface));b.setTextColor(strong?Color.WHITE:primaryDark);b.setStrokeColor(ColorStateList.valueOf(primary));b.setStrokeWidth(strong?0:dp(1));b.setOnClickListener(v->action.run());return b;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    final class PageView extends View{
        Bitmap bitmap;VisualPdf.Page page;boolean markingEnabled;float zoom=1,panX,panY,startX,startY,lastX,lastY;boolean drawing,multiple,suppressUntilUp;RectF draft;final Matrix transform=new Matrix(),inverse=new Matrix();final ScaleGestureDetector scaling;
        PageView(Context c){super(c);setBackgroundColor(Color.rgb(224,230,232));scaling=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(6,zoom*d.getScaleFactor()));invalidate();return true;}});}
        void setMarking(boolean enabled){markingEnabled=enabled;drawing=false;draft=null;invalidate();}void release(){if(bitmap!=null){bitmap.recycle();bitmap=null;}}void setPage(VisualPdf.Page p)throws Exception{release();page=p;bitmap=p.bitmap();zoom=1;panX=panY=0;draft=null;drawing=false;invalidate();}
        void matrix(){if(bitmap==null)return;float fit=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight()),scale=fit*zoom;float maxX=Math.max(0,(bitmap.getWidth()*scale-getWidth())/2),maxY=Math.max(0,(bitmap.getHeight()*scale-getHeight())/2);panX=Math.max(-maxX,Math.min(maxX,panX));panY=Math.max(-maxY,Math.min(maxY,panY));transform.reset();transform.postScale(scale,scale);transform.postTranslate((getWidth()-bitmap.getWidth()*scale)/2+panX,(getHeight()-bitmap.getHeight()*scale)/2+panY);transform.invert(inverse);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(bitmap==null)return;matrix();c.save();c.concat(transform);c.drawBitmap(bitmap,0,0,null);VisualPdf.paintMasks(c,page.masks);if(draft!=null)VisualPdf.paintMasks(c,Collections.singletonList(draft));c.restore();}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(bitmap==null)return false;scaling.onTouchEvent(e);matrix();
            if(e.getPointerCount()>1){drawing=false;draft=null;suppressUntilUp=true;float x=(e.getX(0)+e.getX(1))/2,y=(e.getY(0)+e.getY(1))/2;if(multiple&&e.getActionMasked()==MotionEvent.ACTION_MOVE){panX+=x-lastX;panY+=y-lastY;}lastX=x;lastY=y;multiple=true;invalidate();return true;}
            if(suppressUntilUp){if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){suppressUntilUp=false;multiple=false;}return true;}
            if(!markingEnabled){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:lastX=e.getX();lastY=e.getY();break;case MotionEvent.ACTION_MOVE:if(zoom>1){panX+=e.getX()-lastX;panY+=e.getY()-lastY;lastX=e.getX();lastY=e.getY();invalidate();}break;case MotionEvent.ACTION_UP:performClick();break;}return true;}
            float[] pt={e.getX(),e.getY()};inverse.mapPoints(pt);float x=Math.max(0,Math.min(bitmap.getWidth(),pt[0])),y=Math.max(0,Math.min(bitmap.getHeight(),pt[1]));switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:multiple=false;drawing=pt[0]>=0&&pt[0]<=bitmap.getWidth()&&pt[1]>=0&&pt[1]<=bitmap.getHeight();startX=x;startY=y;break;case MotionEvent.ACTION_MOVE:if(drawing&&!multiple)draft=new RectF(Math.min(startX,x),Math.min(startY,y),Math.max(startX,x),Math.max(startY,y));break;case MotionEvent.ACTION_UP:if(drawing&&!multiple&&draft!=null&&draft.width()>=3&&draft.height()>=3){page.masks.add(new RectF(draft));updateStatus();}drawing=false;multiple=false;draft=null;performClick();break;case MotionEvent.ACTION_CANCEL:drawing=false;multiple=false;draft=null;break;}invalidate();return true;}
        @Override public boolean performClick(){return super.performClick();}
    }
}
