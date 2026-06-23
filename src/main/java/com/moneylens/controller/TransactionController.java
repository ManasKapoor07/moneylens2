package com.moneylens.controller;

import com.moneylens.categorization.TransactionCategorizationService;
import com.moneylens.dto.CategoryUpdateRequest;
import com.moneylens.dto.SubscriptionDto;
import com.moneylens.dto.TransactionDto;
import com.moneylens.dto.TransactionPageResponse;
import com.moneylens.entity.Category;
import com.moneylens.entity.CategorySource;
import com.moneylens.entity.RecurringPayment;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionType;
import com.moneylens.entity.User;
import com.moneylens.repository.RecurringPaymentRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserRepository;
import com.moneylens.service.StatementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final StatementService statementService;
    private final TransactionCategorizationService categorizationService;
    private final RecurringPaymentRepository recurringPaymentRepository;

    public TransactionController(
            TransactionRepository transactionRepository,
            UserRepository userRepository,
            StatementService statementService,
            TransactionCategorizationService categorizationService,
            RecurringPaymentRepository recurringPaymentRepository) {
        this.transactionRepository    = transactionRepository;
        this.userRepository           = userRepository;
        this.statementService         = statementService;
        this.categorizationService    = categorizationService;
        this.recurringPaymentRepository = recurringPaymentRepository;
    }

    /**
     * GET /api/v1/transactions
     *
     * Query params (all optional):
     *   page     — 0-based page number (default 0)
     *   size     — page size (default 25, max 100)
     *   year     — filter by statement year
     *   month    — filter by statement month (1–12), requires year
     *   type     — CREDIT | DEBIT
     *   category — display name e.g. "Food & Dining" (exact match on effectiveCategory)
     *   search   — free text search in merchantName, counterpartyName, rawNarration
     */
    @GetMapping
    public ResponseEntity<TransactionPageResponse> getTransactions(
            @AuthenticationPrincipal String phoneNumber,
            @RequestParam(defaultValue = "0")   int     page,
            @RequestParam(defaultValue = "25")  int     size,
            @RequestParam(required = false)     Integer year,
            @RequestParam(required = false)     Integer month,
            @RequestParam(required = false)     String  type,
            @RequestParam(required = false)     String  category,
            @RequestParam(required = false)     String  search) {

        // Clamp size to avoid abuse
        size = Math.min(size, 100);

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Fetch base set — by month if supplied, else all
        List<Transaction> base;
        if (year != null && month != null) {
            base = transactionRepository
                    .findByUserIdAndStatementYearAndStatementMonth(user.getId(), year, month);
        } else {
            base = transactionRepository.findByUserIdOrderByDateDesc(user.getId());
        }

        // Apply in-memory filters
        List<Transaction> filtered = base.stream()
                .filter(tx -> type == null || tx.getType().name().equalsIgnoreCase(type))
                .filter(tx -> {
                    if (category == null || category.isBlank()) return true;
                    Category eff = tx.getEffectiveCategory();
                    return eff != null && eff.getDisplayName().equalsIgnoreCase(category);
                })
                .filter(tx -> {
                    if (search == null || search.isBlank()) return true;
                    String q = search.toLowerCase();
                    return (tx.getMerchantName()     != null && tx.getMerchantName().toLowerCase().contains(q))
                        || (tx.getCounterpartyName() != null && tx.getCounterpartyName().toLowerCase().contains(q))
                        || (tx.getRawNarration()     != null && tx.getRawNarration().toLowerCase().contains(q));
                })
                .collect(Collectors.toList());

        long total = filtered.size();

        // Sort: newest first
        filtered.sort(Comparator.comparing(Transaction::getDate).reversed());

        // Totals across the FULL filtered set (correct even when paginated)
        BigDecimal filteredCredits = filtered.stream()
                .filter(tx -> tx.getType() == TransactionType.CREDIT)
                .map(tx -> tx.getDepositAmount() != null ? tx.getDepositAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal filteredDebits = filtered.stream()
                .filter(tx -> tx.getType() == TransactionType.DEBIT)
                .map(tx -> tx.getWithdrawalAmount() != null ? tx.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Paginate
        int from = Math.min(page * size, (int) total);
        int to   = Math.min(from + size, (int) total);
        List<TransactionDto> pageContent = filtered.subList(from, to).stream()
                .map(TransactionDto::from)
                .collect(Collectors.toList());

        int totalPages = (int) Math.ceil((double) total / size);

        return ResponseEntity.ok(new TransactionPageResponse(
                pageContent, total, page, size, totalPages, (page + 1) < totalPages,
                filteredCredits, filteredDebits
        ));
    }

    // ── GET /api/v1/transactions/categories ──────────────────────────────────
    @GetMapping("/categories")
    public ResponseEntity<List<Map<String, String>>> getCategories() {
        List<Map<String, String>> categories = Arrays.stream(Category.values())
                .map(c -> Map.of("value", c.name(), "displayName", c.getDisplayName()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(categories);
    }

    // ── PATCH /api/v1/transactions/{id}/category ─────────────────────────────
    @PatchMapping("/{id}/category")
    public ResponseEntity<CategoryResponse> updateCategory(
            @AuthenticationPrincipal String phoneNumber,
            @PathVariable Long id,
            @Valid @RequestBody CategoryUpdateRequest request) {

        Transaction tx = findAndVerifyOwner(id, phoneNumber);
        tx.setUserCategory(request.getCategory());
        tx.setCategorySource(CategorySource.USER);
        transactionRepository.save(tx);
        return ResponseEntity.ok(toResponse(tx));
    }

    // ── PATCH /api/v1/transactions/{id}/category/reset ───────────────────────
    @PatchMapping("/{id}/category/reset")
    public ResponseEntity<CategoryResponse> resetCategory(
            @AuthenticationPrincipal String phoneNumber,
            @PathVariable Long id) {

        Transaction tx = findAndVerifyOwner(id, phoneNumber);
        categorizationService.recategorize(tx);
        transactionRepository.save(tx);
        return ResponseEntity.ok(toResponse(tx));
    }

    // ── GET /api/v1/transactions/subscriptions ────────────────────────────────
    /**
     * Returns all detected recurring payments for the authenticated user,
     * deduplicated by merchantKey (latest entry per merchant wins).
     * Includes SUBSCRIPTION, EMI, RENT, UTILITY, TRANSFER types.
     */
    @GetMapping("/subscriptions")
    public ResponseEntity<SubscriptionDto.SubscriptionsResponse> getSubscriptions(
            @AuthenticationPrincipal String phoneNumber) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<RecurringPayment> all = recurringPaymentRepository
                .findByUserIdOrderByAmountDesc(user.getId());

        // Deduplicate: keep one entry per merchantKey (highest amount / most recent)
        Map<String, RecurringPayment> deduped = new LinkedHashMap<>();
        for (RecurringPayment rp : all) {
            deduped.merge(rp.getMerchantKey(), rp, (existing, incoming) ->
                    incoming.getMonthsDetected() >= existing.getMonthsDetected() ? incoming : existing);
        }

        List<SubscriptionDto.SubscriptionItem> items = deduped.values().stream()
                .sorted(Comparator.comparing(RecurringPayment::getAmount).reversed())
                .map(rp -> new SubscriptionDto.SubscriptionItem(
                        rp.getMerchant(),
                        rp.getMerchantKey(),
                        rp.getRecurringType().name(),
                        rp.getConfidence().name(),
                        rp.getAmount(),
                        rp.getMonthsDetected(),
                        rp.getProfileYear() + "-" + String.format("%02d", rp.getProfileMonth())
                ))
                .collect(Collectors.toList());

        BigDecimal total = items.stream()
                .map(SubscriptionDto.SubscriptionItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long confirmed = items.stream()
                .filter(i -> "CONFIRMED".equals(i.confidence()))
                .count();

        return ResponseEntity.ok(new SubscriptionDto.SubscriptionsResponse(items, total, (int) confirmed));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction findAndVerifyOwner(Long id, String phoneNumber) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + id));
        if (!tx.getUser().getPhoneNumber().equals(phoneNumber)) {
            throw new RuntimeException("Access denied");
        }
        return tx;
    }

    private CategoryResponse toResponse(Transaction tx) {
        return new CategoryResponse(
                tx.getId(),
                tx.getSystemCategory()    != null ? tx.getSystemCategory().getDisplayName()    : null,
                tx.getUserCategory()      != null ? tx.getUserCategory().getDisplayName()      : null,
                tx.getEffectiveCategory() != null ? tx.getEffectiveCategory().getDisplayName() : null,
                tx.getCategorySource()    != null ? tx.getCategorySource().name()              : null
        );
    }

    public record CategoryResponse(
            Long   transactionId,
            String systemCategory,
            String userCategory,
            String effectiveCategory,
            String categorySource
    ) {}
}
