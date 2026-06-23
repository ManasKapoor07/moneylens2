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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses HDFC Bank account statements (net-banking PDF format).
 *
 * Supports single-month and multi-month PDFs (e.g. Jan–Jun in one download).
 *
 * PDF structure (from PDFBox extraction):
 *   Line 1: dd/MM/yy  <narration>  <16-digit-ref>  dd/MM/yy  <amount>  <closingBalance>
 *   Line 2+: continuation of narration
 *
 * Key design decisions:
 *   1. PERIOD_PATTERN captures both From AND To dates — handles any duration PDF
 *   2. Each transaction is tagged with its OWN date's year+month, not the statement period
 *      A March tx in a Jan–Jun PDF gets statementMonth=3, not statementMonth=1
 *   3. Credit/Debit: balance delta first (mathematically correct), narration keyword fallback
 *   4. BOILERPLATE_LINE_PATTERNS filters out page header/footer text (account
 *      holder address, GSTIN, disclaimers, branch info) that PDFBox extracts
 *      as plain lines sitting between two transaction date-lines whenever a
 *      page break falls mid-statement. Without this filter, that boilerplate
 *      text gets silently glued onto whichever transaction precedes it as
 *      "continuation narration" — corrupting merchant names, bloating
 *      merchant keys past sane lengths, and poisoning AI categorization.
 */
public class HdfcStatementParser implements BankStatementParser {

    private static final Logger log = LoggerFactory.getLogger(HdfcStatementParser.class);

    // dd/MM/yy — used in transaction lines
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yy");

    // ── Transaction line pattern ──────────────────────────────────────────────
    // Groups:
    //   1 = date (dd/MM/yy)
    //   2 = narration fragment (non-greedy)
    //   3 = 16-digit reference number
    //   4 = value date (dd/MM/yy)
    //   5 = transaction amount
    //   6 = closing balance (optional — missing on long lines PDFBox wraps)
    private static final Pattern TX_LINE_PATTERN = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{2})\\s+" +
                    "(.+?)\\s+" +
                    "(\\d{16})\\s+" +
                    "(\\d{2}/\\d{2}/\\d{2})\\s+" +
                    "([\\d,]+\\.\\d{2})" +
                    "(?:\\s+([\\d,]+\\.\\d{2}))?"
    );

    private static final Pattern DATE_LINE_START = Pattern.compile("^(\\d{2}/\\d{2}/\\d{2})\\s");

    // ── Summary line pattern ──────────────────────────────────────────────────
    // Format: opening  debit_count  credit_count  total_debits  total_credits  closing
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
            "([\\d,]+\\.\\d{2})\\s+(\\d+)\\s+(\\d+)\\s+" +
                    "([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})"
    );

    // ── Period pattern — captures BOTH From and To dates ─────────────────────
    // HDFC header: "From : 01/01/2024  To : 30/06/2024"
    // Old pattern only captured From. New pattern captures both.
    // Groups: 1=from-dd, 2=from-MM, 3=from-yyyy, 4=to-dd, 5=to-MM, 6=to-yyyy
    private static final Pattern PERIOD_PATTERN = Pattern.compile(
            "From\\s*:\\s*(\\d{2})/(\\d{2})/(\\d{4})" +
                    "\\s+To\\s*:\\s*(\\d{2})/(\\d{2})/(\\d{4})"
    );

    // ── Narration mode patterns ───────────────────────────────────────────────
    private static final Pattern UPIRET_PATTERN  = Pattern.compile("^UPIRET-",                Pattern.CASE_INSENSITIVE);
    private static final Pattern EMI_PATTERN     = Pattern.compile("^EMI\\s+\\d+",            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEFT_PATTERN    = Pattern.compile("^NEFT",                   Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPS_PATTERN    = Pattern.compile("^IMPS",                   Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOPAY_PATTERN = Pattern.compile(
            "MANDATEEEXECUTE|MANDATEEXECUTE|UPI-AUTOPAY", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN   = Pattern.compile("(\\d{10})");

    // ── Statement boilerplate line patterns ───────────────────────────────────
    // Any line matching ANY of these is page header/footer noise, not part of
    // a transaction narration. These are matched against a single extracted
    // line (already trimmed/whitespace-collapsed) using .find(), so partial
    // matches anywhere in the line are enough to flag it as boilerplate.
    //
    // This list is explicit and auditable — extend it as you encounter new
    // boilerplate phrases in real statements, the same way Category.ALIASES
    // or MerchantRegistry are extended. Do NOT replace this with a vague
    // heuristic like "lines longer than N chars are boilerplate" — that would
    // risk discarding real long narrations.
    private static final List<Pattern> BOILERPLATE_LINE_PATTERNS = List.of(
            // ── Standard footer / disclaimer lines ────────────────────────────
            Pattern.compile("CLOSING BALANCE INCLUDES FUNDS", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CONTENTS OF THIS STATEMENT",     Pattern.CASE_INSENSITIVE),
            Pattern.compile("WILL BE CONSIDERED CORRECT",     Pattern.CASE_INSENSITIVE),
            Pattern.compile("ADDRESS ON THIS STATEMENT",      Pattern.CASE_INSENSITIVE),
            Pattern.compile("REGISTERED OFFICE ADDRESS",      Pattern.CASE_INSENSITIVE),
            Pattern.compile("STATE ACCOUNT BRANCH GSTN",      Pattern.CASE_INSENSITIVE),
            Pattern.compile("GSTIN NUMBER DETAILS",           Pattern.CASE_INSENSITIVE),
            Pattern.compile("PAGE NO\\s*\\.?\\s*:",           Pattern.CASE_INSENSITIVE),
            Pattern.compile("ACCOUNT BRANCH\\s*:",            Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bOD LIMIT\\s*:",               Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCURRENCY\\s*:\\s*INR",        Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCUST ID\\s*:",                Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bACCOUNT NO\\s*:",             Pattern.CASE_INSENSITIVE),
            Pattern.compile("A/C OPEN DATE\\s*:",             Pattern.CASE_INSENSITIVE),
            Pattern.compile("JOINT HOLDERS\\s*:",             Pattern.CASE_INSENSITIVE),
            Pattern.compile("ACCOUNT STATUS\\s*:",            Pattern.CASE_INSENSITIVE),
            Pattern.compile("RTGS/NEFT IFSC",                 Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bMICR\\s*:",                   Pattern.CASE_INSENSITIVE),
            Pattern.compile("BRANCH CODE\\s*:",               Pattern.CASE_INSENSITIVE),
            Pattern.compile("NOMINATION\\s*:",                Pattern.CASE_INSENSITIVE),
            Pattern.compile("ACCOUNT TYPE\\s*:",              Pattern.CASE_INSENSITIVE),
            Pattern.compile("STATEMENT OF ACCOUNT",           Pattern.CASE_INSENSITIVE),
            Pattern.compile("PHONE NO\\s*\\.?\\s*:",          Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bEMAIL\\s*:",                  Pattern.CASE_INSENSITIVE),
            Pattern.compile("HDFC BANK GSTIN",                Pattern.CASE_INSENSITIVE),
            Pattern.compile("HDFC BANK HOUSE",                Pattern.CASE_INSENSITIVE),
            Pattern.compile("HTTPS://WWW\\.HDFCBANK\\.COM",   Pattern.CASE_INSENSITIVE),

            // ── Account-holder address block lines ────────────────────────────
            // These appear verbatim when a PDF page break falls mid-transaction:
            //   "HDFC BANK LIMITED"
            //   "THIS STATEMENT."
            //   "ADDRESS : A. K. TOWER,"
            //   "DURGA CITY CENTER,"
            //   "NAINITAL ROAD,"
            //   "MR MANAS KAPOOR CITY : HALDWANI 263139"
            //   "STATE : UTTARAKHAND"
            Pattern.compile("\\bHDFC BANK LIMITED\\b",                   Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bTHIS STATEMENT\\.?\\s*$",                Pattern.CASE_INSENSITIVE),
            Pattern.compile("^ADDRESS\\s*:",                              Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCITY\\s*:\\s*[A-Z]{3}",                  Pattern.CASE_INSENSITIVE),
            Pattern.compile("^STATE\\s*:\\s*[A-Z]{3}",                   Pattern.CASE_INSENSITIVE),
            // Catch intermediate address-continuation lines: short lines ending with a comma
            // that contain no digits and no transaction keywords — these are street / area names
            // (e.g. "DURGA CITY CENTER,", "NAINITAL ROAD,", "SECTOR 62,")
            Pattern.compile("^[A-Z][A-Z .'-]{2,40},$",                   Pattern.CASE_INSENSITIVE)
    );

    // ─────────────────────────────────────────────────────────────────────────
    // BankStatementParser contract
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean supports(String rawText) {
        // Only check the header section of the PDF (first 2000 chars).
        // "HDFC0" and "HDFC BANK" appear in transaction narrations of ANY bank's
        // statement when the counterparty uses HDFC — scanning the full text
        // causes false positives (e.g. Axis/SBI statements with HDFC transfers).
        String header = rawText.length() > 2000 ? rawText.substring(0, 2000) : rawText;
        return header.contains("HDFC BANK") || header.contains("HDFC0");
    }

    @Override
    public StatementParseResult parse(String rawText) {
        StatementParseResult result = new StatementParseResult();
        result.setBankName("HDFC");

        // ── Extract statement period (From + To) ──────────────────────────────
        Matcher periodMatcher = PERIOD_PATTERN.matcher(rawText);
        if (periodMatcher.find()) {
            LocalDate fromDate = LocalDate.of(
                    Integer.parseInt(periodMatcher.group(3)),  // yyyy
                    Integer.parseInt(periodMatcher.group(2)),  // MM
                    Integer.parseInt(periodMatcher.group(1))   // dd
            );
            LocalDate toDate = LocalDate.of(
                    Integer.parseInt(periodMatcher.group(6)),  // yyyy
                    Integer.parseInt(periodMatcher.group(5)),  // MM
                    Integer.parseInt(periodMatcher.group(4))   // dd
            );
            result.setStatementFromDate(fromDate);
            result.setStatementToDate(toDate);

            log.info("HDFC statement period: {} to {} ({} month(s))",
                    fromDate, toDate, result.getMonthsSpanned());
        } else {
            log.warn("Could not detect statement period — From/To pattern not found in PDF");
        }

        // ── Extract summary totals ────────────────────────────────────────────
        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(rawText);
        if (summaryMatcher.find()) {
            result.setOpeningBalance(parseAmount(summaryMatcher.group(1)));
            result.setTotalDebits(parseAmount(summaryMatcher.group(4)));
            result.setTotalCredits(parseAmount(summaryMatcher.group(5)));
            result.setClosingBalance(parseAmount(summaryMatcher.group(6)));
        }

        // ── Extract transactions ──────────────────────────────────────────────
        List<ParsedTransaction> transactions = extractTransactions(rawText, result.getOpeningBalance());
        result.setTransactions(transactions);

        log.info("HDFC parser: extracted {} transactions, period {} to {}",
                transactions.size(), result.getStatementFromDate(), result.getStatementToDate());

        // ── Balance sanity check ──────────────────────────────────────────────
        if (result.getOpeningBalance() != null && result.getTotalCredits() != null
                && result.getTotalDebits() != null && result.getClosingBalance() != null) {
            BigDecimal expected = result.getOpeningBalance()
                    .add(result.getTotalCredits())
                    .subtract(result.getTotalDebits());
            BigDecimal diff = expected.subtract(result.getClosingBalance()).abs();
            if (diff.compareTo(new BigDecimal("1.00")) > 0) {
                log.warn("Balance sanity check FAILED: expected={} actual={} diff={}",
                        expected, result.getClosingBalance(), diff);
            } else {
                log.info("Balance sanity check passed. diff={}", diff);
            }
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core extraction
    // ─────────────────────────────────────────────────────────────────────────

    private List<ParsedTransaction> extractTransactions(String rawText, BigDecimal openingBalance) {
        List<ParsedTransaction> results = new ArrayList<>();

        String[] rawLines = rawText.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            String trimmed = line.trim().replaceAll("\\s{2,}", " ");
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }

        List<Integer> txStarts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (DATE_LINE_START.matcher(lines.get(i)).find()) {
                txStarts.add(i);
            }
        }

        log.debug("Found {} transaction start lines", txStarts.size());

        BigDecimal previousBalance = openingBalance;
        int boilerplateLinesSkipped = 0;

        for (int i = 0; i < txStarts.size(); i++) {
            int from = txStarts.get(i);
            int to   = (i + 1 < txStarts.size()) ? txStarts.get(i + 1) : lines.size();

            String firstLine = lines.get(from);

            StringBuilder continuationSb = new StringBuilder();
            for (int j = from + 1; j < to; j++) {
                String candidateLine = lines.get(j);

                if (isBoilerplateLine(candidateLine)) {
                    boilerplateLinesSkipped++;
                    log.debug("Skipping boilerplate line between transactions: '{}'", candidateLine);
                    continue;
                }

                if (continuationSb.length() > 0) continuationSb.append("-");
                continuationSb.append(candidateLine);
            }
            String continuation = continuationSb.toString().trim();

            try {
                ParsedTransaction tx = parseTransactionLine(firstLine, continuation, previousBalance);
                if (tx != null) {
                    results.add(tx);
                    if (tx.getClosingBalance() != null) {
                        previousBalance = tx.getClosingBalance();
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse tx line: '{}' | error: {}", firstLine, e.getMessage());
            }
        }

        if (boilerplateLinesSkipped > 0) {
            log.info("Skipped {} statement boilerplate lines while extracting transactions", boilerplateLinesSkipped);
        }

        return results;
    }

    /**
     * Returns true if this line is page header/footer boilerplate rather
     * than genuine transaction narration continuation. See
     * BOILERPLATE_LINE_PATTERNS for the explicit, auditable list of phrases
     * that trigger this.
     */
    private boolean isBoilerplateLine(String line) {
        for (Pattern p : BOILERPLATE_LINE_PATTERNS) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private ParsedTransaction parseTransactionLine(String firstLine,
                                                   String continuation,
                                                   BigDecimal previousBalance) {
        Matcher m = TX_LINE_PATTERN.matcher(firstLine);
        if (!m.find()) {
            log.debug("Skipping non-tx date line: '{}'", firstLine);
            return null;
        }

        String dateStr      = m.group(1);
        String narration1   = m.group(2).trim();
        String refNo        = m.group(3);
        String valueDateStr = m.group(4);
        String txAmountStr  = m.group(5);
        String closingStr   = m.group(6);

        String fullNarration = continuation.isBlank()
                ? narration1
                : narration1 + "-" + continuation;

        ParsedTransaction tx = new ParsedTransaction();

        // Parse transaction date
        LocalDate txDate;
        try {
            txDate = LocalDate.parse(dateStr, DATE_FMT);
            tx.setDate(txDate);
        } catch (Exception e) {
            log.warn("Bad date '{}' on line: {}", dateStr, firstLine);
            return null;
        }

        try {
            tx.setValueDate(LocalDate.parse(valueDateStr, DATE_FMT));
        } catch (Exception e) {
            log.warn("Bad value date '{}' on line: {}", valueDateStr, firstLine);
            return null;
        }

        // Tag with transaction's OWN month — not the statement period
        // Critical for multi-month PDFs: a March tx in a Jan-Jun PDF gets month=3
        tx.setStatementYear(txDate.getYear());
        tx.setStatementMonth(txDate.getMonthValue());

        tx.setReferenceNumber(refNo);
        tx.setRawNarration(fullNarration);

        BigDecimal txAmount       = parseAmount(txAmountStr);
        BigDecimal closingBalance = (closingStr != null && !closingStr.isBlank())
                ? parseAmount(closingStr) : null;

        tx.setClosingBalance(closingBalance);

        // Credit/Debit: balance delta first, narration keyword fallback, default DEBIT
        TransactionType type = null;
        if (closingBalance != null && previousBalance != null) {
            int cmp = closingBalance.compareTo(previousBalance);
            if (cmp > 0)      type = TransactionType.CREDIT;
            else if (cmp < 0) type = TransactionType.DEBIT;
        }
        if (type == null) type = inferTypeFromNarration(fullNarration);
        if (type == null) {
            type = TransactionType.DEBIT;
            log.warn("Defaulted DEBIT for ref={} narration='{}' prev={} closing={}",
                    refNo, fullNarration, previousBalance, closingBalance);
        }

        tx.setType(type);
        if (type == TransactionType.CREDIT) tx.setDepositAmount(txAmount);
        else                                tx.setWithdrawalAmount(txAmount);

        enrichFromNarration(tx, fullNarration);

        return tx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Credit/Debit narration fallback
    // ─────────────────────────────────────────────────────────────────────────

    private TransactionType inferTypeFromNarration(String narration) {
        String upper = narration.toUpperCase();

        // CREDIT signals
        if (upper.contains("UPIRET"))            return TransactionType.CREDIT;
        if (upper.contains("NEFT CR"))           return TransactionType.CREDIT;
        if (upper.contains("IMPS CR"))           return TransactionType.CREDIT;
        if (upper.contains("RTGS CR"))           return TransactionType.CREDIT;
        if (upper.contains("SALARY"))            return TransactionType.CREDIT;
        if (upper.contains("INTEREST CR"))       return TransactionType.CREDIT;
        if (upper.contains("INT.CR"))            return TransactionType.CREDIT;
        if (upper.contains("CASHBACK"))          return TransactionType.CREDIT;
        if (upper.contains("REVERSAL"))          return TransactionType.CREDIT;
        if (upper.contains("REFUND"))            return TransactionType.CREDIT;
        if (upper.contains("/CR/"))              return TransactionType.CREDIT;
        if (upper.contains("-CR-"))              return TransactionType.CREDIT;
        if (upper.contains("@PAYTM-PUNB"))       return TransactionType.CREDIT;
        if (upper.contains("@OKHDFCBANK-PUNB"))  return TransactionType.CREDIT;

        // DEBIT signals
        if (upper.contains("NEFT DR"))           return TransactionType.DEBIT;
        if (upper.contains("IMPS DR"))           return TransactionType.DEBIT;
        if (upper.contains("RTGS DR"))           return TransactionType.DEBIT;
        if (upper.contains("ATM"))               return TransactionType.DEBIT;
        if (upper.contains("POS "))              return TransactionType.DEBIT;
        if (upper.contains("EMI"))               return TransactionType.DEBIT;
        if (upper.contains("BILL PAYMENT"))      return TransactionType.DEBIT;
        if (upper.contains("BILLPAY"))           return TransactionType.DEBIT;
        if (upper.contains("MANDATE"))           return TransactionType.DEBIT;
        if (upper.contains("ECS DR"))            return TransactionType.DEBIT;
        if (upper.contains("ACH DR"))            return TransactionType.DEBIT;
        if (upper.contains("CHQ"))               return TransactionType.DEBIT;
        if (upper.contains("CLG"))               return TransactionType.DEBIT;
        if (upper.contains("SENT FROM PAYTM"))   return TransactionType.DEBIT;
        if (upper.contains("PAY VIA RAZORPAY"))  return TransactionType.DEBIT;
        if (upper.contains("PAYVIARAZORPAY"))    return TransactionType.DEBIT;
        if (upper.contains("UPIINTENT"))         return TransactionType.DEBIT;
        if (upper.contains("ZOMATO PAYMENT"))    return TransactionType.DEBIT;
        if (upper.contains("SWIGGY ORDER"))      return TransactionType.DEBIT;
        if (upper.contains("YOU ARE PAYING"))    return TransactionType.DEBIT;
        if (upper.contains("PAYMENT FOR"))       return TransactionType.DEBIT;
        if (upper.contains("PAYMENTTOROPPENTRA"))return TransactionType.DEBIT;

        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Narration enrichment
    // ─────────────────────────────────────────────────────────────────────────

    private void enrichFromNarration(ParsedTransaction tx, String narration) {
        String upper = narration.toUpperCase();

        if (UPIRET_PATTERN.matcher(upper).find()) {
            tx.setMode(TransactionMode.UPI_REFUND);
            tx.setRefund(true);
            return;
        }
        if (EMI_PATTERN.matcher(upper).find()) {
            tx.setMode(TransactionMode.EMI);
            tx.setCounterpartyName("EMI Payment");
            return;
        }
        if (NEFT_PATTERN.matcher(upper).find()) {
            tx.setMode(TransactionMode.NEFT);
            extractNeftCounterparty(tx, narration);
            return;
        }
        if (IMPS_PATTERN.matcher(upper).find()) {
            tx.setMode(TransactionMode.IMPS);
            return;
        }
        if (AUTOPAY_PATTERN.matcher(upper).find()) {
            tx.setMode(TransactionMode.AUTOPAY);
            extractUpiCounterparty(tx, narration);
            return;
        }
        if (upper.startsWith("UPI-")) {
            tx.setMode(TransactionMode.UPI);
            extractUpiCounterparty(tx, narration);
            return;
        }

        tx.setMode(TransactionMode.OTHER);
    }

    private void extractUpiCounterparty(ParsedTransaction tx, String narration) {
        String body = narration
                .replaceFirst("(?i)^UPI-AUTOPAY-", "UPI-")
                .replaceFirst("(?i)^UPI-", "");

        Matcher m = Pattern.compile(
                "^([A-Z0-9 &._-]+?)-([A-Za-z0-9._]+@[A-Za-z0-9]+)-",
                Pattern.CASE_INSENSITIVE
        ).matcher(body);

        if (m.find()) {
            String name   = m.group(1).trim();
            String handle = m.group(2).trim();
            if (looksLikePerson(name) && !isKnownMerchantHandle(handle)) {
                tx.setCounterpartyName(toTitleCase(name));
                Matcher phoneMatcher = PHONE_PATTERN.matcher(narration);
                if (phoneMatcher.find()) tx.setCounterpartyPhone(phoneMatcher.group(1));
            } else {
                tx.setCounterpartyName(resolveKnownMerchant(name));
            }
            tx.setUpiHandle(handle.toLowerCase());
            return;
        }

        String[] parts = body.split("[-@]");
        if (parts.length > 0) tx.setCounterpartyName(toTitleCase(parts[0].trim()));
    }

    private void extractNeftCounterparty(ParsedTransaction tx, String narration) {
        String[] parts = narration.split("[-/]");
        if (parts.length >= 2) tx.setCounterpartyName(toTitleCase(parts[1].trim()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merchant resolution
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveKnownMerchant(String raw) {
        String upper = raw.toUpperCase().trim();
        if (upper.contains("SWIGGY"))             return "Swiggy";
        if (upper.contains("ZOMATO"))             return "Zomato";
        if (upper.contains("BLINKIT"))            return "Blinkit";
        if (upper.contains("ZEPTO"))              return "Zepto";
        if (upper.contains("FLIPKART"))           return "Flipkart";
        if (upper.contains("AMAZON"))             return "Amazon";
        if (upper.contains("DOMINOS"))            return "Domino's";
        if (upper.contains("KREDITBEE"))          return "KreditBee";
        if (upper.contains("PAYSENSE") ||
                upper.contains("LAZYPAY"))            return "LazyPay";
        if (upper.contains("ALISTETECHNOLOGIES")) return "Aliste Technologies";
        if (upper.contains("GODADDY"))            return "GoDaddy";
        if (upper.contains("AIRTEL"))             return "Airtel";
        if (upper.contains("GOOGLE PLAY") ||
                upper.contains("PLAYSTORE"))          return "Google Play";
        if (upper.contains("STAZY"))              return "Stazy (Rent)";
        if (upper.contains("RAZORPAY RIZE") ||
                upper.contains("RAZORPAYRIZE"))       return "Razorpay Rize";
        if (upper.contains("PAYTM TRAVEL"))       return "Paytm Travel";
        if (upper.contains("NOIDA METRO") ||
                upper.contains("NMRCL"))              return "Noida Metro";
        if (upper.contains("HEAD MASTERS") ||
                upper.contains("UNI-PAYTM"))          return "Headmasters Uni";
        if (upper.contains("AXIS BANK") &&
                upper.contains("REECHARGE"))          return "Freecharge";
        if (upper.contains("WHISKY SHOP") ||
                upper.contains("WHISKYSHOP"))         return "Whisky Shop";
        if (upper.contains("CHETANWINE"))         return "Chetan Wine Shop";
        if (upper.contains("RSPL RIZE"))          return "RSPL Rize";
        if (upper.contains("CODEYETI"))           return "CodeYeti Software";
        return toTitleCase(upper);
    }

    private boolean isKnownMerchantHandle(String handle) {
        String lower = handle.toLowerCase();
        return lower.contains("swiggy")    || lower.contains("zomato")    ||
                lower.contains("blinkit")   || lower.contains("zepto")     ||
                lower.contains("flipkart")  || lower.contains("amazon")    ||
                lower.contains("dominos")   || lower.contains("kreditbee") ||
                lower.contains("lazypay")   || lower.contains("aliste")    ||
                lower.contains("godaddy")   || lower.contains("airtel")    ||
                lower.contains("playstore") || lower.contains("stazy")     ||
                lower.contains("razorpay")  || lower.contains("paytm")     ||
                lower.contains("codeyeti");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean looksLikePerson(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length < 2 || words.length > 4) return false;
        for (String word : words) {
            if (word.matches(".*\\d.*")) return false;
        }
        return true;
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(raw.replace(",", "").trim());
    }

    private String toTitleCase(String input) {
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