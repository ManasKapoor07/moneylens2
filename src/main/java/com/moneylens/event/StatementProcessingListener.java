package com.moneylens.event;

import com.moneylens.categorization.TransactionCategorizationService;
import com.moneylens.entity.BankStatement;
import com.moneylens.entity.Transaction;
import com.moneylens.repository.BankStatementRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.service.OverallProfileService;
import com.moneylens.service.StatementProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
public class StatementProcessingListener {

    private static final Logger log = LoggerFactory.getLogger(StatementProcessingListener.class);

    private final TransactionRepository           transactionRepository;
    private final BankStatementRepository         statementRepository;
    private final TransactionCategorizationService categorizationService;
    private final StatementProfileService         statementProfileService;
    private final OverallProfileService           overallProfileService;

    public StatementProcessingListener(
            TransactionRepository transactionRepository,
            BankStatementRepository statementRepository,
            TransactionCategorizationService categorizationService,
            StatementProfileService statementProfileService,
            OverallProfileService overallProfileService) {

        this.transactionRepository  = transactionRepository;
        this.statementRepository    = statementRepository;
        this.categorizationService  = categorizationService;
        this.statementProfileService = statementProfileService;
        this.overallProfileService   = overallProfileService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStatementSaved(StatementSavedEvent event) {
        Long statementId = event.getStatementId();
        Long userId      = event.getUserId();
        log.info("[async] Starting background processing for statement {}", statementId);

        BankStatement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new RuntimeException("Statement not found: " + statementId));

        try {
            // ── Categorize ────────────────────────────────────────────────────
            List<Transaction> transactions =
                    transactionRepository.findAllById(event.getTransactionIds());

            categorizationService.categorize(transactions);
            transactionRepository.saveAll(transactions);

            log.info("[async] Categorized {} transactions for statement {}",
                    transactions.size(), statementId);

            // ── Build monthly profiles ────────────────────────────────────────
            // Accessing getUser() here is safe — we're inside a new transaction
            int profiles = statementProfileService.buildAndSaveAll(
                    statement, statement.getUser(), transactions);

            log.info("[async] Built {} monthly profiles for statement {}", profiles, statementId);

            // ── Refresh overall profile ───────────────────────────────────────
            overallProfileService.refresh(userId);

            // ── Mark done ─────────────────────────────────────────────────────
            statement.setStatus(BankStatement.StatementStatus.PROCESSED);
            statementRepository.save(statement);

            log.info("[async] Statement {} marked PROCESSED", statementId);

        } catch (Exception e) {
            log.error("[async] Processing failed for statement {}: {}", statementId, e.getMessage(), e);
            statement.setStatus(BankStatement.StatementStatus.FAILED);
            statement.setErrorMessage(e.getMessage());
            statementRepository.save(statement);
        }
    }
}
