package com.moneylens.parser;

import com.moneylens.dto.ParsedTransaction;
import com.moneylens.dto.StatementParseResult;
import com.moneylens.entity.TransactionMode;
import com.moneylens.entity.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared parsing logic for Indian savings account statement PDFs.
 *
 * Works with any bank that produces statements with this column layout (post-PDFBox extraction):
 *   DATE  [VALUE_DATE]  NARRATION  [REF]  [DEBIT]  [CREDIT]  BALANCE
 *
 * Core algorithm:
 *   1. Lines starting with a date (DD/MM/YYYY or DD MMM YYYY) are transaction starts.
 *   2. Collect all monetary values from each transaction block.
 *      Last value = closing balance. Last non-zero value before that = tx amount.
 *   3. Determine CREDIT/DEBIT via balance delta first; narration keywords as fallback.
 *   4. Enrich narration using UPI/NEFT/IMPS rail keywords — these are universal.
 *
 * Subclasses only need to implement: getBankName(), supports(), detectPeriod().
 *
 * NOTE: This parser produces correct results in the common case (two separate Debit/Credit
 * columns collapsing to one or two amounts). Edge cases (e.g. 0.00 shown in the empty
 * column) are handled by skipping zeros when finding the transaction amount.
 */
public abstract class AbstractSavingsAccountParser implements BankStatementParser {

    protected static final Logger log = LoggerFactory.getLogger(AbstractSavingsAccountParser.class);

    // DD/MM/YYYY — most Indian banks
    protected static final DateTimeFormatter DMY_SLASH     = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    // DD MMM YYYY — SBI / PNB (Locale.ENGLISH so month abbreviations always parse)
    protected static final DateTimeFormatter DMY_MON       = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    // d MMM yyyy — handles single-digit day (SBI sometimes omits leading zero)
    protected static final DateTimeFormatter DMY_MON_D     = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    // DD-MM-YYYY — Axis Bank numeric dash
    protected static final DateTimeFormatter DMY_DASH      = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    // DD-MMM-YY  — ICICI Bank (e.g. 15-Jan-24)
    protected static final DateTimeFormatter DMY_MON_SHORT = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);
    // DD-MMM-YYYY — ICICI Bank fallback (e.g. 15-Jan-2024)
    protected static final DateTimeFormatter DMY_MON_LONG  = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    // Matches a line starting with DD/MM/YYYY — use (\\s|$) so a date alone on a line is also detected
    private static final Pattern DATE_SLASH     = Pattern.compile("^(\\d{2}/\\d{2}/\\d{4})(\\s|$)");
    // Matches DD MMM YYYY at line start (e.g. "01 Jan 2024")
    private static final Pattern DATE_MON       = Pattern.compile("^(\\d{2} [A-Za-z]{3} \\d{4})(\\s|$)");
    // Matches DD-MM-YYYY at line start (Axis Bank puts the date alone on its own line)
    private static final Pattern DATE_DASH      = Pattern.compile("^(\\d{2}-\\d{2}-\\d{4})(\\s|$)");
    // Matches DD-MMM-YY or DD-MMM-YYYY at line start (ICICI Bank e.g. "15-Jan-24")
    private static final Pattern DATE_MON_SHORT = Pattern.compile("^(\\d{1,2}-[A-Za-z]{3}-\\d{2,4})(\\s|$)");

    // Monetary amount: 1,23,456.78 or 56789.00 (Indian lakh format + plain)
    private static final Pattern MONEY = Pattern.compile("[\\d,]+\\.\\d{2}");

    // Phone number (10 digits)
    private static final Pattern PHONE = Pattern.compile("(\\d{10})");

    // Lines that are definitely not transactions — filtered before extraction.
    // Used in TWO places:
    //   1. Exclude a date-starting line from being a TRANSACTION START
    //   2. Exclude a continuation line from being appended to the current block
    // Add patterns here whenever a new bank's summary/header rows bleed into
    // parsed transactions.  All patterns use .find() (substring match).
    private static final List<Pattern> NON_TX_LINES = List.of(

            // ── Balance / opening / closing markers ───────────────────────────
            Pattern.compile("OPENING BALANCE",        Pattern.CASE_INSENSITIVE),
            Pattern.compile("CLOSING BALANCE",        Pattern.CASE_INSENSITIVE),
            Pattern.compile("OPENING BAL",            Pattern.CASE_INSENSITIVE),
            Pattern.compile("CLOSING BAL",            Pattern.CASE_INSENSITIVE),
            // Balance brought/carried forward — SBI, PNB, BOB
            Pattern.compile("\\bBAL\\.?\\s*B/?F\\b",  Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bBAL\\.?\\s*C/?F\\b",  Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bOPG\\.?\\s*BAL\\b",   Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCLG\\.?\\s*BAL\\b",   Pattern.CASE_INSENSITIVE),
            Pattern.compile("BALANCE BROUGHT",        Pattern.CASE_INSENSITIVE),
            Pattern.compile("BALANCE CARRIED",        Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bB/?F\\s+BAL\\b",      Pattern.CASE_INSENSITIVE),

            // ── Summary section headers ───────────────────────────────────────
            // These appear as section headings in SBI/ICICI/Axis/PNB PDFs.
            // They sometimes start with a date when PDFBox extracts the page
            // header on continuation pages, making them look like tx starts.
            Pattern.compile("STATEMENT SUMMARY",      Pattern.CASE_INSENSITIVE),
            Pattern.compile("ACCOUNT SUMMARY",        Pattern.CASE_INSENSITIVE),
            Pattern.compile("TRANSACTION SUMMARY",    Pattern.CASE_INSENSITIVE),
            Pattern.compile("MONTHLY SUMMARY",        Pattern.CASE_INSENSITIVE),
            // SBI continuation-page header "Account Statement from DD MMM to DD MMM"
            Pattern.compile("ACCOUNT\\s+STATEMENT\\s+FROM", Pattern.CASE_INSENSITIVE),

            // ── Totals / count rows ───────────────────────────────────────────
            Pattern.compile("TRANSACTION TOTAL",      Pattern.CASE_INSENSITIVE),  // Axis Bank
            Pattern.compile("MONTHLY TOTAL",          Pattern.CASE_INSENSITIVE),
            Pattern.compile("GRAND TOTAL",            Pattern.CASE_INSENSITIVE),
            Pattern.compile("TOTAL DEBIT",            Pattern.CASE_INSENSITIVE),
            Pattern.compile("TOTAL CREDIT",           Pattern.CASE_INSENSITIVE),
            Pattern.compile("TOTAL AMOUNT",           Pattern.CASE_INSENSITIVE),
            Pattern.compile("NET AMOUNT",             Pattern.CASE_INSENSITIVE),
            // Transaction count rows — "No. of Transactions: 15", "No of Debit: 8"
            Pattern.compile("NO\\.?\\s*OF\\s+(DEBIT|CREDIT|TRANSACTION)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bNO\\.?\\s*OF\\s+TXN\\b",                  Pattern.CASE_INSENSITIVE),

            // ── Column header rows ────────────────────────────────────────────
            Pattern.compile("STATEMENT OF",          Pattern.CASE_INSENSITIVE),
            Pattern.compile("STATEMENT PERIOD",      Pattern.CASE_INSENSITIVE),
            Pattern.compile("DATE\\s+NARRATION",     Pattern.CASE_INSENSITIVE),
            Pattern.compile("DATE\\s+PARTICULARS",   Pattern.CASE_INSENSITIVE),
            Pattern.compile("TXN DATE\\s+VALUE",     Pattern.CASE_INSENSITIVE),
            Pattern.compile("TRAN DATE\\s+CHQ",      Pattern.CASE_INSENSITIVE),  // Axis Bank
            Pattern.compile("VALUE DATE\\s+NARRATION",Pattern.CASE_INSENSITIVE), // SBI/ICICI header variant
            Pattern.compile("TXN\\s+DATE\\s+DESCRIPTION", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CHEQUE\\s+NO\\s+DATE",  Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bSL\\.?\\s*NO\\.?\\s+DATE\\b", Pattern.CASE_INSENSITIVE),

            // ── Period / date-range lines (page header repeats) ───────────────
            // A line that is itself a date-range ("01/01/2024 to 31/01/2024")
            // rather than a transaction.  Matches if the text contains
            // " to " flanked by date-like tokens.
            Pattern.compile("\\d{4}\\s+[Tt][Oo]\\s+\\d{1,2}[/\\-]",  Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\d{4}\\s+[Tt][Oo]\\s+\\d{1,2}\\s+[A-Za-z]{3}", Pattern.CASE_INSENSITIVE)
    );

    // ── Public contract ───────────────────────────────────────────────────────

    protected abstract String getBankName();

    /** Detect statement period and set fromDate/toDate on result. */
    protected abstract void detectPeriod(String rawText, StatementParseResult result);

    @Override
    public StatementParseResult parse(String rawText) {
        StatementParseResult result = new StatementParseResult();
        result.setBankName(getBankName());
        detectPeriod(rawText, result);

        List<ParsedTransaction> transactions = extractTransactions(rawText);
        result.setTransactions(transactions);

        // Compute totals from parsed transactions
        BigDecimal totalDebits  = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        for (ParsedTransaction tx : transactions) {
            if (tx.getWithdrawalAmount() != null)
                totalDebits  = totalDebits.add(tx.getWithdrawalAmount());
            if (tx.getDepositAmount() != null)
                totalCredits = totalCredits.add(tx.getDepositAmount());
        }
        result.setTotalDebits(totalDebits);
        result.setTotalCredits(totalCredits);

        log.info("{} parser: extracted {} transactions (debits=₹{} credits=₹{})",
                getBankName(), transactions.size(),
                totalDebits.toPlainString(), totalCredits.toPlainString());

        return result;
    }

    // ── Transaction extraction ────────────────────────────────────────────────

    protected List<ParsedTransaction> extractTransactions(String rawText) {
        List<ParsedTransaction> results = new ArrayList<>();

        String[] rawLines = rawText.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            String t = line.trim().replaceAll("\\s{2,}", " ");
            if (!t.isEmpty()) lines.add(t);
        }

        List<Integer> txStarts = new ArrayList<>();
        boolean pastTransactionSection = false;
        int summaryBoundaryLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            // Once we hit a summary/footer section, no transactions can follow.
            if (!pastTransactionSection && isSummarySectionBoundary(line)) {
                log.warn("{}: summary section boundary at line {} — stopping tx scan. Line: '{}'",
                        getBankName(), i, line.length() > 120 ? line.substring(0, 120) : line);
                pastTransactionSection = true;
                summaryBoundaryLine = i;
            }
            if (pastTransactionSection) continue;

            if ((DATE_SLASH.matcher(line).find()
                    || DATE_MON.matcher(line).find()
                    || DATE_DASH.matcher(line).find()
                    || DATE_MON_SHORT.matcher(line).find())
                    && !isNonTxLine(line)) {
                txStarts.add(i);
            }
        }

        // Diagnose zero-transaction result before attempting to parse blocks
        if (txStarts.isEmpty()) {
            log.warn("{}: no transaction-start lines found (total lines={}, boundary={}).",
                    getBankName(), lines.size(), summaryBoundaryLine);
            log.warn("{}: first 40 extracted lines:", getBankName());
            for (int i = 0; i < Math.min(40, lines.size()); i++) {
                log.warn("  [{}] {}", i, lines.get(i));
            }
        }

        BigDecimal previousBalance = null;

        for (int i = 0; i < txStarts.size(); i++) {
            int from = txStarts.get(i);
            int to   = (i + 1 < txStarts.size()) ? txStarts.get(i + 1) : lines.size();

            StringBuilder sb = new StringBuilder(lines.get(from));
            for (int j = from + 1; j < to; j++) {
                String cont = lines.get(j).trim();
                // Stop collecting continuation lines once we hit any summary/footer section.
                // Without this, the Statement Summary table data (total debits, total credits)
                // gets appended to the last real transaction's block and corrupts its amount.
                if (isSummarySectionBoundary(cont)) break;
                if (!isNonTxLine(cont)) {
                    sb.append(" ").append(cont);
                }
            }
            String block = sb.toString();

            try {
                ParsedTransaction tx = parseBlock(block, previousBalance);
                if (tx != null) {
                    results.add(tx);
                    if (tx.getClosingBalance() != null) previousBalance = tx.getClosingBalance();
                }
            } catch (Exception e) {
                log.warn("{}: failed to parse block '{}' — {}",
                        getBankName(),
                        block.length() > 80 ? block.substring(0, 80) : block,
                        e.getMessage());
            }
        }

        return results;
    }

    private ParsedTransaction parseBlock(String block, BigDecimal previousBalance) {
        // Parse the leading date (try all three formats)
        LocalDate txDate = null;
        int dateEnd = 0;

        Matcher m1 = DATE_SLASH.matcher(block);
        Matcher m2 = DATE_MON.matcher(block);
        Matcher m3 = DATE_DASH.matcher(block);
        Matcher m4 = DATE_MON_SHORT.matcher(block);

        if (m1.find()) {
            try { txDate = LocalDate.parse(m1.group(1), DMY_SLASH); dateEnd = m1.end(); } catch (Exception ignored) {}
        } else if (m2.find()) {
            try { txDate = LocalDate.parse(m2.group(1), DMY_MON); dateEnd = m2.end(); } catch (Exception ignored) {}
        } else if (m3.find()) {
            try { txDate = LocalDate.parse(m3.group(1), DMY_DASH); dateEnd = m3.end(); } catch (Exception ignored) {}
        } else if (m4.find()) {
            String raw = m4.group(1);
            try { txDate = LocalDate.parse(raw, DMY_MON_SHORT); dateEnd = m4.end(); } catch (Exception ignored) {}
            if (txDate == null) {
                try { txDate = LocalDate.parse(raw, DMY_MON_LONG); dateEnd = m4.end(); } catch (Exception ignored2) {}
            }
        }

        if (txDate == null) return null;

        // Collect all monetary values from the block
        List<BigDecimal> amounts = new ArrayList<>();
        Matcher mMoney = MONEY.matcher(block);
        while (mMoney.find()) amounts.add(parseAmount(mMoney.group()));

        if (amounts.size() < 1) {
            log.debug("{}: no amounts found in block: {}", getBankName(),
                    block.length() > 60 ? block.substring(0, 60) : block);
            return null;
        }

        // Last amount = closing balance
        BigDecimal closingBalance = amounts.get(amounts.size() - 1);

        // Last NON-ZERO amount before closing balance = transaction amount
        BigDecimal txAmount = BigDecimal.ZERO;
        for (int i = amounts.size() - 2; i >= 0; i--) {
            if (amounts.get(i).compareTo(BigDecimal.ZERO) != 0) {
                txAmount = amounts.get(i);
                break;
            }
        }

        // Extract narration: text from date-end to first monetary value
        String narration = "";
        Matcher firstMoney = MONEY.matcher(block);
        if (firstMoney.find()) {
            narration = block.substring(dateEnd, firstMoney.start()).trim();
            // Remove value date (second date in the narration)
            narration = narration.replaceAll("\\b\\d{2}/\\d{2}/\\d{4}\\b", "").trim();
            narration = narration.replaceAll("\\b\\d{2}-\\d{2}-\\d{4}\\b", "").trim();
            narration = narration.replaceAll("\\b\\d{2} [A-Za-z]{3} \\d{4}\\b", "").trim();
            narration = narration.replaceAll("\\b\\d{1,2}-[A-Za-z]{3}-\\d{2,4}\\b", "").trim();
            // Remove long digit strings (reference numbers: 8+ digits)
            narration = narration.replaceAll("\\b\\d{8,}\\b", "").trim();
            narration = narration.replaceAll("\\s{2,}", " ").trim();
        }

        if (narration.isBlank()) {
            log.debug("{}: empty narration in block, skipping", getBankName());
            return null;
        }

        // Second safety-net: even if a summary row slipped past NON_TX_LINES
        // (because its date-starting line wasn't filtered), reject it here
        // once we can see what the narration text actually says.
        if (isNonTxNarration(narration)) {
            log.debug("{}: skipping summary/header block with narration='{}' amounts={}",
                    getBankName(), narration, amounts);
            return null;
        }

        // Determine CREDIT / DEBIT
        TransactionType type = null;
        if (previousBalance != null && closingBalance != null) {
            int cmp = closingBalance.compareTo(previousBalance);
            if (cmp > 0) type = TransactionType.CREDIT;
            else if (cmp < 0) type = TransactionType.DEBIT;
        }
        if (type == null) type = inferTypeFromNarration(narration);
        if (type == null) {
            log.debug("{}: defaulting DEBIT for narration='{}' prev={} close={}",
                    getBankName(), narration, previousBalance, closingBalance);
            type = TransactionType.DEBIT;
        }

        ParsedTransaction tx = new ParsedTransaction();
        tx.setDate(txDate);
        tx.setStatementYear(txDate.getYear());
        tx.setStatementMonth(txDate.getMonthValue());
        tx.setRawNarration(narration);
        tx.setClosingBalance(closingBalance);
        tx.setType(type);

        if (type == TransactionType.CREDIT) tx.setDepositAmount(txAmount);
        else                                tx.setWithdrawalAmount(txAmount);

        enrichFromNarration(tx, narration);

        return tx;
    }

    // ── Type inference from narration ─────────────────────────────────────────

    protected TransactionType inferTypeFromNarration(String narration) {
        String upper = narration.toUpperCase();
        // Credits
        if (upper.contains("SALARY"))          return TransactionType.CREDIT;
        if (upper.contains("NEFT CR"))         return TransactionType.CREDIT;
        if (upper.contains("IMPS CR"))         return TransactionType.CREDIT;
        if (upper.contains("RTGS CR"))         return TransactionType.CREDIT;
        if (upper.contains("UPIRET"))          return TransactionType.CREDIT;
        if (upper.contains("CASHBACK"))        return TransactionType.CREDIT;
        if (upper.contains("REVERSAL"))        return TransactionType.CREDIT;
        if (upper.contains("REFUND"))          return TransactionType.CREDIT;
        if (upper.contains("INT CR"))          return TransactionType.CREDIT;
        if (upper.contains("INT.CR"))          return TransactionType.CREDIT;
        if (upper.contains("INTEREST CR"))     return TransactionType.CREDIT;
        if (upper.contains("BY TRANSFER"))     return TransactionType.CREDIT;
        if (upper.contains("CR BY"))           return TransactionType.CREDIT;
        if (upper.contains("RECEIVED FROM"))   return TransactionType.CREDIT;
        if (upper.startsWith("TRF/"))          return TransactionType.CREDIT;  // Axis salary/transfer credit
        // Debits
        if (upper.contains("NEFT DR"))         return TransactionType.DEBIT;
        if (upper.contains("IMPS DR"))         return TransactionType.DEBIT;
        if (upper.contains("RTGS DR"))         return TransactionType.DEBIT;
        if (upper.contains("ATM"))             return TransactionType.DEBIT;
        if (upper.contains("EMI"))             return TransactionType.DEBIT;
        if (upper.contains("MANDATE"))         return TransactionType.DEBIT;
        if (upper.contains("ECS DR"))          return TransactionType.DEBIT;
        if (upper.contains("ACH DR"))          return TransactionType.DEBIT;
        if (upper.contains("CHQ"))             return TransactionType.DEBIT;
        if (upper.contains("CLG"))             return TransactionType.DEBIT;
        if (upper.contains("TO TRANSFER"))     return TransactionType.DEBIT;
        if (upper.contains("PAYMENT TO"))      return TransactionType.DEBIT;
        if (upper.contains("SENT TO"))         return TransactionType.DEBIT;
        return null;
    }

    // ── Narration enrichment ──────────────────────────────────────────────────

    protected void enrichFromNarration(ParsedTransaction tx, String narration) {
        String upper = narration.toUpperCase();

        if (upper.startsWith("UPI") || upper.contains("UPI/") || upper.contains("/UPI")
                || upper.contains("UPI-") || upper.contains("-UPI")) {
            tx.setMode(TransactionMode.UPI);
            if (upper.contains("UPIRET") || upper.contains("UPI REFUND") || upper.contains("UPIRETURN")) {
                tx.setMode(TransactionMode.UPI_REFUND);
                tx.setRefund(true);
            } else {
                extractUpiCounterparty(tx, narration);
            }
        } else if (upper.startsWith("TRF/") || upper.startsWith("TRF-")) {
            tx.setMode(TransactionMode.NEFT);  // Axis Bank salary/transfer prefix
            extractNeftCounterparty(tx, narration.replaceFirst("(?i)^TRF/", ""));
        } else if (upper.startsWith("NEFT") || upper.contains(" NEFT ")) {
            tx.setMode(TransactionMode.NEFT);
            extractNeftCounterparty(tx, narration);
        } else if (upper.startsWith("IMPS") || upper.contains(" IMPS ")) {
            tx.setMode(TransactionMode.IMPS);
        } else if (upper.startsWith("RTGS") || upper.contains(" RTGS ")) {
            tx.setMode(TransactionMode.NEFT); // treat RTGS same as NEFT
            extractNeftCounterparty(tx, narration);
        } else if (upper.contains("EMI")) {
            tx.setMode(TransactionMode.EMI);
            tx.setCounterpartyName("EMI Payment");
        } else if (upper.contains("MANDATE") || upper.contains("ACH") || upper.contains("ECS")
                || upper.contains("AUTOPAY")) {
            tx.setMode(TransactionMode.AUTOPAY);
            extractNeftCounterparty(tx, narration);
        } else if (upper.startsWith("ATM") || upper.contains("ATM WDL") || upper.contains("CASH WD")
                || upper.contains("ATM/WDL") || upper.contains("ATM-WDL")) {
            tx.setMode(TransactionMode.ATM);
            tx.setCounterpartyName("ATM Withdrawal");
        } else if (upper.startsWith("CHQ") || upper.startsWith("CLG")) {
            tx.setMode(TransactionMode.CHEQUE);
        } else {
            tx.setMode(TransactionMode.OTHER);
        }

        // Resolve known merchants (overrides whatever was set above)
        String resolved = resolveKnownMerchant(upper);
        if (resolved != null) tx.setCounterpartyName(resolved);

        // Phone number
        Matcher phoneMatcher = PHONE.matcher(narration);
        if (phoneMatcher.find()) tx.setCounterpartyPhone(phoneMatcher.group(1));
    }

    // Axis/standard UPI format: UPI/P2A/<ref>/<NAME>/<mode>/<bank>
    // After ref-number removal (8+ digits stripped), the ref segment becomes empty: UPI/P2A//<NAME>/...
    // So we use [^/]* to skip the ref/empty segment and then capture the name.
    private static final Pattern UPI_NAME_SEGMENT = Pattern.compile(
            "(?i)UPI/P2[AMP2V]+/[^/]*/\\s*([A-Za-z][^/]{1,50}?)\\s*/");

    private void extractUpiCounterparty(ParsedTransaction tx, String narration) {
        // Extract UPI handle (e.g. merchant@ybl)
        Matcher handleMatcher = Pattern.compile("[A-Za-z0-9._]{2,}@[A-Za-z0-9]+").matcher(narration);
        if (handleMatcher.find()) {
            tx.setUpiHandle(handleMatcher.group().toLowerCase());
        }

        // Try structured Axis/common format: UPI/P2A/<ref>/<NAME>/<...>/<bank>
        Matcher nameMatcher = UPI_NAME_SEGMENT.matcher(narration);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim();
            if (!name.isEmpty() && !name.matches("\\d+") && name.length() >= 2) {
                tx.setCounterpartyName(toTitleCase(name));
                return;
            }
        }

        // Fallback: token splitting — skip protocol tokens
        String body = narration.replaceFirst("(?i)^UPI[-/]?", "");
        String[] parts = body.split("[/\\-@\\s]+");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty() || p.contains("@") || p.matches("\\d+")) continue;
            if (p.length() < 2) continue;
            if (p.matches("(?i)P2[AMP2V]*|UPI|UPIRET|AUTOPAY|SENT|COLLEC|PAYVIA")) continue;
            tx.setCounterpartyName(toTitleCase(p));
            break;
        }
    }

    private void extractNeftCounterparty(ParsedTransaction tx, String narration) {
        // NEFT/RTGS/ECS narrations: NEFT CR/DR-SenderName-...
        String body = narration.replaceFirst("(?i)^(NEFT|RTGS|ECS|ACH|MANDATE)[-/ ]?(CR|DR)?[-/]?", "");
        String[] parts = body.split("[-/]");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty() || p.matches("\\d+")) continue;
            if (p.length() < 2) continue;
            tx.setCounterpartyName(toTitleCase(p));
            break;
        }
    }

    // ── Merchant resolution ───────────────────────────────────────────────────

    protected String resolveKnownMerchant(String upper) {
        if (upper.contains("SWIGGY"))             return "Swiggy";
        if (upper.contains("ZOMATO"))             return "Zomato";
        if (upper.contains("BLINKIT"))            return "Blinkit";
        if (upper.contains("ZEPTO"))              return "Zepto";
        if (upper.contains("FLIPKART"))           return "Flipkart";
        if (upper.contains("AMAZON"))             return "Amazon";
        if (upper.contains("AIRTEL"))             return "Airtel";
        if (upper.contains("JIO ") || upper.contains("RELIANCE JIO")) return "Jio";
        if (upper.contains("GOOGLE PLAY") || upper.contains("GOOGLE PAY") || upper.contains("GPAY"))
                                                  return "Google Pay";
        if (upper.contains("NETFLIX"))            return "Netflix";
        if (upper.contains("SPOTIFY"))            return "Spotify";
        if (upper.contains("HOTSTAR") || upper.contains("DISNEY")) return "Disney+ Hotstar";
        if (upper.contains("DOMINOS") || upper.contains("DOMINO"))  return "Domino's";
        if (upper.contains("UBER"))               return "Uber";
        if (upper.contains("OLA ") || upper.contains("OLA-"))       return "Ola";
        if (upper.contains("RAPIDO"))             return "Rapido";
        if (upper.contains("MYNTRA"))             return "Myntra";
        if (upper.contains("NYKAA"))              return "Nykaa";
        if (upper.contains("BIGBASKET"))          return "BigBasket";
        if (upper.contains("DUNZO"))              return "Dunzo";
        if (upper.contains("CRED"))               return "CRED";
        if (upper.contains("PHONEPE"))            return "PhonePe";
        if (upper.contains("PAYTM"))              return "Paytm";
        if (upper.contains("RAZORPAY"))           return "Razorpay";
        if (upper.contains("AAPL") || upper.contains("APPLE"))      return "Apple";
        if (upper.contains("IRCTC"))              return "IRCTC";
        if (upper.contains("MAKEMYTRIP") || upper.contains("MMT"))  return "MakeMyTrip";
        if (upper.contains("BOOKMYSHOW"))         return "BookMyShow";
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Section-boundary sentinel — once any line matches, every subsequent line
    // is skipped entirely (no transaction can follow the summary footer).
    //
    // IMPORTANT: Only include phrases that appear ONLY in the trailing footer,
    // never in the document header or mid-statement sections.
    // "Account Summary" is intentionally excluded — SBI PDFs use it as a
    // HEADER section (account details) at the top, before any transactions.
    private static final List<Pattern> SUMMARY_SECTION_BOUNDARIES = List.of(
            Pattern.compile("\\bSTATEMENT\\s+SUMMARY\\b",   Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTRANSACTION\\s+SUMMARY\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bMONTHLY\\s+SUMMARY\\b",     Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bGRAND\\s+TOTAL\\b",          Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTRANSACTION\\s+TOTAL\\b",   Pattern.CASE_INSENSITIVE)
    );

    private boolean isSummarySectionBoundary(String line) {
        for (Pattern p : SUMMARY_SECTION_BOUNDARIES) {
            if (p.matcher(line).find()) return true;
        }
        return false;
    }

    private boolean isNonTxLine(String line) {
        for (Pattern p : NON_TX_LINES) {
            if (p.matcher(line).find()) return true;
        }
        return false;
    }

    // Keywords that identify a narration as a summary/balance row rather than
    // a real transaction, even when the row has monetary amounts attached.
    // Only add phrases that CANNOT appear in a real transaction narration.
    private static final List<Pattern> NON_TX_NARRATIONS = List.of(
            Pattern.compile("\\bGRAND\\s+TOTAL\\b",              Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bMONTHLY\\s+TOTAL\\b",            Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTOTAL\\s+AMOUNT\\b",             Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTOTAL\\s+DEBIT",                 Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTOTAL\\s+CREDIT",                Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bNET\\s+AMOUNT\\b",               Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bOPENING\\s+BAL\\b",              Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCLOSING\\s+BAL\\b",              Pattern.CASE_INSENSITIVE),
            Pattern.compile("CLOSING\\s+BALANCE",                 Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bBAL\\.?\\s*B/?F\\b",             Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bBAL\\.?\\s*C/?F\\b",             Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bOPG\\.?\\s*BAL\\b",              Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCLG\\.?\\s*BAL\\b",              Pattern.CASE_INSENSITIVE),
            // SBI Statement Summary table columns
            Pattern.compile("\\bBROUGHT\\s+FORWARD\\b",          Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDR\\s+COUNT\\b",                  Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCR\\s+COUNT\\b",                  Pattern.CASE_INSENSITIVE),
            Pattern.compile("NO\\.?\\s*OF\\s+(DEBIT|CREDIT|TRANSACTION)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("STATEMENT\\s+SUMMARY",               Pattern.CASE_INSENSITIVE),
            Pattern.compile("ACCOUNT\\s+SUMMARY",                 Pattern.CASE_INSENSITIVE),
            Pattern.compile("TRANSACTION\\s+SUMMARY",             Pattern.CASE_INSENSITIVE)
    );

    private boolean isNonTxNarration(String narration) {
        for (Pattern p : NON_TX_NARRATIONS) {
            if (p.matcher(narration).find()) return true;
        }
        return false;
    }

    protected BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(raw.replace(",", "").trim());
    }

    protected String toTitleCase(String input) {
        if (input == null || input.isBlank()) return input;
        String[] words = input.trim().toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }
}
