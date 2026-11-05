package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
  @TempDir Path temp;

  private int run(String... args) {
    return Main.run(
        args,
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(new ByteArrayOutputStream()));
  }

  @Test
  void rejectsUnknownDuplicateAndMissingOptions() {
    assertEquals(1, run("compare", "--secret", "x"));
    assertEquals(1, run("compare", "--baseline", "x", "--baseline", "y"));
    assertEquals(1, run("compare", "--baseline"));
    assertEquals(1, run("mcp", "--gate"));
  }

  @Test
  void outputIsNeverOverwritten() throws Exception {
    Path existing = Files.createDirectory(temp.resolve("output"));
    Path marker = Files.writeString(existing.resolve("keep.txt"), "preserve me");
    assertThrows(IOException.class, () -> Main.newDirectory(existing));
    assertEquals("preserve me", Files.readString(marker));
  }

  @Test
  void consumersRejectMalformedAndDuplicateDeclarations() throws Exception {
    Path file = temp.resolve("consumers.json");
    Files.writeString(
        file,
        "{\"schemaVersion\":1,\"consumers\":[{\"name\":\"agent\",\"operations\":[\"GET"
            + " /orders\",\"GET /orders\"]}]}");
    assertThrows(IllegalArgumentException.class, () -> Main.consumers(file));
    Files.writeString(
        file,
        "{\"schemaVersion\":1,\"consumers\":[{\"name\":\"agent\",\"operations\":[\"GET"
            + " /orders\"],\"token\":\"hidden\"}]}");
    assertThrows(IllegalArgumentException.class, () -> Main.consumers(file));
  }

  @Test
  void reportEscapesUntrustedContractValues() throws Exception {
    String payload = "<img src=x onerror=alert(1)>";
    Finding finding =
        new Finding(
            "safe-id",
            "test",
            Severity.REVIEW,
            "GET /x",
            Direction.REQUEST,
            "/x",
            "/x",
            Json.read("\"<script>bad</script>\""),
            null,
            payload,
            payload,
            null,
            List.of(payload));
    Report report =
        new Report(
            1,
            "0.1.0",
            "REVIEW",
            new Source(payload, "hash", "3.1.0"),
            new Source("new", "hash", "3.1.0"),
            1,
            Map.of("REVIEW", 1),
            List.of(finding),
            List.of(),
            List.of(payload));
    String html = ReportWriter.html(report);
    assertFalse(html.contains(payload));
    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;img"));
    assertTrue(html.contains("default-src 'none'"));
    assertFalse(ReportWriter.markdown(report).contains(payload));
  }

  @Test
  void verdictCodesDistinguishManualReview() {
    assertEquals(0, Main.gateCode("COMPATIBLE"));
    assertEquals(2, Main.gateCode("BREAKING"));
    assertEquals(3, Main.gateCode("REVIEW"));
  }

  @Test
  void demoExercisesAllThreeDecisions() {
    assertEquals(0, run("demo", "--out", temp.resolve("demo").toString()));
    assertTrue(Files.isRegularFile(temp.resolve("demo/breaking/report.html")));
    assertEquals(1, run("demo", "--out", temp.resolve("demo").toString()));
  }
}
