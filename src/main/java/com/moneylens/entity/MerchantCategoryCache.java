package com.moneylens.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "merchant_category_cache", indexes = {
        @Index(name = "idx_merchant_cache_name", columnList = "merchantKey", unique = true)
})
public class MerchantCategoryCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String merchantKey; // normalized merchant name, e.g. "ZOMATOFD"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategorySource source; // always AI here — RULE entries live in MerchantRegistry, not this table

    @Column(nullable = false)
    private Double confidence;

    @Column(nullable = false)
    private LocalDateTime lastSeen;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMerchantKey() { return merchantKey; }
    public void setMerchantKey(String merchantKey) { this.merchantKey = merchantKey; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public CategorySource getSource() { return source; }
    public void setSource(CategorySource source) { this.source = source; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}