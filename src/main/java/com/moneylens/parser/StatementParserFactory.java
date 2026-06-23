package com.moneylens.parser;

import com.moneylens.exception.UnsupportedBankException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StatementParserFactory {

    private final List<BankStatementParser> parsers;

    public StatementParserFactory() {
        this.parsers = List.of(
                new HdfcStatementParser(),
                new SbiStatementParser(),
                new IciciStatementParser(),
                new AxisBankStatementParser(),
                new BobStatementParser(),
                new PnbStatementParser()
        );
    }

    /** Auto-detect parser from PDF text (fallback when no bank is explicitly selected). */
    public BankStatementParser getParser(String rawText) {
        return parsers.stream()
                .filter(p -> p.supports(rawText))
                .findFirst()
                .orElseThrow(() -> new UnsupportedBankException(
                        "Could not detect the bank format in this PDF. " +
                                "Supported banks: HDFC, SBI, ICICI, Axis Bank, BOB, PNB. " +
                                "Please download the statement directly from your bank's net banking portal."
                ));
    }

    /** Return the parser for an explicitly user-selected bank name. */
    public BankStatementParser getParserByBank(String bankName) {
        String lower = bankName.toLowerCase();
        if (lower.contains("hdfc"))                                 return new HdfcStatementParser();
        if (lower.contains("sbi") || lower.contains("state bank")) return new SbiStatementParser();
        if (lower.contains("icici"))                               return new IciciStatementParser();
        if (lower.contains("axis"))                                return new AxisBankStatementParser();
        if (lower.contains("bob") || lower.contains("baroda"))     return new BobStatementParser();
        if (lower.contains("pnb") || lower.contains("punjab"))     return new PnbStatementParser();
        throw new UnsupportedBankException(
                "Unsupported bank: " + bankName +
                ". Supported banks: HDFC, SBI, ICICI, Axis Bank, BOB, PNB.");
    }
}