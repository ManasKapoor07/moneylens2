package com.moneylens.parser;

import com.moneylens.dto.StatementParseResult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ICICI Bank savings account statements.
 *
 * Transaction date format: DD-MMM-YY  (e.g. 15-Jan-24)
 *
 * Period patterns:
 *   "for the period January 15, 2024 - February 14, 2024"
 *   "From: 01/01/2024  To: 31/01/2024"  (some older/PDF-export variants)
 */
public class IciciStatementParser extends AbstractSavingsAccountParser {

    @Override
    public boolean supports(String rawText) {
        String header = rawText.length() > 2000 ? rawText.substring(0, 2000) : rawText;
        return header.contains("ICICI Bank")
                || header.contains("ICICI BANK")
                || header.contains("ICICIB")
                || header.contains("ICICI0");
    }

    @Override
    protected String getBankName() { return "ICICI"; }

    // "for the period January 15, 2024 - February 14, 2024"
    private static final Pattern PERIOD_FOR_THE = Pattern.compile(
            "(?i)for the period\\s+([A-Za-z]+ \\d{1,2},?\\s*\\d{4})\\s*[-–]\\s*([A-Za-z]+ \\d{1,2},?\\s*\\d{4})"
    );
    // "From: 01/01/2024  To: 31/01/2024"
    private static final Pattern PERIOD_SLASH = Pattern.compile(
            "(?:From)\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(?:To)\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})"
    );

    // "January 15, 2024" or "January 15 2024"
    private static final DateTimeFormatter MMMM_D_YYYY  = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MMMM_D_YYYY2 = DateTimeFormatter.ofPattern("MMMM d yyyy",  Locale.ENGLISH);

    @Override
    protected void detectPeriod(String rawText, StatementParseResult result) {
        Matcher m = PERIOD_FOR_THE.matcher(rawText);
        if (m.find()) {
            trySetPeriod(result, m.group(1).trim(), m.group(2).trim());
            if (result.getStatementFromDate() != null) return;
        }

        m = PERIOD_SLASH.matcher(rawText);
        if (m.find()) {
            try {
                result.setStatementFromDate(LocalDate.parse(m.group(1), DMY_SLASH));
                result.setStatementToDate(LocalDate.parse(m.group(2), DMY_SLASH));
            } catch (Exception e) {
                log.debug("ICICI: slash period parse failed: {}", e.getMessage());
            }
        }

        if (result.getStatementFromDate() == null) {
            log.warn("ICICI: could not detect statement period from PDF text");
        }
    }

    private void trySetPeriod(StatementParseResult result, String from, String to) {
        // Normalise: remove comma before year if absent ("January 15 2024" vs "January 15, 2024")
        String f = from.replace(",", "").replaceAll("\\s+", " ");
        String t = to.replace(",",   "").replaceAll("\\s+", " ");
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{ MMMM_D_YYYY2, MMMM_D_YYYY }) {
            try {
                result.setStatementFromDate(LocalDate.parse(f, fmt));
                result.setStatementToDate(LocalDate.parse(t, fmt));
                return;
            } catch (Exception ignored) {}
        }
        log.debug("ICICI: period parse failed for '{}' '{}'", from, to);
    }
}
