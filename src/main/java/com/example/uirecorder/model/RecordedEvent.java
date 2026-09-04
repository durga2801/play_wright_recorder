package com.example.uirecorder.model;

public record RecordedEvent(
        long sequence,
        long timestamp,
        String action,
        String url,
        String elementName,
        ElementInfo element,
        String value,
        Boolean checked,
        String key
) {
    public RecordedEvent withSequence(long newSequence) {
        return new RecordedEvent(newSequence, timestamp, action, url, elementName, element, value, checked, key);
    }
}
