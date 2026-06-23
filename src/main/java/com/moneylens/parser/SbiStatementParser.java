package com.moneylens.parser;

import com.moneylens.dto.ParsedTransaction;
import com.moneylens.dto.StatementParseResult;
import com.moneylens.entity.TransactionMode;
import com.moneylens.entity.TransactionType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses State Bank of India (SBI) savings account statements.
 *
 * SBI Details column format (after PDFBox extraction):
 *   WDL TFR UPI/DR/<ref>/<name>/<vpa>/<desc> <account_ref> AT <branch_code> <branch_name>
 *   DEP TFR UPI/CR/<ref>/<name>/<vpa>/<desc> <account_ref> AT <branch_code> <branch_name>
 *   DEP TFR NEFT*<bankcode>*<neft_ref>*<sender> <account_ref> AT <branch_code> <branch_name>
 *
 * Key differences from the abstract base behaviour:
 *   1. Type prefix: "WDL TFR" = DEBIT, "DEP TFR" = CREDIT — used as the reliable type signal.
 *   2. UPI format uses /DR/ and /CR/ (not /P2M/ or /P2A/).
 *   3. NEFT format uses NEFT*bankcode*ref*name asterisk-delimited.
 *   4. Branch noise "AT XXXXX BRANCH_NAME" must be stripped from every narration.
 */
public class SbiStatementParser extends AbstractSavingsAccountParser {

    @Override
    public boolean supports(String rawText) {
        String header = rawText.length() > 2000 ? rawText.substring(0, 2000) : rawText;
        return header.contains("State Bank of India")
                || header.contains("STATE BANK OF INDIA")
                || header.contains("SBIN0");
    }

    @Override
    protected String getBankName() { return "SBI"; }

    // ── Period detection ──────────────────────────────────────────────────────

    private static final Pattern PERIOD_SLASH = Pattern.compile(
            "(?:Statement Period|Period)\\s*[:\\s]+\\s*(\\d{2}/\\d{2}/\\d{4})\\s+[Tt]o\\s+(\\d{2}/\\d{2}/\\d{4})"
    );
    private static final Pattern PERIOD_FROM_TO = Pattern.compile(
            "(?:From|FROM)\\s*[:\\s]+\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(?:To|TO)\\s*[:\\s]+\\s*(\\d{2}/\\d{2}/\\d{4})"
    );
    private static final Pattern PERIOD_MON = Pattern.compile(
            "(?:From Date|From)\\s*:?\\s*(\\d{1,2} [A-Za-z]{3} \\d{4})\\s+(?:To Date|To)\\s*:?\\s*(\\d{1,2} [A-Za-z]{3} \\d{4})"
    );
    // "Account Statement from 01 Jan 2024 to 31 Jan 2024"
    private static final Pattern PERIOD_ACCT_STMT = Pattern.compile(
            "(?i)Account\\s+Statement\\s+from\\s+(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4})\\s+to\\s+(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4})"
    );
    // "Statement From : 01-04-2025 to 31-03-2026"  (seen in header of screenshots)
    private static final Pattern PERIOD_STMT_FROM = Pattern.compile(
            "(?i)Statement\\s+From\\s*:?\\s*(\\d{2}-\\d{2}-\\d{4})\\s+to\\s+(\\d{2}-\\d{2}-\\d{4})"
    );
    private static final Pattern PERIOD_DASH = Pattern.compile(
            "(\\d{2}-\\d{2}-\\d{4})\\s+[Tt]o\\s+(\\d{2}-\\d{2}-\\d{4})"
    );

    private static final DateTimeFormatter DMY_DASH_FULL   = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DMY_MON_LENIENT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    @Override
    protected void detectPeriod(String rawText, StatementParseResult result) {
        if (trySlash(result, PERIOD_SLASH,     rawText)) return;
        if (trySlash(result, PERIOD_FROM_TO,   rawText)) return;
        if (tryMon(  result, PERIOD_MON,       rawText)) return;
        if (tryMon(  result, PERIOD_ACCT_STMT, rawText)) return;
        if (tryDash( result, PERIOD_STMT_FROM, rawText)) return;
        if (tryDash( result, PERIOD_DASH,      rawText)) return;
        log.warn("SBI: could not detect statement period from PDF text");
    }

    private boolean trySlash(StatementParseResult r, Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) return false;
        try {
            r.setStatementFromDate(LocalDate.parse(m.group(1), DMY_SLASH));
            r.setStatementToDate(LocalDate.parse(m.group(2), DMY_SLASH));
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean tryMon(StatementParseResult r, Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) return false;
        try {
            r.setStatementFromDate(LocalDate.parse(m.group(1).trim().replaceAll("\\s+", " "), DMY_MON_LENIENT));
            r.setStatementToDate(LocalDate.parse(m.group(2).trim().replaceAll("\\s+", " "), DMY_MON_LENIENT));
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean tryDash(StatementParseResult r, Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) return false;
        try {
            r.setStatementFromDate(LocalDate.parse(m.group(1), DMY_DASH_FULL));
            r.setStatementToDate(LocalDate.parse(m.group(2), DMY_DASH_FULL));
            return true;
        } catch (Exception e) { return false; }
    }

    // ── SBI-specific narration patterns ───────────────────────────────────────

    // "WDL TFR", "DEP TFR", "CEMTEX DEP" etc. at the very start
    private static final Pattern SBI_TX_PREFIX = Pattern.compile(
            "^(?:WDL\\s+TFR|DEP\\s+TFR|CEMTEX\\s+DEP|WDL\\s+AMT|DEP\\s+AMT"
            + "|INS\\s+DR|INS\\s+CR|CLG\\s+TFR|OTHERS\\s+DR|OTHERS\\s+CR"
            + "|BY\\s+TRANSFER|DR\\s+TFR|CR\\s+TFR|BY\\s+CLG|BY\\s+CASH)\\s+",
            Pattern.CASE_INSENSITIVE
    );

    // "AT 11326 MANGAL PARAO" branch noise at end — 3-6 digit code + all-caps words
    private static final Pattern SBI_BRANCH_SUFFIX = Pattern.compile(
            "\\s+AT\\s+\\d{3,6}(?:\\s+[A-Z][A-Z0-9 .'-]{1,30})+$"
    );

    // Trailing "-" placeholder for the empty debit or credit column
    private static final Pattern SBI_DASH_TRAIL = Pattern.compile("\\s*-\\s*$");

    // SBI UPI: UPI/DR/<ref>/<name>/<vpa_prefix>/<desc>
    //          UPI/CR/<ref>/<name>/<vpa_prefix>/<desc>
    //          UPI/DRC/<ref>/<name>/...
    // After 8+ digit ref removal the ref slot is empty → UPI/DR//<name>/...
    private static final Pattern SBI_UPI_NAME = Pattern.compile(
            "(?i)UPI/[DC]RC?/[^/]*/([A-Za-z][^/]{1,60}?)(?:/|$)"
    );

    // SBI NEFT: NEFT*<bankcode>*<txref>*<sender_name>
    private static final Pattern SBI_NEFT_STAR = Pattern.compile(
            "(?i)NEFT\\*[^*]+\\*[^*]+\\*([A-Za-z][^*\\n]{2,60})"
    );

    // ── Override type inference to use SBI prefix ─────────────────────────────

    @Override
    protected TransactionType inferTypeFromNarration(String narration) {
        String upper = narration.toUpperCase();
        // SBI-specific type indicators are the most reliable signal
        if (upper.startsWith("WDL ") || upper.startsWith("WDL\t"))  return TransactionType.DEBIT;
        if (upper.startsWith("DEP ") || upper.startsWith("DEP\t"))  return TransactionType.CREDIT;
        if (upper.startsWith("CEMTEX DEP"))                         return TransactionType.CREDIT;
        if (upper.startsWith("INS DR"))                             return TransactionType.DEBIT;
        if (upper.startsWith("INS CR"))                             return TransactionType.CREDIT;
        // Fall through to generic keyword checks
        return super.inferTypeFromNarration(narration);
    }

    // ── Override enrichFromNarration for SBI-specific parsing ─────────────────

    @Override
    protected void enrichFromNarration(ParsedTransaction tx, String narration) {
        // 1. Strip SBI branch noise and transaction prefix
        String cleaned = stripSbiNoise(narration);

        // 2. Update rawNarration with the cleaner version for registry lookups and display
        tx.setRawNarration(cleaned);

        String upper = cleaned.toUpperCase();

        // 3. UPI (SBI uses UPI/DR/ and UPI/CR/ instead of UPI/P2M/ or UPI/P2A/)
        if (upper.contains("UPI/DR") || upper.contains("UPI/CR")
                || upper.contains("UPI/DRC") || upper.startsWith("UPI")) {
            tx.setMode(TransactionMode.UPI);
            if (upper.contains("UPIRET") || upper.contains("UPI REFUND")) {
                tx.setMode(TransactionMode.UPI_REFUND);
                tx.setRefund(true);
            } else {
                extractSbiUpiCounterparty(tx, cleaned);
            }
            return;
        }

        // 4. NEFT (asterisk-delimited format in SBI)
        if (upper.startsWith("NEFT") || upper.contains("NEFT*")) {
            tx.setMode(TransactionMode.NEFT);
            extractSbiNeftCounterparty(tx, cleaned);
            return;
        }

        // 5. IMPS
        if (upper.startsWith("IMPS") || upper.contains("IMPS/")) {
            tx.setMode(TransactionMode.IMPS);
            extractSbiNeftCounterparty(tx, cleaned);
            return;
        }

        // 6. EMI / mandate / autopay
        if (upper.contains("EMI")) {
            tx.setMode(TransactionMode.EMI);
            return;
        }
        if (upper.contains("MANDATE") || upper.contains("ACH") || upper.contains("ECS")
                || upper.contains("AUTOPAY")) {
            tx.setMode(TransactionMode.AUTOPAY);
            return;
        }

        // 7. ATM
        if (upper.startsWith("ATM") || upper.contains("ATM WDL") || upper.contains("CASH WD")) {
            tx.setMode(TransactionMode.ATM);
            tx.setCounterpartyName("ATM Withdrawal");
            return;
        }

        // 8. Cheque / clearing
        if (upper.startsWith("CHQ") || upper.startsWith("CLG") || upper.contains("CLEARING")) {
            tx.setMode(TransactionMode.CHEQUE);
            return;
        }

        // 9. Remaining — apply known-merchant check on the raw cleaned narration
        tx.setMode(TransactionMode.OTHER);
        String resolved = resolveKnownMerchant(upper);
        if (resolved != null) tx.setCounterpartyName(resolved);
    }

    // ── SBI noise stripping ───────────────────────────────────────────────────

    private String stripSbiNoise(String narration) {
        if (narration == null) return "";
        String s = narration;

        // Remove trailing "-" (empty debit or credit column placeholder)
        s = SBI_DASH_TRAIL.matcher(s).replaceFirst("").trim();

        // Remove branch suffix: "AT 11326 MANGAL PARAO"
        Matcher branch = SBI_BRANCH_SUFFIX.matcher(s);
        if (branch.find()) s = s.substring(0, branch.start()).trim();

        // Remove WDL TFR / DEP TFR / CEMTEX DEP prefix
        s = SBI_TX_PREFIX.matcher(s).replaceFirst("").trim();

        return s;
    }

    // ── SBI UPI counterparty extraction ──────────────────────────────────────

    private void extractSbiUpiCounterparty(ParsedTransaction tx, String narration) {
        // Try UPI handle (some SBI narrations include @-form handle)
        Matcher handleMatcher = Pattern.compile("[A-Za-z0-9._]{2,}@[A-Za-z0-9]+").matcher(narration);
        if (handleMatcher.find()) {
            tx.setUpiHandle(handleMatcher.group().toLowerCase());
        }

        // Try structured SBI UPI name segment: UPI/DR//<name>/...
        Matcher nameMatcher = SBI_UPI_NAME.matcher(narration);
        if (nameMatcher.find()) {
            String raw = nameMatcher.group(1).trim();
            // Skip pure-digit segments (sometimes a reference ends up here)
            if (!raw.isEmpty() && !raw.matches("\\d+") && raw.length() >= 2) {
                String resolved = resolveKnownMerchant(raw.toUpperCase());
                tx.setCounterpartyName(resolved != null ? resolved : toTitleCase(raw));
                return;
            }
        }

        // Fallback: split on common delimiters, skip SBI-specific noise tokens
        String body = narration.replaceFirst("(?i)^UPI/[DC]RC?/[^/]*/", ""); // skip past ref slot
        String[] parts = body.split("[/\\-@\\s]+");
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty() || p.matches("\\d+") || p.length() < 2) continue;
            if (p.matches("(?i)UPI|DR|CR|DRC|UPIRET|AUTOPAY|SENT|COLLEC")) continue;
            String resolved = resolveKnownMerchant(p.toUpperCase());
            tx.setCounterpartyName(resolved != null ? resolved : toTitleCase(p));
            break;
        }
    }

    // ── SBI NEFT counterparty extraction ─────────────────────────────────────

    private void extractSbiNeftCounterparty(ParsedTransaction tx, String narration) {
        // NEFT*bankcode*txref*sender_name
        Matcher starMatcher = SBI_NEFT_STAR.matcher(narration);
        if (starMatcher.find()) {
            tx.setCounterpartyName(toTitleCase(starMatcher.group(1).trim()));
            return;
        }
        // Fallback: slash-delimited format  NEFT/CR/sender
        String body = narration.replaceFirst("(?i)^(NEFT|IMPS)[/\\- ]?(CR|DR)?[/\\- ]?", "");
        String[] parts = body.split("[-/*]");
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty() || p.matches("\\d+") || p.length() < 2) continue;
            tx.setCounterpartyName(toTitleCase(p));
            break;
        }
    }
}
