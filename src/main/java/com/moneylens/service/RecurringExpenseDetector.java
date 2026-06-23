package com.moneylens.service;

import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects recurring expenses from a single month's transaction list.
 *
 * Strategy:
 *   - Group DEBIT transactions by normalized counterparty/merchant name
 *   - Flag as "recurring" if the same counterparty appears 2+ times
 *     OR if a known subscription/EMI keyword is found in the narration
 *   - Classify each recurring item into a RecurringType
 */
@Service
public class RecurringExpenseDetector {

    public enum RecurringType { SUBSCRIPTION, EMI, RENT, UTILITY, TRANSFER, UNKNOWN }

    public static class RecurringExpense {
        public final String        merchant;
        public final BigDecimal    totalAmount;
        public final int           occurrences;
        public final RecurringType type;

        public RecurringExpense(String merchant, BigDecimal totalAmount,
                                int occurrences, RecurringType type) {
            this.merchant    = merchant;
            this.totalAmount = totalAmount;
            this.occurrences = occurrences;
            this.type        = type;
        }

        @Override
        public String toString() {
            return String.format("RecurringExpense{merchant='%s', amount=₹%s, type=%s, occurrences=%d}",
                    merchant, totalAmount.toPlainString(), type, occurrences);
        }
    }

    // Known subscription merchants / narration keywords → type
    private static final Map<String, RecurringType> KNOWN_KEYWORDS = new LinkedHashMap<>();
    static {
        // Streaming & digital subscriptions
        KNOWN_KEYWORDS.put("netflix",     RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("spotify",     RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("prime video", RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("amazon prime",RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("hotstar",     RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("disney",      RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("youtube",     RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("zee5",        RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("sonyliv",     RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("mxplayer",    RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("apple music", RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("gaana",       RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("jiosaavn",    RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("linkedin",    RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("chatgpt",     RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("openai",      RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("dropbox",     RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("google one",  RecurringType.SUBSCRIPTION);
        KNOWN_KEYWORDS.put("icloud",      RecurringType.SUBSCRIPTION);
        // Telecom / recharge
        KNOWN_KEYWORDS.put("airtel",      RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("jio",         RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("bsnl",        RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("vodafone",    RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("vi ",         RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("idea",        RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("recharge",    RecurringType.UTILITY);
        // Utilities
        KNOWN_KEYWORDS.put("electricity", RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("bescom",      RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("msedcl",      RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("tata power",  RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("bses",        RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("adani elec",  RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("water",       RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("gas",         RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("indraprastha",RecurringType.UTILITY);
        KNOWN_KEYWORDS.put("mahanagar",   RecurringType.UTILITY);
        // EMI / loan repayments
        KNOWN_KEYWORDS.put("emi",         RecurringType.EMI);
        KNOWN_KEYWORDS.put("loan",        RecurringType.EMI);
        KNOWN_KEYWORDS.put("bajaj fin",   RecurringType.EMI);
        KNOWN_KEYWORDS.put("hdfc loan",   RecurringType.EMI);
        KNOWN_KEYWORDS.put("icici loan",  RecurringType.EMI);
        KNOWN_KEYWORDS.put("sbi loan",    RecurringType.EMI);
        KNOWN_KEYWORDS.put("kotak loan",  RecurringType.EMI);
        KNOWN_KEYWORDS.put("axis loan",   RecurringType.EMI);
        KNOWN_KEYWORDS.put("kreditbee",   RecurringType.EMI);
        KNOWN_KEYWORDS.put("lazypay",     RecurringType.EMI);
        KNOWN_KEYWORDS.put("simpl",       RecurringType.EMI);
        KNOWN_KEYWORDS.put("zestmoney",   RecurringType.EMI);
        KNOWN_KEYWORDS.put("navi",        RecurringType.EMI);
        KNOWN_KEYWORDS.put("moneyview",   RecurringType.EMI);
        KNOWN_KEYWORDS.put("autopay",     RecurringType.EMI);
        // Rent
        KNOWN_KEYWORDS.put("rent",        RecurringType.RENT);
        KNOWN_KEYWORDS.put("house rent",  RecurringType.RENT);
        KNOWN_KEYWORDS.put("landlord",    RecurringType.RENT);
        KNOWN_KEYWORDS.put("pg ",         RecurringType.RENT);
        KNOWN_KEYWORDS.put("hostel",      RecurringType.RENT);
        KNOWN_KEYWORDS.put("flat rent",   RecurringType.RENT);
        KNOWN_KEYWORDS.put("maintenance", RecurringType.RENT);
        KNOWN_KEYWORDS.put("society",     RecurringType.RENT);
        // Recurring transfers
        KNOWN_KEYWORDS.put("sip",         RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("mutual fund", RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("mf ",         RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("ppf",         RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("nps",         RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("rd ",         RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("recurring dep",RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("insurance",   RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("lic ",        RecurringType.TRANSFER);
        KNOWN_KEYWORDS.put("policy",      RecurringType.TRANSFER);
    }

    /**
     * Detects recurring expenses from the provided transaction list.
     * Pure statement analysis — no assessment data involved.
     *
     * @param transactions All transactions for the period (filtered to DEBIT internally)
     * @return             List of recurring expenses, sorted by total amount descending
     */
    public List<RecurringExpense> detect(List<Transaction> transactions) {
        List<Transaction> debits = transactions.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .collect(Collectors.toList());

        Map<String, List<Transaction>> grouped = new LinkedHashMap<>();
        for (Transaction t : debits) {
            String key = normalizeKey(t);
            if (key == null || key.isBlank()) continue;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<RecurringExpense> result = new ArrayList<>();

        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            String key            = entry.getKey();
            List<Transaction> txs = entry.getValue();

            RecurringType type  = detectType(key);
            boolean isRecurring = txs.size() >= 2 || type != RecurringType.UNKNOWN;
            if (!isRecurring) continue;

            BigDecimal total = txs.stream()
                    .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(new RecurringExpense(key, total, txs.size(), type));
        }

        result.sort(Comparator.comparing((RecurringExpense r) -> r.totalAmount).reversed());
        return result;
    }

    /**
     * Returns the total monthly fixed-obligation amount from detected recurring expenses.
     */
    public BigDecimal getTotalRecurringAmount(List<Transaction> transactions) {
        return detect(transactions).stream()
                .map(r -> r.totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Normalizes merchant/counterparty name to a clean, deduplicated key.
     * Prefers merchantName → counterpartyName → rawNarration (truncated).
     */
    private String normalizeKey(Transaction t) {
        String name = null;
        if (t.getMerchantName() != null && !t.getMerchantName().isBlank()) {
            name = t.getMerchantName();
        } else if (t.getCounterpartyName() != null && !t.getCounterpartyName().isBlank()) {
            name = t.getCounterpartyName();
        } else if (t.getRawNarration() != null) {
            // Use first 30 chars of narration as fallback key
            name = t.getRawNarration().length() > 30
                    ? t.getRawNarration().substring(0, 30)
                    : t.getRawNarration();
        }
        if (name == null) return null;
        return name.toLowerCase()
                .trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[^a-z0-9 ]", "");
    }

    /**
     * Matches the normalized key against known keywords to classify the expense type.
     * Iterates in insertion order so more specific matches (e.g. "amazon prime") win
     * over broader ones (e.g. "amazon").
     */
    private RecurringType detectType(String normalizedKey) {
        for (Map.Entry<String, RecurringType> entry : KNOWN_KEYWORDS.entrySet()) {
            if (normalizedKey.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return RecurringType.UNKNOWN;
    }
}