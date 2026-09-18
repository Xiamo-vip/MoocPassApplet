package top.xiamoi.moocpass.platform.impl;

import tools.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CxSecretDecoder {
    private static final Pattern FONT_DATA = Pattern.compile("data:(?:application/(?:font-ttf|x-font-ttf|octet-stream|x-font-opentype)|font/(?:ttf|otf))[^,]*,([A-Za-z0-9+/=\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Map<Character, Character>> FONT_MAPS = new ConcurrentHashMap<>();
    private static volatile List<Glyph> referenceGlyphs;
    private final Map<Character, Character> mapping;
    private final Font secretFont;
    private final boolean encryptedPage;

    private CxSecretDecoder(Map<Character, Character> mapping, Font secretFont, boolean encryptedPage) {
        this.mapping = mapping;
        this.secretFont = secretFont;
        this.encryptedPage = encryptedPage;
    }

    static CxSecretDecoder fromHtml(String html) {
        Matcher matcher = FONT_DATA.matcher(html);
        if (!matcher.find()) return new CxSecretDecoder(Map.of(), null, html.contains("font-cxsecret"));
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(matcher.group(1));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes)).deriveFont(100f);
            Map<Character, Character> map = FONT_MAPS.computeIfAbsent(hash, key -> {
                Map<Character, Character> stored = loadMap("chaoxing/cxsecret_" + key + ".json", false, key);
                if (stored.isEmpty()) stored = loadMap("chaoxing/cxsecret_map.json", true, key);
                Map<Character, Character> combined = new HashMap<>(stored);
                combined.putAll(matchFont(font, combined.keySet()));
                return Map.copyOf(combined);
            });
            return new CxSecretDecoder(map, font, true);
        } catch (Exception error) {
            return new CxSecretDecoder(Map.of(), null, true);
        }
    }

    Decoded decode(String text) {
        if (!encryptedPage || text == null || text.isEmpty()) return new Decoded(text == null ? "" : text, true);
        if (secretFont == null) return new Decoded(text, false);
        StringBuilder result = new StringBuilder(text.length());
        boolean complete = true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (encryptedCode(ch) && secretFont.canDisplay(ch)) {
                Character decoded = mapping.get(ch);
                if (decoded == null) complete = false;
                if (decoded == null) result.append("[未识别:").append(ch).append(']');
                else result.append(decoded);
            } else {
                result.append(ch);
            }
        }
        return new Decoded(result.toString(), complete);
    }

    record Decoded(String text, boolean complete) {}

    Decoded decode(Element element) {
        if (element == null) return new Decoded("", true);
        boolean inherited = encryptedElement(element);
        Element copy = element.clone();
        boolean[] complete = { true };
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text && (inherited || encryptedElement(text.parent()))) {
                    Decoded decoded = decode(text.getWholeText());
                    text.text(decoded.text());
                    complete[0] &= decoded.complete();
                }
            }
            @Override
            public void tail(Node node, int depth) {}
        }, copy);
        return new Decoded(copy.text().trim(), complete[0]);
    }

    private static boolean encryptedElement(Node node) {
        for (Node current = node; current != null; current = current.parent()) {
            if (current instanceof Element element && (element.hasClass("font-cxsecret")
                || element.attr("style").contains("cxsecret"))) return true;
        }
        return false;
    }

    private static Map<Character, Character> loadMap(String path, boolean requireVerified, String hash) {
        try (InputStream in = CxSecretDecoder.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return Map.of();
            var root = new ObjectMapper().readTree(in);
            if (requireVerified) {
                boolean verified = false;
                for (var item : root.path("verified")) {
                    if (hash.startsWith(item.asText())) { verified = true; break; }
                }
                if (!verified) return Map.of();
            } else if (!hash.equals(root.path("fontMd5").asText())) {
                return Map.of();
            }
            Map<Character, Character> result = new HashMap<>();
            root.path("table").properties().forEach(entry -> {
                String key = entry.getKey(), value = entry.getValue().asText();
                if (key.length() == 1 && value.length() == 1) result.put(key.charAt(0), value.charAt(0));
            });
            return Map.copyOf(result);
        } catch (Exception error) {
            return Map.of();
        }
    }

    private static boolean encryptedCode(int code) {
        return code >= 0x3400 && code <= 0x9fff || code >= 0xe000 && code <= 0xf8ff;
    }

    private static Map<Character, Character> matchFont(Font secretFont, Set<Character> known) {
        List<Integer> missing = new ArrayList<>();
        for (int code = 0x3400; code <= 0xf8ff; code++) {
            if (encryptedCode(code) && !known.contains((char) code) && secretFont.canDisplay(code)) missing.add(code);
        }
        if (missing.isEmpty()) return Map.of();
        List<Glyph> bases = referenceGlyphs();
        if (bases.isEmpty()) return Map.of();
        Map<Character, Character> result = new HashMap<>();
        for (int code : missing) {
            Glyph secret = glyph(secretFont, code);
            if (secret.area == 0) continue;
            double bestScore = 0;
            double secondScore = 0;
            int bestCode = 0;
            for (Glyph base : bases) {
                int intersection = 0;
                for (int i = 0; i < secret.bits.length; i++)
                    intersection += Long.bitCount(secret.bits[i] & base.bits[i]);
                double score = (double) intersection / (secret.area + base.area - intersection);
                if (score > bestScore) {
                    if (bestCode != base.code) secondScore = bestScore;
                    bestScore = score; bestCode = base.code;
                } else if (base.code != bestCode && score > secondScore) secondScore = score;
            }
            if (bestScore >= 0.75 && bestScore - secondScore >= 0.06)
                result.put((char) code, (char) bestCode);
        }
        return Map.copyOf(result);
    }

    private static List<Glyph> referenceGlyphs() {
        List<Glyph> cached = referenceGlyphs;
        if (cached != null) return cached;
        synchronized (CxSecretDecoder.class) {
            if (referenceGlyphs != null) return referenceGlyphs;
            List<Glyph> result = new ArrayList<>();
            try (InputStream in = CxSecretDecoder.class.getClassLoader().getResourceAsStream("chaoxing/cxsecret_reference.ttf")) {
                if (in != null) {
                    Font reference = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(100f);
                    var verified = loadMap("chaoxing/cxsecret_776ffbbbf2d4826ca34a7d06e7b38d67.json", false,
                        "776ffbbbf2d4826ca34a7d06e7b38d67");
                    verified.forEach((encoded, plain) -> {
                        Glyph shape = glyph(reference, encoded);
                        if (shape.area > 0) result.add(new Glyph(plain, shape.bits, shape.area));
                    });
                }
            } catch (Exception ignored) {}
            Font font = referenceFont();
            if (font != null) {
                for (int code = 0x3400; code <= 0x9fff; code++) {
                    if (!font.canDisplay(code)) continue;
                    Glyph glyph = glyph(font, code);
                    if (glyph.area > 0) result.add(glyph);
                }
            }
            return referenceGlyphs = List.copyOf(result);
        }
    }

    private static Font referenceFont() {
        for (String path : List.of("C:/Windows/Fonts/NotoSansSC-VF.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simsun.ttc")) {
            if (!Files.isRegularFile(Path.of(path))) continue;
            try { return Font.createFonts(Path.of(path).toFile())[0].deriveFont(100f); }
            catch (Exception ignored) {}
        }
        return null;
    }

    private static Glyph glyph(Font font, int code) {
        BufferedImage canvas = new BufferedImage(300, 300, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.setFont(font);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        var metrics = graphics.getFontMetrics();
        String text = String.valueOf((char) code);
        graphics.drawString(text, (300 - metrics.stringWidth(text)) / 2,
            (300 - metrics.getHeight()) / 2 + metrics.getAscent());
        graphics.dispose();
        int minX = 300, minY = 300, maxX = -1, maxY = -1;
        for (int y = 0; y < 300; y++) for (int x = 0; x < 300; x++) {
            if (canvas.getRaster().getSample(x, y, 0) <= 5) continue;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        long[] bits = new long[36];
        if (maxX < 0) return new Glyph(code, bits, 0);
        BufferedImage scaled = new BufferedImage(48, 48, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D scale = scaled.createGraphics();
        scale.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        scale.drawImage(canvas, 0, 0, 48, 48, minX, minY, maxX + 1, maxY + 1, null);
        scale.dispose();
        int area = 0;
        for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
            if (scaled.getRaster().getSample(x, y, 0) <= 100) continue;
            int index = y * 48 + x;
            bits[index / 64] |= 1L << (index % 64);
            area++;
        }
        return new Glyph(code, bits, area);
    }

    private record Glyph(int code, long[] bits, int area) {}
}
