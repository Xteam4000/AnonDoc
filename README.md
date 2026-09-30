# AnonDoc

Prototipo Android para extraer texto de imágenes y PDF, ocultar datos personales y exportar un PDF de texto revisado.

## Flujo

1. Seleccionar un PDF o imagen.
2. Esperar a la extracción y al OCR.
3. Pulsar Anonimizar; opcionalmente indicar datos adicionales (un nombre, dirección o valor por línea).
4. Revisar y corregir el texto propuesto.
5. Exportar expresamente el texto revisado.

## Lectura y límites

- PDFBox extrae el texto integrado. PdfRenderer y el modelo latino de ML Kit aplican OCR local a todas las páginas, incluidas páginas mixtas.
- Límites del PDF: 25 MB, 30 páginas y renderizado de hasta 1600 píxeles en su lado mayor.
- Los archivos que excedan los límites o fallen en una página no producen una salida parcial.
- Los PDF protegidos que requieren contraseña o impiden extraer contenido muestran un error.
- Las páginas sin texto reconocido se señalan. No se implementa reconocimiento fiable de escritura manuscrita.
- Se usa una copia temporal privada del PDF y se elimina al terminar o fallar.
- El OCR puede omitir, duplicar o alterar caracteres; se conserva el texto integrado y se añaden líneas OCR distintas.
- DOCX, ODT, lotes y envío a IA no están implementados.

## Anonimización

Reglas para DNI/NIE/NIF, IBAN españoles, correos, teléfonos españoles con separadores, campos de nombres/direcciones, fecha de nacimiento y CSV/CVE, códigos postales etiquetados y nombres precedidos de tratamientos. Se admiten valores literales adicionales indicados por el usuario.

No se reemplaza indiscriminadamente toda palabra con mayúscula inicial. Los nombres libres, direcciones sin contexto, identificadores extranjeros y errores de OCR requieren revisión humana. No se garantiza anonimización completa ni detección de todas las categorías de datos personales.

Se genera un documento nuevo que solo contiene el texto confirmado. No se copian imágenes, firmas, páginas originales ni sus metadatos. Es una reconstrucción textual, no una modificación del PDF original. Se ajustan las líneas y se usan nombres de archivo únicos.

## Desarrollo y verificación

Java 17, Gradle 8.6, Android Gradle Plugin 8.2.2, SDK 34 y Build Tools 34.0.0. Configurar local.properties con la ruta local del SDK (no subirlo a Git).

- Compilar: ./gradlew assembleDebug
- Pruebas de reglas: ./gradlew testDebugUnitTest
- Pruebas en dispositivo o emulador: ./gradlew connectedDebugAndroidTest

GitHub Actions ejecuta compilación, pruebas unitarias y pruebas instrumentadas en Android 10/API 29. Pendientes las pruebas manuales del selector, la revisión editable y el ciclo de vida en dispositivos reales.
