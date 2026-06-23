package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.budget.BudgetJson;
import com.moneylens.dto.budget.ManualExpenseDto;
import com.moneylens.entity.*;
import com.moneylens.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    // ═════════════════════════════════════════════════════════════════════
    // Bucket mapping — built on top of the REAL com.moneylens.entity.Category,
    // not a parallel invented enum. This is the single source of truth for
    // Needs vs Wants vs Excluded-from-budget. The math here is fully
    // deterministic; AI is never involved in deciding these numbers — see
    // the class-level note in the chat for why.
    // ═════════════════════════════════════════════════════════════════════

    private enum Bucket { NEEDS, WANTS, EXCLUDED }

    private static final Map<Category, Bucket> CATEGORY_BUCKETS = Map.ofEntries(
            Map.entry(Category.RENT_AND_HOUSING,    Bucket.NEEDS),
            Map.entry(Category.LOAN_AND_EMI,        Bucket.NEEDS),
            Map.entry(Category.GROCERIES,           Bucket.NEEDS),
            Map.entry(Category.FOOD_AND_DINING,     Bucket.NEEDS), // food is essential regardless of where it's bought
            Map.entry(Category.UTILITIES_AND_BILLS, Bucket.NEEDS),
            Map.entry(Category.HEALTH_AND_FITNESS,  Bucket.NEEDS),
            Map.entry(Category.TRANSPORT,           Bucket.NEEDS),

            Map.entry(Category.ENTERTAINMENT,       Bucket.WANTS),
            Map.entry(Category.SHOPPING,            Bucket.WANTS),
            Map.entry(Category.TRAVEL,              Bucket.WANTS),
            Map.entry(Category.OTHER,               Bucket.WANTS),

            // Not spending categories — excluded entirely from budget math.
            // Including these would double-count savings/income as if they
            // were expenses, or distort percentages with non-spend money
            // movement.
            Map.entry(Category.TRANSFERS,       Bucket.EXCLUDED),
            Map.entry(Category.REFUND,          Bucket.EXCLUDED),
            Map.entry(Category.CASH_WITHDRAWAL, Bucket.EXCLUDED),
            Map.entry(Category.INVESTMENT,      Bucket.EXCLUDED)
    );

    // Fixed set of budget categories. These always appear in the budget regardless
    // of what the AI transaction classifier produced. Users pick from this list
    // (or add custom entries) and set their own amounts. The bucket assignment
    // here is the source of truth — AI never decides Needs vs Wants.
    public static final List<BudgetJson.CategoryTemplate> PREDEFINED_CATEGORIES = List.of(
            new BudgetJson.CategoryTemplate("Rent & Housing",    "NEEDS", true),
            new BudgetJson.CategoryTemplate("Loan & EMI",        "NEEDS", true),
            new BudgetJson.CategoryTemplate("Groceries",         "NEEDS", true),
            new BudgetJson.CategoryTemplate("Food & Dining",    "NEEDS", true),
            new BudgetJson.CategoryTemplate("Utilities & Bills","NEEDS", true),
            new BudgetJson.CategoryTemplate("Health & Fitness", "NEEDS", true),
            new BudgetJson.CategoryTemplate("Transport",        "NEEDS", true),
            new BudgetJson.CategoryTemplate("Entertainment",    "WANTS", true),
            new BudgetJson.CategoryTemplate("Shopping",          "WANTS", true),
            new BudgetJson.CategoryTemplate("Travel",            "WANTS", true),
            new BudgetJson.CategoryTemplate("Other",             "WANTS", true)
    );

    // Cut order for the "aggressive" strategy — earlier entries absorb the
    // hardest cuts first; the last entry absorbs whatever budget remains.
    // Only WANTS categories belong here.
    private static final List<Category> DISCRETIONARY_CUT_ORDER = List.of(
            Category.ENTERTAINMENT, Category.TRAVEL, Category.SHOPPING, Category.OTHER
    );

    private enum Strategy { BALANCED, AGGRESSIVE, COMFORTABLE }

    // ── Strategy split percentages [needsPct, wantsPct, savingsPct] ─────────
    // These are the source of truth for the Needs/Wants/Savings bar.
    // All three must sum to 1.0. Savings drives the algorithm — we allocate
    // savings first, then split the rest between Needs and Wants.
    private static final double[] BALANCED_SPLIT    = {0.50, 0.30, 0.20};
    private static final double[] AGGRESSIVE_SPLIT  = {0.50, 0.15, 0.35};
    private static final double[] COMFORTABLE_SPLIT = {0.55, 0.35, 0.10};

    // Placeholder — not tuned against real user data yet.
    private static final double EMERGENCY_FUND_TARGET_PCT_OF_SAVINGS = 0.25;

    private static final BigDecimal CHANGE_THRESHOLD = BigDecimal.valueOf(50); // ₹50

    private final BudgetRepository budgetRepository;
    private final OverallProfileRepository overallProfileRepository;
    private final StatementProfileRepository statementProfileRepository;
    private final UserAssessmentRepository assessmentRepository;
    private final TransactionRepository transactionRepository;
    private final ManualExpenseRepository manualExpenseRepository;
    private final DeclaredVsActualAnalyzer declaredVsActualAnalyzer;
    private final RecurringExpenseDetector recurringExpenseDetector;
    private final ObjectMapper objectMapper;

    public BudgetService(BudgetRepository budgetRepository,
                         OverallProfileRepository overallProfileRepository,
                         StatementProfileRepository statementProfileRepository,
                         UserAssessmentRepository assessmentRepository,
                         TransactionRepository transactionRepository,
                         ManualExpenseRepository manualExpenseRepository,
                         DeclaredVsActualAnalyzer declaredVsActualAnalyzer,
                         RecurringExpenseDetector recurringExpenseDetector,
                         ObjectMapper objectMapper) {
        this.budgetRepository = budgetRepository;
        this.overallProfileRepository = overallProfileRepository;
        this.statementProfileRepository = statementProfileRepository;
        this.assessmentRepository = assessmentRepository;
        this.transactionRepository = transactionRepository;
        this.manualExpenseRepository = manualExpenseRepository;
        this.declaredVsActualAnalyzer = declaredVsActualAnalyzer;
        this.recurringExpenseDetector = recurringExpenseDetector;
        this.objectMapper = objectMapper;
    }

    // ═════════════════════════════════════════════════════════════════════
    // PUBLIC API — core budget
    // ═════════════════════════════════════════════════════════════════════

    public BudgetJson getOrGenerateBudget(Long userId) {
        Optional<Budget> existing = budgetRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return toJson(existing.get());
        }
        return generateBaseline(userId, true);
    }

    public BudgetJson generateBaseline(Long userId, boolean persist) {
        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);
        Map<Category, BigDecimal> avgSpending = computeAverageSpendingByCategory(userId);
        BigDecimal avgIncome = getAvgIncome(userId, assessment);

        BudgetJson json = buildBudgetJson(avgSpending, avgIncome, BALANCED_SPLIT, "AUTO", assessment);

        if (persist) {
            saveBudget(userId, json, Budget.Source.AUTO);
        }
        return json;
    }

    public BudgetJson applyAiRefinedBudget(Long userId, BudgetJson refined) {
        validateBudget(refined);
        saveBudget(userId, refined, Budget.Source.AI_REFINED);
        return refined;
    }

    public BudgetJson adjustCategory(Long userId, String categoryDisplayName, BigDecimal newAmount, String reason) {
        if (newAmount == null || newAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be zero or positive.");
        }

        Budget budget = budgetRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No budget exists for user " + userId));

        Map<String, BigDecimal> categoryBudgets = parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class);
        Map<String, String> reasoning = parseMap(budget.getReasoningJson(), String.class);

        String key = resolveToDisplayKey(categoryDisplayName);
        BigDecimal oldAmount = categoryBudgets.getOrDefault(key, BigDecimal.ZERO);
        categoryBudgets.put(key, newAmount.setScale(0, RoundingMode.HALF_UP));

        // totalBudget tracks discretionary only — exclude committed categories
        Map<String, String> storedBuckets = parseMap(budget.getCategoryBucketsJson(), String.class);
        BigDecimal newTotal = categoryBudgets.entrySet().stream()
                .filter(e -> !"COMMITTED".equals(storedBuckets.get(e.getKey())))
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);
        BigDecimal avgIncome = getAvgIncome(userId, assessment);

        if (avgIncome.compareTo(BigDecimal.ZERO) > 0 && newTotal.compareTo(avgIncome) > 0) {
            throw new IllegalArgumentException(String.format(
                    "Adjusting %s to ₹%s would push discretionary budget (₹%s) above your income (₹%s). " +
                            "Consider reducing another category first.",
                    key, fmt(newAmount), fmt(newTotal), fmt(avgIncome)));
        }

        if (reason != null && !reason.isBlank()) {
            reasoning.put(key, reason);
        }

        budget.setPreviousCategoryBudgetsJson(budget.getCategoryBudgetsJson());
        budget.setPreviousTotalBudget(budget.getTotalBudget());
        budget.setPreviousSavingsTarget(budget.getSavingsTarget());

        String diffSummary = String.format("%s changed from ₹%s to ₹%s%s",
                key, fmt(oldAmount), fmt(newAmount),
                reason != null && !reason.isBlank() ? " — " + reason : "");

        budget.setCategoryBudgetsJson(serialize(categoryBudgets));
        budget.setReasoningJson(serialize(reasoning));
        budget.setTotalBudget(newTotal.setScale(0, RoundingMode.HALF_UP));
        budget.setLastDiffSummary(diffSummary);
        budget.setSource(Budget.Source.USER_ADJUSTED);
        budget.setUpdatedAt(LocalDateTime.now());
        budgetRepository.save(budget);

        log.info("Budget category '{}' adjusted for user {}: ₹{} → ₹{}", key, userId, fmt(oldAmount), fmt(newAmount));

        return toJson(budget);
    }

    public BudgetJson updateCategoryBucket(Long userId, String name, String bucket) {
        String bucketUpper = bucket != null ? bucket.toUpperCase() : "WANTS";
        if (!"NEEDS".equals(bucketUpper) && !"WANTS".equals(bucketUpper)) {
            throw new IllegalArgumentException("Bucket must be NEEDS or WANTS.");
        }

        Budget budget = budgetRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No budget exists for user " + userId));

        Map<String, String> categoryBuckets = parseMap(budget.getCategoryBucketsJson(), String.class);
        String key = name.trim();
        categoryBuckets.put(key, bucketUpper);
        budget.setCategoryBucketsJson(serialize(categoryBuckets));
        budget.setSource(Budget.Source.USER_ADJUSTED);
        budget.setUpdatedAt(LocalDateTime.now());
        budgetRepository.save(budget);

        log.info("Bucket for '{}' changed to {} for user {}", key, bucketUpper, userId);
        return toJson(budget);
    }

    public BudgetJson addCategory(Long userId, String name, BigDecimal amount, String bucket) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Category name is required.");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("Amount must be zero or positive.");
        String bucketUpper = bucket != null ? bucket.toUpperCase() : "WANTS";
        if (!"NEEDS".equals(bucketUpper) && !"WANTS".equals(bucketUpper)) {
            throw new IllegalArgumentException("Bucket must be NEEDS or WANTS.");
        }

        Budget budget = budgetRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No budget exists for user " + userId));

        Map<String, BigDecimal> categoryBudgets = parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class);
        Map<String, String> categoryBuckets = parseMap(budget.getCategoryBucketsJson(), String.class);

        String key = name.trim();
        categoryBudgets.put(key, amount.setScale(0, RoundingMode.HALF_UP));
        categoryBuckets.put(key, bucketUpper);

        budget.setCategoryBudgetsJson(serialize(categoryBudgets));
        budget.setCategoryBucketsJson(serialize(categoryBuckets));
        budget.setTotalBudget(discretionaryTotal(categoryBudgets, categoryBuckets));
        budget.setSource(Budget.Source.USER_ADJUSTED);
        budget.setUpdatedAt(LocalDateTime.now());
        budgetRepository.save(budget);

        log.info("Category '{}' ({}) added for user {} with budget ₹{}", key, bucketUpper, userId, fmt(amount));
        return toJson(budget);
    }

    public BudgetJson removeCategory(Long userId, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Category name is required.");

        Budget budget = budgetRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No budget exists for user " + userId));

        Map<String, BigDecimal> categoryBudgets = parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class);
        Map<String, String> categoryBuckets = parseMap(budget.getCategoryBucketsJson(), String.class);

        String key = name.trim();
        if (!categoryBudgets.containsKey(key)) {
            throw new IllegalArgumentException("Category '" + key + "' not found in budget.");
        }

        categoryBudgets.remove(key);
        categoryBuckets.remove(key);

        budget.setCategoryBudgetsJson(serialize(categoryBudgets));
        budget.setCategoryBucketsJson(serialize(categoryBuckets));
        budget.setTotalBudget(discretionaryTotal(categoryBudgets, categoryBuckets));
        budget.setSource(Budget.Source.USER_ADJUSTED);
        budget.setUpdatedAt(LocalDateTime.now());
        budgetRepository.save(budget);

        log.info("Category '{}' removed for user {}", key, userId);
        return toJson(budget);
    }

    public List<BudgetJson.CategoryTemplate> getCategoryTemplates(Long userId) {
        Budget budget = budgetRepository.findByUserId(userId).orElse(null);
        Map<String, String> existingBuckets = budget != null
                ? parseMap(budget.getCategoryBucketsJson(), String.class)
                : new LinkedHashMap<>();

        // Start with all predefined categories, annotating whether the user has them already
        List<BudgetJson.CategoryTemplate> result = new ArrayList<>(PREDEFINED_CATEGORIES);

        // Also include any custom categories the user has added that aren't in the predefined list
        if (budget != null) {
            Set<String> predefinedNames = PREDEFINED_CATEGORIES.stream()
                    .map(t -> t.name)
                    .collect(Collectors.toSet());
            parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class).forEach((name, amt) -> {
                if (!predefinedNames.contains(name)) {
                    String bucketStr = existingBuckets.getOrDefault(name, "WANTS");
                    result.add(new BudgetJson.CategoryTemplate(name, bucketStr, false));
                }
            });
        }

        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Multi-strategy budget options — deterministic math, AI never decides
    // these numbers. AI's place is narrating them / helping a user choose
    // between them conversationally (see AiCopilotService), not computing
    // them.
    // ═════════════════════════════════════════════════════════════════════

    public BudgetJson.BudgetOptionsResponse generateBudgetOptions(Long userId) {
        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);
        Map<Category, BigDecimal> avgSpending = computeAverageSpendingByCategory(userId);
        BigDecimal avgIncome = getAvgIncome(userId, assessment);

        if (avgIncome.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "We don't have enough income data yet to build a budget — please complete onboarding or upload a statement first.");
        }

        BudgetJson.BudgetOption balanced = buildOption(
                "balanced", "Balanced", "50% needs · 30% wants · 20% savings",
                avgSpending, avgIncome, BALANCED_SPLIT, assessment);

        BudgetJson.BudgetOption aggressive = buildOption(
                "aggressive", "Save more", "50% needs · 15% wants · 35% savings — cuts luxuries hard",
                avgSpending, avgIncome, AGGRESSIVE_SPLIT, assessment);

        BudgetJson.BudgetOption comfortable = buildOption(
                "comfortable", "Breathing room", "55% needs · 35% wants · 10% savings",
                avgSpending, avgIncome, COMFORTABLE_SPLIT, assessment);

        BudgetJson.BudgetOptionsResponse response = new BudgetJson.BudgetOptionsResponse();
        response.options = List.of(balanced, aggressive, comfortable);
        response.recommendedStrategyId = recommendStrategy(assessment, avgIncome);
        return response;
    }

    public BudgetJson commitBudgetOption(Long userId, String strategyId) {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId is required.");
        }

        BudgetJson.BudgetOptionsResponse options = generateBudgetOptions(userId);

        BudgetJson.BudgetOption chosen = options.options.stream()
                .filter(o -> o.strategyId.equalsIgnoreCase(strategyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown strategyId '" + strategyId + "'. Valid options: balanced, aggressive, comfortable."));

        BudgetJson json = new BudgetJson();
        json.totalBudget = chosen.totalBudget;
        json.savingsTarget = chosen.savingsTarget;
        json.categoryBudgets = chosen.categoryBudgets;   // this is Map<String, BigDecimal> — fine, matches BudgetJson's field type
        json.reasoning = chosen.reasoning;
        json.bucketBreakdown = chosen.bucketBreakdown;
        json.source = "USER_ADJUSTED";
        json.generatedAt = LocalDate.now().toString();

        validateBudget(json);
        saveBudget(userId, json, Budget.Source.USER_ADJUSTED);

        log.info("User {} committed budget strategy '{}' — total=₹{} savings=₹{}",
                userId, strategyId, fmt(json.totalBudget), fmt(json.savingsTarget));

        return json;
    }

    private BudgetJson.BudgetOption buildOption(String strategyId, String label, String tagline,
                                                Map<Category, BigDecimal> avgSpending,
                                                BigDecimal income, double[] split,
                                                UserAssessment assessment) {
        BudgetJson json = buildBudgetJson(avgSpending, income, split, strategyId.toUpperCase(), assessment);

        BudgetJson.BudgetOption option = new BudgetJson.BudgetOption();
        option.strategyId    = strategyId;
        option.label         = label;
        option.tagline       = tagline;
        option.totalBudget   = json.totalBudget;
        option.savingsTarget = json.savingsTarget;
        option.categoryBudgets = json.categoryBudgets;
        option.reasoning     = json.reasoning;
        option.bucketBreakdown = json.bucketBreakdown;
        return option;
    }

    private String recommendStrategy(UserAssessment assessment, BigDecimal avgIncome) {
        if (assessment != null && assessment.getGoalDeadline() != null) {
            long monthsLeft = ChronoUnit.MONTHS.between(LocalDate.now(), assessment.getGoalDeadline());
            if (monthsLeft > 0 && monthsLeft <= 6) return "aggressive";
        }
        return "balanced";
    }

    // ═════════════════════════════════════════════════════════════════════
    // Daily pacing
    // ═════════════════════════════════════════════════════════════════════

    private int computeCurrentStreak(Long userId, YearMonth currentMonth, BigDecimal monthlyBudget) {
        int today = LocalDate.now().getDayOfMonth();
        int daysInMonth = currentMonth.lengthOfMonth();
        if (monthlyBudget.compareTo(BigDecimal.ZERO) <= 0) return 0;

        List<Transaction> monthTx = transactionRepository
                .findByUserIdAndStatementYearAndStatementMonth(userId, currentMonth.getYear(), currentMonth.getMonthValue());

        Map<Integer, BigDecimal> spendByDay = new HashMap<>();
        for (Transaction t : monthTx) {
            if (t.getType() != TransactionType.DEBIT) continue;
            Category cat = t.getEffectiveCategory();
            if (cat != null && bucketOf(cat) == Bucket.EXCLUDED) continue;
            BigDecimal amt = t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO;
            int day = t.getDate() != null ? t.getDate().getDayOfMonth() : 1;
            spendByDay.merge(day, amt, BigDecimal::add);
        }

        LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = currentMonth.atEndOfMonth().atTime(23, 59, 59);
        for (ManualExpense m : manualExpenseRepository.findByUserIdAndSpentAtBetween(userId, monthStart, monthEnd)) {
            int day = m.getSpentAt().getDayOfMonth();
            spendByDay.merge(day, m.getAmount(), BigDecimal::add);
        }

        // No data at all → no streak. Without this guard, days 1..today would all
        // appear "on track" (cumulative=0 ≤ expected) even before the user first logged anything.
        if (spendByDay.isEmpty()) return 0;

        // The streak can only start from the first day the user recorded any spend.
        // Days before that have no data and must not be counted.
        int firstExpenseDay = spendByDay.keySet().stream().mapToInt(Integer::intValue).min().getAsInt();

        BigDecimal cumulativeSpend = BigDecimal.ZERO;
        for (int d = firstExpenseDay; d <= today; d++) {
            cumulativeSpend = cumulativeSpend.add(spendByDay.getOrDefault(d, BigDecimal.ZERO));
        }

        int streak = 0;
        for (int d = today; d >= firstExpenseDay; d--) {
            BigDecimal expectedCumulative = monthlyBudget
                    .multiply(BigDecimal.valueOf(d))
                    .divide(BigDecimal.valueOf(daysInMonth), 0, RoundingMode.HALF_UP);

            if (cumulativeSpend.compareTo(expectedCumulative) <= 0) {
                streak++;
            } else {
                break;
            }

            cumulativeSpend = cumulativeSpend.subtract(spendByDay.getOrDefault(d, BigDecimal.ZERO));
        }

        return streak;
    }

    private BigDecimal computeSavedVsLastMonth(Long userId, YearMonth currentMonth) {
        int today = LocalDate.now().getDayOfMonth();
        YearMonth lastMonth = currentMonth.minusMonths(1);

        List<Transaction> lastMonthTx = transactionRepository
                .findByUserIdAndStatementYearAndStatementMonth(userId, lastMonth.getYear(), lastMonth.getMonthValue());

        BigDecimal lastMonthSpendToDate = lastMonthTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> t.getDate() != null && t.getDate().getDayOfMonth() <= today)
                .filter(t -> { Category cat = t.getEffectiveCategory(); return cat != null && bucketOf(cat) != Bucket.EXCLUDED; })
                .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal thisMonthSpendToDate = sumDebitsForMonth(userId, currentMonth)
                .add(sumManualExpensesForMonth(userId, currentMonth));

        if (lastMonthSpendToDate.compareTo(BigDecimal.ZERO) == 0) return null;

        return lastMonthSpendToDate.subtract(thisMonthSpendToDate).setScale(0, RoundingMode.HALF_UP);
    }

    public BudgetJson.DailyPacing getDailyPacing(Long userId) {
        Budget budget = budgetRepository.findByUserId(userId).orElse(null);
        if (budget == null) {
            generateBaseline(userId, true);
            budget = budgetRepository.findByUserId(userId).orElseThrow();
        }

        YearMonth currentMonth = YearMonth.now();
        Optional<StatementProfile> currentProfile = statementProfileRepository
                .findByUserIdAndProfileYearAndProfileMonth(userId, currentMonth.getYear(), currentMonth.getMonthValue());

        // Use only non-excluded categories so this number matches the sum of all envelope "spent" amounts.
        // StatementProfile.totalSpend includes transfers/investments which have no budget envelope.
        // Spending is tracked manually only — matches what envelope progress shows.
        BigDecimal spentSoFar = sumManualExpensesForMonth(userId, currentMonth);

        int today = LocalDate.now().getDayOfMonth();
        int daysInMonth = currentMonth.lengthOfMonth();
        int daysRemaining = Math.max(1, daysInMonth - today + 1);

        BigDecimal monthlyBudget = budget.getTotalBudget();
        BigDecimal remaining = monthlyBudget.subtract(spentSoFar);
        BigDecimal dailyAllowance = remaining.divide(BigDecimal.valueOf(daysRemaining), 0, RoundingMode.HALF_UP);

        BigDecimal expectedSoFar = monthlyBudget
                .multiply(BigDecimal.valueOf(today))
                .divide(BigDecimal.valueOf(daysInMonth), 0, RoundingMode.HALF_UP);

        String status;
        String insight;
        if (dailyAllowance.compareTo(BigDecimal.ZERO) <= 0) {
            status = "OVER_BUDGET";
            insight = String.format("You've used up this month's budget with %d day(s) left — try to minimize non-essential spending.", daysRemaining);
        } else if (spentSoFar.compareTo(expectedSoFar) > 0) {
            BigDecimal over = spentSoFar.subtract(expectedSoFar);
            status = "BEHIND";
            insight = String.format("You've spent ₹%s more than ideal pace so far. Stick to ₹%s/day for the rest of the month to stay on track.", fmt(over), fmt(dailyAllowance));
        } else {
            BigDecimal under = expectedSoFar.subtract(spentSoFar);
            status = "ON_TRACK";
            insight = String.format("You're ₹%s under pace — nice. You can spend up to ₹%s/day for the rest of the month.", fmt(under), fmt(dailyAllowance));
        }

        int currentStreak = computeCurrentStreak(userId, currentMonth, monthlyBudget);
        boolean streakActive = "ON_TRACK".equals(status) || "BEHIND".equals(status);
        BigDecimal savedVsLastMonth = computeSavedVsLastMonth(userId, currentMonth);

        // Weekly figures — based on the current Mon-to-today window.
        // weeklyBudget is a proportional slice: monthlyBudget × 7 / daysInMonth.
        // spentThisWeek is the actual budgetable spend since Monday of this week.
        LocalDate todayDate = LocalDate.now();
        LocalDate weekStart = todayDate.with(java.time.DayOfWeek.MONDAY);
        BigDecimal spentThisWeek = computeSpentInRange(userId, currentMonth, weekStart, todayDate);
        BigDecimal weeklyBudget = monthlyBudget
                .multiply(BigDecimal.valueOf(7))
                .divide(BigDecimal.valueOf(daysInMonth), 0, RoundingMode.HALF_UP);

        // Gross income and committed totals for display in the budget hero
        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);
        BigDecimal grossIncome = getAvgIncome(userId, assessment);
        Map<String, BigDecimal> committed = loadCommittedExpenses(assessment);
        BigDecimal committedTotal = committed.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        BudgetJson.DailyPacing pacing = new BudgetJson.DailyPacing();
        pacing.monthlyBudget  = monthlyBudget;
        pacing.grossIncome    = grossIncome.setScale(0, RoundingMode.HALF_UP);
        pacing.committedTotal = committedTotal.setScale(0, RoundingMode.HALF_UP);
        pacing.spentSoFar = spentSoFar.setScale(0, RoundingMode.HALF_UP);
        pacing.remaining = remaining;
        pacing.daysRemaining = daysRemaining;
        pacing.dailyAllowance = dailyAllowance.max(BigDecimal.ZERO);
        pacing.status = status;
        pacing.insight = insight;
        pacing.currentStreak = currentStreak;
        pacing.streakActive = streakActive;
        pacing.savedVsLastMonth = savedVsLastMonth;
        pacing.spentThisWeek = spentThisWeek;
        pacing.weeklyBudget = weeklyBudget;
        return pacing;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Per-category live progress
    // ═════════════════════════════════════════════════════════════════════

    public BudgetJson.HomeHighlight getHomeHighlight(Long userId) {
        BudgetJson.HomeHighlight h = new BudgetJson.HomeHighlight();

        Budget budget = budgetRepository.findByUserId(userId).orElse(null);
        if (budget == null) {
            h.type = "NONE";
            h.severity = "INFO";
            h.message = "Set up your budget to start tracking your daily spending.";
            return h;
        }

        BudgetJson.CategoryProgress progress = getCategoryProgress(userId);
        for (Map.Entry<String, BudgetJson.CategoryProgressEntry> e : progress.categories.entrySet()) {
            var entry = e.getValue();
            if ("OVER_BUDGET".equals(entry.status)) {
                h.type = "OVERSPEND";
                h.severity = "WARNING";
                h.message = String.format("%s is over budget by ₹%s this month.",
                        e.getKey(), fmt(entry.spent.subtract(entry.budgeted)));
                return h;
            }
        }
        for (Map.Entry<String, BudgetJson.CategoryProgressEntry> e : progress.categories.entrySet()) {
            var entry = e.getValue();
            if ("NEAR_LIMIT".equals(entry.status)) {
                h.type = "NEAR_LIMIT";
                h.severity = "WARNING";
                h.message = String.format("%s is at %.0f%% of its budget — %d days left this month.",
                        e.getKey(), entry.pct,
                        YearMonth.now().lengthOfMonth() - LocalDate.now().getDayOfMonth() + 1);
                return h;
            }
        }

        BudgetJson.DailyPacing pacing = getDailyPacing(userId);
        if (pacing.currentStreak >= 2) {
            h.type = "STREAK";
            h.severity = "POSITIVE";
            h.message = String.format("%d days in a row on pace with your budget. Keep it going!", pacing.currentStreak);
            return h;
        }

        if (pacing.savedVsLastMonth != null && pacing.savedVsLastMonth.compareTo(BigDecimal.valueOf(100)) > 0) {
            h.type = "SAVED_MORE";
            h.severity = "POSITIVE";
            h.message = String.format("You've spent ₹%s less than this time last month.", fmt(pacing.savedVsLastMonth));
            return h;
        }

        BudgetJson.BudgetDiff diff = getLastDiff(userId);
        if (diff != null) {
            h.type = "BUDGET_CHANGED";
            h.severity = "INFO";
            h.message = diff.summary;
            return h;
        }

        h.type = "NONE";
        h.severity = "INFO";
        h.message = pacing.insight;
        return h;
    }

    public BudgetJson.CategoryProgress getCategoryProgress(Long userId) {
        Budget budget = budgetRepository.findByUserId(userId).orElse(null);
        if (budget == null) {
            generateBaseline(userId, true);
            budget = budgetRepository.findByUserId(userId).orElseThrow();
        }

        Map<String, BigDecimal> categoryBudgets = parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class);

        YearMonth currentMonth = YearMonth.now();
        LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd   = currentMonth.atEndOfMonth().atTime(23, 59, 59);

        // Envelope progress is driven solely by manual expense logs.
        // Bank statement transactions are used for insights/analytics only.
        Map<String, BigDecimal> spentByCategory = new HashMap<>();
        List<ManualExpense> manualExpenses = manualExpenseRepository
                .findByUserIdAndSpentAtBetween(userId, monthStart, monthEnd);
        for (ManualExpense m : manualExpenses) {
            String key = resolveToDisplayKey(m.getCategory());
            spentByCategory.merge(key, m.getAmount(), BigDecimal::add);
        }

        BudgetJson.CategoryProgress progress = new BudgetJson.CategoryProgress();
        progress.categories = new LinkedHashMap<>();
        progress.month = currentMonth.toString();

        for (Map.Entry<String, BigDecimal> e : categoryBudgets.entrySet()) {
            String key = e.getKey();
            BigDecimal budgeted = e.getValue();
            BigDecimal spent = spentByCategory.getOrDefault(key, BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP);
            BigDecimal remaining = budgeted.subtract(spent);

            double pct = budgeted.compareTo(BigDecimal.ZERO) > 0
                    ? spent.divide(budgeted, 4, RoundingMode.HALF_UP).doubleValue() * 100
                    : 0;

            String status;
            if (pct >= 100) status = "OVER_BUDGET";
            else if (pct >= 85) status = "NEAR_LIMIT";
            else status = "ON_TRACK";

            BudgetJson.CategoryProgressEntry entry = new BudgetJson.CategoryProgressEntry();
            entry.budgeted = budgeted;
            entry.spent = spent;
            entry.remaining = remaining;
            entry.pct = Math.round(pct * 10) / 10.0;
            entry.status = status;

            progress.categories.put(key, entry);
        }

        return progress;
    }

    private BigDecimal sumDebitsForMonth(Long userId, YearMonth month) {
        List<Transaction> monthTx = transactionRepository
                .findByUserIdAndStatementYearAndStatementMonth(userId, month.getYear(), month.getMonthValue());
        return monthTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Sums only DEBIT transactions whose effective category is NOT in the EXCLUDED bucket
     * (i.e. not transfers, investments, refunds, cash withdrawals).
     *
     * This is the correct basis for pacing and streak calculations because budget envelopes
     * are also built only from non-excluded categories. Using raw debit totals inflates
     * "spent so far" with transfers/investments, creating a mismatch between the hero
     * card number and the sum of all envelope "spent" amounts.
     */
    private BigDecimal sumBudgetableDebitsForMonth(Long userId, YearMonth month) {
        List<Transaction> monthTx = transactionRepository
                .findByUserIdAndStatementYearAndStatementMonth(userId, month.getYear(), month.getMonthValue());
        return monthTx.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> {
                    Category cat = t.getEffectiveCategory();
                    return cat != null && bucketOf(cat) != Bucket.EXCLUDED;
                })
                .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal sumManualExpensesForMonth(Long userId, YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(23, 59, 59);
        return manualExpenseRepository.findByUserIdAndSpentAtBetween(userId, start, end).stream()
                .map(ManualExpense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Sums budgetable spend (non-EXCLUDED debits + manual expenses) for a specific
     * date range within a month. Used to compute this-week spend for the weekly pulse.
     * The range is inclusive on both ends.
     */
    private BigDecimal computeSpentInRange(Long userId, YearMonth month, LocalDate from, LocalDate to) {
        LocalDateTime dtFrom = from.atStartOfDay();
        LocalDateTime dtTo   = to.atTime(23, 59, 59);
        return manualExpenseRepository
                .findByUserIdAndSpentAtBetween(userId, dtFrom, dtTo).stream()
                .map(ManualExpense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Manual expenses
    // ═════════════════════════════════════════════════════════════════════

    public void addManualExpense(Long userId, String category, BigDecimal amount, String note) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Expense amount must be positive.");
        }

        User user = new User();
        user.setId(userId);

        ManualExpense expense = new ManualExpense();
        expense.setUser(user);
        expense.setCategory(resolveToDisplayKey(category));
        expense.setAmount(amount.setScale(0, RoundingMode.HALF_UP));
        expense.setNote(note);
        expense.setSpentAt(LocalDateTime.now());
        manualExpenseRepository.save(expense);

        log.info("Manual expense added for user {}: ₹{} in '{}'", userId, fmt(amount), resolveToDisplayKey(category));
    }

    public List<ManualExpenseDto> getManualExpenses(Long userId) {
        return manualExpenseRepository.findByUserIdOrderBySpentAtDesc(userId).stream()
                .map(e -> {
                    ManualExpenseDto dto = new ManualExpenseDto();
                    dto.id = e.getId();
                    dto.category = e.getCategory();
                    dto.amount = e.getAmount();
                    dto.note = e.getNote();
                    dto.spentAt = e.getSpentAt().toString();
                    return dto;
                })
                .collect(Collectors.toList());
    }

    public void deleteManualExpense(Long userId, Long expenseId) {
        ManualExpense expense = manualExpenseRepository.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Expense not found"));
        if (!expense.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to delete this expense.");
        }
        manualExpenseRepository.deleteById(expenseId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Budget diff ("what changed on refresh")
    // ═════════════════════════════════════════════════════════════════════

    public BudgetJson.BudgetDiff getLastDiff(Long userId) {
        Budget budget = budgetRepository.findByUserId(userId).orElse(null);
        if (budget == null || budget.getPreviousCategoryBudgetsJson() == null) {
            return null;
        }

        Map<String, BigDecimal> previous = parseMap(budget.getPreviousCategoryBudgetsJson(), BigDecimal.class);
        Map<String, BigDecimal> current = parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class);

        List<BudgetJson.BudgetChange> changes = new ArrayList<>();

        Set<String> allCategories = new LinkedHashSet<>();
        allCategories.addAll(previous.keySet());
        allCategories.addAll(current.keySet());

        for (String cat : allCategories) {
            BigDecimal oldAmt = previous.getOrDefault(cat, BigDecimal.ZERO);
            BigDecimal newAmt = current.getOrDefault(cat, BigDecimal.ZERO);
            if (oldAmt.subtract(newAmt).abs().compareTo(CHANGE_THRESHOLD) >= 0) {
                BudgetJson.BudgetChange change = new BudgetJson.BudgetChange();
                change.category = cat;
                change.oldAmount = oldAmt;
                change.newAmount = newAmt;
                changes.add(change);
            }
        }

        if (budget.getPreviousSavingsTarget() != null
                && budget.getSavingsTarget() != null
                && budget.getPreviousSavingsTarget().subtract(budget.getSavingsTarget()).abs().compareTo(CHANGE_THRESHOLD) >= 0) {
            BudgetJson.BudgetChange change = new BudgetJson.BudgetChange();
            change.category = "_savings";
            change.oldAmount = budget.getPreviousSavingsTarget();
            change.newAmount = budget.getSavingsTarget();
            changes.add(change);
        }

        if (changes.isEmpty()) return null;

        BudgetJson.BudgetDiff diff = new BudgetJson.BudgetDiff();
        diff.changes = changes;
        diff.summary = budget.getLastDiffSummary() != null
                ? budget.getLastDiffSummary()
                : buildDiffSummary(changes);
        return diff;
    }

    private String buildDiffSummary(List<BudgetJson.BudgetChange> changes) {
        if (changes.size() == 1) {
            BudgetJson.BudgetChange c = changes.get(0);
            String name = "_savings".equals(c.category) ? "Your savings target" : c.category + " budget";
            String direction = c.newAmount.compareTo(c.oldAmount) > 0 ? "increased" : "decreased";
            return String.format("%s %s from ₹%s to ₹%s based on your latest data.",
                    name, direction, fmt(c.oldAmount), fmt(c.newAmount));
        }
        return String.format("%d categories updated based on your latest data — review the changes below.", changes.size());
    }

    public void clearDiff(Long userId) {
        Budget budget = budgetRepository.findByUserId(userId).orElse(null);
        if (budget == null) return;
        budget.setPreviousCategoryBudgetsJson(null);
        budget.setPreviousTotalBudget(null);
        budget.setPreviousSavingsTarget(null);
        budget.setLastDiffSummary(null);
        budgetRepository.save(budget);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Goal progress
    // ═════════════════════════════════════════════════════════════════════

    public BudgetJson.GoalProgress getGoalProgress(Long userId) {
        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);
        BudgetJson.GoalProgress progress = new BudgetJson.GoalProgress();

        if (assessment == null || assessment.getFinancialGoal() == null
                || assessment.getGoalAmount() == null || assessment.getGoalDeadline() == null) {
            progress.hasGoal = false;
            return progress;
        }

        progress.hasGoal = true;
        progress.goalName = assessment.getFinancialGoal();
        progress.targetAmount = assessment.getGoalAmount();
        progress.deadline = assessment.getGoalDeadline().toString();

        List<StatementProfile> profiles = statementProfileRepository.findByUserIdOrderByProfileYearDescProfileMonthDesc(userId);
        BigDecimal savedSoFar = profiles.stream()
                .map(p -> p.getActualNetCashFlow() != null ? p.getActualNetCashFlow() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        savedSoFar = savedSoFar.max(BigDecimal.ZERO);

        progress.savedSoFar = savedSoFar.setScale(0, RoundingMode.HALF_UP);
        progress.remaining = progress.targetAmount.subtract(savedSoFar).max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP);
        progress.pct = progress.targetAmount.compareTo(BigDecimal.ZERO) > 0
                ? Math.min(100, Math.round(savedSoFar.divide(progress.targetAmount, 4, RoundingMode.HALF_UP).doubleValue() * 1000) / 10.0)
                : 0;

        long monthsLeft = ChronoUnit.MONTHS.between(LocalDate.now(), assessment.getGoalDeadline());
        progress.monthsRemaining = (int) Math.max(0, monthsLeft);

        if (monthsLeft > 0) {
            progress.requiredMonthlyPace = progress.remaining
                    .divide(BigDecimal.valueOf(monthsLeft), 0, RoundingMode.HALF_UP);
        } else {
            progress.requiredMonthlyPace = progress.remaining;
        }

        Budget budget = budgetRepository.findByUserId(userId).orElse(null);
        progress.currentMonthlyPace = budget != null && budget.getSavingsTarget() != null
                ? budget.getSavingsTarget() : BigDecimal.ZERO;

        if (progress.currentMonthlyPace.compareTo(progress.requiredMonthlyPace) >= 0) {
            progress.paceStatus = "ON_TRACK";
            progress.insight = String.format(
                    "At ₹%s/month, you're on track to reach your goal by %s.",
                    fmt(progress.currentMonthlyPace), formatMonthYear(assessment.getGoalDeadline()));
        } else if (progress.currentMonthlyPace.compareTo(BigDecimal.ZERO) > 0) {
            progress.paceStatus = "BEHIND";
            progress.insight = String.format(
                    "At your current pace of ₹%s/month, you'd need ₹%s/month to hit this goal by %s.",
                    fmt(progress.currentMonthlyPace), fmt(progress.requiredMonthlyPace), formatMonthYear(assessment.getGoalDeadline()));
        } else {
            progress.paceStatus = "BEHIND";
            progress.insight = String.format(
                    "You'd need to save ₹%s/month to reach this goal by %s.",
                    fmt(progress.requiredMonthlyPace), formatMonthYear(assessment.getGoalDeadline()));
        }

        return progress;
    }

    private String formatMonthYear(LocalDate date) {
        String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
        return months[date.getMonthValue() - 1] + " " + date.getYear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AI-driven plan updates: income & goal
    //
    // Note: even here, "AI-driven" means the AI copilot decided WHEN to
    // call this (e.g. user said "my income changed"), not that AI computed
    // the new budget numbers. generateBaseline() below is still the same
    // deterministic algorithm.
    // ═════════════════════════════════════════════════════════════════════

    public BudgetJson.IncomeUpdateResult updateIncomeAndRebudget(Long userId, BigDecimal newIncome) {
        if (newIncome == null || newIncome.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Income must be a positive amount.");
        }

        UserAssessment assessment = assessmentRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No financial assessment found — please complete onboarding first."));

        BigDecimal oldIncome = getAvgIncome(userId, assessment);

        assessment.setDeclaredCurrentIncome(newIncome.setScale(0, RoundingMode.HALF_UP));
        assessment.setDeclaredIncomeUpdatedAt(LocalDateTime.now());
        assessmentRepository.save(assessment);

        log.info("User {} updated declared income: ₹{} → ₹{}", userId, fmt(oldIncome), fmt(newIncome));

        BudgetJson newBudget = generateBaseline(userId, true);

        BudgetJson.IncomeUpdateResult result = new BudgetJson.IncomeUpdateResult();
        result.oldIncome = oldIncome.setScale(0, RoundingMode.HALF_UP);
        result.newIncome = newIncome.setScale(0, RoundingMode.HALF_UP);
        result.budget = newBudget;
        return result;
    }

    public BudgetJson.GoalUpdateResult updateGoalAndRebudget(Long userId, String goalName,
                                                             BigDecimal targetAmount, LocalDate deadline) {
        UserAssessment assessment = assessmentRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No financial assessment found — please complete onboarding first."));

        BigDecimal oldTarget = assessment.getGoalAmount();
        LocalDate oldDeadline = assessment.getGoalDeadline();

        if (goalName != null && !goalName.isBlank()) {
            assessment.setFinancialGoal(goalName);
        }
        if (targetAmount != null) {
            if (targetAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Goal amount must be a positive value.");
            }
            assessment.setGoalAmount(targetAmount.setScale(0, RoundingMode.HALF_UP));
        }
        if (deadline != null) {
            if (!deadline.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("Goal deadline must be in the future.");
            }
            assessment.setGoalDeadline(deadline);
        }

        if (assessment.getFinancialGoal() == null || assessment.getGoalAmount() == null
                || assessment.getGoalDeadline() == null) {
            throw new IllegalArgumentException(
                    "A goal needs a name, target amount, and deadline — please provide whichever is missing.");
        }

        assessmentRepository.save(assessment);

        log.info("User {} updated goal '{}': amount ₹{}→₹{}, deadline {}→{}",
                userId, assessment.getFinancialGoal(),
                fmt(oldTarget), fmt(assessment.getGoalAmount()),
                oldDeadline, assessment.getGoalDeadline());

        BudgetJson newBudget = generateBaseline(userId, true);

        BudgetJson.GoalUpdateResult result = new BudgetJson.GoalUpdateResult();
        result.goalName = assessment.getFinancialGoal();
        result.oldTargetAmount = oldTarget != null ? oldTarget.setScale(0, RoundingMode.HALF_UP) : null;
        result.newTargetAmount = assessment.getGoalAmount().setScale(0, RoundingMode.HALF_UP);
        result.oldDeadline = oldDeadline != null ? oldDeadline.toString() : null;
        result.newDeadline = assessment.getGoalDeadline().toString();
        result.budget = newBudget;
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Proactive check-in highlights (for AI copilot)
    // ═════════════════════════════════════════════════════════════════════

    public List<String> getCheckInHighlights(Long userId) {
        List<String> highlights = new ArrayList<>();

        Budget budget = budgetRepository.findByUserId(userId).orElse(null);
        if (budget == null) {
            highlights.add("This user has no budget yet — offer to generate one.");
            return highlights;
        }

        BudgetJson.BudgetDiff diff = getLastDiff(userId);
        if (diff != null) {
            highlights.add("Their budget recently changed: " + diff.summary);
        }

        BudgetJson.CategoryProgress progress = getCategoryProgress(userId);
        for (Map.Entry<String, BudgetJson.CategoryProgressEntry> e : progress.categories.entrySet()) {
            var entry = e.getValue();
            if ("OVER_BUDGET".equals(entry.status)) {
                highlights.add(String.format("%s is over budget by ₹%s this month.",
                        e.getKey(), fmt(entry.spent.subtract(entry.budgeted))));
            } else if ("NEAR_LIMIT".equals(entry.status)) {
                highlights.add(String.format("%s is at %.0f%% of its budget with the month not over yet.",
                        e.getKey(), entry.pct));
            }
        }

        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);
        if (assessment != null && assessment.getDeclaredIncomeUpdatedAt() != null) {
            long hoursAgo = ChronoUnit.HOURS.between(
                    assessment.getDeclaredIncomeUpdatedAt(), LocalDateTime.now());
            if (hoursAgo < 24) {
                highlights.add(String.format("They recently updated their income to ₹%s/month.",
                        fmt(assessment.getDeclaredCurrentIncome())));
            }
        }

        return highlights;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Needs / Wants / Savings bucket breakdown
    // ═════════════════════════════════════════════════════════════════════

    private BudgetJson.BucketBreakdown computeBucketBreakdown(Map<Category, BigDecimal> categoryBudgets,
                                                              BigDecimal savingsTarget,
                                                              UserAssessment assessment) {
        BigDecimal needsTotal = BigDecimal.ZERO;
        BigDecimal wantsTotal = BigDecimal.ZERO;

        for (Map.Entry<Category, BigDecimal> e : categoryBudgets.entrySet()) {
            if (bucketOf(e.getKey()) == Bucket.NEEDS) {
                needsTotal = needsTotal.add(e.getValue());
            } else {
                wantsTotal = wantsTotal.add(e.getValue());
            }
        }

        BigDecimal grandTotal = needsTotal.add(wantsTotal).add(savingsTarget);

        BudgetJson.BucketBreakdown breakdown = new BudgetJson.BucketBreakdown();
        breakdown.needsTotal = needsTotal.setScale(0, RoundingMode.HALF_UP);
        breakdown.wantsTotal = wantsTotal.setScale(0, RoundingMode.HALF_UP);
        breakdown.savingsTotal = savingsTarget.setScale(0, RoundingMode.HALF_UP);
        breakdown.savingsBreakdown = computeSavingsBreakdown(assessment, savingsTarget);

        if (grandTotal.compareTo(BigDecimal.ZERO) > 0) {
            breakdown.needsPct = round1(needsTotal.divide(grandTotal, 4, RoundingMode.HALF_UP).doubleValue() * 100);
            breakdown.wantsPct = round1(wantsTotal.divide(grandTotal, 4, RoundingMode.HALF_UP).doubleValue() * 100);
            breakdown.savingsPct = round1(savingsTarget.divide(grandTotal, 4, RoundingMode.HALF_UP).doubleValue() * 100);
        }

        return breakdown;
    }

    /**
     * Computes bucket breakdown from display-name-keyed category budgets.
     * Uses storedBuckets (written by saveBudget / addCategory) as the source of truth
     * so that (a) custom user categories carry the correct bucket, and (b) we never
     * re-run the fuzzy string-to-Category resolution at read time.
     */
    private BudgetJson.BucketBreakdown computeBucketBreakdownFromDisplayKeys(
            Map<String, BigDecimal> categoryBudgets,
            BigDecimal savingsTarget,
            UserAssessment assessment,
            Map<String, String> storedBuckets) {
        BigDecimal needsTotal     = BigDecimal.ZERO;
        BigDecimal wantsTotal     = BigDecimal.ZERO;
        BigDecimal committedTotal = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> e : categoryBudgets.entrySet()) {
            String bucketStr = storedBuckets.get(e.getKey());
            if ("COMMITTED".equals(bucketStr)) {
                committedTotal = committedTotal.add(e.getValue());
                continue;
            }
            Bucket bucket;
            if (bucketStr != null) {
                bucket = "NEEDS".equals(bucketStr) ? Bucket.NEEDS : Bucket.WANTS;
            } else {
                Category resolved = resolveRawCategoryString(e.getKey());
                bucket = bucketOf(resolved);
            }
            if (bucket == Bucket.NEEDS) {
                needsTotal = needsTotal.add(e.getValue());
            } else if (bucket == Bucket.WANTS) {
                wantsTotal = wantsTotal.add(e.getValue());
            }
        }

        if (savingsTarget == null) savingsTarget = BigDecimal.ZERO;
        BigDecimal grandTotal = committedTotal.add(needsTotal).add(wantsTotal).add(savingsTarget);

        BudgetJson.BucketBreakdown breakdown = new BudgetJson.BucketBreakdown();
        breakdown.committedTotal = committedTotal.setScale(0, RoundingMode.HALF_UP);
        breakdown.needsTotal     = needsTotal.setScale(0, RoundingMode.HALF_UP);
        breakdown.wantsTotal     = wantsTotal.setScale(0, RoundingMode.HALF_UP);
        breakdown.savingsTotal   = savingsTarget.setScale(0, RoundingMode.HALF_UP);
        breakdown.savingsBreakdown = computeSavingsBreakdown(assessment, savingsTarget);

        if (grandTotal.compareTo(BigDecimal.ZERO) > 0) {
            breakdown.committedPct = round1(committedTotal.divide(grandTotal, 4, RoundingMode.HALF_UP).doubleValue() * 100);
            breakdown.needsPct     = round1(needsTotal.divide(grandTotal, 4, RoundingMode.HALF_UP).doubleValue() * 100);
            breakdown.wantsPct     = round1(wantsTotal.divide(grandTotal, 4, RoundingMode.HALF_UP).doubleValue() * 100);
            breakdown.savingsPct   = round1(savingsTarget.divide(grandTotal, 4, RoundingMode.HALF_UP).doubleValue() * 100);
        }

        return breakdown;
    }

    private BudgetJson.SavingsBreakdown computeSavingsBreakdown(UserAssessment assessment, BigDecimal savingsTarget) {
        BudgetJson.SavingsBreakdown breakdown = new BudgetJson.SavingsBreakdown();

        if (savingsTarget == null || savingsTarget.compareTo(BigDecimal.ZERO) <= 0) {
            breakdown.goalFund = BigDecimal.ZERO;
            breakdown.emergencyFund = BigDecimal.ZERO;
            breakdown.freeSavings = BigDecimal.ZERO;
            return breakdown;
        }

        BigDecimal remaining = savingsTarget;

        BigDecimal goalFund = BigDecimal.ZERO;
        if (assessment != null && assessment.getGoalAmount() != null && assessment.getGoalDeadline() != null) {
            long monthsLeft = ChronoUnit.MONTHS.between(LocalDate.now(), assessment.getGoalDeadline());
            if (monthsLeft > 0) {
                BigDecimal requiredPace = assessment.getGoalAmount()
                        .divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP);
                goalFund = requiredPace.min(remaining).max(BigDecimal.ZERO);
            }
            breakdown.goalName = assessment.getFinancialGoal();
        }
        remaining = remaining.subtract(goalFund);

        BigDecimal emergencyCap = savingsTarget.multiply(BigDecimal.valueOf(EMERGENCY_FUND_TARGET_PCT_OF_SAVINGS));
        BigDecimal emergencyFund = emergencyCap.min(remaining).max(BigDecimal.ZERO);
        remaining = remaining.subtract(emergencyFund);

        BigDecimal freeSavings = remaining.max(BigDecimal.ZERO);

        breakdown.goalFund = goalFund.setScale(0, RoundingMode.HALF_UP);
        breakdown.emergencyFund = emergencyFund.setScale(0, RoundingMode.HALF_UP);
        breakdown.freeSavings = freeSavings.setScale(0, RoundingMode.HALF_UP);

        if (savingsTarget.compareTo(BigDecimal.ZERO) > 0) {
            breakdown.goalPct = round1(goalFund.divide(savingsTarget, 4, RoundingMode.HALF_UP).doubleValue() * 100);
            breakdown.emergencyPct = round1(emergencyFund.divide(savingsTarget, 4, RoundingMode.HALF_UP).doubleValue() * 100);
            breakdown.freePct = round1(freeSavings.divide(savingsTarget, 4, RoundingMode.HALF_UP).doubleValue() * 100);
        }

        return breakdown;
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Baseline generation internals
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Core budget builder — strategy percentages are the input, not user history.
     *
     * Algorithm:
     *   1. Load committed expenses from assessment (rent, EMI, bills, home transfer, etc.)
     *   2. discretionary = income − totalCommitted
     *   3. savings   = discretionary × split[2]
     *   4. needsPool = discretionary × split[0]
     *   5. wantsPool = discretionary × split[1]
     *   6. Split each pool equally across its predefined envelopes.
     *   7. Committed categories are added to categoryBudgets with COMMITTED bucket —
     *      they show as fixed allocations, not as envelopes the user manages.
     *
     * The split bar always reflects the strategy applied to discretionary income —
     * no AI, no goal timeline, no statement bias.
     */
    private BudgetJson buildBudgetJson(Map<Category, BigDecimal> avgSpending,
                                       BigDecimal income, double[] split,
                                       String source, UserAssessment assessment) {
        // ── Step 1: extract committed expenses from assessment ─────────────
        Map<String, BigDecimal> committed = loadCommittedExpenses(assessment);
        BigDecimal totalCommitted = committed.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);

        // ── Step 2: discretionary = what's left after committed ────────────
        BigDecimal discretionary = income.subtract(totalCommitted).max(BigDecimal.ZERO)
                .setScale(0, RoundingMode.HALF_UP);

        BigDecimal savingsTarget = discretionary.multiply(BigDecimal.valueOf(split[2])).setScale(0, RoundingMode.HALF_UP);
        BigDecimal needsPool     = discretionary.multiply(BigDecimal.valueOf(split[0])).setScale(0, RoundingMode.HALF_UP);
        BigDecimal wantsPool     = discretionary.multiply(BigDecimal.valueOf(split[1])).setScale(0, RoundingMode.HALF_UP);

        // ── Step 3: separate predefined categories by bucket ──────────────
        // Only include non-committed predefined categories (skip any whose name
        // is already in committed so we don't double-count "Rent & Housing", etc.)
        Set<String> committedNames = committed.keySet().stream()
                .map(String::toLowerCase).collect(Collectors.toSet());

        Map<Category, BigDecimal> needsHistory = new LinkedHashMap<>();
        Map<Category, BigDecimal> wantsHistory = new LinkedHashMap<>();
        for (Map.Entry<Category, BigDecimal> e : avgSpending.entrySet()) {
            String displayName = e.getKey().getDisplayName().toLowerCase();
            if (committedNames.contains(displayName)) continue; // already in committed
            Bucket b = bucketOf(e.getKey());
            if (b == Bucket.NEEDS) needsHistory.put(e.getKey(), e.getValue());
            else if (b == Bucket.WANTS) wantsHistory.put(e.getKey(), e.getValue());
        }

        // ── Step 4: equal-split pools across discretionary envelopes ──────
        Map<Category, BigDecimal> discretionaryBudgets = new LinkedHashMap<>();
        discretionaryBudgets.putAll(distributePool(needsHistory, needsPool));
        discretionaryBudgets.putAll(distributePool(wantsHistory, wantsPool));

        Map<String, String> reasoning = buildReasoning(avgSpending, discretionaryBudgets, savingsTarget, income);

        // ── Step 5: merge committed + discretionary into categoryBudgets ──
        Map<String, BigDecimal> allCategoryBudgets = new LinkedHashMap<>();
        allCategoryBudgets.putAll(committed); // committed first
        allCategoryBudgets.putAll(toDisplayKeyedMap(discretionaryBudgets));

        // totalBudget = discretionary only (needsPool + wantsPool + savingsTarget).
        // Committed expenses are fixed outflows shown separately — they don't drive pacing.
        BigDecimal discretionaryTotal = toDisplayKeyedMap(discretionaryBudgets).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(savingsTarget)
                .setScale(0, RoundingMode.HALF_UP);

        BudgetJson json = new BudgetJson();
        json.totalBudget      = discretionaryTotal;
        json.savingsTarget    = savingsTarget;
        json.categoryBudgets  = allCategoryBudgets;
        json.committedExpenses = committed;
        json.reasoning        = reasoning;
        json.source           = source;
        json.generatedAt      = LocalDate.now().toString();

        // Build bucket breakdown; committed names → bucket "COMMITTED"
        Map<String, String> bucketMap = new LinkedHashMap<>();
        committed.keySet().forEach(k -> bucketMap.put(k, "COMMITTED"));
        discretionaryBudgets.forEach((cat, amt) ->
                bucketMap.put(cat.getDisplayName(), bucketOf(cat).name()));

        json.bucketBreakdown = computeBucketBreakdownFromDisplayKeys(
                allCategoryBudgets, json.savingsTarget, assessment, bucketMap);
        return json;
    }

    /** Parses the assessment's committedExpensesJson into a name→amount map. */
    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> loadCommittedExpenses(UserAssessment assessment) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        if (assessment == null || assessment.getCommittedExpensesJson() == null) return result;
        try {
            List<Map<String, Object>> list = objectMapper.readValue(
                    assessment.getCommittedExpensesJson(), List.class);
            for (Map<String, Object> entry : list) {
                String name = String.valueOf(entry.get("name")).trim();
                Object rawAmt = entry.get("amount");
                if (name.isEmpty() || rawAmt == null) continue;
                BigDecimal amt = new BigDecimal(rawAmt.toString()).setScale(0, RoundingMode.HALF_UP);
                if (amt.compareTo(BigDecimal.ZERO) > 0) {
                    result.put(name, amt);
                }
            }
        } catch (Exception ex) {
            log.warn("Could not parse committedExpensesJson: {}", ex.getMessage());
        }
        return result;
    }

    /**
     * Splits a pool equally across all categories in the bucket.
     * No AI, no statement data — every envelope starts with the same slice.
     * The user adjusts individual amounts from the app; this is just a neutral starting point.
     * The last entry absorbs the rounding remainder so totals stay exact.
     */
    private Map<Category, BigDecimal> distributePool(Map<Category, BigDecimal> categories, BigDecimal pool) {
        if (categories.isEmpty() || pool.compareTo(BigDecimal.ZERO) <= 0) return new LinkedHashMap<>();

        int count = categories.size();
        BigDecimal each = pool.divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP);
        List<Category> keys = new ArrayList<>(categories.keySet());
        Map<Category, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;

        for (int i = 0; i < keys.size(); i++) {
            boolean isLast = i == keys.size() - 1;
            BigDecimal share = isLast ? pool.subtract(allocated).max(BigDecimal.ZERO) : each;
            result.put(keys.get(i), share);
            allocated = allocated.add(share);
        }
        return result;
    }

    /**
     * Reads historical category spend from StatementProfile JSON and
     * resolves each raw category string to the REAL Category enum via
     * Category.valueOf on the stored enum name, falling back to a
     * display-name match, and finally to OTHER with a loud warning if
     * neither works. This is the only place raw strings from
     * statement-profile JSON get converted into real Category values —
     * everywhere else in this service works with Category directly.
     */
    private Map<Category, BigDecimal> computeAverageSpendingByCategory(Long userId) {
        List<StatementProfile> profiles = statementProfileRepository.findByUserIdOrderByProfileYearDescProfileMonthDesc(userId);
        if (profiles.isEmpty()) return new LinkedHashMap<>();

        Map<Category, List<BigDecimal>> byCategory = new HashMap<>();

        for (StatementProfile p : profiles) {
            try {
                Map<String, Object> json = objectMapper.readValue(p.getProfileJson(), Map.class);
                Object breakdownObj = json.get("spendingBreakdown");
                if (breakdownObj instanceof Map<?, ?> breakdown) {
                    for (Map.Entry<?, ?> e : breakdown.entrySet()) {
                        String rawCat = String.valueOf(e.getKey());
                        Category resolved = resolveRawCategoryString(rawCat);

                        if (bucketOf(resolved) == Bucket.EXCLUDED) {
                            continue; // not a spend category — never enters budget math
                        }

                        BigDecimal amt = new BigDecimal(String.valueOf(e.getValue()));
                        byCategory.computeIfAbsent(resolved, k -> new ArrayList<>()).add(amt);
                    }
                }
            } catch (Exception ex) {
                log.warn("Could not parse profileJson for statement profile {}: {}", p.getId(), ex.getMessage());
            }
        }

        Map<Category, BigDecimal> avg = new LinkedHashMap<>();
        for (Map.Entry<Category, List<BigDecimal>> e : byCategory.entrySet()) {
            BigDecimal sum = e.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal average = sum.divide(BigDecimal.valueOf(e.getValue().size()), 2, RoundingMode.HALF_UP);
            avg.put(e.getKey(), average);
        }

        // Ensure every predefined category appears in the map even if no historical
        // spending exists for it. This means the baseline budget always includes all
        // standard envelopes — user sets amounts, not the AI classifier.
        for (BudgetJson.CategoryTemplate tmpl : PREDEFINED_CATEGORIES) {
            Category cat = resolveRawCategoryString(tmpl.name);
            avg.putIfAbsent(cat, BigDecimal.ZERO);
        }

        return avg;
    }

    /**
     * Resolves a raw category string (could be an enum constant name like
     * "FOOD_AND_DINING", a display name like "Food & Dining", or something
     * unrecognized) into a real Category. Logs loudly and defaults to OTHER
     * only as a last resort — this is the one deliberate fuzzy-matching
     * point in the file, and it is auditable via the warning log.
     */
    private Category resolveRawCategoryString(String raw) {
        if (raw == null || raw.isBlank()) return Category.OTHER;

        try {
            return Category.valueOf(raw.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            // fall through to display-name match
        }

        for (Category c : Category.values()) {
            if (c.getDisplayName().equalsIgnoreCase(raw.trim())) {
                return c;
            }
        }

        log.warn("Unresolved category string '{}' from statement profile — defaulting to OTHER. " +
                "Check the categorizer/parser that produced this value.", raw);
        return Category.OTHER;
    }

    private BigDecimal getAvgIncome(Long userId, UserAssessment assessment) {
        if (assessment != null && assessment.getDeclaredCurrentIncome() != null
                && assessment.getDeclaredCurrentIncome().compareTo(BigDecimal.ZERO) > 0) {
            return assessment.getDeclaredCurrentIncome();
        }

        return overallProfileRepository.findByUserId(userId)
                .map(OverallProfile::getAvgIncome)
                .filter(v -> v != null && v.compareTo(BigDecimal.ZERO) > 0)
                .orElseGet(() -> assessment != null && assessment.getMonthlyIncome() != null
                        ? assessment.getMonthlyIncome()
                        : BigDecimal.ZERO);
    }

    private Map<String, String> buildReasoning(Map<Category, BigDecimal> avgSpending,
                                               Map<Category, BigDecimal> categoryBudgets,
                                               BigDecimal savingsTarget,
                                               BigDecimal avgIncome) {
        Map<String, String> reasoning = new LinkedHashMap<>();
        for (Map.Entry<Category, BigDecimal> e : categoryBudgets.entrySet()) {
            Category cat = e.getKey();
            BigDecimal budgeted = e.getValue();
            BigDecimal historical = avgSpending.getOrDefault(cat, BigDecimal.ZERO);

            if (historical.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal diff = budgeted.subtract(historical);
            double pctChange = historical.compareTo(BigDecimal.ZERO) != 0
                    ? diff.divide(historical, 4, RoundingMode.HALF_UP).doubleValue() * 100
                    : 0;

            String key = cat.getDisplayName();
            if (Math.abs(pctChange) < 2) {
                reasoning.put(key, String.format("Kept at your historical average of ₹%s/month.", fmt(historical)));
            } else if (pctChange < 0) {
                reasoning.put(key, String.format("Trimmed %.0f%% from your average (₹%s → ₹%s) to free up money for savings.",
                        Math.abs(pctChange), fmt(historical), fmt(budgeted)));
            } else {
                reasoning.put(key, String.format("Set %.0f%% above your average — you have room based on your income.",
                        pctChange));
            }
        }
        reasoning.put("_savings", String.format("That's %s%% of your average income of ₹%s/month.",
                avgIncome.compareTo(BigDecimal.ZERO) > 0
                        ? fmt(savingsTarget.divide(avgIncome, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)))
                        : "0",
                fmt(avgIncome)));
        return reasoning;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════

    private Bucket bucketOf(Category category) {
        return CATEGORY_BUCKETS.getOrDefault(category, Bucket.WANTS);
    }

    private Map<String, BigDecimal> toDisplayKeyedMap(Map<Category, BigDecimal> categoryMap) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<Category, BigDecimal> e : categoryMap.entrySet()) {
            result.put(e.getKey().getDisplayName(), e.getValue());
        }
        return result;
    }

    /**
     * Resolves any user-supplied or stored category string (could be a
     * Category enum name, a display name, or free text from older data) to
     * the canonical display-name key used in categoryBudgets/reasoning maps
     * going forward. Falls back to the raw trimmed string if nothing
     * resolves, rather than silently coercing to "Other" — keeps it visible.
     */
    private String resolveToDisplayKey(String raw) {
        if (raw == null || raw.isBlank()) return Category.OTHER.getDisplayName();
        Category resolved = resolveRawCategoryString(raw);
        return resolved != Category.OTHER || raw.trim().equalsIgnoreCase(Category.OTHER.getDisplayName())
                ? resolved.getDisplayName()
                : raw.trim();
    }

    private String resolveCategoryDisplayKey(Transaction t) {
        Category effective = t.getEffectiveCategory();
        if (effective != null) {
            return effective.getDisplayName();
        }
        if (t.getCategory() != null && !t.getCategory().isBlank()) {
            return resolveToDisplayKey(t.getCategory());
        }
        return Category.OTHER.getDisplayName();
    }

    /** Sums only non-COMMITTED categories — the portion that drives pacing. */
    private BigDecimal discretionaryTotal(Map<String, BigDecimal> categoryBudgets,
                                          Map<String, String> buckets) {
        return categoryBudgets.entrySet().stream()
                .filter(e -> !"COMMITTED".equals(buckets.get(e.getKey())))
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal sumValues(Map<Category, BigDecimal> map) {
        return map.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumValuesByKey(Map<String, BigDecimal> map) {
        return map.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void validateBudget(BudgetJson budget) {
        if (budget.categoryBudgets == null || budget.categoryBudgets.isEmpty()) {
            throw new IllegalArgumentException("Budget must have at least one category");
        }
        for (Map.Entry<String, BigDecimal> e : budget.categoryBudgets.entrySet()) {
            if (e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Category '" + e.getKey() + "' has invalid amount");
            }
        }
        BigDecimal sum = budget.categoryBudgets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (budget.totalBudget != null) {
            BigDecimal diff = sum.subtract(budget.totalBudget).abs();
            if (diff.compareTo(BigDecimal.valueOf(10)) > 0) {
                throw new IllegalArgumentException(String.format(
                        "Category budgets sum (₹%s) doesn't match totalBudget (₹%s)", fmt(sum), fmt(budget.totalBudget)));
            }
        } else {
            budget.totalBudget = sum;
        }
    }

    private void saveBudget(Long userId, BudgetJson json, Budget.Source source) {
        Budget budget = budgetRepository.findByUserId(userId).orElseGet(() -> {
            Budget b = new Budget();
            User user = new User();
            user.setId(userId);
            b.setUser(user);
            return b;
        });

        if (budget.getId() != null && budget.getSource() == Budget.Source.USER_ADJUSTED && source == Budget.Source.AUTO) {
            log.info("Skipping auto-budget overwrite for user {} — existing budget is USER_ADJUSTED", userId);
            return;
        }

        if (budget.getId() != null && budget.getCategoryBudgetsJson() != null) {
            Map<String, BigDecimal> oldCategories = parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class);
            Map<String, BigDecimal> newCategories = json.categoryBudgets;

            boolean changed = !mapsRoughlyEqual(oldCategories, newCategories)
                    || (budget.getSavingsTarget() != null && json.savingsTarget != null
                    && budget.getSavingsTarget().subtract(json.savingsTarget).abs().compareTo(CHANGE_THRESHOLD) >= 0);

            if (changed) {
                BigDecimal prevSavings = budget.getSavingsTarget();
                budget.setPreviousCategoryBudgetsJson(budget.getCategoryBudgetsJson());
                budget.setPreviousTotalBudget(budget.getTotalBudget());
                budget.setPreviousSavingsTarget(prevSavings);

                List<BudgetJson.BudgetChange> changes = computeChanges(oldCategories, newCategories,
                        prevSavings, json.savingsTarget);
                budget.setLastDiffSummary(buildDiffSummary(changes));
            }
        }

        budget.setTotalBudget(json.totalBudget);
        budget.setSavingsTarget(json.savingsTarget);
        budget.setCategoryBudgetsJson(serialize(json.categoryBudgets));
        budget.setReasoningJson(serialize(json.reasoning));

        // Rebuild stored bucket assignments from the current category list.
        // Committed categories (present in json.committedExpenses) are stored
        // as "COMMITTED" so they're excluded from Needs/Wants math at read time.
        // Existing overrides (custom user-added categories) are preserved.
        Map<String, String> storedBuckets = parseMap(budget.getCategoryBucketsJson(), String.class);
        Set<String> committedKeys = json.committedExpenses != null ? json.committedExpenses.keySet() : Set.of();
        for (String key : json.categoryBudgets.keySet()) {
            if (committedKeys.contains(key)) {
                storedBuckets.put(key, "COMMITTED");
            } else if (!storedBuckets.containsKey(key)) {
                Category resolved = resolveRawCategoryString(key);
                Bucket b = bucketOf(resolved);
                if (b != Bucket.EXCLUDED) {
                    storedBuckets.put(key, b.name());
                }
            }
        }
        budget.setCategoryBucketsJson(serialize(storedBuckets));

        budget.setSource(source);
        budget.setGeneratedAt(LocalDateTime.now());
        budget.setUpdatedAt(LocalDateTime.now());

        budgetRepository.save(budget);
        log.info("Budget saved for user {} — total=₹{} source={}", userId, fmt(json.totalBudget), source);
    }

    private boolean mapsRoughlyEqual(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        Set<String> keys = new HashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            BigDecimal av = a.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal bv = b.getOrDefault(k, BigDecimal.ZERO);
            if (av.subtract(bv).abs().compareTo(CHANGE_THRESHOLD) >= 0) return false;
        }
        return true;
    }

    private List<BudgetJson.BudgetChange> computeChanges(Map<String, BigDecimal> oldMap, Map<String, BigDecimal> newMap,
                                                         BigDecimal oldSavings, BigDecimal newSavings) {
        List<BudgetJson.BudgetChange> changes = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(oldMap.keySet());
        keys.addAll(newMap.keySet());

        for (String k : keys) {
            BigDecimal ov = oldMap.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal nv = newMap.getOrDefault(k, BigDecimal.ZERO);
            if (ov.subtract(nv).abs().compareTo(CHANGE_THRESHOLD) >= 0) {
                BudgetJson.BudgetChange c = new BudgetJson.BudgetChange();
                c.category = k;
                c.oldAmount = ov;
                c.newAmount = nv;
                changes.add(c);
            }
        }

        if (oldSavings != null && newSavings != null
                && oldSavings.subtract(newSavings).abs().compareTo(CHANGE_THRESHOLD) >= 0) {
            BudgetJson.BudgetChange c = new BudgetJson.BudgetChange();
            c.category = "_savings";
            c.oldAmount = oldSavings;
            c.newAmount = newSavings;
            changes.add(c);
        }

        return changes;
    }

    private BudgetJson toJson(Budget budget) {
        BudgetJson json = new BudgetJson();
        json.totalBudget = budget.getTotalBudget();
        json.savingsTarget = budget.getSavingsTarget();
        json.categoryBudgets = parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class);
        json.reasoning = parseMap(budget.getReasoningJson(), String.class);
        json.source = budget.getSource().name();
        json.generatedAt = budget.getGeneratedAt().toLocalDate().toString();

        UserAssessment assessment = assessmentRepository.findByUserId(budget.getUser().getId()).orElse(null);
        Map<String, String> storedBuckets = parseMap(budget.getCategoryBucketsJson(), String.class);

        // Derive committedExpenses from the stored bucket map — any category
        // tagged "COMMITTED" is a fixed outflow declared in the assessment.
        Map<String, BigDecimal> committed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : storedBuckets.entrySet()) {
            if ("COMMITTED".equals(e.getValue())) {
                BigDecimal amt = json.categoryBudgets.getOrDefault(e.getKey(), BigDecimal.ZERO);
                committed.put(e.getKey(), amt);
            }
        }
        json.committedExpenses = committed;

        json.bucketBreakdown = computeBucketBreakdownFromDisplayKeys(
                json.categoryBudgets, json.savingsTarget, assessment, storedBuckets);
        return json;
    }

    private <V> Map<String, V> parseMap(String jsonStr, Class<V> valueType) {
        if (jsonStr == null || jsonStr.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(jsonStr, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, valueType));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String serialize(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private String fmt(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Financial Health Score — assessment + statement data, lives on Budget
    // ═════════════════════════════════════════════════════════════════════

    public BudgetJson.FinancialHealthScore getFinancialHealthScore(Long userId) {
        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);
        List<Transaction> recentTx = getRecentTransactions(userId);

        int savingsScore   = fhScoreSavingsRate(assessment);
        int disciplineScore = fhScoreSpendingDiscipline(recentTx);
        int debtScore      = fhScoreDebtBurden(assessment, recentTx);
        int incomeScore    = fhScoreIncomeStability(assessment);
        int emergencyScore = fhScoreEmergencyCushion(assessment);
        int goalScore      = fhScoreGoalAlignment(assessment);

        int composite = (int) Math.round(
                savingsScore   * 0.20 + disciplineScore * 0.20 + debtScore   * 0.15
                + incomeScore  * 0.15 + emergencyScore  * 0.15 + goalScore   * 0.15);

        BudgetJson.FinancialHealthSubScores sub = new BudgetJson.FinancialHealthSubScores();
        sub.savingsRate       = savingsScore;
        sub.spendingDiscipline = disciplineScore;
        sub.debtBurden        = debtScore;
        sub.incomeStability   = incomeScore;
        sub.emergencyCushion  = emergencyScore;
        sub.goalAlignment     = goalScore;

        BudgetJson.FinancialHealthScore result = new BudgetJson.FinancialHealthScore();
        result.score     = composite;
        result.archetype = resolveFhArchetype(composite, debtScore);
        result.subScores = sub;
        return result;
    }

    public BudgetJson.DeclaredVsActual getDeclaredVsActual(Long userId) {
        UserAssessment assessment = assessmentRepository.findByUserId(userId).orElse(null);
        if (assessment == null) return null;

        List<Transaction> recentTx = getRecentTransactions(userId);
        List<RecurringExpenseDetector.RecurringExpense> recurring =
                recurringExpenseDetector.detect(recentTx);

        DeclaredVsActualAnalyzer.DiscrepancyReport report =
                declaredVsActualAnalyzer.analyze(assessment, recentTx, recurring);

        BudgetJson.DeclaredVsActual dva = new BudgetJson.DeclaredVsActual();
        dva.overallSummary        = report.overallSummary;
        dva.savingsInsight        = report.savingsInsight;
        dva.debtInsight           = report.debtInsight;
        dva.declaredIncome        = report.declaredIncome;
        dva.declaredSavings       = report.declaredSavings;
        dva.actualSpend           = report.actualSpend;
        dva.impliedActualSavings  = report.impliedActualSavings;
        dva.savingsOverstated     = report.overstatedSavings;
        dva.hiddenDebtFound       = report.hiddenDebtFound;
        dva.estimatedMonthlyEmi   = report.estimatedMonthlyEmi;
        return dva;
    }

    private List<Transaction> getRecentTransactions(Long userId) {
        YearMonth current = YearMonth.now();
        List<Transaction> txs = transactionRepository.findByUserIdAndStatementYearAndStatementMonth(
                userId, current.getYear(), current.getMonthValue());
        if (txs.isEmpty()) {
            YearMonth prev = current.minusMonths(1);
            txs = transactionRepository.findByUserIdAndStatementYearAndStatementMonth(
                    userId, prev.getYear(), prev.getMonthValue());
        }
        return txs;
    }

    private int fhScoreSavingsRate(UserAssessment a) {
        if (a == null || a.getMonthlyIncome() == null || a.getMonthlySavings() == null) return 50;
        if (a.getMonthlyIncome().compareTo(BigDecimal.ZERO) == 0) return 10;
        double rate = a.getMonthlySavings().doubleValue() / a.getMonthlyIncome().doubleValue();
        if (rate >= 0.40) return 100;
        if (rate >= 0.30) return 85;
        if (rate >= 0.20) return 70;
        if (rate >= 0.10) return 50;
        if (rate >= 0.05) return 30;
        return 10;
    }

    private int fhScoreSpendingDiscipline(List<Transaction> txs) {
        BigDecimal total = txs.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .map(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) == 0) return 60;

        Set<String> lifestyleCats = Set.of(
                "food", "dining", "entertainment", "travel", "shopping",
                "drinks", "restaurants", "subscriptions", "fashion");

        double lifestyle = txs.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> {
                    String cat = t.getSystemCategory() != null
                            ? t.getSystemCategory().name().toLowerCase()
                            : (t.getCategory() != null ? t.getCategory().toLowerCase() : "");
                    return lifestyleCats.stream().anyMatch(cat::contains);
                })
                .mapToDouble(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount().doubleValue() : 0)
                .sum();

        double ratio = (lifestyle / total.doubleValue()) * 100;
        return (int) Math.round(Math.max(10, Math.max(20, 100 - (ratio / 100 * 160))));
    }

    private int fhScoreDebtBurden(UserAssessment a, List<Transaction> txs) {
        int base = 80;
        if (a == null) return base;
        if (Boolean.FALSE.equals(a.getHasDebt())) return 100;
        if (Boolean.TRUE.equals(a.getHasDebt())) base -= 25;

        double emiDebit = txs.stream()
                .filter(t -> t.getType() == TransactionType.DEBIT)
                .filter(t -> t.getMode() == TransactionMode.EMI || t.getMode() == TransactionMode.AUTOPAY)
                .mapToDouble(t -> t.getWithdrawalAmount() != null ? t.getWithdrawalAmount().doubleValue() : 0)
                .sum();

        if (a.getMonthlyIncome() != null && a.getMonthlyIncome().doubleValue() > 0) {
            double foir = emiDebit / a.getMonthlyIncome().doubleValue();
            if      (foir > 0.50) base -= 30;
            else if (foir > 0.35) base -= 15;
            else if (foir > 0.20) base -= 5;
        }
        return Math.max(10, Math.min(100, base));
    }

    private int fhScoreIncomeStability(UserAssessment a) {
        if (a == null || a.getMonthlyIncome() == null
                || a.getMonthlyIncome().compareTo(BigDecimal.ZERO) <= 0) return 40;
        int base = 70;
        if (a.getOccupation() != null) {
            String occ = a.getOccupation().toLowerCase();
            if (occ.contains("salaried") || occ.contains("government")) base += 20;
            else if (occ.contains("freelance") || occ.contains("self-employed")) base -= 10;
        }
        return Math.max(10, Math.min(100, base));
    }

    private int fhScoreEmergencyCushion(UserAssessment a) {
        if (a == null) return 40;
        if (Boolean.FALSE.equals(a.getHasEmergencyFund())) return 10;
        if (Boolean.TRUE.equals(a.getHasEmergencyFund())) {
            Integer months = a.getEmergencyMonths();
            if (months == null) return 50;
            if (months >= 6) return 100;
            if (months >= 4) return 80;
            if (months >= 2) return 55;
            if (months >= 1) return 30;
            return 10;
        }
        return 40;
    }

    private int fhScoreGoalAlignment(UserAssessment a) {
        if (a == null || a.getFinancialGoal() == null) return 30;
        if (a.getGoalDeadline() == null) return 50;
        if (a.getGoalAmount() == null || a.getMonthlySavings() == null) return 60;
        long monthsLeft = java.time.temporal.ChronoUnit.MONTHS.between(
                LocalDate.now(), a.getGoalDeadline());
        if (monthsLeft <= 0) return 40;
        BigDecimal projected = a.getMonthlySavings().multiply(new BigDecimal(monthsLeft));
        double ratio = projected.doubleValue() / a.getGoalAmount().doubleValue();
        if (ratio >= 1.0)  return 100;
        if (ratio >= 0.75) return 85;
        if (ratio >= 0.50) return 70;
        if (ratio >= 0.25) return 55;
        return 40;
    }

    private String resolveFhArchetype(int score, int debt) {
        if (score >= 90) return "The Wealth Builder";
        if (score >= 75) return "The Disciplined Saver";
        if (score >= 60) return "The Balanced Planner";
        if (score >= 45) return debt < 50 ? "The Debt Wrestler" : "The Aspirational Improver";
        if (score >= 30) return "The Cash-Flow Juggler";
        return "The Financial Rebuilder";
    }
}