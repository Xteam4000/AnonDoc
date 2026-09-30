package com.anon.doc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import android.graphics.pdf.PdfDocument;

public class MainActivity extends Activity {

    private Button btnSelect;
    private Button btnAnon;
    private TextView txtStatus;

    private static final int PICK_FILE = 1;

    private String lastText = "";
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
        txtStatus.setText("Extrayendo texto del PDF...");
        documentExecutor.execute(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new java.io.IOException("No se puede abrir el PDF");
                String text = PdfTextReader.read(stream);
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    lastText = text == null ? "" : text.trim();
                    setBusy(false);
                    txtStatus.setText(lastText.isEmpty()
                            ? "PDF sin texto extraíble. Puede necesitar OCR; no se ha generado una salida."
                            : "Texto extraído; las imágenes no se han procesado con OCR. Pulsa Anonimizar.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isCurrentDocument(version)) return;
                    lastText = "";
                    setBusy(false);
                    txtStatus.setText("No se pudo leer el PDF (puede estar protegido o dañado): " + e.getMessage());
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

        String anonymized = anonymizeText(lastText);
        exportPdf(anonymized);
    }

    private String anonymizeText(String text) {

        text = text.replaceAll("\\b\\d{8}[A-Z]\\b", "[DNI]");
        text = text.replaceAll("\\b[XYZ]\\d{7}[A-Z]\\b", "[NIE]");
        text = text.replaceAll("\\b[A-HJ-NP-SUVW]\\d{7}[0-9A-J]\\b", "[CIF]");
        text = text.replaceAll("\\bES\\d{22}\\b", "[IBAN]");
        text = text.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+", "[EMAIL]");
        text = text.replaceAll("\\b[6-7]\\d{8}\\b", "[TEL]");
        text = text.replaceAll("\\b[A-ZÁÉÍÓÚÑ][a-záéíóúñ]+\\b", "[PERSONA]");

        return text;
    }

    private void exportPdf(String text) {

        try {
            PdfDocument pdf = new PdfDocument();
            Paint paint = new Paint();
            paint.setTextSize(12);

            PdfDocument.PageInfo pageInfo =
                    new PdfDocument.PageInfo.Builder(595, 842, 1).create();

            PdfDocument.Page page = pdf.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            int x = 10;
            int y = 25;

            String[] lines = text.split("\n");

            for (String line : lines) {
                canvas.drawText(line, x, y, paint);
                y += 18;

                if (y > 800) {
                    pdf.finishPage(page);

                    pageInfo =
                            new PdfDocument.PageInfo.Builder(595, 842, 1).create();

                    page = pdf.startPage(pageInfo);
                    canvas = page.getCanvas();
                    y = 25;
                }
            }

            pdf.finishPage(page);

            File file = new File(getExternalFilesDir(null), "anonimizado.pdf");
            FileOutputStream fos = new FileOutputStream(file);

            pdf.writeTo(fos);

            pdf.close();
            fos.close();

            txtStatus.setText("PDF generado: " + file.getAbsolutePath());

        } catch (Exception e) {
            txtStatus.setText("Error PDF: " + e.getMessage());
        }
    }
}