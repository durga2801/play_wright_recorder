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
                    System.out.println("[RECORDED] " + node.path("action").asText() + " -> "
                            + node.path("label").asText(node.path("text").asText("")));
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
            String inputName = "INPUT".equals(action) || "CHANGE".equals(action) || "SELECT".equals(action)
                    ? NameUtil.inputName(element) : null;
            Boolean checked = n.has("checked") && !n.get("checked").isNull() ? n.get("checked").asBoolean() : null;
            events.add(new RecordedEvent(
                    sequence++, n.path("timestamp").asLong(), action, text(n, "url"), element,
                    inputName, textOrNull(n, "value"), checked, textOrNull(n, "key")
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

          const clean = v => (v || '').replace(/\\s+/g, ' ').trim();

          function associatedLabel(el) {
            try {
              if (el.labels && el.labels.length) {
                const t = Array.from(el.labels).map(x => clean(x.innerText || x.textContent)).filter(Boolean).join(' ');
                if (t) return t;
              }
              if (el.id) {
                const l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
                if (l) return clean(l.innerText || l.textContent);
              }
              const parent = el.closest && el.closest('label');
              if (parent) return clean(parent.innerText || parent.textContent);
              const labelledBy = el.getAttribute && el.getAttribute('aria-labelledby');
              if (labelledBy) {
                const t = labelledBy.split(/\\s+/).map(id => document.getElementById(id)).filter(Boolean)
                  .map(x => clean(x.innerText || x.textContent)).filter(Boolean).join(' ');
                if (t) return t;
              }
            } catch (_) {}
            return '';
          }

          function cssSelector(el) {
            if (!el || el.nodeType !== 1) return '';
            if (el.id) return '#' + CSS.escape(el.id);
            const parts = [];
            let node = el;
            for (let depth = 0; node && node.nodeType === 1 && depth < 5; depth++, node = node.parentElement) {
              let part = node.tagName.toLowerCase();
              if (node.getAttribute('name')) part += '[name="' + CSS.escape(node.getAttribute('name')) + '"]';
              else if (node.classList && node.classList.length) part += '.' + Array.from(node.classList).slice(0,2).map(CSS.escape).join('.');
              parts.unshift(part);
            }
            return parts.join(' > ');
          }

          function base(el) {
            const inputType = el.tagName && el.tagName.toLowerCase() === 'input' ? String(el.type || '') : '';
            return {
              timestamp: Date.now(),
              url: location.href,
              tag: (el.tagName || '').toLowerCase(),
              inputType,
              label: associatedLabel(el),
              ariaLabel: clean(el.getAttribute && el.getAttribute('aria-label')),
              role: clean(el.getAttribute && el.getAttribute('role')) || inputType,
              text: clean(el.innerText || el.textContent),
              id: clean(el.id),
              name: clean(el.getAttribute && el.getAttribute('name')),
              placeholder: clean(el.getAttribute && el.getAttribute('placeholder')),
              selector: cssSelector(el)
            };
          }

          function push(action, el, extra = {}) {
            if (!el || el.nodeType !== 1) return;
            const event = Object.assign(base(el), {action}, extra);
            window.__uiRecorderEvents.push(event);
            // console.log is intentionally used instead of console.debug because it is
            // consistently surfaced by Playwright/IDEs.
            console.log('__UI_RECORDER_EVENT__' + JSON.stringify(event));
          }

          function meaningfulClickTarget(target) {
            if (!target) return null;
            if (target.nodeType !== 1) target = target.parentElement;
            if (!target) return null;
            return target.closest && target.closest(
              'button,a,input,textarea,select,label,[role=button],[role=link],[role=tab],'+
              '[role=checkbox],[role=radio],[role=option],[role=menuitem],[role=combobox],'+
              '[onclick],[tabindex]'
            ) || target;
          }

          document.addEventListener('click', e => {
            const el = meaningfulClickTarget(e.target);
            if (!el) return;
            const type = String(el.type || '').toLowerCase();
            if (type === 'checkbox' || type === 'radio') {
              push(type === 'radio' ? 'RADIO' : 'CHECK', el, {checked: !!el.checked, value: el.value || null});
            } else {
              push('CLICK', el);
            }
          }, true);

          document.addEventListener('input', e => {
            const el = e.target;
            if (!el || el.nodeType !== 1) return;
            const tag = (el.tagName || '').toLowerCase();
            const type = String(el.type || '').toLowerCase();
            if (tag === 'select' || type === 'checkbox' || type === 'radio' || type === 'file') return;
            push('INPUT', el, {value: el.value ?? ''});
          }, true);

          document.addEventListener('change', e => {
            const el = e.target;
            if (!el || el.nodeType !== 1) return;
            const tag = (el.tagName || '').toLowerCase();
            const type = String(el.type || '').toLowerCase();
            if (tag === 'select') push('SELECT', el, {value: el.value ?? ''});
            else if (type === 'checkbox' || type === 'radio') push(type === 'radio' ? 'RADIO' : 'CHECK', el, {checked: !!el.checked, value: el.value || null});
            else push('CHANGE', el, {value: el.value ?? ''});
          }, true);

          document.addEventListener('keydown', e => {
            if (['Enter', 'Tab', 'Escape'].includes(e.key)) push('KEY', e.target, {key: e.key});
          }, true);

          console.log('__UI_RECORDER_READY__');
        })();
        """;
}
