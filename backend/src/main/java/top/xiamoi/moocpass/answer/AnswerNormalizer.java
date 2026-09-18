package top.xiamoi.moocpass.answer;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import top.xiamoi.moocpass.infrastructure.Crypto;
import top.xiamoi.moocpass.infrastructure.Json;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Component
public class AnswerNormalizer {
    private static final Set<String> YES = Set.of("是", "对", "正确", "确定", "√", "对的", "是的", "正确的", "true", "t", "yes", "1");
    private static final Set<String> NO = Set.of("非", "否", "错", "错误", "×", "x", "错的", "不对", "不正确", "不正确的", "不是", "false", "f", "no", "0");

    public record Match(boolean matched, boolean needsReview, String value, List<Integer> indices,
                        List<String> blanks, String method) {
        public static Match missing() { return new Match(false, true, "", List.of(), List.of(), "NONE"); }
    }

    public String normalized(String value) {
        return Normalizer.normalize(Jsoup.parse(Objects.toString(value, "")).text(), Normalizer.Form.NFKC)
            .replaceAll("^[A-Za-z][.、．:：)）]\\s*", "")
            .replaceAll("[\\s\\u00a0]+", "").toLowerCase(Locale.ROOT).trim();
    }

    public String questionKey(String platform, String type, String question, List<String> options, String context) {
        List<String> parts = new ArrayList<>(List.of(platform, type, normalized(question), context));
        parts.addAll(options.stream().map(this::normalized).toList());
        return Crypto.hash(Json.write(parts));
    }

    public Match resolve(String type, String raw, List<String> options, int blankCount) {
        if (raw == null || raw.isBlank() || raw.length() > 20000) return Match.missing();
        String answer = raw.trim().replaceAll("^(?:答案|正确答案)\\s*[:：]\\s*", "");
        answer = answer.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (answer.isBlank() || answer.matches("(?i)^(?:UNKNOWN|N/A|无法确定|不确定|不知道|无答案)[。.!！\\s]*$"))
            return Match.missing();
        if ("subjective".equals(type) || "4".equals(type))
            return new Match(true, false, answer, List.of(), List.of(), "TEXT");
        List<String> normalizedOptions = options == null ? List.of() : options.stream().map(this::normalized).toList();
        if ("judgement".equals(type) || "3".equals(type)) {
            String letters = optionLetters(answer, false);
            if ("A".equals(letters)) {
                if (options != null && options.size() >= 1 && (options.get(0).contains("错") || options.get(0).contains("错误") || options.get(0).toLowerCase(Locale.ROOT).contains("false") || options.get(0).contains("否"))) {
                    return new Match(true, false, "false", List.of(0), List.of(), "OPTION_LABEL");
                }
                return new Match(true, false, "true", List.of(0), List.of(), "OPTION_LABEL");
            }
            if ("B".equals(letters)) {
                if (options != null && options.size() >= 2 && (options.get(1).contains("对") || options.get(1).contains("正确") || options.get(1).toLowerCase(Locale.ROOT).contains("true") || options.get(1).contains("是"))) {
                    return new Match(true, false, "true", List.of(1), List.of(), "OPTION_LABEL");
                }
                return new Match(true, false, "false", List.of(1), List.of(), "OPTION_LABEL");
            }
            String pure = answer.replaceAll("[.。!！?？\\s*`_#]+", "").trim();
            if (pure.equalsIgnoreCase("A") || pure.equals("0")) {
                if (options != null && options.size() >= 1 && (options.get(0).contains("错") || options.get(0).contains("错误") || options.get(0).toLowerCase(Locale.ROOT).contains("false") || options.get(0).contains("否"))) {
                    return new Match(true, false, "false", List.of(0), List.of(), "OPTION_LABEL");
                }
                return new Match(true, false, "true", List.of(0), List.of(), "OPTION_LABEL");
            }
            if (pure.equalsIgnoreCase("B") || pure.equals("1")) {
                if (options != null && options.size() >= 2 && (options.get(1).contains("对") || options.get(1).contains("正确") || options.get(1).toLowerCase(Locale.ROOT).contains("true") || options.get(1).contains("是"))) {
                    return new Match(true, false, "true", List.of(1), List.of(), "OPTION_LABEL");
                }
                return new Match(true, false, "false", List.of(1), List.of(), "OPTION_LABEL");
            }
            String n = normalized(pure).replaceAll("[.。!！?？\\s*`_#]+", "");
            if (NO.contains(n) || n.startsWith("错误") || n.endsWith("错误") || n.startsWith("错") || n.endsWith("错") || n.contains("是错误的") || n.contains("说法错误") || n.contains("不正确") || n.contains("不对") || n.equals("false") || n.equals("f"))
                return new Match(true, false, "false", List.of(), List.of(), "BOOLEAN");
            if (YES.contains(n) || n.startsWith("正确") || n.endsWith("正确") || n.startsWith("对") || n.endsWith("对") || n.contains("是正确的") || n.contains("说法正确") || n.equals("true") || n.equals("t"))
                return new Match(true, false, "true", List.of(), List.of(), "BOOLEAN");
            return Match.missing();
        }
        if ("completion".equals(type) || "2".equals(type)) {
            String clean = answer.replaceAll("(?s)<think>.*?</think>", "").trim();
            clean = clean.replaceAll("^```(?:json)?\\s*|\\s*```$", "").trim();
            List<String> blanks;
            if (clean.startsWith("[")) {
                try {
                    var node = Json.read(clean);
                    if (!node.isArray()) return Match.missing();
                    blanks = new ArrayList<>();
                    for (var part : node) {
                        if (!part.isTextual()) return Match.missing();
                        blanks.add(part.asText().trim());
                    }
                } catch (RuntimeException error) { return Match.missing(); }
            } else if (blankCount == 1) {
                blanks = List.of(clean);
            } else {
                blanks = Arrays.stream(clean.split("\\s*(?:###|#|\\r?\\n|；|;)\\s*")).filter(s -> !s.isBlank()).toList();
            }
            if (blanks.size() != Math.max(1, blankCount) || blanks.stream().anyMatch(String::isBlank))
                return Match.missing();
            return new Match(true, false, String.join("#", blanks), List.of(), blanks, "BLANK_COUNT");
        }
        boolean multiple = "multiple".equals(type) || "1".equals(type);
        if (!multiple && !"single".equals(type) && !"0".equals(type)) return Match.missing();
        if (options == null || options.isEmpty() || options.size() > 26) return Match.missing();
        List<String> fragments = multiple ? split(answer) : List.of(answer);
        Set<Integer> exact = new LinkedHashSet<>();
        for (String fragment : fragments) {
            String n = normalized(fragment);
            List<Integer> matches = IntStream.range(0, options.size()).filter(i -> normalizedOptions.get(i).equals(n)).boxed().toList();
            if (matches.size() > 1) return Match.missing();
            exact.addAll(matches);
        }
        if ((!multiple && exact.size() == 1) || (multiple && !exact.isEmpty() && exact.size() == fragments.size()))
            return selected(exact, false, "NORMALIZED_EXACT");
        String letters = optionLetters(answer, multiple);
        if (letters.matches(multiple ? "[A-Z]{1,26}" : "[A-Z]")) {
            Set<Integer> indices = new LinkedHashSet<>();
            for (char c : letters.toCharArray()) {
                int index = c - 'A';
                if (index >= options.size() || !indices.add(index)) return Match.missing();
            }
            return selected(indices, false, "OPTION_LABEL");
        }
        if (multiple) return Match.missing();
        String n = normalized(answer);
        int best = -1;
        double first = 0, second = 0;
        for (int i = 0; i < options.size(); i++) {
            double score = similarity(n, normalizedOptions.get(i));
            if (score > first) { second = first; first = score; best = i; }
            else second = Math.max(second, score);
        }
        if (best >= 0 && first >= .85 && first - second >= .15)
            return selected(Set.of(best), true, "SIMILARITY_REVIEW");
        return Match.missing();
    }
    private String optionLetters(String answer, boolean multiple) {
        String clean = answer.replaceAll("(?s)<think>.*?</think>", "").trim();
        clean = clean.replaceAll("[*`_#]+", "").trim();
        clean = clean.replaceAll("^[【(（\\[\\s]*(?:答案|正确答案|选项|参考答案|最终答案|我的答案)[】)）\\]\\s]*[:：为是]?\\s*", "").trim();
        if (clean.matches("^[A-Za-z]{1,6}$")) {
            return clean.toUpperCase(Locale.ROOT);
        }
        if (clean.matches("^[A-Za-z](?:[\\s,，、;；#|]+[A-Za-z])*$")) {
            return clean.replaceAll("[\\s,，、;；#|]+", "").toUpperCase(Locale.ROOT);
        }
        Matcher encMatcher = Pattern.compile("^[【(（\\[\\s]*([A-Za-z]+)[】)）\\]\\s]*$").matcher(clean);
        if (encMatcher.find()) {
            return encMatcher.group(1).toUpperCase(Locale.ROOT);
        }
        Matcher startMatcher = Pattern.compile("^(?:[【(（\\[\\s]*)([A-Za-z]+)[.)）\\]】、:：\\s]").matcher(clean);
        if (startMatcher.find()) {
            return startMatcher.group(1).toUpperCase(Locale.ROOT);
        }
        String normalized = Normalizer.normalize(clean, Normalizer.Form.NFKC);
        Pattern pattern = multiple
            ? Pattern.compile("(?i)(?:答案|正确答案|选项|选择|应选|为|是|选|key|answer)\\s*[:：为是]?\\s*([A-Za-z](?:\\s*[,，、;；#|和与]?\\s*[A-Za-z])*)")
            : Pattern.compile("(?i)(?:答案|正确答案|选项|选择|应选|为|是|选|key|answer)\\s*[:：为是]?\\s*([A-Za-z])(?:\\b|[^A-Za-z]|$)");
        Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).replaceAll("[\\s,，、;；#|和与.]+", "").toUpperCase(Locale.ROOT);
        }
        String compact = normalized.replaceAll("[\\s,，、;；#|.]+", "").toUpperCase(Locale.ROOT);
        if (compact.matches(multiple ? "[A-Z]{1,26}" : "[A-Z]")) return compact;
        return compact;
    }
    private List<String> split(String value) {
        if (value.startsWith("[")) {
            try {
                var node = Json.read(value);
                if (node.isArray()) {
                    List<String> parts = new ArrayList<>();
                    for (var part : node) if (part.isTextual()) parts.add(part.asText());
                    if (!parts.isEmpty()) return parts;
                }
            } catch (RuntimeException ignored) {}
        }
        return Arrays.stream(value.split("\\s*(?:###|#|\\r?\\n|；|;|、)\\s*")).filter(s -> !s.isBlank()).toList();
    }
    private Match selected(Set<Integer> indices, boolean review, String method) {
        List<Integer> sorted = indices.stream().sorted().toList();
        String value = sorted.stream().map(i -> String.valueOf((char) ('A' + i))).reduce("", String::concat);
        return new Match(true, review, value, sorted, List.of(), method);
    }
    private double similarity(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.length() < 2 || b.length() < 2) return 0;
        Map<String, Integer> pairs = new HashMap<>();
        for (int i = 0; i < a.length() - 1; i++) pairs.merge(a.substring(i, i + 2), 1, Integer::sum);
        int overlap = 0;
        for (int i = 0; i < b.length() - 1; i++) {
            String pair = b.substring(i, i + 2);
            int count = pairs.getOrDefault(pair, 0);
            if (count > 0) { overlap++; pairs.put(pair, count - 1); }
        }
        return 2.0 * overlap / (a.length() + b.length() - 2);
    }
}
