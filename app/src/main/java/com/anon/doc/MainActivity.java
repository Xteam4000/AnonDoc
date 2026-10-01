package com.anon.doc;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.os.Build;
import java.util.List;
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
    private Button btnSelectImage;
    private Button btnAnon;
    private TextView txtStatus;
    private Button btnViewFiles;
    private Button btnTextOnly;
    private EditText editAdditional;
    private File pendingSave;
    private static final int SAVE_PDF = 2;

    private static final int PICK_FILE = 1;

    private String lastText = "";
    private Uri sourceDocument;
    private String documentWarning = "";
    private final ExecutorService documentExecutor = Executors.newSingleThreadExecutor();
    private int documentVersion = 0;
    private boolean destroyed = false;
    private TextRecognizer activeRecognizer;

    private void setBusy(boolean busy) {
        btnSelect.setEnabled(!busy);
        if (btnSelectImage != null) btnSelectImage.setEnabled(!busy);
        btnAnon.setEnabled(!busy && (!lastText.isEmpty() || sourceDocument != null));
        if (btnTextOnly != null) btnTextOnly.setEnabled(!busy && !lastText.isEmpty());
        if (btnViewFiles != null) btnViewFiles.setEnabled(!busy);
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
        btnSelectImage = findViewById(R.id.btnSelectImage);
        btnAnon = findViewById(R.id.btnAnon);
        btnViewFiles = findViewById(R.id.btnViewFiles);
        btnTextOnly = findViewById(R.id.btnTextOnly);
        editAdditional = findViewById(R.id.editAdditional);
        btnViewFiles.setOnClickListener(v -> startActivity(new Intent(this, OutputFilesActivity.class)));
        txtStatus = findViewById(R.id.txtStatus);

        PDFBoxResourceLoader.init(getApplicationContext());
        setBusy(false);
        txtStatus.setText("Preparado para seleccionar un PDF o una imagen");

        btnSelect.setOnClickListener(v -> openPdfPicker());
        btnSelectImage.setOnClickListener(v -> openImagePicker());
        btnAnon.setOnClickListener(v -> startVisualReview());
        btnTextOnly.setOnClickListener(v -> {
            if (lastText == null || lastText.isEmpty()) {
                txtStatus.setText("Selecciona primero un documento con texto reconocido");
                return;
            }
            showReview(TextAnonymizer.anonymize(lastText, editAdditional.getText().toString()), documentVersion);
        });

    } catch (Exception e) {
        // Si la app iba a crashear, lo mostramos aquí
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

        if (requestCode == SAVE_PDF) {
            File source = pendingSave;
            pendingSave = null;
            if (source == null) return;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                source.delete();
                setBusy(false);
                txtStatus.setText("Guardado cancelado. Puedes volver a exportar.");
                return;
            }
            Uri destination = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(destination, flags);
            } catch (SecurityException unsupported) {
                // The save can still complete; later access may require the file manager.
            }
            documentExecutor.execute(() -> {
                try {
                    OutputStore.copyTo(getApplicationContext(), source, destination);
                    OutputStore.rememberPickedFile(getApplicationContext(), destination, source.getName());
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        setBusy(false);
                        txtStatus.setText("PDF guardado en la ubicación elegida. Pulsa Ver archivos anonimizados.");
                    });
                } catch (Exception error) {
                    try { getContentResolver().delete(destination, null, null); }
                    catch (Exception ignored) { }
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        setBusy(false);
                        txtStatus.setText("No se pudo guardar: " + error.getMessage());
                    });
                } finally { source.delete(); }
            });
            return;
        }
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
        if (pendingSave != null) { pendingSave.delete(); pendingSave = null; }
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
        if (sourceDocument == null) { txtStatus.setText("No se puede abrir la revisión visual"); return; }
        Intent visual = new Intent(this, VisualReviewActivity.class).setData(sourceDocument)
                .putExtra("additional", editAdditional.getText().toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(visual);
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
        txtStatus.setText("Guardando PDF revisado...");
        documentExecutor.execute(() -> {
            File file = null;
            try {
                file = TextPdfExporter.export(getApplicationContext(), text);
                if (Build.VERSION.SDK_INT >= 29) {
                    OutputStore.publishToDownloads(getApplicationContext(), file);
                    runOnUiThread(() -> {
                        if (!isCurrentDocument(version)) return;
                        setBusy(false);
                        txtStatus.setText("Guardado en Descargas/AnonDoc. Pulsa Ver archivos anonimizados.");
                    });
                } else {
                    File prepared = file;
                    file = null;
                    runOnUiThread(() -> {
                        if (!isCurrentDocument(version)) { prepared.delete(); return; }
                        pendingSave = prepared;
                        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        save.addCategory(Intent.CATEGORY_OPENABLE);
                        save.setType("application/pdf");
                        save.putExtra(Intent.EXTRA_TITLE, prepared.getName());
                        save.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try {
                            startActivityForResult(save, SAVE_PDF);
                        } catch (ActivityNotFoundException missing) {
                            pendingSave.delete();
                            pendingSave = null;
                            setBusy(false);
                            txtStatus.setText("No hay un selector de archivos disponible");
                        }
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    setBusy(false);
                    txtStatus.setText("Error al guardar: " + e.getMessage());
                });
            } finally { if (file != null) file.delete(); }
        });
    }

}
