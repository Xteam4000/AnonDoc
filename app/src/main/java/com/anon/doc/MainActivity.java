package com.anon.doc;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private View cardSelect;
    private View cardSelectImage;
    private MaterialButton btnAnon;
    private TextView txtStatus;
    private TextView txtStatusTitle;
    private View cardViewFiles;
    private static final int PICK_FILE = 1;

    private Uri sourceDocument;
    private final ExecutorService documentExecutor = Executors.newSingleThreadExecutor();
    private int documentVersion = 0;
    private boolean destroyed = false;

    private void setBusy(boolean busy) {
        cardSelect.setEnabled(!busy);
        if (cardSelectImage != null) cardSelectImage.setEnabled(!busy);
        btnAnon.setEnabled(!busy && sourceDocument != null);
        if (cardViewFiles != null) cardViewFiles.setEnabled(!busy);
        if (txtStatusTitle != null) txtStatusTitle.setText(busy ? "Procesando" : "Preparado");
    }

    private boolean isCurrentDocument(int version) {
        return !destroyed && version == documentVersion;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            setContentView(R.layout.activity_main);

            cardSelect = findViewById(R.id.btnSelect);
            cardSelectImage = findViewById(R.id.btnSelectImage);
            btnAnon = findViewById(R.id.btnAnon);
            cardViewFiles = findViewById(R.id.btnViewFiles);
            txtStatus = findViewById(R.id.txtStatus);
            txtStatusTitle = findViewById(R.id.txtStatusTitle);

            cardViewFiles.setOnClickListener(v ->
                    startActivity(new Intent(this, OutputFilesActivity.class)));

            PDFBoxResourceLoader.init(getApplicationContext());
            setBusy(false);
            txtStatus.setText("Selecciona un PDF o una imagen para comenzar.");

            cardSelect.setOnClickListener(v -> openPdfPicker());
            cardSelectImage.setOnClickListener(v -> openImagePicker());
            btnAnon.setOnClickListener(v -> startVisualReview());
        } catch (Exception e) {
            TextView fallback = new TextView(this);
            fallback.setText("CRASH EN INICIO:\n\n" + e.toString());
            setContentView(fallback);
        }
    }

    private void openPdfPicker() {
        openFilePicker("application/pdf");
    }

    private void openImagePicker() {
        openFilePicker("image/*");
    }

    private void openFilePicker(String mimeType) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(mimeType);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            final int version = ++documentVersion;
            sourceDocument = null;
            setBusy(false);
            if (uri == null) {
                txtStatus.setText("No se ha recibido ningún documento");
                return;
            }
            try {
                String mime = getContentResolver().getType(uri);
                mime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
                if (mime.equals("application/pdf")) {
                    handlePdf(uri, version);
                } else if (mime.startsWith("image/")) {
                    handleImage(uri, version);
                } else {
                    txtStatus.setText("Formato no compatible o desconocido. Selecciona un PDF o una imagen.");
                }
            } catch (Exception e) {
                txtStatus.setText("Error al abrir el documento: " + e.getMessage());
                setBusy(false);
            }
        }
    }

    private void handlePdf(Uri uri, int version) {
        setBusy(true);
        txtStatus.setText("Comprobando PDF...");
        documentExecutor.execute(() -> {
            try {
                DocumentValidator.validatePdf(getApplicationContext(), uri);
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    sourceDocument = uri;
                    setBusy(false);
                    txtStatus.setText("PDF preparado. Pulsa Anonimizar y revisa visualmente todas las páginas.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    sourceDocument = null;
                    setBusy(false);
                    txtStatus.setText("No se pudo procesar el PDF: " + e.getMessage());
                });
            }
        });
    }

    private void handleImage(Uri uri, int version) {
        setBusy(true);
        txtStatus.setText("Comprobando imagen...");
        documentExecutor.execute(() -> {
            try {
                DocumentValidator.validateImage(getApplicationContext(), uri);
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    sourceDocument = uri;
                    setBusy(false);
                    txtStatus.setText("Imagen preparada. Pulsa Anonimizar y revisa visualmente el resultado.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    sourceDocument = null;
                    setBusy(false);
                    txtStatus.setText("No se pudo procesar la imagen: " + e.getMessage());
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        documentVersion++;
        documentExecutor.shutdownNow();
        super.onDestroy();
    }

    private void startVisualReview() {
        if (sourceDocument == null) {
            txtStatus.setText("Primero selecciona un documento");
            return;
        }
        Intent visual = new Intent(this, VisualReviewActivity.class)
                .setData(sourceDocument)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(visual);
    }
}
