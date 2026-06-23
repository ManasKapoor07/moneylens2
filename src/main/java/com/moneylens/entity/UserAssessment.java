package com.moneylens.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "user_assessments")
public class UserAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "full_name")
    private String fullName;

    @ElementCollection
    @CollectionTable(name = "assessment_app_purposes", joinColumns = @JoinColumn(name = "assessment_id"))
    @Column(name = "purpose")
    private List<String> appPurpose;

    private String occupation;

    @Column(name = "occupation_detail")
    private String occupationDetail;

    @Column(name = "income_source")
    private String incomeSource;

    @Column(name = "monthly_income")
    private BigDecimal monthlyIncome;

    @Column(name = "monthly_savings")
    private BigDecimal monthlySavings;

    @Column(name = "pay_frequency")
    private String payFrequency;

    @ElementCollection
    @CollectionTable(name = "assessment_spending_categories", joinColumns = @JoinColumn(name = "assessment_id"))
    @Column(name = "category")
    private List<String> spendingCategories;

    @Column(name = "has_debt")
    private Boolean hasDebt;

    @Column(name = "financial_goal")
    private String financialGoal;

    @Column(name = "goal_amount")
    private BigDecimal goalAmount;

    @Column(name = "goal_deadline")
    private LocalDate goalDeadline;

    @Column(name = "expense_tracking")
    private String expenseTracking;

    @Column(name = "retirement_age")
    private Integer retirementAge;

    private Integer dependents;

    @Column(name = "finance_sentiment")
    private String financeSentiment;

    @Column(name = "has_emergency_fund")
    private Boolean hasEmergencyFund;

    @Column(name = "emergency_months")
    private Integer emergencyMonths;

    @Column(name = "spending_behaviour")
    private String spendingBehaviour;

    @ElementCollection
    @CollectionTable(name = "assessment_financial_challenges", joinColumns = @JoinColumn(name = "assessment_id"))
    @Column(name = "challenge")
    private List<String> financialChallenges;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "committed_expenses", columnDefinition = "jsonb")
    private String committedExpensesJson;

    @Column(name = "declared_current_income")
    private BigDecimal declaredCurrentIncome;

    @Column(name = "declared_income_updated_at")
    private LocalDateTime declaredIncomeUpdatedAt;


    public UserAssessment() {}

    // Getters
    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getFullName() { return fullName; }
    public List<String> getAppPurpose() { return appPurpose; }
    public String getOccupation() { return occupation; }
    public String getOccupationDetail() { return occupationDetail; }
    public String getIncomeSource() { return incomeSource; }
    public BigDecimal getMonthlyIncome() { return monthlyIncome; }
    public BigDecimal getMonthlySavings() { return monthlySavings; }
    public String getPayFrequency() { return payFrequency; }
    public List<String> getSpendingCategories() { return spendingCategories; }
    public Boolean getHasDebt() { return hasDebt; }
    public String getFinancialGoal() { return financialGoal; }
    public BigDecimal getGoalAmount() { return goalAmount; }
    public LocalDate getGoalDeadline() { return goalDeadline; }
    public String getExpenseTracking() { return expenseTracking; }
    public Integer getRetirementAge() { return retirementAge; }
    public Integer getDependents() { return dependents; }
    public String getFinanceSentiment() { return financeSentiment; }
    public Boolean getHasEmergencyFund() { return hasEmergencyFund; }
    public Integer getEmergencyMonths() { return emergencyMonths; }
    public String getSpendingBehaviour() { return spendingBehaviour; }
    public List<String> getFinancialChallenges() { return financialChallenges; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public String getCommittedExpensesJson() { return committedExpensesJson; }
    public void setCommittedExpensesJson(String v) { this.committedExpensesJson = v; }

    public BigDecimal getDeclaredCurrentIncome() { return declaredCurrentIncome; }
    public void setDeclaredCurrentIncome(BigDecimal v) { this.declaredCurrentIncome = v; }
    public LocalDateTime getDeclaredIncomeUpdatedAt() { return declaredIncomeUpdatedAt; }
    public void setDeclaredIncomeUpdatedAt(LocalDateTime v) { this.declaredIncomeUpdatedAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // Setters
    public void setId(Long id) { this.id = id; }
    public void setUser(User user) { this.user = user; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setAppPurpose(List<String> appPurpose) { this.appPurpose = appPurpose; }
    public void setOccupation(String occupation) { this.occupation = occupation; }
    public void setOccupationDetail(String occupationDetail) { this.occupationDetail = occupationDetail; }
    public void setIncomeSource(String incomeSource) { this.incomeSource = incomeSource; }
    public void setMonthlyIncome(BigDecimal monthlyIncome) { this.monthlyIncome = monthlyIncome; }
    public void setMonthlySavings(BigDecimal monthlySavings) { this.monthlySavings = monthlySavings; }
    public void setPayFrequency(String payFrequency) { this.payFrequency = payFrequency; }
    public void setSpendingCategories(List<String> spendingCategories) { this.spendingCategories = spendingCategories; }
    public void setHasDebt(Boolean hasDebt) { this.hasDebt = hasDebt; }
    public void setFinancialGoal(String financialGoal) { this.financialGoal = financialGoal; }
    public void setGoalAmount(BigDecimal goalAmount) { this.goalAmount = goalAmount; }
    public void setGoalDeadline(LocalDate goalDeadline) { this.goalDeadline = goalDeadline; }
    public void setExpenseTracking(String expenseTracking) { this.expenseTracking = expenseTracking; }
    public void setRetirementAge(Integer retirementAge) { this.retirementAge = retirementAge; }
    public void setDependents(Integer dependents) { this.dependents = dependents; }
    public void setFinanceSentiment(String financeSentiment) { this.financeSentiment = financeSentiment; }
    public void setHasEmergencyFund(Boolean hasEmergencyFund) { this.hasEmergencyFund = hasEmergencyFund; }
    public void setEmergencyMonths(Integer emergencyMonths) { this.emergencyMonths = emergencyMonths; }
    public void setSpendingBehaviour(String spendingBehaviour) { this.spendingBehaviour = spendingBehaviour; }
    public void setFinancialChallenges(List<String> financialChallenges) { this.financialChallenges = financialChallenges; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // Builder
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private User user;
        private String fullName;
        private List<String> appPurpose;
        private String occupation;
        private String occupationDetail;
        private String incomeSource;
        private BigDecimal monthlyIncome;
        private BigDecimal monthlySavings;
        private String payFrequency;
        private List<String> spendingCategories;
        private Boolean hasDebt;
        private String financialGoal;
        private BigDecimal goalAmount;
        private LocalDate goalDeadline;
        private String expenseTracking;
        private Integer retirementAge;
        private Integer dependents;
        private String financeSentiment;
        private Boolean hasEmergencyFund;
        private Integer emergencyMonths;
        private String spendingBehaviour;
        private List<String> financialChallenges;

        public Builder user(User user) { this.user = user; return this; }
        public Builder fullName(String fullName) { this.fullName = fullName; return this; }
        public Builder appPurpose(List<String> appPurpose) { this.appPurpose = appPurpose; return this; }
        public Builder occupation(String occupation) { this.occupation = occupation; return this; }
        public Builder occupationDetail(String occupationDetail) { this.occupationDetail = occupationDetail; return this; }
        public Builder incomeSource(String incomeSource) { this.incomeSource = incomeSource; return this; }
        public Builder monthlyIncome(BigDecimal monthlyIncome) { this.monthlyIncome = monthlyIncome; return this; }
        public Builder monthlySavings(BigDecimal monthlySavings) { this.monthlySavings = monthlySavings; return this; }
        public Builder payFrequency(String payFrequency) { this.payFrequency = payFrequency; return this; }
        public Builder spendingCategories(List<String> spendingCategories) { this.spendingCategories = spendingCategories; return this; }
        public Builder hasDebt(Boolean hasDebt) { this.hasDebt = hasDebt; return this; }
        public Builder financialGoal(String financialGoal) { this.financialGoal = financialGoal; return this; }
        public Builder goalAmount(BigDecimal goalAmount) { this.goalAmount = goalAmount; return this; }
        public Builder goalDeadline(LocalDate goalDeadline) { this.goalDeadline = goalDeadline; return this; }
        public Builder expenseTracking(String expenseTracking) { this.expenseTracking = expenseTracking; return this; }
        public Builder retirementAge(Integer retirementAge) { this.retirementAge = retirementAge; return this; }
        public Builder dependents(Integer dependents) { this.dependents = dependents; return this; }
        public Builder financeSentiment(String financeSentiment) { this.financeSentiment = financeSentiment; return this; }
        public Builder hasEmergencyFund(Boolean hasEmergencyFund) { this.hasEmergencyFund = hasEmergencyFund; return this; }
        public Builder emergencyMonths(Integer emergencyMonths) { this.emergencyMonths = emergencyMonths; return this; }
        public Builder spendingBehaviour(String spendingBehaviour) { this.spendingBehaviour = spendingBehaviour; return this; }
        public Builder financialChallenges(List<String> financialChallenges) { this.financialChallenges = financialChallenges; return this; }

        public UserAssessment build() {
            UserAssessment a = new UserAssessment();
            a.user = this.user;
            a.fullName = this.fullName;
            a.appPurpose = this.appPurpose;
            a.occupation = this.occupation;
            a.occupationDetail = this.occupationDetail;
            a.incomeSource = this.incomeSource;
            a.monthlyIncome = this.monthlyIncome;
            a.monthlySavings = this.monthlySavings;
            a.payFrequency = this.payFrequency;
            a.spendingCategories = this.spendingCategories;
            a.hasDebt = this.hasDebt;
            a.financialGoal = this.financialGoal;
            a.goalAmount = this.goalAmount;
            a.goalDeadline = this.goalDeadline;
            a.expenseTracking = this.expenseTracking;
            a.retirementAge = this.retirementAge;
            a.dependents = this.dependents;
            a.financeSentiment = this.financeSentiment;
            a.hasEmergencyFund = this.hasEmergencyFund;
            a.emergencyMonths = this.emergencyMonths;
            a.spendingBehaviour = this.spendingBehaviour;
            a.financialChallenges = this.financialChallenges;
            return a;
        }
    }
}