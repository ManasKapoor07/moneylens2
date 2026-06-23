package com.moneylens.dto;

import com.moneylens.entity.Category;
import jakarta.validation.constraints.NotNull;

public class CategoryUpdateRequest {

    @NotNull(message = "Category must not be null")
    private Category category;

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
}