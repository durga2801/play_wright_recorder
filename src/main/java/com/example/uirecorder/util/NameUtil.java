package com.example.uirecorder.util;

import com.example.uirecorder.model.ElementInfo;

import java.text.Normalizer;
import java.util.Locale;

public final class NameUtil {
    private NameUtil() {}

    /**
     * Human-readable semantic element name for the recording.
     * Priority is intentionally label/name based rather than DOM/CSS based.
     */
    public static String elementName(ElementInfo element) {
        String source = firstNonBlank(
                element.label(),
                element.ariaLabel(),
                element.name(),
                element.id(),
                element.text(),
                element.placeholder()
        );
        return source == null ? "unnamed_element" : source.trim();
    }

    /**
     * Stable machine-friendly form, useful when Copilot wants a variable/data name.
     */
    public static String inputName(ElementInfo element) {
        String source = elementName(element);
        String normalized = Normalizer.normalize(source, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "input" : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
