package com.dongran.work.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxProtocolTest {
  private static final String START =
      "{\"type\":\"started\",\"backend\":\"windows-appcontainer\",\"pid\":123}\n";
  private static final String OUTPUT =
      "{\"type\":\"output\",\"stream\":\"stdout\",\"text\":\"中文 output\\n\"}\n";
  private static final String ERROR = "{\"type\":\"error\",\"message\":\"startup failed\"}\n";
  private static final String COMPLETED =
      "{\"type\":\"exit\",\"status\":\"completed\",\"exitCode\":0}\n";
  private static final String FAILED =
      "{\"type\":\"exit\",\"status\":\"failed\",\"exitCode\":125}\n";
  private final SandboxExecutor executor = new SandboxExecutor(new ObjectMapper(), "");

  private SandboxExecutor.Result consume(String protocol) throws IOException {
    return executor.consume(bytes(protocol), ignored -> {}, ignored -> {});
  }

  private static ByteArrayInputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private void rejects(String protocol) {
    assertThrows(IOException.class, () -> consume(protocol));
  }

  @Test
  void acceptsStartedOutputAndSingleSuccessfulExit() throws IOException {
    var output = new ArrayList<String>();
    var started = new ArrayList<String>();
    var result = executor.consume(bytes(START + OUTPUT + COMPLETED), output::add, started::add);
    assertEquals(new SandboxExecutor.Result("completed", 0), result);
    assertEquals(List.of("中文 output\n"), output);
    assertEquals(List.of("windows-appcontainer"), started);
  }

  @Test
  void acceptsStartupErrorBeforeStartedWithFailedExit() throws IOException {
    var started = new ArrayList<String>();
    var result = executor.consume(bytes(ERROR + FAILED), ignored -> {}, started::add);
    assertEquals(new SandboxExecutor.Result("failed", 125), result);
    assertTrue(started.isEmpty());
  }

  @Test
  void acceptsFailureTimeoutAndCancellationAfterStarted() throws IOException {
    for (String status : List.of("failed", "timed_out", "cancelled")) {
      var result =
          consume(START + "{\"type\":\"exit\",\"status\":\"" + status + "\",\"exitCode\":130}\n");
      assertEquals(status, result.status());
    }
  }

  @Test
  void allowsPendingOutputAfterErrorButRequiresFailedExit() throws IOException {
    assertEquals(
        new SandboxExecutor.Result("failed", 125), consume(START + ERROR + OUTPUT + FAILED));
    rejects(START + ERROR + COMPLETED);
    rejects(ERROR + COMPLETED);
    rejects(START + ERROR + "{\"type\":\"exit\",\"status\":\"cancelled\",\"exitCode\":130}\n");
  }

  @Test
  void rejectsMissingOrRepeatedStartedEvents() {
    rejects(COMPLETED);
    rejects(FAILED);
    rejects(OUTPUT + COMPLETED);
    rejects(START + START + COMPLETED);
    rejects(ERROR + START + FAILED);
    rejects("{\"type\":\"started\"}\n" + COMPLETED);
  }

  @Test
  void rejectsAllMessagesAfterExitIncludingDuplicateExit() {
    for (String trailing : List.of(COMPLETED, FAILED, START, OUTPUT, ERROR, "\n")) {
      rejects(START + COMPLETED + trailing);
    }
  }

  @Test
  void requiresAnExitEvenAfterError() {
    for (String incomplete : List.of("", START, START + OUTPUT, ERROR, START + ERROR)) {
      rejects(incomplete);
    }
  }

  @Test
  void rejectsMalformedNonObjectAndConcatenatedJson() {
    for (String malformed :
        List.of(
            "null\n",
            "[]\n",
            "\"text\"\n",
            "{\n",
            "\n",
            "{}{}\n",
            "{\"type\":\"started\",\"backend\":\"native\"} null\n")) {
      rejects(malformed + COMPLETED);
    }
    rejects(START + "{\"type\":null}\n" + COMPLETED);
    rejects(START + "{\"type\":\"unknown\"}\n" + COMPLETED);
    rejects(START + "{\"type\":\"output\",\"text\":{}}\n" + COMPLETED);
    rejects("{\"type\":\"error\",\"message\":null}\n" + FAILED);
  }

  @Test
  void rejectsInvalidOrMissingExitCodesAndStatuses() {
    for (String code : List.of("null", "\"0\"", "0.5", "2147483648")) {
      rejects(START + "{\"type\":\"exit\",\"status\":\"completed\",\"exitCode\":" + code + "}\n");
    }
    rejects(START + "{\"type\":\"exit\",\"status\":\"completed\"}\n");
    rejects(START + "{\"type\":\"exit\",\"status\":\"completed\",\"exitCode\":1}\n");
    rejects(START + "{\"type\":\"exit\",\"status\":\"unknown\",\"exitCode\":0}\n");
  }

  @Test
  void rejectsValidJsonWithoutFinalNewline() {
    rejects(START + COMPLETED.stripTrailing());
    assertThrows(IOException.class, () -> SandboxExecutor.readLine(bytes("{}"), 4));
  }

  @Test
  void rejectsMalformedUtf8InsteadOfReplacingBytes() {
    byte[] invalid = {(byte) 0xc3, (byte) 0x28, (byte) '\n'};
    assertThrows(
        IOException.class, () -> SandboxExecutor.readLine(new ByteArrayInputStream(invalid), 4));
  }

  @Test
  void enforcesLineByteLimitAndAllowsExactBoundary() throws IOException {
    assertEquals("{}", SandboxExecutor.readLine(bytes("{}\n"), 2));
    assertEquals("{}\r", SandboxExecutor.readLine(bytes("{}\r\n"), 3));
    assertNull(SandboxExecutor.readLine(bytes(""), 2));
    assertThrows(IOException.class, () -> SandboxExecutor.readLine(bytes("{} \n"), 2));
    rejects(START + " ".repeat(65537) + "\n");
  }
}
