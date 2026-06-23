package com.moneylens.controller;
import com.moneylens.dto.budget.BudgetJson;
import com.moneylens.dto.budget.ChooseOptionRequest;
import com.moneylens.dto.budget.ManualExpenseDto;
import com.moneylens.entity.User;
import com.moneylens.repository.UserRepository;
import com.moneylens.service.BudgetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/budget")
public class BudgetController {

    private final BudgetService budgetService;
    private final UserRepository userRepository;

    public BudgetController(BudgetService budgetService, UserRepository userRepository) {
        this.budgetService = budgetService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<BudgetJson> getBudget(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.getOrGenerateBudget(userId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<BudgetJson> refresh(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.generateBaseline(userId, true));
    }

    @GetMapping("/pacing")
    public ResponseEntity<BudgetJson.DailyPacing> pacing(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.getDailyPacing(userId));
    }

    @GetMapping("/progress")
    public ResponseEntity<BudgetJson.CategoryProgress> progress(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.getCategoryProgress(userId));
    }

    @GetMapping("/diff")
    public ResponseEntity<BudgetJson.BudgetDiff> diff(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        BudgetJson.BudgetDiff diff = budgetService.getLastDiff(userId);
        if (diff == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(diff);
    }

    @PostMapping("/diff/dismiss")
    public ResponseEntity<Void> dismissDiff(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        budgetService.clearDiff(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/goal")
    public ResponseEntity<BudgetJson.GoalProgress> goal(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.getGoalProgress(userId));
    }

    @PatchMapping("/category")
    public ResponseEntity<BudgetJson> adjustCategory(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody AdjustCategoryRequest req) {
        Long userId = resolveUserId(phoneNumber);
        BudgetJson updated = budgetService.adjustCategory(userId, req.category, req.newAmount, req.reason);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/expense")
    public ResponseEntity<Void> addExpense(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody AddExpenseRequest req) {
        Long userId = resolveUserId(phoneNumber);
        budgetService.addManualExpense(userId, req.category, req.amount, req.note);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/expenses")
    public ResponseEntity<List<ManualExpenseDto>> getExpenses(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.getManualExpenses(userId));
    }

    @DeleteMapping("/expense/{id}")
    public ResponseEntity<Void> deleteExpense(
            @AuthenticationPrincipal String phoneNumber,
            @PathVariable Long id) {
        Long userId = resolveUserId(phoneNumber);
        budgetService.deleteManualExpense(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/highlight")
    public ResponseEntity<BudgetJson.HomeHighlight> highlight(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.getHomeHighlight(userId));
    }

    @PatchMapping("/income")
    public ResponseEntity<BudgetJson.IncomeUpdateResult> updateIncome(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody UpdateIncomeRequest req) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.updateIncomeAndRebudget(userId, req.newIncome));
    }

    @PatchMapping("/goal")
    public ResponseEntity<BudgetJson.GoalUpdateResult> updateGoal(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody UpdateGoalRequest req) {
        Long userId = resolveUserId(phoneNumber);
        LocalDate deadline = req.deadline != null ? LocalDate.parse(req.deadline) : null;
        return ResponseEntity.ok(budgetService.updateGoalAndRebudget(userId, req.goalName, req.targetAmount, deadline));
    }

    @GetMapping("/options")
    public ResponseEntity<BudgetJson.BudgetOptionsResponse> getOptions(@AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.generateBudgetOptions(userId));
    }

    @PostMapping("/options/choose")
    public ResponseEntity<BudgetJson> chooseOption(@AuthenticationPrincipal String phoneNumber,
                                                   @RequestBody ChooseOptionRequest request) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.commitBudgetOption(userId, request.strategyId));
    }

    @GetMapping("/category-templates")
    public ResponseEntity<List<BudgetJson.CategoryTemplate>> getCategoryTemplates(
            @AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.getCategoryTemplates(userId));
    }

    @PatchMapping("/category/bucket")
    public ResponseEntity<BudgetJson> updateCategoryBucket(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody UpdateBucketRequest req) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.updateCategoryBucket(userId, req.name, req.bucket));
    }

    @PostMapping("/category")
    public ResponseEntity<BudgetJson> addCategory(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody AddCategoryRequest req) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.addCategory(userId, req.name, req.amount, req.bucket));
    }

    @DeleteMapping("/category")
    public ResponseEntity<BudgetJson> removeCategory(
            @AuthenticationPrincipal String phoneNumber,
            @RequestBody RemoveCategoryRequest req) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.removeCategory(userId, req.name));
    }

    @GetMapping("/financial-health")
    public ResponseEntity<BudgetJson.FinancialHealthScore> getFinancialHealth(
            @AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        return ResponseEntity.ok(budgetService.getFinancialHealthScore(userId));
    }

    @GetMapping("/declared-vs-actual")
    public ResponseEntity<BudgetJson.DeclaredVsActual> getDeclaredVsActual(
            @AuthenticationPrincipal String phoneNumber) {
        Long userId = resolveUserId(phoneNumber);
        BudgetJson.DeclaredVsActual result = budgetService.getDeclaredVsActual(userId);
        if (result == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(result);
    }

    public static class UpdateIncomeRequest {
        public BigDecimal newIncome;
    }

    public static class UpdateGoalRequest {
        public String goalName;
        public BigDecimal targetAmount;
        public String deadline; // YYYY-MM-DD
    }

    public static class AddExpenseRequest {
        public String category;
        public BigDecimal amount;
        public String note;
    }

    private Long resolveUserId(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getId();
    }

    public static class AdjustCategoryRequest {
        public String category;
        public BigDecimal newAmount;
        public String reason;
    }

    public static class AddCategoryRequest {
        public String name;
        public BigDecimal amount;
        public String bucket; // "NEEDS" or "WANTS"
    }

    public static class RemoveCategoryRequest {
        public String name;
    }

    public static class UpdateBucketRequest {
        public String name;
        public String bucket; // "NEEDS" or "WANTS"
    }

}