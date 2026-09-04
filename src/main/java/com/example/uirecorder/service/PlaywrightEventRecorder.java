package com.example.uirecorder.service;

import com.example.uirecorder.model.ElementInfo;
import com.example.uirecorder.model.RecordedEvent;
import com.example.uirecorder.util.NameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PlaywrightEventRecorder implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;
    private final List<JsonNode> captured = new CopyOnWriteArrayList<>();
    private static final String CONSOLE_PREFIX = "__UI_RECORDER_EVENT__";

    public PlaywrightEventRecorder(boolean headless) {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
        context = browser.newContext();

        // Install recorder in every document, including navigations/reloads.
        context.addInitScript(RECORDING_SCRIPT);
        page = context.newPage();

        // Primary bridge from browser JS -> Java.
        page.onConsoleMessage(message -> {
            String text = message.text();
            if (text != null && text.startsWith(CONSOLE_PREFIX)) {
                try {
                    JsonNode node = mapper.readTree(text.substring(CONSOLE_PREFIX.length()));
                    captured.add(node);
                    String semantic = firstNonBlank(
                            node.path("label").asText(""),
                            node.path("ariaLabel").asText(""),
                            node.path("name").asText(""),
                            node.path("id").asText(""),
                            node.path("text").asText(""),
                            node.path("placeholder").asText("")
                    );
                    System.out.println("[RECORDED] " + node.path("action").asText() + " -> " + semantic);
                } catch (Exception e) {
                    System.err.println("Unable to parse recorder event: " + e.getMessage());
                }
            }
        });
    }

    public void open(String url) {
        page.navigate(url);
        page.waitForLoadState();
        System.out.println("Recorder installed: " + isRecorderInstalled());
    }

    public boolean isRecorderInstalled() {
        try {
            return Boolean.TRUE.equals(page.evaluate("() => window.__uiRecorderInstalled === true"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns events captured through the console bridge and also pulls the current page's
     * in-memory event list as a fallback. The fallback is particularly useful when an IDE/browser
     * suppresses debug-style console messages.
     */
    public List<RecordedEvent> readEvents() {
        mergeCurrentPageFallback();

        List<RecordedEvent> events = new ArrayList<>();
        long sequence = 1;
        for (JsonNode n : captured) {
            ElementInfo element = new ElementInfo(
                    text(n, "tag"), text(n, "inputType"), text(n, "label"), text(n, "ariaLabel"),
                    text(n, "role"), text(n, "text"), text(n, "id"), text(n, "name"),
                    text(n, "placeholder"), text(n, "selector")
            );
            String action = text(n, "action");
            String elementName = NameUtil.elementName(element);
            Boolean checked = n.has("checked") && !n.get("checked").isNull() ? n.get("checked").asBoolean() : null;
            events.add(new RecordedEvent(
                    sequence++, n.path("timestamp").asLong(), action, text(n, "url"),
                    elementName, element, textOrNull(n, "value"), checked, textOrNull(n, "key")
            ));
        }
        return events;
    }

    private void mergeCurrentPageFallback() {
        try {
            Object raw = page.evaluate("() => window.__uiRecorderEvents || []");
            JsonNode array = mapper.valueToTree(raw);
            if (!array.isArray()) return;

            Set<String> seen = new HashSet<>();
            for (JsonNode n : captured) seen.add(eventKey(n));

            for (JsonNode n : array) {
                String key = eventKey(n);
                if (seen.add(key)) captured.add(n);
            }
        } catch (Exception e) {
            System.err.println("Recorder fallback read failed: " + e.getMessage());
        }
    }

    private String eventKey(JsonNode n) {
        return n.path("timestamp").asText() + "|" + n.path("action").asText() + "|"
                + n.path("selector").asText() + "|" + n.path("value").asText();
    }

    private String text(JsonNode n, String field) {
        return n.path(field).asText("");
    }

    private String textOrNull(JsonNode n, String field) {
        if (!n.has(field) || n.get(field).isNull()) return null;
        String value = n.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "unnamed_element";
    }

    @Override
    public void close() {
        browser.close();
        playwright.close();
    }

    private static final String RECORDING_SCRIPT = """
        (() => {
          if (window.__uiRecorderInstalled) return;
          window.__uiRecorderInstalled = true;
          window.__uiRecorderEvents = [];

          const clean = v => (v || '').replace(/\s+/g, ' ').trim();
          const shortText = v => {
            const t = clean(v);
            return t.length > 160 ? t.substring(0, 160) : t;
          };

          function associatedLabel(el) {
            try {
              if (!el) return '';
              if (el.labels && el.labels.length) {
                const t = Array.from(el.labels)
                  .map(x => clean(x.innerText || x.textContent))
                  .filter(Boolean).join(' ');
                if (t) return t;
              }
              if (el.id) {
                const l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
                if (l) return clean(l.innerText || l.textContent);
              }
              const parentLabel = el.closest && el.closest('label');
              if (parentLabel) return clean(parentLabel.innerText || parentLabel.textContent);

              const labelledBy = el.getAttribute && el.getAttribute('aria-labelledby');
              if (labelledBy) {
                const t = labelledBy.split(/\s+/)
                  .map(id => document.getElementById(id)).filter(Boolean)
                  .map(x => clean(x.innerText || x.textContent)).filter(Boolean).join(' ');
                if (t) return t;
              }

              // Common form wrappers when markup does not use label[for].
              const wrapper = el.closest && el.closest(
                '.form-group,.form-field,.field,.input-group,.mat-mdc-form-field,.mat-form-field'
              );
              if (wrapper) {
                const l = wrapper.querySelector('label,.mat-mdc-form-field-label,.mat-form-field-label');
                if (l) return clean(l.innerText || l.textContent);
              }
            } catch (_) {}
            return '';
          }

          // Deliberately avoid long DOM paths. This is only a last-resort stable hint.
          function stableSelector(el) {
            if (!el || el.nodeType !== 1) return '';
            if (el.getAttribute('data-testid')) return '[data-testid="' + CSS.escape(el.getAttribute('data-testid')) + '"]';
            if (el.getAttribute('data-test')) return '[data-test="' + CSS.escape(el.getAttribute('data-test')) + '"]';
            if (el.id) return '#' + CSS.escape(el.id);
            if (el.getAttribute('name')) return el.tagName.toLowerCase() + '[name="' + CSS.escape(el.getAttribute('name')) + '"]';
            return '';
          }

          function semanticText(el) {
            const tag = (el.tagName || '').toLowerCase();
            const role = clean(el.getAttribute && el.getAttribute('role'));
            if (['button','a','option'].includes(tag) ||
                ['button','link','tab','option','menuitem'].includes(role)) {
              return shortText(el.innerText || el.textContent);
            }
            return '';
          }

          function base(el) {
            const tag = (el.tagName || '').toLowerCase();
            const inputType = tag === 'input' ? String(el.type || '') : '';
            return {
              timestamp: Date.now(),
              url: location.href,
              tag,
              inputType,
              label: associatedLabel(el),
              ariaLabel: clean(el.getAttribute && el.getAttribute('aria-label')),
              role: clean(el.getAttribute && el.getAttribute('role')) || inputType,
              text: semanticText(el),
              id: clean(el.id),
              name: clean(el.getAttribute && el.getAttribute('name')),
              placeholder: clean(el.getAttribute && el.getAttribute('placeholder')),
              selector: stableSelector(el)
            };
          }

          function semanticName(el) {
            const b = base(el);
            return b.label || b.ariaLabel || b.name || b.id || b.text || b.placeholder || 'unnamed_element';
          }

          function push(action, el, extra = {}) {
            if (!el || el.nodeType !== 1) return;
            const event = Object.assign(base(el), {action}, extra);
            window.__uiRecorderEvents.push(event);
            console.log('__UI_RECORDER_EVENT__' + JSON.stringify(event));
          }

          function meaningfulTarget(target) {
            if (!target) return null;
            if (target.nodeType !== 1) target = target.parentElement;
            if (!target) return null;

            let el = target.closest && target.closest(
              'button,a,input,textarea,select,[role=button],[role=link],[role=tab],'+
              '[role=checkbox],[role=radio],[role=option],[role=menuitem],[role=combobox],'+
              '[contenteditable=true],[onclick]'
            );

            // If a label was clicked, use its associated control instead of recording the label HTML/text.
            if (!el) {
              const label = target.closest && target.closest('label');
              if (label && label.control) el = label.control;
            }
            return el || null;
          }

          document.addEventListener('click', e => {
            const el = meaningfulTarget(e.target);
            if (!el) return; // Do not capture arbitrary div/span HTML structure.

            const tag = (el.tagName || '').toLowerCase();
            const type = String(el.type || '').toLowerCase();

            if (type === 'checkbox' || type === 'radio') {
              push(type === 'radio' ? 'RADIO' : 'CHECK', el,
                   {checked: !!el.checked, value: el.value || null});
              return;
            }

            // A click used only to focus a text/select field is noise. INPUT/SELECT captures it semantically.
            if (tag === 'textarea' || tag === 'select' ||
                (tag === 'input' && !['button','submit','reset'].includes(type))) {
              return;
            }

            push('CLICK', el);
          }, true);

          document.addEventListener('input', e => {
            const el = e.target;
            if (!el || el.nodeType !== 1) return;
            const tag = (el.tagName || '').toLowerCase();
            const type = String(el.type || '').toLowerCase();
            if (tag === 'select' || ['checkbox','radio','file','button','submit','reset'].includes(type)) return;
            push('INPUT', el, {value: el.value ?? ''});
          }, true);

          document.addEventListener('change', e => {
            const el = e.target;
            if (!el || el.nodeType !== 1) return;
            const tag = (el.tagName || '').toLowerCase();
            const type = String(el.type || '').toLowerCase();
            if (tag === 'select') {
              const option = el.options && el.selectedIndex >= 0 ? el.options[el.selectedIndex] : null;
              push('SELECT', el, {value: el.value ?? '', selectedText: option ? clean(option.text) : ''});
            } else if (type === 'checkbox' || type === 'radio') {
              push(type === 'radio' ? 'RADIO' : 'CHECK', el,
                   {checked: !!el.checked, value: el.value || null});
            } else {
              push('CHANGE', el, {value: el.value ?? ''});
            }
          }, true);

          document.addEventListener('keydown', e => {
            if (!['Enter', 'Tab', 'Escape'].includes(e.key)) return;
            const el = meaningfulTarget(e.target) || e.target;
            if (el && el.nodeType === 1) push('KEY', el, {key: e.key});
          }, true);

          console.log('__UI_RECORDER_READY__');
        })();
        """;
}
