package com.moneylens.dto;

import java.util.List;

public class AssessmentDto {

    public static class CommittedExpense {
        public String name;
        public double amount;
        public CommittedExpense() {}
    }

    // ── Request ──────────────────────────────────────────────────────────────

    public static class SaveAssessmentRequest {
        private String fullName;
        private List<String> appPurpose;
        private String occupation;
        private String occupationDetail;
        private String incomeSource;
        private Double monthlyIncome;
        private Double monthlySavings;
        private String payFrequency;
        private List<String> spendingCategories;
        private Boolean hasDebt;
        private String financialGoal;
        private Double goalAmount;
        private String goalDeadline;
        private String expenseTracking;
        private Integer retirementAge;
        private Integer dependents;
        private String financeSentiment;
        private Boolean hasEmergencyFund;
        private Integer emergencyMonths;
        private String spendingBehaviour;
        private List<String> financialChallenges;
        private List<CommittedExpense> committedExpenses;

        public SaveAssessmentRequest() {}

        // Getters
        public String getFullName() { return fullName; }
        public List<String> getAppPurpose() { return appPurpose; }
        public String getOccupation() { return occupation; }
        public String getOccupationDetail() { return occupationDetail; }
        public String getIncomeSource() { return incomeSource; }
        public Double getMonthlyIncome() { return monthlyIncome; }
        public Double getMonthlySavings() { return monthlySavings; }
        public String getPayFrequency() { return payFrequency; }
        public List<String> getSpendingCategories() { return spendingCategories; }
        public Boolean getHasDebt() { return hasDebt; }
        public String getFinancialGoal() { return financialGoal; }
        public Double getGoalAmount() { return goalAmount; }
        public String getGoalDeadline() { return goalDeadline; }
        public String getExpenseTracking() { return expenseTracking; }
        public Integer getRetirementAge() { return retirementAge; }
        public Integer getDependents() { return dependents; }
        public String getFinanceSentiment() { return financeSentiment; }
        public Boolean getHasEmergencyFund() { return hasEmergencyFund; }
        public Integer getEmergencyMonths() { return emergencyMonths; }
        public String getSpendingBehaviour() { return spendingBehaviour; }
        public List<String> getFinancialChallenges() { return financialChallenges; }
        public List<CommittedExpense> getCommittedExpenses() { return committedExpenses; }

        // Setters
        public void setFullName(String fullName) { this.fullName = fullName; }
        public void setAppPurpose(List<String> appPurpose) { this.appPurpose = appPurpose; }
        public void setOccupation(String occupation) { this.occupation = occupation; }
        public void setOccupationDetail(String occupationDetail) { this.occupationDetail = occupationDetail; }
        public void setIncomeSource(String incomeSource) { this.incomeSource = incomeSource; }
        public void setMonthlyIncome(Double monthlyIncome) { this.monthlyIncome = monthlyIncome; }
        public void setMonthlySavings(Double monthlySavings) { this.monthlySavings = monthlySavings; }
        public void setPayFrequency(String payFrequency) { this.payFrequency = payFrequency; }
        public void setSpendingCategories(List<String> spendingCategories) { this.spendingCategories = spendingCategories; }
        public void setHasDebt(Boolean hasDebt) { this.hasDebt = hasDebt; }
        public void setFinancialGoal(String financialGoal) { this.financialGoal = financialGoal; }
        public void setGoalAmount(Double goalAmount) { this.goalAmount = goalAmount; }
        public void setGoalDeadline(String goalDeadline) { this.goalDeadline = goalDeadline; }
        public void setExpenseTracking(String expenseTracking) { this.expenseTracking = expenseTracking; }
        public void setRetirementAge(Integer retirementAge) { this.retirementAge = retirementAge; }
        public void setDependents(Integer dependents) { this.dependents = dependents; }
        public void setFinanceSentiment(String financeSentiment) { this.financeSentiment = financeSentiment; }
        public void setHasEmergencyFund(Boolean hasEmergencyFund) { this.hasEmergencyFund = hasEmergencyFund; }
        public void setEmergencyMonths(Integer emergencyMonths) { this.emergencyMonths = emergencyMonths; }
        public void setSpendingBehaviour(String spendingBehaviour) { this.spendingBehaviour = spendingBehaviour; }
        public void setFinancialChallenges(List<String> financialChallenges) { this.financialChallenges = financialChallenges; }
        public void setCommittedExpenses(List<CommittedExpense> committedExpenses) { this.committedExpenses = committedExpenses; }
    }

    // ── Response ─────────────────────────────────────────────────────────────

    public static class AssessmentResponse {
        private Long assessmentId;
        private String message;

        public AssessmentResponse(Long assessmentId, String message) {
            this.assessmentId = assessmentId;
            this.message = message;
        }

        public Long getAssessmentId() { return assessmentId; }
        public String getMessage() { return message; }
    }
}