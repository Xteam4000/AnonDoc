package com.anon.doc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

/** Conservative rules plus user-supplied values. Always requires human review. */
final class TextAnonymizer {
    private static final Pattern POSTAL_LOCALITY = Pattern.compile(
            "(?iu).*\\b(?:0[1-9]|[1-4]\\d|5[0-2])\\d{3}\\b[ \\t,;-]+[\\p{L}][\\p{L}\\p{M} .'-]{1,60}.*");
    private static final Pattern ADDRESS = Pattern.compile(
            "(?iu).*\\b(?:calle|c/|avenida|avda\\.?|plaza|paseo|carretera|camino|ronda|traves[ií]a|"
                    + "urbanizaci[oó]n|pol[ií]gono|apartado|bloque|portal)\\b.*");
    private static final Pattern FIELD_LABEL = Pattern.compile(
            "(?iu)^[ \\t]*(?:nombre(?:[ \\t]+y[ \\t]+apellidos)?|apellidos|titular|cliente|"
                    + "destinatario|direcci[oó]n(?:[ \\t]+de[ \\t]+suministro)?|domicilio|datos[ \\t]+del[ \\t]+cliente|"
                    + "dni|nie|nif|cif|iban|cuenta[ \\t]+bancaria|tel[eé]fono|m[oó]vil|correo|e-?mail|email|"
                    + "cups|n[uú]mero[ \\t]+de[ \\t]+cliente|referencia[ \\t]+de[ \\t]+contrato)[ \\t:=-]*$");
    private static final Pattern FIELD_WITH_VALUE = Pattern.compile(
            "(?iu)^.*\\b(?:nombre(?:[ \\t]+y[ \\t]+apellidos)?|apellidos|titular|cliente|destinatario|"
                    + "direcci[oó]n(?:[ \\t]+de[ \\t]+suministro)?|domicilio|dni|nie|nif|cif|iban|"
                    + "cuenta[ \\t]+bancaria|tel[eé]fono|m[oó]vil|correo|e-?mail|email|fecha[ \\t]+de[ \\t]+nacimiento|"
                    + "csv|cve|cups|n[uú]mero[ \\t]+de[ \\t]+cliente|referencia[ \\t]+de[ \\t]+contrato)"
                    + "[ \\t]*[:=-][ \\t]*\\S.+$");
    private static final Set<String> NON_PERSON_LINES = new HashSet<>(Arrays.asList(
            "factura", "factura de electricidad", "aviso", "notificacion", "notificación",
            "informe", "tribunal supremo", "atencion al cliente", "atención al cliente",
            "datos del cliente", "direccion de suministro", "dirección de suministro"));
    private TextAnonymizer() {}

    static String anonymize(String source, String additionalValues) {
        String text = source == null ? "" : source;
        text = hidePostalBlocks(text);
        text = hideField(text, "nombre(?:[ \\t]+y[ \\t]+apellidos)?|apellidos|"
                + "titular|interesad[oa]|paciente|trabajador(?:a)?|"
                + "demandante|demandad[oa]|representante", "[PERSONA]");
        text = hideField(text, "direcci[oó]n|domicilio(?:[ \\t]+(?:fiscal|habitual))?", "[DIRECCION]");
        text = hideField(text, "dni", "[DNI]");
        text = hideField(text, "nie", "[NIE]");
        text = hideField(text, "nif|cif", "[NIF]");
        text = hideField(text, "iban|cuenta[ \\t]+bancaria", "[IBAN]");
        text = hideField(text, "tel[eé]fono|m[oó]vil", "[TEL]");
        text = hideField(text, "correo(?:[ \\t]+electr[oó]nico)?|e-?mail|email", "[EMAIL]");
        text = hideField(text, "fecha[ \\t]+de[ \\t]+nacimiento", "[FECHA_NACIMIENTO]");
        text = hideField(text, "csv|cve|c[oó]digo[ \\t]+seguro[ \\t]+de[ \\t]+verificaci[oó]n", "[CSV]");

        text = text.replaceAll("(?iu)https?://[^\\s<>]*[?&](?:csv|cve|codigo|code)=[^\\s<>]+", "[URL_VERIFICACION]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])ES(?:[\\s-]*\\d){22}(?![ \\t-]*\\d|[\\p{L}\\p{N}])", "[IBAN]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])\\d(?:[\\s.-]?\\d){7}[\\s-]?[A-Z](?![\\p{L}\\p{N}])", "[DNI]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])[XYZ](?:[\\s.-]?\\d){7}[\\s-]?[A-Z](?![\\p{L}\\p{N}])", "[NIE]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])[ABCDEFGHJNPQRSUVW](?:[ \\t.-]?\\d){7}[ \\t-]?[0-9A-J](?![\\p{L}\\p{N}])", "[NIF]");
        text = text.replaceAll("(?iu)[\\p{L}\\p{N}._%+-]+[ \\t]*@[ \\t]*[\\p{L}\\p{N}-]+(?:[ \\t]*\\.[ \\t]*[\\p{L}\\p{N}-]+)+", "[EMAIL]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])(?:(?:\\+34|0034)[ \\t.-]*)?[6789](?:[ \\t.-]?\\d){8}(?![ \\t.-]?\\d|\\p{L})", "[TEL]");
        text = text.replaceAll("(?imu)(\\b(?:c[oó]digo[ \\t]+postal|c\\.?[ \\t]*p\\.?)"
                + "[ \\t]*[:=]?[ \\t]*)(?:0[1-9]|[1-4]\\d|5[0-2])\\d{3}\\b", "$1[CP]");
        text = text.replaceAll("(?imu)(?<!\\p{L})(?:calle|avenida|avda\\.?|"
                + "plaza|paseo|carretera|c/)[ \\t]+[^\\r\\n]+", "[DIRECCION]");

        // Recognize explicit personal titles, not every capitalized word.
        String word = "(?:\\p{Lu}[\\p{Ll}\\p{M}]+|\\p{Lu}{2,})(?:[-'][\\p{L}]+)?";
        String name = word + "(?:[ \\t]+(?:(?:de|del|la|las|los|y)[ \\t]+){0,2}"
                + word + "){1,5}";
        text = text.replaceAll("(?<!\\p{L})(?iu:don|doña|d\\.|dña\\.|sr\\.|sra\\.)[ \\t]+"
                + name + "(?!\\p{L})", "[PERSONA]");
        if (additionalValues != null) {
            for (String value : additionalValues.split("\\r?\\n")) {
                value = value.trim();
                if (value.isEmpty()) continue;
                text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])"
                        + Pattern.quote(value) + "(?![\\p{L}\\p{N}])",
                        Matcher.quoteReplacement("[DATO]"));
            }
        }
        return text;
    }

    /** Detects unlabelled recipient blocks without treating ordinary prose as a person's name. */
    private static String hidePostalBlocks(String text) {
        String[] lines = text.split("\\r?\\n", -1);
        boolean[] hide = sensitiveLines(Arrays.asList(lines), "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(hide[i] ? "[DATO_PERSONAL]" : lines[i]);
        }
        return out.toString();
    }

    static boolean[] sensitiveLines(List<String> lines, String additionalValues) {
        boolean[] sensitive = new boolean[lines.size()];
        boolean[] postalOrAddress = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            postalOrAddress[i] = POSTAL_LOCALITY.matcher(line).matches() || ADDRESS.matcher(line).matches();
            sensitive[i] = postalOrAddress[i] || !line.equals(anonymizeSingleLine(line, additionalValues));
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            if (FIELD_LABEL.matcher(line).matches()) {
                for (int j = i + 1; j < lines.size() && j <= i + 2; j++) {
                    if (!lines.get(j).trim().isEmpty()) sensitive[j] = true;
                }
            }
            if (isProbablePersonLine(line)
                    && ((i > 0 && postalOrAddress[i - 1]) || (i + 1 < lines.size() && postalOrAddress[i + 1])
                    || (i + 2 < lines.size() && postalOrAddress[i + 2]))) sensitive[i] = true;
        }
        return sensitive;
    }

    private static String anonymizeSingleLine(String line, String additionalValues) {
        String result = line;
        if(FIELD_WITH_VALUE.matcher(line).matches()) return "[DATO]";
        result = result.replaceAll("(?iu)https?://[^\\s<>]*[?&](?:csv|cve|codigo|code)=[^\\s<>]+", "[DATO]");
        result = result.replaceAll("(?iu)(?<![\\p{L}\\p{N}])ES(?:[\\s-]*\\d){22}(?![ \\t-]*\\d|[\\p{L}\\p{N}])", "[DATO]");
        result = result.replaceAll("(?iu)(?<![\\p{L}\\p{N}])(?:\\d(?:[\\s.-]?\\d){7}[\\s-]?[A-Z]|[XYZ](?:[\\s.-]?\\d){7}[\\s-]?[A-Z]|[ABCDEFGHJNPQRSUVW](?:[ \\t.-]?\\d){7}[ \\t-]?[0-9A-J])(?![\\p{L}\\p{N}])", "[DATO]");
        result = result.replaceAll("(?iu)[\\p{L}\\p{N}._%+-]+[ \\t]*@[ \\t]*[\\p{L}\\p{N}-]+(?:[ \\t]*\\.[ \\t]*[\\p{L}\\p{N}-]+)+", "[DATO]");
        result = result.replaceAll("(?iu)(?<![\\p{L}\\p{N}])(?:(?:\\+34|0034)[ \\t.-]*)?[6789](?:[ \\t.-]?\\d){8}(?![ \\t.-]?\\d|\\p{L})", "[DATO]");
        if (additionalValues != null) for (String value : additionalValues.split("\\r?\\n")) {
            value = value.trim();
            if (!value.isEmpty()) result = result.replaceAll("(?iu)" + Pattern.quote(value), "[DATO]");
        }
        return result;
    }

    private static boolean isProbablePersonLine(String line) {
        String normalized = line.toLowerCase(Locale.ROOT).trim();
        if (NON_PERSON_LINES.contains(normalized) || line.length() < 5 || line.length() > 80
                || line.matches(".*\\d.*") || line.matches(".*[.:;!?].*")) return false;
        String[] words = line.split("[ \\t]+");
        if (words.length < 2 || words.length > 7) return false;
        int names = 0;
        for (String word : words) {
            if (word.matches("(?iu)(?:de|del|la|las|los|y)") || word.isEmpty()) continue;
            if (!word.matches("[\\p{L}\\p{M}][\\p{L}\\p{M}'-]*")) return false;
            if (word.equals(word.toUpperCase(Locale.ROOT)) || Character.isUpperCase(word.codePointAt(0))) names++;
        }
        return names >= 2;
    }

    private static String hideField(String text, String labels, String marker) {
        return text.replaceAll("(?imu)(\\b(?:" + labels
                + ")[ \\t]*[:=][ \\t]*)[^\\r\\n]+", "$1" + marker);
    }
}
