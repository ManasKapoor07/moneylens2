package com.moneylens.parser;

import com.moneylens.dto.StatementParseResult;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Punjab National Bank (PNB) savings account statements.
 *
 * Period patterns:
 *   "From: 01/01/2024 To: 31/01/2024"
 *   "Statement from 01 Jan 2024 to 31 Jan 2024"
 */
public class PnbStatementParser extends AbstractSavingsAccountParser {

    @Override
    public boolean supports(String rawText) {
        String header = rawText.length() > 2000 ? rawText.substring(0, 2000) : rawText;
        return header.contains("Punjab National Bank")
                || header.contains("PUNJAB NATIONAL BANK")
                || header.contains("PUNB0");
    }

    @Override
    protected String getBankName() { return "PNB"; }

    private static final Pattern PERIOD_SLASH = Pattern.compile(
            "(?:From|FROM)\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(?:To|TO)\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})"
    );
    private static final Pattern PERIOD_MON = Pattern.compile(
            "(?:[Ss]tatement)?\\s*[Ff]rom\\s+(\\d{2} [A-Za-z]{3} \\d{4})\\s+[Tt]o\\s+(\\d{2} [A-Za-z]{3} \\d{4})"
    );

    @Override
    protected void detectPeriod(String rawText, StatementParseResult result) {
        Matcher m = PERIOD_SLASH.matcher(rawText);
        if (m.find()) {
            try {
                result.setStatementFromDate(LocalDate.parse(m.group(1), DMY_SLASH));
                result.setStatementToDate(LocalDate.parse(m.group(2), DMY_SLASH));
                return;
            } catch (Exception e) { log.debug("PNB: slash period parse failed: {}", e.getMessage()); }
        }
        m = PERIOD_MON.matcher(rawText);
        if (m.find()) {
            try {
                result.setStatementFromDate(LocalDate.parse(m.group(1), DMY_MON));
                result.setStatementToDate(LocalDate.parse(m.group(2), DMY_MON));
            } catch (Exception e) { log.debug("PNB: mon period parse failed: {}", e.getMessage()); }
        }
        if (result.getStatementFromDate() == null) {
            log.warn("PNB: could not detect statement period from PDF text");
        }
    }
}
