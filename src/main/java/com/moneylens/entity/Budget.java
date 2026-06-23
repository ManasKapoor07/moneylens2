package com.moneylens.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "budgets")
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "total_budget", nullable = false)
    private BigDecimal totalBudget;

    @Column(name = "savings_target")
    private BigDecimal savingsTarget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_budgets", columnDefinition = "jsonb", nullable = false)
    private String categoryBudgetsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_buckets", columnDefinition = "jsonb")
    private String categoryBucketsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasoning", columnDefinition = "jsonb")
    private String reasoningJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_category_budgets", columnDefinition = "jsonb")
    private String previousCategoryBudgetsJson;

    @Column(name = "previous_total_budget")
    private BigDecimal previousTotalBudget;

    @Column(name = "previous_savings_target")
    private BigDecimal previousSavingsTarget;

    @Column(name = "last_diff_summary")
    private String lastDiffSummary;

    public enum Source { AUTO, AI_REFINED, USER_ADJUSTED }

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private Source source = Source.AUTO;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public BigDecimal getTotalBudget() { return totalBudget; }
    public void setTotalBudget(BigDecimal totalBudget) { this.totalBudget = totalBudget; }
    public BigDecimal getSavingsTarget() { return savingsTarget; }
    public void setSavingsTarget(BigDecimal savingsTarget) { this.savingsTarget = savingsTarget; }
    public String getCategoryBudgetsJson() { return categoryBudgetsJson; }
    public void setCategoryBudgetsJson(String categoryBudgetsJson) { this.categoryBudgetsJson = categoryBudgetsJson; }
    public String getCategoryBucketsJson() { return categoryBucketsJson; }
    public void setCategoryBucketsJson(String categoryBucketsJson) { this.categoryBucketsJson = categoryBucketsJson; }
    public String getReasoningJson() { return reasoningJson; }
    public void setReasoningJson(String reasoningJson) { this.reasoningJson = reasoningJson; }
    public String getPreviousCategoryBudgetsJson() { return previousCategoryBudgetsJson; }
    public void setPreviousCategoryBudgetsJson(String v) { this.previousCategoryBudgetsJson = v; }
    public BigDecimal getPreviousTotalBudget() { return previousTotalBudget; }
    public void setPreviousTotalBudget(BigDecimal v) { this.previousTotalBudget = v; }
    public BigDecimal getPreviousSavingsTarget() { return previousSavingsTarget; }
    public void setPreviousSavingsTarget(BigDecimal v) { this.previousSavingsTarget = v; }
    public String getLastDiffSummary() { return lastDiffSummary; }
    public void setLastDiffSummary(String v) { this.lastDiffSummary = v; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}