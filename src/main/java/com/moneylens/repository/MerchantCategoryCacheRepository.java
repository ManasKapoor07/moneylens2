package com.moneylens.repository;

import com.moneylens.entity.MerchantCategoryCache;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantCategoryCacheRepository extends JpaRepository<MerchantCategoryCache, Long> {
    Optional<MerchantCategoryCache> findByMerchantKey(String merchantKey);
}