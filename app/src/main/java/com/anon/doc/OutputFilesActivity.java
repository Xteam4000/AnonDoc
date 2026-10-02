package com.anon.doc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen, readable management of the PDFs explicitly saved by AnonDoc. */
public class OutputFilesActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;
    private boolean destroyed;

    private final int primary = Color.rgb(23, 107, 114);
    private final int primaryDark = Color.rgb(15, 82, 88);
    private final int background = Color.rgb(244, 247, 248);
    private final int text = Color.rgb(24, 32, 34);
    private final int secondary = Color.rgb(93, 105, 108);
    private final int outline = Color.rgb(216, 225, 227);
    private final int danger = Color.rgb(179, 38, 30);
    private final int dangerSoft = Color.rgb(253, 236, 234);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) load();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.setBackgroundColor(background);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = button("‹", false);
        back.setTextSize(30);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));

        TextView title = text("Mis archivos", 26, text);
        title.setTypeface(null, 1);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(10), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(header);

        TextView subtitle = text("Documentos anonimizados guardados en Descargas/AnonDoc", 14, secondary);
        subtitle.setPadding(dp(62), 0, dp(8), dp(6));
        root.addView(subtitle);

        status = text("Buscando archivos…", 14, primaryDark);
        status.setPadding(dp(4), dp(8), 0, dp(10));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        MaterialButton deleteAll = button("Eliminar todos", false);
        deleteAll.setTextColor(danger);
        deleteAll.setStrokeColor(ColorStateList.valueOf(danger));
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        LinearLayout.LayoutParams deleteAllParams = new LinearLayout.LayoutParams(-1, dp(54));
        deleteAllParams.setMargins(0, dp(8), 0, 0);
        root.addView(deleteAll, deleteAllParams);

        setContentView(root);
    }

    private void load() {
        status.setText("Buscando archivos…");
        worker.execute(() -> {
            try {
                List<OutputStore.Entry> files = OutputStore.list(getApplicationContext());
                runOnUiThread(() -> {
                    if (!destroyed) show(files);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!destroyed) {
                        status.setText("No se pudieron leer los archivos: " + error.getMessage());
                    }
                });
            }
        });
    }

    private void show(List<OutputStore.Entry> files) {
        list.removeAllViews();
        status.setText(files.isEmpty()
                ? "Todavía no hay documentos guardados."
                : files.size() + (files.size() == 1 ? " documento" : " documentos"));

        for (OutputStore.Entry entry : files) {
            MaterialCardView card = new MaterialCardView(this);
            card.setCardBackgroundColor(Color.WHITE);
            card.setRadius(dp(20));
            card.setCardElevation(dp(1));
            card.setStrokeColor(outline);
            card.setStrokeWidth(dp(1));

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(14), dp(14), dp(14), dp(12));

            LinearLayout fileRow = new LinearLayout(this);
            fileRow.setGravity(Gravity.CENTER_VERTICAL);

            MaterialCardView badgeCard = new MaterialCardView(this);
            badgeCard.setRadius(dp(14));
            badgeCard.setCardBackgroundColor(dangerSoft);
            badgeCard.setCardElevation(0);
            badgeCard.setStrokeWidth(0);

            TextView pdfBadge = text("PDF", 13, danger);
            pdfBadge.setTypeface(null, 1);
            pdfBadge.setGravity(Gravity.CENTER);
            badgeCard.addView(pdfBadge, new LinearLayout.LayoutParams(-1, -1));
            fileRow.addView(badgeCard, new LinearLayout.LayoutParams(dp(54), dp(54)));

            LinearLayout fileText = new LinearLayout(this);
            fileText.setOrientation(LinearLayout.VERTICAL);
            fileText.setPadding(dp(13), 0, 0, 0);

            TextView name = text(entry.name, 16, text);
            name.setTypeface(null, 1);
            fileText.addView(name);
            TextView kind = text("Documento PDF anonimizado", 13, secondary);
            kind.setPadding(0, dp(4), 0, 0);
            fileText.addView(kind);

            fileRow.addView(fileText, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(fileRow);

            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            actions.setPadding(0, dp(10), 0, 0);

            MaterialButton open = button("Abrir", true);
            open.setContentDescription("Abrir " + entry.name);
            open.setOnClickListener(v -> open(entry.uri));

            MaterialButton delete = button("Eliminar", false);
            delete.setTextColor(danger);
            delete.setStrokeColor(ColorStateList.valueOf(danger));
            delete.setContentDescription("Eliminar " + entry.name);
            delete.setOnClickListener(v -> confirmDelete(entry));

            LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(0, dp(48), 1);
            action.setMargins(dp(3), 0, dp(3), 0);
            actions.addView(open, action);
            actions.addView(delete, action);
            body.addView(actions);

            card.addView(body);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.setMargins(0, 0, 0, dp(12));
            list.addView(card, cardParams);
        }
    }

    private void confirmDelete(OutputStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar documento")
                .setMessage("Se eliminará “" + entry.name + "”. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> worker.execute(() -> {
                    boolean deleted = OutputStore.delete(getApplicationContext(), entry.uri);
                    runOnUiThread(() -> {
                        if (!destroyed) {
                            status.setText(deleted ? "Documento eliminado" : "No se pudo eliminar");
                            load();
                        }
                    });
                }))
                .show();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar todos los documentos")
                .setMessage("Se eliminarán todos los PDF guardados por AnonDoc. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar todos", (dialog, which) -> worker.execute(() -> {
                    int deleted = OutputStore.deleteAll(getApplicationContext());
                    runOnUiThread(() -> {
                        if (!destroyed) {
                            status.setText("Documentos eliminados: " + deleted);
                            load();
                        }
                    });
                }))
                .show();
    }

    private void open(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException missing) {
            status.setText("Instala un visor de PDF para abrirlo");
        } catch (SecurityException unavailable) {
            status.setText("El archivo ya no está disponible");
        }
    }

    private MaterialButton button(String label, boolean strong) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setCornerRadius(dp(16));
        button.setBackgroundTintList(ColorStateList.valueOf(strong ? primary : Color.WHITE));
        button.setTextColor(strong ? Color.WHITE : primaryDark);
        button.setStrokeColor(ColorStateList.valueOf(primary));
        button.setStrokeWidth(strong ? 0 : dp(1));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(size);
        textView.setTextColor(color);
        return textView;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }
}
