package com.example.uirecorder.service;

import com.example.uirecorder.model.RecordedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reduces noisy browser events into automation-intent events.
 * In particular, repeated INPUT events for the same field become one final INPUT event.
 */
public final class EventNormalizer {

    public List<RecordedEvent> normalize(List<RecordedEvent> raw) {
        List<RecordedEvent> result = new ArrayList<>();

        for (RecordedEvent event : raw) {
            if ("INPUT".equals(event.action()) && isTextLike(event)) {
                int last = result.size() - 1;
                if (last >= 0 && "INPUT".equals(result.get(last).action())
                        && sameElement(result.get(last), event)) {
                    result.set(last, event);
                    continue;
                }
            }

            if ("CHANGE".equals(event.action()) && isTextLike(event)) {
                int last = result.size() - 1;
                if (last >= 0 && "INPUT".equals(result.get(last).action())
                        && sameElement(result.get(last), event)
                        && Objects.equals(result.get(last).value(), event.value())) {
                    continue;
                }
            }

            result.add(event);
        }

        List<RecordedEvent> sequenced = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            sequenced.add(result.get(i).withSequence(i + 1L));
        }
        return sequenced;
    }

    private boolean sameElement(RecordedEvent a, RecordedEvent b) {
        if (a.element() == null || b.element() == null) return false;
        String aKey = key(a);
        String bKey = key(b);
        return Objects.equals(aKey, bKey);
    }

    private String key(RecordedEvent event) {
        var e = event.element();
        return String.join("|",
                safe(e.id()), safe(e.name()), safe(e.label()), safe(e.ariaLabel()), safe(e.selector()));
    }

    private boolean isTextLike(RecordedEvent event) {
        if (event.element() == null) return false;
        String tag = safe(event.element().tag()).toLowerCase();
        String type = safe(event.element().type()).toLowerCase();
        if ("textarea".equals(tag)) return true;
        if (!"input".equals(tag)) return false;
        return !switch (type) {
            case "checkbox", "radio", "file", "button", "submit", "reset", "hidden", "range", "color" -> true;
            default -> false;
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
