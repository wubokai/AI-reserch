package com.aiquantresearch.api.research.filing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class FilingTextProcessor {

    public static final String PARSER_VERSION = "filing_parser_v2";
    private static final int MAX_SOURCE_CHARACTERS = 3_000_000;
    private static final int CHUNK_CHARACTERS = 1_800;
    private static final int CHUNK_OVERLAP = 180;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ITEM_1A = Pattern.compile("^ITEM\\s+1A\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_1 = Pattern.compile("^ITEM\\s+1\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_7A = Pattern.compile("^ITEM\\s+7A\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_7 = Pattern.compile("^ITEM\\s+7\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_8 = Pattern.compile("^ITEM\\s+8\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_ITEM = Pattern.compile("^ITEM\\s+[0-9]+[A-Z]?\\b.*", Pattern.CASE_INSENSITIVE);

    public ProcessedFiling process(String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("Filing HTML must not be blank");
        }
        boolean truncated = requiresBoundedProcessing(html);
        String boundedHtml = truncated
                ? html.substring(0, safeBoundary(html, MAX_SOURCE_CHARACTERS))
                : html;
        Document document = Jsoup.parse(boundedHtml);
        document.select("script,style,noscript,iframe,object,embed,svg,form").remove();
        List<String> lines = blockLines(document);
        List<FilingSection> sections = sections(lines);
        String cleanedText = sections.stream()
                .map(section -> section.name() + "\n" + section.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow(() -> new IllegalArgumentException("Filing has no usable text"));
        List<FilingChunk> chunks = new ArrayList<>();
        sections.forEach(section -> chunks.addAll(chunks(section)));
        return new ProcessedFiling(
                cleanedText,
                sections,
                List.copyOf(chunks),
                truncated,
                html.length(),
                boundedHtml.length()
        );
    }

    public static boolean requiresBoundedProcessing(String html) {
        return html != null && html.length() > MAX_SOURCE_CHARACTERS;
    }

    public static int maximumSourceCharacters() {
        return MAX_SOURCE_CHARACTERS;
    }

    private static int safeBoundary(String value, int proposedEnd) {
        int end = Math.min(value.length(), proposedEnd);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            return end - 1;
        }
        return end;
    }

    private static List<String> blockLines(Document document) {
        List<String> lines = new ArrayList<>();
        for (Element element : document.select("h1,h2,h3,h4,h5,h6,p,li,td")) {
            String text = normalize(element.text());
            if (!text.isBlank() && (lines.isEmpty() || !lines.getLast().equals(text))) {
                lines.add(text);
            }
        }
        if (lines.isEmpty()) {
            String text = normalize(document.text());
            if (!text.isBlank()) {
                lines.add(text);
            }
        }
        return List.copyOf(lines);
    }

    private static List<FilingSection> sections(List<String> lines) {
        Map<String, StringBuilder> content = new LinkedHashMap<>();
        String current = "DOCUMENT";
        content.put(current, new StringBuilder());
        for (String line : lines) {
            String section = canonicalSection(line);
            if (section != null) {
                current = section;
                content.computeIfAbsent(current, ignored -> new StringBuilder());
            }
            StringBuilder target = content.get(current);
            if (!target.isEmpty()) {
                target.append('\n');
            }
            target.append(line);
        }
        return content.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> new FilingSection(entry.getKey(), entry.getValue().toString()))
                .toList();
    }

    private static String canonicalSection(String line) {
        String heading = line.toUpperCase(Locale.ROOT);
        if (ITEM_1A.matcher(heading).matches()) {
            return "ITEM_1A_RISK_FACTORS";
        }
        if (ITEM_1.matcher(heading).matches()) {
            return "ITEM_1_BUSINESS";
        }
        if (ITEM_7A.matcher(heading).matches()) {
            return "ITEM_7A_MARKET_RISK";
        }
        if (ITEM_7.matcher(heading).matches()) {
            return "ITEM_7_MD_AND_A";
        }
        if (ITEM_8.matcher(heading).matches()) {
            return "ITEM_8_FINANCIAL_STATEMENTS";
        }
        if (ANY_ITEM.matcher(heading).matches()) {
            return heading.replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        }
        return null;
    }

    private static List<FilingChunk> chunks(FilingSection section) {
        List<FilingChunk> chunks = new ArrayList<>();
        String content = section.content();
        int index = 0;
        int start = 0;
        while (start < content.length()) {
            int desiredEnd = Math.min(content.length(), start + CHUNK_CHARACTERS);
            int end = desiredEnd;
            if (desiredEnd < content.length()) {
                int boundary = Math.max(content.lastIndexOf('\n', desiredEnd),
                        content.lastIndexOf(' ', desiredEnd));
                if (boundary > start + CHUNK_CHARACTERS / 2) {
                    end = boundary;
                }
            }
            String value = content.substring(start, end).strip();
            if (!value.isBlank()) {
                chunks.add(new FilingChunk(
                        section.name(),
                        index++,
                        value,
                        start,
                        end,
                        Math.max(1, (int) Math.ceil(value.length() / 4.0))
                ));
            }
            if (end >= content.length()) {
                break;
            }
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        return List.copyOf(chunks);
    }

    private static String normalize(String value) {
        return WHITESPACE.matcher(value.replace('\u0000', ' ')).replaceAll(" ").strip();
    }

    public record ProcessedFiling(
            String cleanedText,
            List<FilingSection> sections,
            List<FilingChunk> chunks,
            boolean truncated,
            int sourceCharacterCount,
            int processedCharacterCount
    ) {
    }

    public record FilingSection(String name, String content) {
    }

    public record FilingChunk(
            String sectionName,
            int index,
            String content,
            int characterStart,
            int characterEnd,
            int tokenEstimate
    ) {
    }
}
