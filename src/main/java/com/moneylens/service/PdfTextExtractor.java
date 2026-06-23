package com.moneylens.service;

import com.moneylens.exception.PdfExtractionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Extracts raw text from PDF files in memory.
 * Supports password-protected PDFs.
 * No files are written to disk.
 */
@Component
public class PdfTextExtractor {

    /**
     * Extract all text from a PDF input stream.
     *
     * @param pdfStream  The PDF file input stream
     * @param password   Optional password for encrypted PDFs (null or empty = no password)
     * @return           Full extracted text
     */
    public String extract(InputStream pdfStream, String password) {
        try {
            PDDocument document = loadDocument(pdfStream, password);
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true); // critical for table alignment
                return stripper.getText(document);
            } finally {
                document.close();
            }
        } catch (InvalidPasswordException e) {
            throw new PdfExtractionException(
                    "PDF is password protected. Please provide the correct password. " +
                            "For HDFC statements, this is usually your date of birth (DDMMYYYY) " +
                            "or the last 4 digits of your registered mobile number."
            );
        } catch (IOException e) {
            throw new PdfExtractionException("Failed to read PDF: " + e.getMessage());
        }
    }

    private PDDocument loadDocument(InputStream stream, String password) throws IOException {
        // PDFBox 3.x: Loader.loadPDF() requires byte[] or File, not InputStream
        byte[] bytes = stream.readAllBytes();
        if (password != null && !password.isBlank()) {
            return Loader.loadPDF(bytes, password);
        }
        return Loader.loadPDF(bytes);
    }
}