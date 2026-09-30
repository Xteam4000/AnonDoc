package com.anon.doc;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextAnonymizerTest {
    @Test public void masksIdentifiersWithCaseAndSeparators() {
        String result = TextAnonymizer.anonymize(
                "12345678z / x 1234567 l / B-1234567-8\n"
                + "ES91 2100 0418 4502 0005 1332", "");
        assertFalse(result.contains("1234567"));
        assertFalse(result.contains("2100"));
        assertTrue(result.contains("[DNI]"));
        assertTrue(result.contains("[NIE]"));
        assertTrue(result.contains("[NIF]"));
        assertTrue(result.contains("[IBAN]"));
    }

    @Test public void masksMobileLandlineInternationalAndEmail() {
        String result = TextAnonymizer.anonymize(
                "+34 612 345 678 / 967-123-456 / 0034 712 345 678 / juan@example.es", "");
        assertFalse(result.contains("345"));
        assertFalse(result.contains("967"));
        assertFalse(result.contains("example"));
        assertTrue(result.contains("[EMAIL]"));
    }

    @Test public void masksContextualNamesAddressesAndVerificationCodes() {
        String result = TextAnonymizer.anonymize(
                "Nombre y apellidos: JUAN PEREZ LOPEZ\n"
                + "Domicilio: Calle Mayor 4, 02001 Albacete\n"
                + "CSV: AB12-CD34-EF56\n"
                + "CP: 02001\n"
                + "Don Juan de la Cruz solicita una copia.", "");
        assertFalse(result.contains("JUAN"));
        assertFalse(result.contains("Mayor"));
        assertFalse(result.contains("AB12"));
        assertFalse(result.contains("02001"));
        assertFalse(result.contains("Juan"));
        assertTrue(result.contains("solicita una copia"));
    }

    @Test public void preservesOrdinaryCapitalizedWordsAndLegalReferences() {
        String text = "Sentencia del Tribunal Supremo. Artículo 24. España y Albacete.";
        assertEquals(text, TextAnonymizer.anonymize(text, ""));
    }

    @Test public void additionalValuesAreLiteralCaseInsensitiveAndBounded() {
        String result = TextAnonymizer.anonymize(
                "ANA (PEREZ) y Ana (Perez). Anabel mantiene su nombre.", "Ana (Perez)\nAna");
        assertFalse(result.contains("PEREZ"));
        assertFalse(result.contains("Perez"));
        assertTrue(result.contains("Anabel"));
    }

    @Test public void doesNotLeakAcrossDocuments() {
        assertFalse(TextAnonymizer.anonymize("DNI 12345678Z", "").contains("12345678Z"));
        assertEquals("Texto nuevo sin datos", TextAnonymizer.anonymize("Texto nuevo sin datos", ""));
    }

    @Test public void hidesLabeledIdentifiersEvenWhenOcrMisreadsCharacters() {
        String result = TextAnonymizer.anonymize("DNI: I2345678Z\nIBAN: ES9I 2I00 O418", "");
        assertFalse(result.contains("I234"));
        assertFalse(result.contains("2I00"));
    }

    @Test public void masksEmailsWithOcrSpacesAndLabeledCorruptedValues() {
        String result = TextAnonymizer.anonymize(
                "juan @example.es / ana@example . es\nCorreo: juan @ example . es", "");
        assertFalse(result.replaceAll("\\s+", "").contains("juan@example.es"));
        assertFalse(result.replaceAll("\\s+", "").contains("ana@example.es"));
        assertTrue(result.contains("[EMAIL]"));
    }

    @Test public void doesNotMislabelMoneyAsPostalCode() {
        assertEquals("Importe: 12345 euros", TextAnonymizer.anonymize("Importe: 12345 euros", ""));
    }
}
