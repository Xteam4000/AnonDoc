package com.anon.doc;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;


public class MainActivity extends Activity {

    private Button btnSelect;
    private Button btnAnon;
    private TextView txtStatus;

    private static final int PICK_FILE = 1;

    private String lastText = "";
    private String documentWarning = "";
    private final ExecutorService documentExecutor = Executors.newSingleThreadExecutor();
    private int documentVersion = 0;
    private boolean destroyed = false;
    private TextRecognizer activeRecognizer;

    private void setBusy(boolean busy) {
        btnSelect.setEnabled(!busy);
        btnAnon.setEnabled(!busy && !lastText.isEmpty());
    }

    private boolean isCurrentDocument(int version) {
        return !destroyed && version == documentVersion;
    }

    @Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    try {
        setContentView(R.layout.activity_main);

        btnSelect = findViewById(R.id.btnSelect);
        btnAnon = findViewById(R.id.btnAnon);
        txtStatus = findViewById(R.id.txtStatus);

        PDFBoxResourceLoader.init(getApplicationContext());
        setBusy(false);
        txtStatus.setText("App iniciada correctamente");

        btnSelect.setOnClickListener(v -> openFilePicker());
        btnAnon.setOnClickListener(v -> processAnonymization());

    } catch (Exception e) {
        // Si la app iba a crashear, lo mostramos aquí
        TextView fallback = new TextView(this);
        fallback.setText("CRASH EN INICIO:\n\n" + e.toString());
        setContentView(fallback);
    }
}

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/pdf", "image/*"});
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
                    documentWarning = result.emptyPages > 0
                            ? result.emptyPages + " página(s) sin texto reconocido. Comprueba el original. "
                            : "";
                    setBusy(false);
                    txtStatus.setText(lastText.isEmpty()
                            ? "No se ha reconocido texto. No se puede exportar."
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

    private void processAnonymization() {
        if (lastText == null || lastText.isEmpty()) {
            txtStatus.setText("Primero selecciona un documento");
            return;
        }
        final int version = documentVersion;
        EditText additional = new EditText(this);
        additional.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        additional.setHint("Un nombre, dirección u otro dato por línea");
        additional.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Datos adicionales que quieres ocultar")
                .setMessage("Puedes indicar nombres o datos que conozcas. Es opcional; después podrás editar el resultado.")
                .setView(additional)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Revisar resultado", (dialog, which) -> {
                    if (!isCurrentDocument(version)) return;
                    showReview(TextAnonymizer.anonymize(lastText,
                            additional.getText().toString()), version);
                })
                .show();
    }

    private void showReview(String text, int version) {
        EditText reviewed = new EditText(this);
        reviewed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        reviewed.setText(text);
        reviewed.setMinLines(5);
        reviewed.setMaxLines(10);
        new AlertDialog.Builder(this)
                .setTitle("Revisa y corrige antes de exportar")
                .setMessage(documentWarning
                        + "La detección automática y el OCR pueden omitir datos. Revisa nombres, direcciones y códigos. "
                        + "Se exportará solo este texto; no se copiarán imágenes ni firmas del original.")
                .setView(reviewed)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Exportar texto revisado", (dialog, which) -> {
                    if (!isCurrentDocument(version)) return;
                    String finalText = reviewed.getText().toString();
                    if (finalText.trim().isEmpty()) {
                        txtStatus.setText("El texto revisado está vacío; no se ha exportado");
                        return;
                    }
                    exportPdf(finalText, version);
                })
                .show();
    }

    private void exportPdf(String text, int version) {
        setBusy(true);
        txtStatus.setText("Generando PDF con el texto revisado...");
        documentExecutor.execute(() -> {
            try {
                File file = TextPdfExporter.export(getApplicationContext(), text);
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    setBusy(false);
                    txtStatus.setText("PDF de texto revisado generado: " + file.getAbsolutePath());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    setBusy(false);
                    txtStatus.setText("Error al exportar: " + e.getMessage());
                });
            }
        });
    }
}
