# AnonDoc

Prototipo Android para extraer texto de imágenes y PDF, ocultar datos personales y exportar texto revisado o un PDF que conserve el aspecto original.

## Flujo

1. Seleccionar un PDF o imagen.
2. Esperar a la extracción y al OCR.
3. Pulsar Anonimizar; opcionalmente indicar datos adicionales (un nombre, dirección o valor por línea).
4. Elegir Revisar resultado para texto editable, o Conservar diseño para un PDF con su aspecto original.
   En Conservar diseño, revisar todas las páginas, marcar con un dedo zonas adicionales (firmas, fotos, códigos), ampliar/desplazar con dos dedos y confirmar cada página. Deshacer elimina la última zona manual; las propuestas automáticas se mantienen.
5. Exportar expresamente el texto revisado o Guardar PDF con diseño tras confirmar todas las páginas. En Android 10 o posterior se guarda en Descargas/AnonDoc; en Android anteriores se elige destino con Guardar como.
6. Pulsar Ver archivos anonimizados y tocar un PDF para abrirlo con el visor instalado.

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

Se genera un documento nuevo que solo contiene el texto confirmado. No se copian imágenes, firmas, páginas originales ni sus metadatos. Es una reconstrucción textual, no una modificación del PDF original. Se ajustan las líneas y se usan nombres de archivo únicos. La copia de trabajo se crea en la caché privada y se elimina después del guardado. En Android 10 o posterior se publica el PDF completo en Descargas/AnonDoc mediante MediaStore, sin solicitar permisos amplios de almacenamiento. El botón Ver archivos anonimizados muestra los archivos disponibles de esa carpeta dentro de la aplicación; no depende de que un explorador externo admita abrir carpetas mediante intents. En versiones anteriores se usa el selector Guardar como y se registran los archivos elegidos. Es necesario tener un visor de PDF para abrirlos.

## Desarrollo y verificación

Java 17, Gradle 8.6, Android Gradle Plugin 8.2.2, SDK 34 y Build Tools 34.0.0. Configurar local.properties con la ruta local del SDK (no subirlo a Git).

- Compilar: ./gradlew assembleDebug
- Pruebas de reglas: ./gradlew testDebugUnitTest
- Pruebas en dispositivo o emulador: ./gradlew connectedDebugAndroidTest

GitHub Actions ejecuta compilación, pruebas unitarias y pruebas instrumentadas en Android 10/API 29 y Android 14/API 34. Incluye flujos de texto y diseño, conservación de imágenes/tamaño, borrado de píxeles sensibles, ausencia de capa de texto y bloqueo de páginas no confirmadas. Las pruebas manuales del selector y visores externos se dejan para el final; Android 5–9 y dispositivos reales siguen pendientes.

## PDF con el diseño original

El modo Conservar diseño está disponible al cargar un PDF correctamente. Renderiza cada página con PdfRenderer, propone ocultaciones con posiciones OCR y las mismas reglas/valores manuales y permite dibujar rectángulos adicionales. No hay exportación hasta confirmar todas las páginas; añadir o deshacer una zona invalida su confirmación. Requiere comprobación visual completa, incluidos firmas, fotografías, QR/códigos de barras y datos que el OCR no detecte. Una propuesta puede ocultar una línea completa o un bloque para proteger campos repartidos; no se garantiza precisión ni anonimización automática completa.

La salida se construye desde imágenes con las zonas ya pintadas de negro de forma opaca. No se insertan imágenes originales debajo de overlays ni se copian texto oculto, metadatos originales, adjuntos, formularios, anotaciones o firmas digitales. Se mantiene el aspecto de las áreas no censuradas, las imágenes, el tamaño visible, la orientación y el orden de páginas. No es una copia idéntica de los objetos PDF: el texto queda rasterizado, no seleccionable; los enlaces y formularios pierden interactividad y las firmas digitales no conservan validez. Se usa PNG/compresión sin pérdida después de renderizar (hasta 3 píxeles por punto, lado mayor máximo 2000 píxeles). Puede haber pérdida de nitidez al ampliar y aumento de tamaño. Límite adicional de 24 millones de píxeles totales; el exceso falla completo, sin publicar páginas parciales.

Las copias de revisión se guardan solo en caché privada y se eliminan al salir, fallar o terminar la Activity. Si el proceso es terminado abruptamente por el sistema, Android puede conservar la caché temporal hasta que la elimine; no se guarda progreso de revisión y es necesario empezar de nuevo. La copia de seguridad de la aplicación está desactivada. El original no se modifica. El guardado/listado usa el mismo OutputStore que el modo texto.
