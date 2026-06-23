package com.moneylens.categorization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class MerchantKeyNormalizer {
    private static final Logger log = LoggerFactory.getLogger(TransactionCategorizationService.class);


    private static final Pattern NOISE_PREFIX = Pattern.compile(
            "(?i)^(UPI[-/]?|NEFT[-/]?|IMPS[-/]?|POS[-/]?|ACH[-/]?)");
    private static final Pattern NOISE_SUFFIX = Pattern.compile(
            "(?i)(\\.PAYU|\\.RAZORPAY|\\.CCAVENUE|@[A-Z]+|\\d{6,})$");
    private static final Pattern NON_ALPHA_TAIL = Pattern.compile("[^A-Za-z]+$");

    // Strip bank address boilerplate that leaks into narrations at PDF page breaks.
    // Pattern anchors on the bank name + "THIS STATEMENT" phrase and discards everything after.
    private static final Pattern BANK_BOILERPLATE = Pattern.compile(
            "(?i)[-\\s]*(HDFC BANK LIMITED|STATE BANK OF INDIA|ICICI BANK|AXIS BANK|KOTAK BANK|YES BANK)" +
            "[-\\s]*THIS STATEMENT.*", Pattern.DOTALL);

    private static final int MAX_MERCHANT_KEY_LENGTH = 255;


    public String normalize(String merchantName, String rawNarration) {
        String source = (merchantName != null && !merchantName.isBlank()) ? merchantName : rawNarration;
        if (source == null || source.isBlank()) return "UNKNOWN";

        // Strip bank boilerplate BEFORE any other cleaning so it doesn't end up in the DB key.
        source = BANK_BOILERPLATE.matcher(source).replaceAll("").trim();
        if (source.isBlank()) return "UNKNOWN";

        String cleaned = source.trim().toUpperCase();
        cleaned = NOISE_PREFIX.matcher(cleaned).replaceAll("");
        cleaned = NOISE_SUFFIX.matcher(cleaned).replaceAll("");
        cleaned = NON_ALPHA_TAIL.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (cleaned.isEmpty()) return "UNKNOWN";

        if (cleaned.length() > MAX_MERCHANT_KEY_LENGTH) {
            log.warn("Merchant key exceeded {} chars (was {}), truncating — likely a narration parsing bug upstream: '{}...'",
                    MAX_MERCHANT_KEY_LENGTH, cleaned.length(), cleaned.substring(0, Math.min(80, cleaned.length())));
            cleaned = cleaned.substring(0, MAX_MERCHANT_KEY_LENGTH);
        }

        return cleaned;
    }
}