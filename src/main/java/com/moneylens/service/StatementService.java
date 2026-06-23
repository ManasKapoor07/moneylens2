package com.moneylens.service;

import com.moneylens.dto.ParsedTransaction;
import com.moneylens.dto.StatementParseResult;
import com.moneylens.dto.StatementUploadResponse;
import com.moneylens.entity.BankStatement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.User;
import com.moneylens.event.StatementSavedEvent;
import com.moneylens.exception.DuplicateStatementException;
import com.moneylens.parser.BankStatementParser;
import com.moneylens.parser.StatementParserFactory;
import com.moneylens.repository.BankStatementRepository;
import com.moneylens.repository.TransactionRepository;
import com.moneylens.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    private final PdfTextExtractor                pdfExtractor;
    private final StatementParserFactory          parserFactory;
    private final TransactionRepository           transactionRepository;
    private final BankStatementRepository         statementRepository;
    private final UserRepository                  userRepository;
    private final ApplicationEventPublisher       eventPublisher;

    public StatementService(
            PdfTextExtractor pdfExtractor,
            StatementParserFactory parserFactory,
            TransactionRepository transactionRepository,
            BankStatementRepository statementRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher) {

        this.pdfExtractor          = pdfExtractor;
        this.parserFactory         = parserFactory;
        this.transactionRepository = transactionRepository;
        this.statementRepository   = statementRepository;
        this.userRepository        = userRepository;
        this.eventPublisher        = eventPublisher;
    }

    @Transactional
    public StatementUploadResponse uploadStatement(String phoneNumber,
                                                   MultipartFile file,
                                                   String password,
                                                   String bank) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // ── Step 1: Extract text ──────────────────────────────────────────────
        log.info("Extracting PDF for user {}", user.getId());
        String rawText;
        try {
            rawText = pdfExtractor.extract(file.getInputStream(), password);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read PDF: " + e.getMessage(), e);
        }

        // ── Step 2: Parse ─────────────────────────────────────────────────────
        BankStatementParser parser = (bank != null && !bank.isBlank())
                ? parserFactory.getParserByBank(bank)
                : parserFactory.getParser(rawText);
        StatementParseResult parseResult = parser.parse(rawText);

        log.info("Parsed {} transactions from {} ({} to {}) — {} month(s)",
                parseResult.getTransactions().size(),
                parseResult.getBankName(),
                parseResult.getStatementFromDate(),
                parseResult.getStatementToDate(),
                parseResult.getMonthsSpanned());

        // Bail early if the parser couldn't identify the statement period.
        // This usually means the wrong parser was selected or the PDF is malformed.
        if (parseResult.getStatementFromDate() == null || parseResult.getStatementToDate() == null) {
            throw new RuntimeException(
                    "Could not detect the statement period in this " + parseResult.getBankName() +
                    " PDF. Make sure you download the statement directly from your bank's " +
                    "net banking portal (not the mobile app), and that it is not a passbook or mini-statement PDF.");
        }

        if (parseResult.getTransactions().isEmpty()) {
            throw new RuntimeException(
                    "No transactions found in this " + parseResult.getBankName() + " statement. " +
                    "The PDF may be a summary, passbook, or unsupported format. " +
                    "Please download a full account statement from net banking.");
        }

        // ── Step 3: Exact duplicate check ─────────────────────────────────────
        boolean exactDuplicate = statementRepository
                .existsByUserIdAndBankNameAndStatementFromDateAndStatementToDate(
                        user.getId(),
                        parseResult.getBankName(),
                        parseResult.getStatementFromDate(),
                        parseResult.getStatementToDate());

        if (exactDuplicate) {
            throw new DuplicateStatementException(
                    String.format("%s statement %s – %s already uploaded.",
                            parseResult.getBankName(),
                            parseResult.getStatementFromDate(),
                            parseResult.getStatementToDate()));
        }

        // Warn on overlap — tx dedup handles actual duplicates
        boolean overlaps = statementRepository.existsOverlappingPeriod(
                user.getId(), parseResult.getBankName(),
                parseResult.getStatementFromDate(), parseResult.getStatementToDate());
        if (overlaps) {
            log.warn("Overlapping period detected for user={} — tx dedup will handle duplicates",
                    user.getId());
        }

        // ── Step 4a: Dedup within parsed list ─────────────────────────────────
        Set<String> seenRefs = new HashSet<>();
        List<ParsedTransaction> uniqueParsed = new ArrayList<>();
        for (ParsedTransaction tx : parseResult.getTransactions()) {
            String ref = tx.getReferenceNumber();
            boolean hasNoRef = ref == null || ref.isBlank()
                    || ref.equals("000000000000000")
                    || ref.equals("0000000000000000");
            if (hasNoRef) uniqueParsed.add(tx);
            else if (seenRefs.add(ref)) uniqueParsed.add(tx);
            else log.info("Skipped intra-statement duplicate ref: {}", ref);
        }

        // ── Step 4b: Dedup against DB ─────────────────────────────────────────
        List<String> refsToCheck = uniqueParsed.stream()
                .map(ParsedTransaction::getReferenceNumber)
                .filter(ref -> ref != null && !ref.isBlank())
                .collect(Collectors.toList());

        Set<String> existingRefs = refsToCheck.isEmpty()
                ? Collections.emptySet()
                : transactionRepository.findExistingReferenceNumbers(user.getId(), refsToCheck);

        List<ParsedTransaction> newParsed = uniqueParsed.stream()
                .filter(tx -> tx.getReferenceNumber() == null
                        || !existingRefs.contains(tx.getReferenceNumber()))
                .collect(Collectors.toList());

        int skipped = parseResult.getTransactions().size() - newParsed.size();
        if (skipped > 0) log.info("Skipped {} duplicate transactions", skipped);

        // ── Step 5: Save BankStatement ────────────────────────────────────────
        BankStatement statement = new BankStatement();
        statement.setUser(user);
        statement.setBankName(parseResult.getBankName());
        statement.setStatementFromDate(parseResult.getStatementFromDate());
        statement.setStatementToDate(parseResult.getStatementToDate());
        statement.setStatus(BankStatement.StatementStatus.PROCESSING);
        statement.setRawTransactionCount(parseResult.getTransactions().size());
        statement.setParsedTransactionCount(newParsed.size());
        statement.setOpeningBalance(parseResult.getOpeningBalance());
        statement.setClosingBalance(parseResult.getClosingBalance());
        statement.setTotalDebits(parseResult.getTotalDebits());
        statement.setTotalCredits(parseResult.getTotalCredits());
        statement.setParsedAt(LocalDateTime.now());
        statementRepository.save(statement);

        // ── Step 6: Build Transaction entities ────────────────────────────────
        List<Transaction> entities = new ArrayList<>();
        for (ParsedTransaction pt : newParsed) {
            Transaction tx = new Transaction();
            tx.setUser(user);
            tx.setDate(pt.getDate());
            tx.setValueDate(pt.getValueDate());
            tx.setRawNarration(pt.getRawNarration());
            tx.setReferenceNumber(pt.getReferenceNumber());
            tx.setWithdrawalAmount(pt.getWithdrawalAmount());
            tx.setDepositAmount(pt.getDepositAmount());
            tx.setClosingBalance(pt.getClosingBalance());
            tx.setType(pt.getType());
            tx.setMode(pt.getMode());
            tx.setUpiHandle(pt.getUpiHandle());
            tx.setCounterpartyName(pt.getCounterpartyName());
            tx.setCounterpartyPhone(pt.getCounterpartyPhone());
            tx.setRefund(pt.isRefund());
            tx.setMerchantName(pt.getMerchantName());
            tx.setBankName(parseResult.getBankName());
            tx.setStatementYear(pt.getStatementYear());   // from tx's own date
            tx.setStatementMonth(pt.getStatementMonth()); // from tx's own date
            entities.add(tx);
        }

        // ── Step 7: Save transactions ─────────────────────────────────────────
        List<Transaction> saved = transactionRepository.saveAll(entities);

        log.info("Saved {} transactions for user {} — queuing background processing",
                saved.size(), user.getId());

        // ── Step 8: Publish event (processed AFTER_COMMIT in async thread) ────
        List<Long> txIds = saved.stream().map(Transaction::getId).collect(Collectors.toList());
        eventPublisher.publishEvent(new StatementSavedEvent(statement.getId(), user.getId(), txIds));

        // ── Step 9: Return immediately ────────────────────────────────────────
        return new StatementUploadResponse(
                parseResult.getBankName(),
                parseResult.getStatementFromDate(),
                parseResult.getStatementToDate(),
                parseResult.getMonthsSpanned(),
                parseResult.getTransactions().size(),
                newParsed.size(),
                skipped,
                parseResult.getOpeningBalance(),
                parseResult.getClosingBalance(),
                parseResult.getTotalDebits(),
                parseResult.getTotalCredits(),
                0
        );
    }

    public List<BankStatement> getStatements(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return statementRepository.findByUserIdOrderByStatementFromDateDesc(user.getId());
    }

    public List<Transaction> getTransactions(String phoneNumber,
                                             Integer year,
                                             Integer month) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (year != null && month != null) {
            return transactionRepository
                    .findByUserIdAndStatementYearAndStatementMonth(user.getId(), year, month);
        }
        return transactionRepository.findByUserIdOrderByDateDesc(user.getId());
    }
}