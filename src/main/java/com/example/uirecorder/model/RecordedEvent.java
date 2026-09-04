package com.example.uirecorder.model;

public record RecordedEvent(
        long sequence,
        long timestamp,
        String action,
        String url,
        ElementInfo element,
        String inputName,
        String value,
        Boolean checked,
        String key
) {
    public RecordedEvent withSequence(long newSequence) {
        return new RecordedEvent(newSequence, timestamp, action, url, element, inputName, value, checked, key);
    }
}
