package com.moneylens.parser;

import com.moneylens.dto.StatementParseResult;

public interface BankStatementParser {
    /** Returns true if this parser can handle the given raw PDF text */
    boolean supports(String rawText);

    /** Parse the raw text and return structured result */
    StatementParseResult parse(String rawText);
}