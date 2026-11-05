package io.github.almeida96.contractlens;

import static io.github.almeida96.contractlens.Model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ReportWriter {
  private ReportWriter() {}

  public static void write(Report report, Path directory) throws IOException {
    writeFile(directory.resolve("report.json"), Json.pretty(report));
    writeFile(directory.resolve("report.md"), markdown(report));
    writeFile(directory.resolve("report.html"), html(report));
  }

  private static void writeFile(Path path, String value) throws IOException {
    Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  public static String html(Report r) {
    StringBuilder b =
        new StringBuilder(
            """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
            <title>ContractLens · API migration review</title>
            <style>
            :root{color-scheme:light;--ink:#172c3b;--muted:#536473;--paper:#f4f5f1;--line:#d5ddd9}
            *{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.55 system-ui,sans-serif}
            main{max-width:1200px;margin:auto;padding:38px 32px}header{background:#172c3b;color:#fff;padding:32px;border-radius:8px}
            .eyebrow{text-transform:uppercase;letter-spacing:2px;font-size:12px;font-weight:700;color:#acd1c9}h1{font-size:40px;letter-spacing:-1.5px;margin:8px 0}h2{font-size:23px;margin:0 0 14px}h3{font-size:19px;margin:8px 0}p{margin:8px 0}.subtitle{color:#d1dee3;max-width:850px}
            .verdict{display:inline-block;border:1px solid #789291;padding:5px 12px;font-weight:700;border-radius:4px;margin-top:16px}
            .stats{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;background:var(--line);border:1px solid var(--line);margin:22px 0}.stat{padding:18px;background:white}.stat strong{display:block;font-size:30px}.stat span{font-size:13px;color:var(--muted)}
            .sources{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin:20px 0}.source{font-size:13px;min-width:0}.source code{word-break:break-all;color:var(--muted)}
            article{background:white;border:1px solid var(--line);border-left:4px solid #8d553f;border-radius:5px;padding:24px;margin:16px 0;break-inside:avoid}article.REVIEW{border-left-color:#ab842f}article.INFO{border-left-color:#388371}.label{font-size:12px;letter-spacing:1px;color:#79503c;font-weight:700}.op{font-family:ui-monospace,Consolas,monospace;margin-top:8px;font-size:14px;overflow-wrap:anywhere}
            .evidence{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin:16px 0}.evidence>div{min-width:0}pre{background:#eff2f0;border:1px solid #e0e6e1;border-radius:4px;padding:12px;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;max-height:300px;overflow:auto}code{font-family:ui-monospace,Consolas,monospace}.pointer{font-size:12px;overflow-wrap:anywhere;color:var(--muted)}.action{background:#edf3f1;padding:12px;border-radius:4px}.consumers{font-size:14px}.witness{font-size:14px}.muted,footer{color:var(--muted);font-size:13px}details{margin-top:12px}summary{cursor:pointer}footer{margin-top:32px;border-top:1px solid var(--line);padding-top:16px}ul{padding-left:20px}
            .index{margin:24px 0;overflow:auto}.index table{width:100%;border-collapse:collapse;background:white;font-size:14px}.index th,.index td{text-align:left;padding:12px 14px;border:1px solid var(--line)}.index th{background:#e8eeea;font-size:12px;text-transform:uppercase;letter-spacing:.5px}.index a{color:#245e6a;text-decoration:underline;text-underline-offset:3px}.index td:first-child{font-family:ui-monospace,Consolas,monospace;white-space:nowrap}
            @media(max-width:700px){main{padding:18px}.sources,.evidence{grid-template-columns:1fr}.stats{grid-template-columns:1fr 1fr}h1{font-size:31px}article{padding:18px}}@media print{pre{max-height:none}main{max-width:none}header{color:var(--ink);background:white;border:1px solid var(--line)}.eyebrow,.subtitle{color:var(--muted)}}
            </style></head><body><main><header><div class="eyebrow">ContractLens / API migration evidence</div><h1>What changed. Who may be affected.</h1>
            <p class="subtitle">Direction-aware checks against two supplied OpenAPI contracts. Each finding links to its source values, declared consumers, and a suggested next step.</p>
            """);
    b.append("<div class=\"verdict\">")
        .append(escape(r.verdict()))
        .append("</div></header><div class=\"stats\">");
    stat(b, r.operationsCompared(), "Operations compared");
    for (String level : new String[] {"BREAKING", "REVIEW", "INFO"})
      stat(b, r.counts().getOrDefault(level, 0), level.toLowerCase());
    b.append("</div><section class=\"sources\">");
    source(b, "Baseline", r.baseline());
    source(b, "Candidate", r.candidate());
    b.append(
        "</section><p class=\"muted\">Consumer impact comes from the supplied manifest, not runtime"
            + " traffic. A schema-fragment witness is not a complete HTTP request. No API calls"
            + " were made.</p>");
    navigation(b, r);
    b.append("<section><h2>Findings</h2>");
    if (r.findings().isEmpty())
      b.append(
          "<article class=\"INFO\">No incompatibilities were found within the supported checks."
              + " Review the scope below before a release decision.</article>");
    for (Finding f : r.findings()) {
      b.append("<article class=\"")
          .append(f.severity())
          .append("\" id=\"")
          .append(escape(f.id()))
          .append("\"><div class=\"label\">")
          .append(f.severity())
          .append(" · ")
          .append(f.direction())
          .append(" · ")
          .append(escape(f.rule()))
          .append("</div><div class=\"op\">")
          .append(escape(f.operation()))
          .append("</div><h3>")
          .append(escape(f.message()))
          .append("</h3><p class=\"consumers\"><strong>Declared consumers:</strong> ")
          .append(
              escape(
                  f.consumers().isEmpty()
                      ? "None mapped; coverage is unknown"
                      : String.join(", ", f.consumers())))
          .append("</p><div class=\"evidence\">");
      evidence(b, "Before", f.beforePointer(), f.before());
      evidence(b, "After", f.afterPointer(), f.after());
      b.append("</div><p class=\"action\"><strong>Next step:</strong> ")
          .append(escape(f.action()))
          .append("</p>");
      if (f.witness() != null)
        b.append("<div class=\"witness\"><strong>Verified schema-fragment witness</strong><pre>")
            .append(escape(Json.pretty(f.witness().value())))
            .append("</pre>Accepted by ")
            .append(escape(f.witness().acceptedBy()))
            .append("; rejected by ")
            .append(escape(f.witness().rejectedBy()))
            .append(". Scope: ")
            .append(escape(f.witness().scope()))
            .append(".</div>");
      b.append("<p class=\"muted\">Finding <code>")
          .append(escape(f.id()))
          .append("</code></p></article>");
    }
    b.append("</section><footer><h2>Scope and limits</h2><ul>");
    for (String limitation : r.limitations())
      b.append("<li>").append(escape(limitation)).append("</li>");
    return b.append("</ul><p>ContractLens ")
        .append(escape(r.toolVersion()))
        .append(" · report schema ")
        .append(r.schemaVersion())
        .append(" · deterministic, local analysis</p></footer></main></body></html>\n")
        .toString();
  }

  private static void navigation(StringBuilder b, Report report) {
    if (report.findings().isEmpty()) return;
    Map<String, List<Finding>> operations = new TreeMap<>();
    for (Finding finding : report.findings())
      operations.computeIfAbsent(finding.operation(), ignored -> new ArrayList<>()).add(finding);
    b.append(
        "<section class=\"index\"><h2>Changed"
            + " operations</h2><table><thead><tr><th>Operation</th><th>Breaking</th><th>Review</th><th>Declared"
            + " consumers</th></tr></thead><tbody>");
    for (var entry : operations.entrySet()) {
      List<Finding> findings = entry.getValue();
      SortedSet<String> consumers = new TreeSet<>();
      findings.forEach(f -> consumers.addAll(f.consumers()));
      b.append("<tr><td><a href=\"#")
          .append(escape(findings.getFirst().id()))
          .append("\">")
          .append(escape(entry.getKey()))
          .append("</a></td><td>")
          .append(findings.stream().filter(f -> f.severity() == Severity.BREAKING).count())
          .append("</td><td>")
          .append(findings.stream().filter(f -> f.severity() == Severity.REVIEW).count())
          .append("</td><td>")
          .append(escape(consumers.isEmpty() ? "Not mapped" : String.join(", ", consumers)))
          .append("</td></tr>");
    }
    b.append("</tbody></table></section>");
  }

  private static void stat(StringBuilder b, int value, String label) {
    b.append("<div class=\"stat\"><strong>")
        .append(value)
        .append("</strong><span>")
        .append(escape(label))
        .append("</span></div>");
  }

  private static void source(StringBuilder b, String label, Source s) {
    b.append("<div class=\"source\"><strong>")
        .append(label)
        .append(": ")
        .append(escape(s.name()))
        .append("</strong><br>OpenAPI ")
        .append(escape(s.openapi()))
        .append("<br><code>sha256:")
        .append(escape(s.sha256()))
        .append("</code></div>");
  }

  private static void evidence(StringBuilder b, String label, String pointer, Object value) {
    b.append("<div><strong>")
        .append(label)
        .append("</strong><div class=\"pointer\">")
        .append(escape(pointer == null ? "Not present" : pointer))
        .append("</div><pre>")
        .append(escape(value == null ? "Not present" : Json.pretty(value)))
        .append("</pre></div>");
  }

  public static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  public static String markdown(Report r) {
    StringBuilder b =
        new StringBuilder("# ContractLens API migration review\n\n**Verdict: ")
            .append(r.verdict())
            .append("**\n\n");
    b.append("Baseline: ")
        .append(md(r.baseline().name()))
        .append(" (SHA-256 ")
        .append(r.baseline().sha256())
        .append(")\n\nCandidate: ")
        .append(md(r.candidate().name()))
        .append(" (SHA-256 ")
        .append(r.candidate().sha256())
        .append(")\n\n");
    b.append("Compared operations: ")
        .append(r.operationsCompared())
        .append(". Counts: ")
        .append(r.counts())
        .append(".\n\n");
    for (Finding f : r.findings()) {
      b.append("## ")
          .append(f.severity())
          .append(" · ")
          .append(md(f.rule()))
          .append("\n\n")
          .append(md(f.operation()))
          .append(" · ")
          .append(f.direction())
          .append("\n\n")
          .append(md(f.message()))
          .append("\n\n");
      b.append("Declared consumers: ")
          .append(
              f.consumers().isEmpty()
                  ? "none mapped; coverage unknown"
                  : md(String.join(", ", f.consumers())))
          .append("\n\n");
      b.append("Before pointer: ")
          .append(md(f.beforePointer() == null ? "not present" : f.beforePointer()))
          .append("\n\n");
      b.append("After pointer: ")
          .append(md(f.afterPointer() == null ? "not present" : f.afterPointer()))
          .append("\n\n");
      b.append("Next step: ")
          .append(md(f.action()))
          .append("\n\nFinding ID: ")
          .append(f.id())
          .append("\n\n");
      if (f.witness() != null)
        b.append("Schema-fragment witness: ")
            .append(md(Json.compact(f.witness().value())))
            .append(". Accepted by ")
            .append(f.witness().acceptedBy())
            .append("; rejected by ")
            .append(f.witness().rejectedBy())
            .append(".\n\n");
    }
    b.append("## Scope\n\n");
    for (String l : r.limitations()) b.append("- ").append(md(l)).append("\n");
    return b.toString();
  }

  private static String md(String value) {
    return escape(value)
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("#", "\\#")
        .replace("\r", " ")
        .replace("\n", " ");
  }
}
