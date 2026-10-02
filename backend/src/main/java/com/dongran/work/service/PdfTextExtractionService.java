package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.springframework.stereotype.Service;

@Service
public class PdfTextExtractionService {
  public record Result(String content, String status, String warning) {}

  public Result extract(byte[] bytes) {
    try (var pdf = Loader.loadPDF(bytes)) {
      if (pdf.getNumberOfPages() > 500) throw ApiException.bad("PDF 最多支持 500 页。");
      var stripper = new CheckedStripper();
      stripper.setSortByPosition(true);
      String content = stripper.getText(pdf);
      if (content.length() > 1_000_000) throw ApiException.bad("提取文本超过 100 万字符。");
      if (stripper.unmapped > 0)
        return new Result(
            "",
            "needs_ocr",
            "PDF 中有 "
                + stripper.unmapped
                + " 个字形无法映射为文字，已停止文本索引以避免乱码。原文件可正常预览；需要 OCR 识别后才能检索，当前尚未接入 OCR。");
      if (content.isBlank())
        return new Result("", "needs_ocr", "该 PDF 没有可提取的文本层，可能是扫描件。需要 OCR；当前尚未接入 OCR。");
      return new Result(content, "ready", "");
    } catch (IOException e) {
      throw ApiException.bad("PDF 无法解析，请检查是否损坏或需要密码。");
    }
  }

  private static final class CheckedStripper extends PDFTextStripper {
    int unmapped;

    CheckedStripper() throws IOException {
      super();
    }

    @Override
    protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement)
        throws IOException {
      String unicode = font.toUnicode(code);
      if (unicode == null || unicode.isEmpty() || unicode.indexOf('\ufffd') >= 0) {
        unmapped++;
        return;
      }
      super.showGlyph(matrix, font, code, displacement);
    }
  }
}
