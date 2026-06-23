package com.moneylens.categorization;

import com.moneylens.entity.*;
import com.moneylens.repository.MerchantCategoryCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Assigns systemCategory + categorySource to each transaction.
 *
 * Priority:
 *   1. Refund flag             → REFUND
 *   2. Merchant registry       → specific category (deterministic, free, instant)
 *   3. Mode-based rules        → ATM / EMI / NEFT etc.
 *   4. Merchant cache (AI)     → previously AI-classified merchant, reused for free
 *   5. AI classification       → genuinely unseen merchants, classified in ONE
 *                                 batched call per statement (not one call per
 *                                 transaction), cached forever
 *   6. Type fallback           → CREDIT → TRANSFERS, DEBIT → OTHER
 *
 * Never writes to userCategory — that is only written by user action.
 */
@Service
public class TransactionCategorizationService {

    private static final Logger log = LoggerFactory.getLogger(TransactionCategorizationService.class);

    private final MerchantRegistry merchantRegistry;
    private final MerchantCategoryCacheRepository cacheRepository;
    private final MerchantKeyNormalizer keyNormalizer;
    private final AiMerchantClassifier aiClassifier;

    public TransactionCategorizationService(MerchantRegistry merchantRegistry,
                                            MerchantCategoryCacheRepository cacheRepository,
                                            MerchantKeyNormalizer keyNormalizer,
                                            AiMerchantClassifier aiClassifier) {
        this.merchantRegistry = merchantRegistry;
        this.cacheRepository = cacheRepository;
        this.keyNormalizer = keyNormalizer;
        this.aiClassifier = aiClassifier;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Two-pass categorization:
     *   Pass 1 — resolve everything deterministic (refund, registry, mode,
     *            existing cache) and collect whatever's left unresolved.
     *   Pass 2 — batch-classify every distinct unresolved merchant in as
     *            few AI calls as possible, then apply results back to every
     *            transaction sharing that merchant key.
     */
    public void categorize(List<Transaction> transactions) {
        int matched = 0;
        int aiClassified = 0;
        int fallback = 0;

        // merchantKey -> all transactions in this batch sharing that key
        Map<String, List<Transaction>> unresolved = new LinkedHashMap<>();
        // merchantKey -> the raw narration to use when asking the AI
        // (first transaction's narration is representative enough)
        Map<String, String> narrationByKey = new LinkedHashMap<>();

        // ── Pass 1: deterministic rules + existing cache ──────────────────
        for (Transaction tx : transactions) {
            try {
                if (tx.isRefund()) {
                    apply(tx, Category.REFUND, CategorySource.RULE);
                    matched++;
                    continue;
                }

                Optional<Category> fromRegistry = merchantRegistry.lookup(
                        tx.getMerchantName(), tx.getRawNarration(), tx.getCounterpartyName());
                if (fromRegistry.isPresent()) {
                    apply(tx, fromRegistry.get(), CategorySource.RULE);
                    matched++;
                    continue;
                }

                if (tx.getMode() != null) {
                    Category fromMode = categoryFromMode(tx.getMode());
                    if (fromMode != null) {
                        apply(tx, fromMode, CategorySource.RULE);
                        matched++;
                        continue;
                    }
                }

                // UPI P2A / P2P narrations are always person-to-person transfers
                if (tx.getMode() == TransactionMode.UPI) {
                    String n = tx.getRawNarration();
                    if (n != null) {
                        String nu = n.toUpperCase();
                        if (nu.contains("/P2A/") || nu.contains("-P2A-")
                                || nu.contains("/P2P/") || nu.contains("-P2P-")) {
                            apply(tx, Category.TRANSFERS, CategorySource.RULE);
                            matched++;
                            continue;
                        }
                    }
                }

                // Prefer counterpartyName over merchantName as the AI lookup key —
                // parsers set counterpartyName but leave merchantName null.
                String nameForKey = tx.getMerchantName() != null ? tx.getMerchantName()
                        : tx.getCounterpartyName();
                String merchantKey = keyNormalizer.normalize(nameForKey, tx.getRawNarration());

                Optional<MerchantCategoryCache> cached = cacheRepository.findByMerchantKey(merchantKey);
                if (cached.isPresent()) {
                    MerchantCategoryCache c = cached.get();
                    c.setLastSeen(LocalDateTime.now());
                    cacheRepository.save(c);
                    apply(tx, c.getCategory(), CategorySource.AI);
                    aiClassified++;
                    continue;
                }

                // Genuinely unresolved — defer to the batch AI pass below.
                unresolved.computeIfAbsent(merchantKey, k -> new ArrayList<>()).add(tx);
                narrationByKey.putIfAbsent(merchantKey, tx.getRawNarration());

            } catch (Exception e) {
                log.warn("Pass-1 categorization failed for ref={}: {}",
                        tx.getReferenceNumber(), e.getMessage());
                apply(tx, Category.OTHER, CategorySource.RULE);
                fallback++;
            }
        }

        // ── Pass 2: one batched AI call for every distinct unresolved merchant ──
        if (!unresolved.isEmpty()) {
            List<AiMerchantClassifier.MerchantInput> batchInput = new ArrayList<>();
            for (String merchantKey : unresolved.keySet()) {
                batchInput.add(new AiMerchantClassifier.MerchantInput(merchantKey, narrationByKey.get(merchantKey)));
            }

            log.info("{} distinct unresolved merchants across {} transactions — sending to batched AI classifier",
                    unresolved.size(), unresolved.values().stream().mapToInt(List::size).sum());

            Map<String, AiMerchantClassifier.AiClassificationResult> aiResults =
                    aiClassifier.classifyBatch(batchInput);

            for (Map.Entry<String, List<Transaction>> entry : unresolved.entrySet()) {
                String merchantKey = entry.getKey();
                List<Transaction> txsForMerchant = entry.getValue();

                AiMerchantClassifier.AiClassificationResult result = aiResults.get(merchantKey);

                if (result == null) {
                    // AI failed for this specific merchant — fall back to type-based default.
                    for (Transaction tx : txsForMerchant) {
                        if (tx.getType() == TransactionType.CREDIT) {
                            apply(tx, Category.TRANSFERS, CategorySource.RULE);
                        } else {
                            apply(tx, Category.OTHER, CategorySource.RULE);
                        }
                        fallback++;
                    }
                    log.warn("AI returned no result for merchant '{}' (narration: '{}') — {} txn(s) → OTHER/TRANSFERS",
                            merchantKey,
                            narrationByKey.get(merchantKey) != null
                                    ? narrationByKey.get(merchantKey).substring(0, Math.min(60, narrationByKey.get(merchantKey).length()))
                                    : "null",
                            txsForMerchant.size());
                    continue;
                }

                Category resolvedCategory = resolveAndCache(merchantKey, result);

                for (Transaction tx : txsForMerchant) {
                    apply(tx, resolvedCategory, CategorySource.AI);
                    aiClassified++;
                }
            }
        }

        int total = matched + aiClassified + fallback;
        log.info("Categorization done — total: {}, rule-matched: {} ({}%), AI-classified: {} ({}%), OTHER/fallback: {} ({}%)",
                total,
                matched,      total > 0 ? matched      * 100 / total : 0,
                aiClassified, total > 0 ? aiClassified * 100 / total : 0,
                fallback,     total > 0 ? fallback      * 100 / total : 0);
    }

    /**
     * Single-transaction recategorize — has nothing to batch against, so it
     * goes through the AiMerchantClassifier's single-item convenience method
     * (which internally still uses the batch code path, just with one item).
     */
    public void recategorize(Transaction tx) {
        tx.setUserCategory(null);

        if (tx.isRefund()) {
            apply(tx, Category.REFUND, CategorySource.RULE);
            return;
        }

        Optional<Category> fromRegistry = merchantRegistry.lookup(tx.getMerchantName(), tx.getRawNarration(), tx.getCounterpartyName());
        if (fromRegistry.isPresent()) {
            apply(tx, fromRegistry.get(), CategorySource.RULE);
            return;
        }

        if (tx.getMode() != null) {
            Category fromMode = categoryFromMode(tx.getMode());
            if (fromMode != null) {
                apply(tx, fromMode, CategorySource.RULE);
                return;
            }
        }

        if (tx.getMode() == TransactionMode.UPI) {
            String n = tx.getRawNarration();
            if (n != null) {
                String nu = n.toUpperCase();
                if (nu.contains("/P2A/") || nu.contains("-P2A-")
                        || nu.contains("/P2P/") || nu.contains("-P2P-")) {
                    apply(tx, Category.TRANSFERS, CategorySource.RULE);
                    return;
                }
            }
        }

        String nameForKey = tx.getMerchantName() != null ? tx.getMerchantName()
                : tx.getCounterpartyName();
        String merchantKey = keyNormalizer.normalize(nameForKey, tx.getRawNarration());

        Optional<MerchantCategoryCache> cached = cacheRepository.findByMerchantKey(merchantKey);
        if (cached.isPresent()) {
            MerchantCategoryCache c = cached.get();
            c.setLastSeen(LocalDateTime.now());
            cacheRepository.save(c);
            apply(tx, c.getCategory(), CategorySource.AI);
            return;
        }

        AiMerchantClassifier.AiClassificationResult result =
                aiClassifier.classify(merchantKey, tx.getRawNarration());

        if (result == null) {
            if (tx.getType() == TransactionType.CREDIT) {
                apply(tx, Category.TRANSFERS, CategorySource.RULE);
            } else {
                apply(tx, Category.OTHER, CategorySource.RULE);
            }
            return;
        }

        Category resolvedCategory = resolveAndCache(merchantKey, result);
        apply(tx, resolvedCategory, CategorySource.AI);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core logic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates an AI result's category against the real Category enum and
     * persists it to the cache permanently — same defensive validation and
     * truncation-safe persistence as before, just factored out so both the
     * batch path and recategorize() share one implementation.
     */
    private Category resolveAndCache(String merchantKey, AiMerchantClassifier.AiClassificationResult result) {
        Category resolvedCategory;
        try {
            resolvedCategory = Category.valueOf(result.categoryName);
        } catch (IllegalArgumentException e) {
            log.warn("AI returned unrecognized category '{}' for merchant '{}' — defaulting to OTHER. " +
                            "This means the AI ignored the allowed category list in the prompt.",
                    result.categoryName, merchantKey);
            resolvedCategory = Category.OTHER;
        }

        MerchantCategoryCache cacheEntry = new MerchantCategoryCache();
        cacheEntry.setMerchantKey(merchantKey);
        cacheEntry.setCategory(resolvedCategory);
        cacheEntry.setSource(CategorySource.AI);
        cacheEntry.setConfidence(result.confidence);
        cacheEntry.setLastSeen(LocalDateTime.now());
        cacheEntry.setCreatedAt(LocalDateTime.now());

        try {
            cacheRepository.saveAndFlush(cacheEntry);
            log.info("Cached AI classification: '{}' -> {} (confidence={})",
                    merchantKey, resolvedCategory, result.confidence);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("Merchant '{}' was cached concurrently — re-fetching instead of inserting duplicate.", merchantKey);
            return cacheRepository.findByMerchantKey(merchantKey)
                    .map(MerchantCategoryCache::getCategory)
                    .orElse(resolvedCategory);
        } catch (Exception e) {
            log.error("Failed to persist merchant cache entry for '{}': {} — category still applied in-memory, " +
                    "just not cached for reuse.", merchantKey, e.getMessage());
            return resolvedCategory;
        }

        return resolvedCategory;
    }

    private void apply(Transaction tx, Category category, CategorySource source) {
        tx.setSystemCategory(category);
        tx.setCategorySource(source);
    }

    private Category categoryFromMode(TransactionMode mode) {
        return switch (mode) {
            case ATM              -> Category.CASH_WITHDRAWAL;
            case EMI              -> Category.LOAN_AND_EMI;
            case AUTOPAY          -> Category.UTILITIES_AND_BILLS;
            case UPI_REFUND       -> Category.REFUND;
            case NEFT, IMPS, RTGS -> Category.TRANSFERS;
            case CHEQUE           -> Category.TRANSFERS;
            default               -> null;
        };
    }
}