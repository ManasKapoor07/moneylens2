package com.moneylens.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "financial_goals")
public class FinancialGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    private String emoji;

    @Column(name = "target_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal targetAmount;

    @Column(name = "saved_amount", precision = 15, scale = 2)
    private BigDecimal savedAmount = BigDecimal.ZERO;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "frequency", length = 10)
    private String frequency = "MONTHLY"; // MONTHLY | WEEKLY

    @Column(name = "ai_plan", columnDefinition = "TEXT")
    private String aiPlan; // JSON blob

    @Column(name = "status", length = 20)
    private String status = "ACTIVE"; // ACTIVE | COMPLETED | PAUSED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── getters / setters ─────────────────────────────────────────────────────

    public Long getId()                          { return id; }

    public User getUser()                        { return user; }
    public void setUser(User v)                  { this.user = v; }

    public String getTitle()                     { return title; }
    public void setTitle(String v)               { this.title = v; }

    public String getEmoji()                     { return emoji; }
    public void setEmoji(String v)               { this.emoji = v; }

    public BigDecimal getTargetAmount()          { return targetAmount; }
    public void setTargetAmount(BigDecimal v)    { this.targetAmount = v; }

    public BigDecimal getSavedAmount()           { return savedAmount; }
    public void setSavedAmount(BigDecimal v)     { this.savedAmount = v; }

    public LocalDate getTargetDate()             { return targetDate; }
    public void setTargetDate(LocalDate v)       { this.targetDate = v; }

    public String getFrequency()                 { return frequency; }
    public void setFrequency(String v)           { this.frequency = v; }

    public String getAiPlan()                    { return aiPlan; }
    public void setAiPlan(String v)              { this.aiPlan = v; }

    public String getStatus()                    { return status; }
    public void setStatus(String v)              { this.status = v; }

    public LocalDateTime getCreatedAt()          { return createdAt; }
    public LocalDateTime getUpdatedAt()          { return updatedAt; }
}
