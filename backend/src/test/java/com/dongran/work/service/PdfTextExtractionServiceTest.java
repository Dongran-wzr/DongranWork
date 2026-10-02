package com.dongran.work.service;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;

class PdfTextExtractionServiceTest {
  byte[] fixture(boolean broken) throws Exception {
    try (var pdf = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      var page = new PDPage();
      pdf.addPage(page);
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (var content = new PDPageContentStream(pdf, page)) {
        content.beginText();
        content.setFont(font, 12);
        content.newLineAtOffset(50, 700);
        content.showText("AAA ! # $ % & + / = 123");
        content.endText();
      }
      if (broken) {
        var encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
        var differences = new COSArray();
        differences.add(COSInteger.get(65));
        differences.add(COSName.getPDFName("G41"));
        encoding.setItem(COSName.DIFFERENCES, differences);
        font.getCOSObject().setItem(COSName.ENCODING, encoding);
      }
      pdf.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void validPunctuationAndMathArePreserved() throws Exception {
    var result = new PdfTextExtractionService().extract(fixture(false));
    assertThat(result.status()).isEqualTo("ready");
    assertThat(result.content()).contains("AAA ! # $ % & + / = 123");
  }

  @Test
  void unknownGlyphNamesAreNotIndexedAsAscii() throws Exception {
    var result = new PdfTextExtractionService().extract(fixture(true));
    assertThat(result.status()).isEqualTo("needs_ocr");
    assertThat(result.content()).isEmpty();
    assertThat(result.warning()).contains("字形", "OCR");
  }
}
