package com.moneylens.parser;

import com.moneylens.dto.ParsedTransaction;
import com.moneylens.dto.StatementParseResult;
import com.moneylens.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Bank of Baroda (BOB) savings account statements.
 *
 * BOB (bob World mobile app) format — each transaction spans 2-3 lines:
 *
 *   UPI/606077435541/18:32:41/UPI/8868837142@ptaxis   ← description (overflows before anchor)
 *   4 01-03-2026 01-03-2026 - 500.00 2,775.39          ← anchor: serial# + date + vdate + D + C + bal
 *   /S                                                  ← optional description continuation
 *
 * OR when description fits inline:
 *   3 01-03-2026 01-03-2026 DCARDFEE/7417/ANNUALFEE FEB26JAN27 236.00 - 2,275.39
 *
 * The base parser doesn't work for BOB because:
 *   - Tx lines start with a serial number, not a date (^date patterns never match)
 *   - The footer timestamp "18/05/2026 08:34:59 PM" DOES start with a slash-date
 *     and is wrongly picked up as 16 fake transactions (one per page)
 *
 * Period patterns:
 *   "From : 01/01/2024 To : 31/01/2024"
 *   "Statement Period: 01/01/2024 to 31/01/2024"
 *   "Account Statement from 01-03-2026 to 31-03-2026"  ← bob World mobile app
 *   "From : 01 Jan 2024 To : 31 Jan 2024"
 */
public class BobStatementParser extends AbstractSavingsAccountParser {

    @Override
    public boolean supports(String rawText) {
        String header = rawText.length() > 2000 ? rawText.substring(0, 2000) : rawText;
        return header.contains("Bank of Baroda")
                || header.contains("BANK OF BARODA")
                || header.contains("BARB0")
                || header.contains("BARODA");
    }

    @Override
    protected String getBankName() { return "BOB"; }

    // ── Period detection ──────────────────────────────────────────────────────

    private static final Pattern PERIOD_SLASH = Pattern.compile(
            "(?:From)\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(?:To)\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})"
    );
    private static final Pattern PERIOD_STMT = Pattern.compile(
            "(?i)Statement\\s+Period\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})\\s+[Tt]o\\s+(\\d{2}/\\d{2}/\\d{4})"
    );
    private static final Pattern PERIOD_DASH = Pattern.compile(
            "(?i)Account\\s+Statement\\s+from\\s+(\\d{2}-\\d{2}-\\d{4})\\s+to\\s+(\\d{2}-\\d{2}-\\d{4})"
    );
    private static final Pattern PERIOD_MON = Pattern.compile(
            "(?:From)\\s*:?\\s*(\\d{1,2} [A-Za-z]{3} \\d{4})\\s+(?:To)\\s*:?\\s*(\\d{1,2} [A-Za-z]{3} \\d{4})"
    );

    private static final DateTimeFormatter DMY_DASH_FULL   = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DMY_MON_LENIENT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    @Override
    protected void detectPeriod(String rawText, StatementParseResult result) {
        Matcher m = PERIOD_SLASH.matcher(rawText);
        if (m.find()) { trySet(result, m.group(1), m.group(2), DMY_SLASH); if (result.getStatementFromDate() != null) return; }

        m = PERIOD_STMT.matcher(rawText);
        if (m.find()) { trySet(result, m.group(1), m.group(2), DMY_SLASH); if (result.getStatementFromDate() != null) return; }

        m = PERIOD_DASH.matcher(rawText);
        if (m.find()) { trySet(result, m.group(1), m.group(2), DMY_DASH_FULL); if (result.getStatementFromDate() != null) return; }

        m = PERIOD_MON.matcher(rawText);
        if (m.find()) { trySet(result, m.group(1), m.group(2), DMY_MON_LENIENT); }

        if (result.getStatementFromDate() == null) log.warn("BOB: could not detect statement period from PDF text");
    }

    private void trySet(StatementParseResult r, String from, String to, DateTimeFormatter fmt) {
        try {
            r.setStatementFromDate(LocalDate.parse(from.trim(), fmt));
            r.setStatementToDate(LocalDate.parse(to.trim(), fmt));
        } catch (Exception e) {
            log.debug("BOB: period parse failed for '{}' '{}': {}", from, to, e.getMessage());
        }
    }

    // ── BOB transaction extraction ────────────────────────────────────────────

    // Detects an anchor line: starts with serial# + two DD-MM-YYYY dates
    private static final Pattern BOB_TX_ANCHOR = Pattern.compile(
            "^\\d{1,3}\\s+\\d{2}-\\d{2}-\\d{4}\\s+\\d{2}-\\d{2}-\\d{4}"
    );

    // Full parse of anchor line:
    //   group 1 = serial
    //   group 2 = tx date
    //   group 3 = value date
    //   group 4 = inline description (may be empty)
    //   group 5 = debit amount OR "-"
    //   group 6 = credit amount OR "-"
    //   group 7 = closing balance
    private static final Pattern BOB_TX_LINE = Pattern.compile(
            "^(\\d{1,3})\\s+(\\d{2}-\\d{2}-\\d{4})\\s+(\\d{2}-\\d{2}-\\d{4})\\s*(.*?)\\s*(-|[\\d,]+\\.\\d{2})\\s+(-|[\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s*$"
    );

    // Lines to discard when building narration text
    private static final List<Pattern> BOB_NOISE = List.of(
            Pattern.compile("से.*तक की खाता"),                              // Hindi date-range header
            Pattern.compile("(?i)Account\\s+Statement\\s+from"),
            Pattern.compile("(?i)Sr\\.?\\s*No|सं लेनदेन|Value.*Description"),
            Pattern.compile("(?i)Date\\s+Date\\s+(Number|Cheque)"),
            Pattern.compile("(?i)This\\s+is\\s+a\\s+computer"),
            Pattern.compile("(?i)maintained\\s+in\\s+the\\s+bank"),
            Pattern.compile("(?i)Page\\s+\\d+\\s+of\\s+\\d+"),
            Pattern.compile("^\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}"), // footer timestamp
            Pattern.compile("(?i)गाहक|शाखा|खाता\\s*संर|खाता\\s*पकार|एमआईसीआर|पभाव|आईएफएससी|एमआईसीआर"),
            Pattern.compile("(?i)Cheque.*Debit.*Credit|Debit.*Credit.*Balance"),
            Pattern.compile("(?i)through\\s+bob"),
            Pattern.compile("^-$"),                                           // lone dash continuation fragment
            Pattern.compile("(?i)^Balance$")
    );

    @Override
    protected List<ParsedTransaction> extractTransactions(String rawText) {
        String[] rawLines = rawText.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String raw : rawLines) {
            String t = raw.trim().replaceAll("\\s{2,}", " ");
            if (!t.isEmpty()) lines.add(t);
        }

        // Identify every anchor line
        List<Integer> anchors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (BOB_TX_ANCHOR.matcher(lines.get(i)).find()) {
                anchors.add(i);
            }
        }

        log.info("BOB: found {} anchor lines across {} text lines", anchors.size(), lines.size());

        List<ParsedTransaction> results = new ArrayList<>();

        for (int a = 0; a < anchors.size(); a++) {
            int anchorIdx = anchors.get(a);
            String anchorLine = lines.get(anchorIdx);

            Matcher m = BOB_TX_LINE.matcher(anchorLine);
            if (!m.matches()) {
                log.warn("BOB: anchor didn't match full pattern: '{}'", anchorLine);
                continue;
            }

            String txDateStr  = m.group(2);
            String inlineDesc = m.group(4).trim();
            String debitStr   = m.group(5).trim();
            String creditStr  = m.group(6).trim();
            String balStr     = m.group(7).trim();

            // Skip opening/closing balance sentinel rows
            String upper = inlineDesc.toUpperCase();
            if (upper.contains("OPENING BALANCE") || upper.contains("CLOSING BALANCE")) continue;

            // ── Collect narration ──────────────────────────────────────────
            // Pre-anchor: lines between end of previous anchor and this anchor
            //   (these are UPI/NEFT description text that overflowed before this row)
            int prevEnd = (a > 0) ? anchors.get(a - 1) + 1 : 0;
            StringBuilder narr = new StringBuilder();
            for (int j = prevEnd; j < anchorIdx; j++) {
                String l = lines.get(j).trim();
                if (isBobNoise(l)) continue;
                if (narr.length() > 0) narr.append(" ");
                narr.append(l);
            }

            // Inline description from the anchor line itself
            if (!inlineDesc.isEmpty()) {
                if (narr.length() > 0) narr.append(" ");
                narr.append(inlineDesc);
            }

            // Post-anchor: continuation lines until the next anchor
            int nextStart = (a + 1 < anchors.size()) ? anchors.get(a + 1) : lines.size();
            for (int j = anchorIdx + 1; j < nextStart; j++) {
                String l = lines.get(j).trim();
                if (isBobNoise(l)) continue;
                if (narr.length() > 0) narr.append(" ");
                narr.append(l);
            }

            String narration = narr.toString().trim();

            // ── Parse date ─────────────────────────────────────────────────
            LocalDate txDate;
            try {
                txDate = LocalDate.parse(txDateStr, DMY_DASH_FULL);
            } catch (Exception e) {
                log.warn("BOB: bad date '{}' in anchor: '{}'", txDateStr, anchorLine);
                continue;
            }

            // ── Parse amounts ──────────────────────────────────────────────
            BigDecimal debit   = "-".equals(debitStr)  ? null : parseAmount(debitStr);
            BigDecimal credit  = "-".equals(creditStr) ? null : parseAmount(creditStr);
            BigDecimal balance = parseAmount(balStr);

            boolean hasDebit  = debit  != null && debit.compareTo(BigDecimal.ZERO)  > 0;
            boolean hasCredit = credit != null && credit.compareTo(BigDecimal.ZERO) > 0;
            if (!hasDebit && !hasCredit) continue;

            // ── Build transaction ──────────────────────────────────────────
            ParsedTransaction tx = new ParsedTransaction();
            tx.setDate(txDate);
            tx.setValueDate(txDate);
            tx.setStatementYear(txDate.getYear());
            tx.setStatementMonth(txDate.getMonthValue());
            tx.setRawNarration(narration);
            tx.setClosingBalance(balance);

            if (hasDebit) {
                tx.setWithdrawalAmount(debit);
                tx.setType(TransactionType.DEBIT);
            } else {
                tx.setDepositAmount(credit);
                tx.setType(TransactionType.CREDIT);
            }

            enrichFromNarration(tx, narration);
            results.add(tx);
        }

        return results;
    }

    private boolean isBobNoise(String line) {
        for (Pattern p : BOB_NOISE) {
            if (p.matcher(line).find()) return true;
        }
        return false;
    }
}
