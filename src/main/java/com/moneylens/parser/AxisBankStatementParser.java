package com.moneylens.parser;

import com.moneylens.dto.StatementParseResult;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Axis Bank savings account statements.
 *
 * Period patterns:
 *   "From Date : 01/01/2024  To Date : 31/01/2024"
 *   "From : 01-01-2024 To : 31-01-2024"
 */
public class AxisBankStatementParser extends AbstractSavingsAccountParser {

    @Override
    public boolean supports(String rawText) {
        String header = rawText.length() > 2000 ? rawText.substring(0, 2000) : rawText;
        return header.contains("Axis Bank")
                || header.contains("AXIS BANK")
                || header.contains("UTIB0");
    }

    @Override
    protected String getBankName() { return "Axis Bank"; }

    private static final Pattern PERIOD_SLASH = Pattern.compile(
            "(?:From Date|From)\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(?:To Date|To)\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})"
    );
    private static final Pattern PERIOD_DASH = Pattern.compile(
            "(?:From Date|From)\\s*:?\\s*(\\d{2}-\\d{2}-\\d{4})\\s+(?:To Date|To)\\s*:?\\s*(\\d{2}-\\d{2}-\\d{4})"
    );
    private static final java.time.format.DateTimeFormatter DMY_DASH_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @Override
    protected void detectPeriod(String rawText, StatementParseResult result) {
        Matcher m = PERIOD_SLASH.matcher(rawText);
        if (m.find()) {
            try {
                result.setStatementFromDate(LocalDate.parse(m.group(1), DMY_SLASH));
                result.setStatementToDate(LocalDate.parse(m.group(2), DMY_SLASH));
                return;
            } catch (Exception e) { log.debug("Axis: slash period parse failed: {}", e.getMessage()); }
        }
        m = PERIOD_DASH.matcher(rawText);
        if (m.find()) {
            try {
                result.setStatementFromDate(LocalDate.parse(m.group(1), DMY_DASH_FMT));
                result.setStatementToDate(LocalDate.parse(m.group(2), DMY_DASH_FMT));
            } catch (Exception e) { log.debug("Axis: dash period parse failed: {}", e.getMessage()); }
        }
        if (result.getStatementFromDate() == null) {
            log.warn("Axis Bank: could not detect statement period from PDF text");
        }
    }
}
