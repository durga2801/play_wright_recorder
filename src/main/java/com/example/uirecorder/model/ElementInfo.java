package com.example.uirecorder.model;

public record ElementInfo(
        String tag,
        String type,
        String label,
        String ariaLabel,
        String role,
        String text,
        String id,
        String name,
        String placeholder,
        String selector
) {
}
