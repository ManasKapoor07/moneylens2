package com.moneylens.categorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class AiMerchantClassifier {

    private static final Logger log = LoggerFactory.getLogger(AiMerchantClassifier.class);

    private static final int BATCH_SIZE = 40;

    // Tokens per response object: {"idx":0,"category":"FOOD_AND_DINING","confidence":0.9}
    // ≈ 20 tokens max. Add 20% buffer → 24 per item.
    private static final int TOKENS_PER_ITEM = 24;

    @Value("${openai.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String VALID_CATEGORIES = String.join(", ",
            "FOOD_AND_DINING", "GROCERIES", "SHOPPING", "TRANSPORT", "TRAVEL",
            "ENTERTAINMENT", "UTILITIES_AND_BILLS", "RENT_AND_HOUSING",
            "HEALTH_AND_FITNESS", "LOAN_AND_EMI", "INVESTMENT", "TRANSFERS",
            "REFUND", "CASH_WITHDRAWAL", "OTHER");

    // Strip bank-statement boilerplate that appears after a genuine narration.
    // e.g. "NOIDA GROCERY ... HDFC BANK LIMITED-THIS STATEMENT.-ADDRESS : A. K. TOWER, ..."
    private static final Pattern BANK_BOILERPLATE = Pattern.compile(
            "(?i)[-\\s]*(HDFC BANK LIMITED|STATE BANK OF INDIA|ICICI BANK|AXIS BANK|KOTAK BANK|YES BANK)" +
            "[-\\s]*THIS STATEMENT.*", Pattern.DOTALL);

    public static class MerchantInput {
        public final String merchantKey;
        public final String rawNarration;

        public MerchantInput(String merchantKey, String rawNarration) {
            this.merchantKey = merchantKey;
            this.rawNarration = rawNarration;
        }
    }

    public static class AiClassificationResult {
        public final String categoryName;
        public final double confidence;

        public AiClassificationResult(String categoryName, double confidence) {
            this.categoryName = categoryName;
            this.confidence = confidence;
        }
    }

    public AiClassificationResult classify(String merchantKey, String rawNarration) {
        Map<String, AiClassificationResult> result =
                classifyBatch(List.of(new MerchantInput(merchantKey, rawNarration)));
        return result.get(merchantKey);
    }

    public Map<String, AiClassificationResult> classifyBatch(List<MerchantInput> merchants) {
        Map<String, AiClassificationResult> allResults = new HashMap<>();
        if (merchants.isEmpty()) return allResults;

        List<List<MerchantInput>> chunks = chunk(merchants, BATCH_SIZE);
        log.info("Classifying {} unique merchants in {} batch(es) of up to {}",
                merchants.size(), chunks.size(), BATCH_SIZE);

        for (List<MerchantInput> batch : chunks) {
            try {
                String prompt = buildPrompt(batch);
                String raw    = callModel(prompt, batch.size());
                allResults.putAll(parseResponse(raw, batch));
            } catch (Exception e) {
                log.warn("Batch AI classification failed for chunk of {} merchants: {}", batch.size(), e.getMessage());
            }
        }

        return allResults;
    }

    // ── Prompt: index-based, no merchant key in the response ─────────────────
    //
    // Each item is identified by its zero-based index. The AI returns
    //   [{"idx":0,"category":"...","confidence":0.9}, ...]
    // This keeps every response object small (~20 tokens) regardless of how
    // long the merchant key or narration is, so max_tokens never truncates.

    private String buildPrompt(List<MerchantInput> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify each Indian bank transaction below into exactly one category.\n");
        sb.append("Valid categories (use these exact spellings only): ").append(VALID_CATEGORIES).append("\n\n");
        sb.append("Transactions (idx | description):\n");

        for (int i = 0; i < batch.size(); i++) {
            MerchantInput m = batch.get(i);
            String desc = cleanNarration(m.merchantKey, m.rawNarration);
            sb.append(i).append(" | ").append(desc).append("\n");
        }

        sb.append("\nReturn ONLY a JSON array in the same order, one object per line:\n");
        sb.append("[{\"idx\":0,\"category\":\"CATEGORY_NAME\",\"confidence\":0.9}, ...]\n");
        sb.append("No markdown, no explanation — JSON array only.");
        return sb.toString();
    }

    /**
     * Produces a clean, short description for the AI.
     * Uses the merchantKey (already normalised) as primary, falls back to
     * a cleaned rawNarration. Strips bank statement boilerplate and truncates.
     */
    private String cleanNarration(String merchantKey, String rawNarration) {
        // Prefer the already-normalised merchant key if it's meaningful
        if (merchantKey != null && !merchantKey.isBlank() && !merchantKey.equals("UNKNOWN")) {
            String key = BANK_BOILERPLATE.matcher(merchantKey).replaceAll("").trim();
            if (!key.isBlank()) return truncate(key, 80);
        }
        if (rawNarration == null) return "UNKNOWN";
        String cleaned = BANK_BOILERPLATE.matcher(rawNarration).replaceAll("").trim();
        return truncate(cleaned.isBlank() ? rawNarration : cleaned, 80);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private String callModel(String prompt, int batchSize) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o-mini");
        body.put("messages", new Object[]{
                Map.of("role", "system", "content",
                        "You output only valid JSON arrays, nothing else. " +
                        "Never include merchant names or narrations in your response."),
                Map.of("role", "user", "content", prompt),
        });
        body.put("temperature", 0.0);
        // Each response object: {"idx":0,"category":"FOOD_AND_DINING","confidence":0.9}
        // ≈ 20 tokens. TOKENS_PER_ITEM adds 20% buffer.
        body.put("max_tokens", TOKENS_PER_ITEM * batchSize + 64); // +64 for array brackets & whitespace

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                "https://api.openai.com/v1/chat/completions", entity, Map.class);

        @SuppressWarnings("unchecked")
        var choices = (List<Map<String, Object>>) response.get("choices");
        @SuppressWarnings("unchecked")
        var message = (Map<String, Object>) choices.get(0).get("message");

        // Log finish_reason so we catch future truncations immediately
        String finishReason = (String) choices.get(0).get("finish_reason");
        if (!"stop".equals(finishReason)) {
            log.warn("AI response for batch of {} finished with reason '{}' — may be truncated",
                    batchSize, finishReason);
        }

        return (String) message.get("content");
    }

    // ── Parse: map by idx back to merchantKey ─────────────────────────────────

    private Map<String, AiClassificationResult> parseResponse(String raw, List<MerchantInput> batch) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("```json", "").replaceAll("```", "").trim();
        }

        Map<String, AiClassificationResult> results = new HashMap<>();
        try {
            JsonNode array = objectMapper.readTree(cleaned);
            if (!array.isArray()) {
                log.warn("Expected JSON array from batch classification, got: {}", cleaned);
                return results;
            }

            for (JsonNode node : array) {
                int    idx        = node.path("idx").asInt(-1);
                String category   = node.path("category").asText(null);
                double confidence = node.path("confidence").asDouble(0.5);

                if (idx < 0 || idx >= batch.size()) {
                    log.warn("AI returned out-of-range idx={} for batch of {} — ignoring", idx, batch.size());
                    continue;
                }
                if (category == null || category.isBlank()) {
                    log.warn("AI returned null/blank category for idx={} — ignoring", idx);
                    continue;
                }

                String merchantKey = batch.get(idx).merchantKey;
                results.put(merchantKey, new AiClassificationResult(category.trim().toUpperCase(), confidence));
            }

            if (results.size() < batch.size()) {
                log.warn("Batch returned {} results for {} requested — missing entries will use type fallback",
                        results.size(), batch.size());
            }

        } catch (Exception e) {
            log.warn("Could not parse batch AI classification JSON: '{}' — {}", cleaned, e.getMessage());
        }

        return results;
    }

    private List<List<MerchantInput>> chunk(List<MerchantInput> list, int size) {
        List<List<MerchantInput>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
