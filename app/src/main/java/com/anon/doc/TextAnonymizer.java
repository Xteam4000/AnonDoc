package com.anon.doc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative rules plus user-supplied values. Always requires human review. */
final class TextAnonymizer {
    private TextAnonymizer() {}

    static String anonymize(String source, String additionalValues) {
        String text = source == null ? "" : source;
        text = hideField(text, "nombre(?:[ \\t]+y[ \\t]+apellidos)?|apellidos|"
                + "titular|interesad[oa]|paciente|trabajador(?:a)?|"
                + "demandante|demandad[oa]|representante", "[PERSONA]");
        text = hideField(text, "direcci[oó]n|domicilio(?:[ \\t]+(?:fiscal|habitual))?", "[DIRECCION]");
        text = hideField(text, "dni", "[DNI]");
        text = hideField(text, "nie", "[NIE]");
        text = hideField(text, "nif|cif", "[NIF]");
        text = hideField(text, "iban|cuenta[ \\t]+bancaria", "[IBAN]");
        text = hideField(text, "tel[eé]fono|m[oó]vil", "[TEL]");
        text = hideField(text, "fecha[ \\t]+de[ \\t]+nacimiento", "[FECHA_NACIMIENTO]");
        text = hideField(text, "csv|cve|c[oó]digo[ \\t]+seguro[ \\t]+de[ \\t]+verificaci[oó]n", "[CSV]");

        text = text.replaceAll("(?iu)https?://[^\\s<>]*[?&](?:csv|cve|codigo|code)=[^\\s<>]+", "[URL_VERIFICACION]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])ES(?:[\\s-]*\\d){22}(?![ \\t-]*\\d|[\\p{L}\\p{N}])", "[IBAN]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])\\d(?:[\\s.-]?\\d){7}[\\s-]?[A-Z](?![\\p{L}\\p{N}])", "[DNI]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])[XYZ](?:[\\s.-]?\\d){7}[\\s-]?[A-Z](?![\\p{L}\\p{N}])", "[NIE]");
        text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])[ABCDEFGHJNPQRSUVW](?:[ \\t.-]?\\d){7}[ \\t-]?[0-9A-J](?![\\p{L}\\p{N}])", "[NIF]");
        text = text.replaceAll("(?iu)[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}", "[EMAIL]");
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

    private static String hideField(String text, String labels, String marker) {
        return text.replaceAll("(?imu)(\\b(?:" + labels
                + ")[ \\t]*[:=][ \\t]*)[^\\r\\n]+", "$1" + marker);
    }
}
