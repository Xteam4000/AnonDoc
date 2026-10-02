package com.anon.doc;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

public class MainActivity extends Activity {

    private View cardSelect;
    private View cardSelectImage;
    private MaterialButton btnAnon;
    private TextView txtStatus;
    private TextView txtStatusTitle;
    private View cardViewFiles;
    private static final int PICK_FILE = 1;

    private String lastText = "";
    private Uri sourceDocument;
    private String documentWarning = "";
    private final ExecutorService documentExecutor = Executors.newSingleThreadExecutor();
    private int documentVersion = 0;
    private boolean destroyed = false;
    private TextRecognizer activeRecognizer;

    private void setBusy(boolean busy) {
        cardSelect.setEnabled(!busy);
        if (cardSelectImage != null) cardSelectImage.setEnabled(!busy);
        btnAnon.setEnabled(!busy && (!lastText.isEmpty() || sourceDocument != null));
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
            lastText = "";
            sourceDocument = null;
            documentWarning = "";
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
        txtStatus.setText("Leyendo PDF y reconociendo sus imágenes...");
        documentExecutor.execute(() -> {
            try {
                PdfOcrReader.Result result = PdfOcrReader.read(getApplicationContext(), uri,
                        (page, total) -> runOnUiThread(() -> {
                            if (isCurrentDocument(version)) {
                                txtStatus.setText("OCR de PDF: página " + page + " de " + total);
                            }
                        }));
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    lastText = result.text.trim();
                    sourceDocument = uri;
                    documentWarning = result.emptyPages > 0
                            ? result.emptyPages + " página(s) sin texto reconocido. Comprueba el original. "
                            : "";
                    setBusy(false);
                    txtStatus.setText(lastText.isEmpty()
                            ? "No se ha reconocido texto. Pulsa Anonimizar y revisa visualmente todas las páginas."
                            : documentWarning + "Lectura y OCR completados. Pulsa Anonimizar y revisa el resultado.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    lastText = "";
                    setBusy(false);
                    txtStatus.setText("No se pudo procesar todo el PDF: " + e.getMessage());
                });
            }
        });
    }

    private void handleImage(Uri uri, int version) {
        setBusy(true);
        txtStatus.setText("Reconociendo texto de la imagen...");
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            final TextRecognizer recognizer =
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            activeRecognizer = recognizer;
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        if (!isCurrentDocument(version)) return;
                        lastText = result.getText().trim();
                        sourceDocument = uri;
                        setBusy(false);
                        txtStatus.setText(lastText.isEmpty()
                                ? "No se ha reconocido texto en la imagen"
                                : "OCR completado. Pulsa Anonimizar.");
                    })
                    .addOnFailureListener(e -> {
                        if (!isCurrentDocument(version)) return;
                        lastText = "";
                        setBusy(false);
                        txtStatus.setText("Error OCR: " + e.getMessage());
                    })
                    .addOnCompleteListener(task -> {
                        recognizer.close();
                        if (activeRecognizer == recognizer) activeRecognizer = null;
                    });
        } catch (Exception e) {
            lastText = "";
            setBusy(false);
            if (activeRecognizer != null) {
                activeRecognizer.close();
                activeRecognizer = null;
            }
            txtStatus.setText("Error imagen: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        documentVersion++;
        documentExecutor.shutdownNow();
        if (activeRecognizer != null) activeRecognizer.close();
        super.onDestroy();
    }

    private void startVisualReview() {
        if ((lastText == null || lastText.isEmpty()) && sourceDocument == null) {
            txtStatus.setText("Primero selecciona un documento");
            return;
        }
        if (sourceDocument == null) {
            txtStatus.setText("No se puede abrir la revisión visual");
            return;
        }
        Intent visual = new Intent(this, VisualReviewActivity.class)
                .setData(sourceDocument)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(visual);
    }
}
