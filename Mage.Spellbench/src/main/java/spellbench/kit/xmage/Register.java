package spellbench.kit.xmage;

import spellbench.kit.core.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The state-qualified mechanics register (design Section 3.4; A1 result review, change 3), as data: per card, whether
 * its triggers' resolution reads event data or captured values, whether its activated abilities read captured values
 * or paid costs, its optional and alternative costs, the emblem it creates. Built by {@code kit/register/scan.py}
 * from the pinned XMage sources and shipped in the kit jar ({@code register.json}). The world builder consults it
 * when it rebuilds a stack object or a command object; a card the register does not list is treated conservatively
 * (its triggers and activations approximate, with the search horizon).
 */
public final class Register {

    private static Map<String, Object> cards;
    private static final Map<String, Map<String, Object>> byFace = new LinkedHashMap<>();
    private static String source = "none";

    private Register() {
    }

    private static synchronized void load() {
        if (cards != null) {
            return;
        }
        cards = new LinkedHashMap<>();
        try (InputStream in = Register.class.getResourceAsStream("/spellbench/kit/xmage/register.json")) {
            if (in != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                }
                Map<String, Object> r = Json.parseObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
                cards = Json.obj(r, "cards");
                source = Json.str(r, "source");
            }
        } catch (IOException e) {
            System.err.println("kit-runner: register unreadable: " + e);
        }
        for (Map.Entry<String, Object> e : cards.entrySet()) {
            Map<String, Object> c = Json.obj(e.getValue());
            byFace.put(e.getKey(), c);
            if (e.getKey().contains(" // ")) {
                for (String face : e.getKey().split(" // ")) {
                    if (!byFace.containsKey(face)) {
                        byFace.put(face, c);
                    }
                }
            }
        }
    }

    /** The register entry of a card or face name, or null. */
    public static Map<String, Object> card(String name) {
        load();
        return name == null ? null : byFace.get(name);
    }

    public static boolean loaded() {
        load();
        return !cards.isEmpty();
    }

    public static String source() {
        load();
        return source;
    }

    /** Supported trigger state: the card's triggers read no event object or captured value. */
    public static boolean triggersEventFree(String name) {
        Map<String, Object> c = card(name);
        return c != null && "event_free".equals(c.get("triggers"));
    }

    /** The card's activated abilities read captured values or paid costs (or the card is unknown). */
    public static boolean activationReadsCaptured(String name) {
        Map<String, Object> c = card(name);
        return c == null || "captured_value".equals(c.get("activated"));
    }

    /** The card has optional or alternative costs whose paid state the observation does not show. */
    public static boolean optionalCosts(String name) {
        Map<String, Object> c = card(name);
        return c == null || c.get("optional_costs") != null;
    }

    /** The register row's optional-cost classes (XMage simple names), or null for an unknown card. */
    public static List<Object> optionalCostClasses(String name) {
        Map<String, Object> c = card(name);
        return c == null ? null : c.get("optional_costs") == null ? Collections.emptyList() : Json.arr(c, "optional_costs");
    }

    /** The token classes (XMage simple names) the register's cards create: every token a pool game can hold. */
    public static synchronized java.util.Set<String> tokenClasses() {
        load();
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Map<String, Object> c : byFace.values()) {
            for (Object t : Json.arr(c, "tokens")) {
                out.add(String.valueOf(t));
            }
        }
        return out;
    }

    /** The class of the one emblem a card creates when it can be rebuilt (a no-argument constructor), else null. */
    public static String emblemClass(String sourceName) {
        Map<String, Object> c = card(sourceName);
        if (c == null) {
            return null;
        }
        List<Object> emblems = Json.arr(c, "emblems");
        if (emblems.size() != 1 || !Json.bool(Json.obj(emblems.get(0)), "no_arg")) {
            return null;
        }
        return Json.str(Json.obj(emblems.get(0)), "class");
    }
}
