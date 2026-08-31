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

import android.graphics.pdf.PdfDocument;

public class MainActivity extends Activity {

    private Button btnSelect;
    private Button btnAnon;
    private TextView txtStatus;

    private static final int PICK_FILE = 1;

    private String lastText = "";

    @Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    try {
        setContentView(R.layout.activity_main);

        btnSelect = findViewById(R.id.btnSelect);
        btnAnon = findViewById(R.id.btnAnon);
        txtStatus = findViewById(R.id.txtStatus);

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
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();

            try {
                InputStream inputStream = getContentResolver().openInputStream(uri);

                String path = uri.toString().toLowerCase();

                if (path.contains("pdf")) {
                    handlePdf(inputStream);
                } else {
                    handleImage(uri);
                }

            } catch (Exception e) {
                txtStatus.setText("Error: " + e.getMessage());
            }
        }
    }

    private void handlePdf(InputStream inputStream) {
        try {
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            lastText = new String(buffer);

            txtStatus.setText("PDF cargado (lectura básica)");
        } catch (Exception e) {
            txtStatus.setText("Error PDF: " + e.getMessage());
        }
    }

    private void handleImage(Uri uri) {
        try {
            InputImage image = InputImage.fromFilePath(this, uri);

            TextRecognizer recognizer =
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        lastText = result.getText();
                        txtStatus.setText("OCR completado");
                    
                        processAnonymization();
                    })
                    .addOnFailureListener(e ->
                            txtStatus.setText("Error OCR: " + e.getMessage())
                    );

        } catch (Exception e) {
            txtStatus.setText("Error imagen: " + e.getMessage());
        }
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