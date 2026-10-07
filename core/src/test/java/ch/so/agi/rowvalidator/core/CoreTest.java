package ch.so.agi.rowvalidator.core;

import static org.junit.jupiter.api.Assertions.*;

import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iox_j.validator.ValidationConfig;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {
  @TempDir Path temp;

  static ModelDescriptor model(String name) throws Exception {
    String dir =
        Path.of(CoreTest.class.getResource("/models/" + name + ".ili").toURI())
            .getParent()
            .toString();
    return ModelDescriptor.of(ModelService.compile(name, dir), name + ".Data.Row");
  }

  @Test
  void inheritedScalarAndTemporalTypes() throws Exception {
    var m = model("Rows");
    assertEquals(10, m.attributes().size());
    assertTrue(m.attributes().stream().anyMatch(a -> a.name().equals("key") && a.required()));
    assertKind(m, "day", ScalarKind.DATE);
    assertKind(m, "clock", ScalarKind.TIME);
    assertKind(m, "moment", ScalarKind.DATETIME);
    assertKind(m, "oldDay", ScalarKind.LEGACY_DATE);
    assertKind(m, "active", ScalarKind.BOOLEAN);
    m.checkAutomaticOid(true);
  }

  private static void assertKind(ModelDescriptor m, String name, ScalarKind kind) {
    assertEquals(
        kind,
        m.attributes().stream()
            .filter(a -> a.name().equals(name))
            .findFirst()
            .orElseThrow()
            .kind());
  }

  @Test
  void interlis24AndRestrictedDomainChain() throws Exception {
    var m = model("Times24");
    assertKind(m, "day", ScalarKind.DATE);
    assertKind(m, "clock", ScalarKind.TIME);
    assertKind(m, "moment", ScalarKind.DATETIME);
  }

  @Test
  void nativeSinglePassDiffersFromFullForClassConstraintsAndUnique() throws Exception {
    for (boolean single : List.of(false, true)) {
      var m = model("Rows");
      List<Issue> issues = new ArrayList<>();
      try (var session =
          new ValidationSession(
              m, ValidationSession.configuration(m, null), single, issues::add, () -> false)) {
        session.accept(row("1", "same", "9", "2"), new Issue.RowReference(0, 1, "source-a"));
        session.accept(row("2", "same", "1", "2"), new Issue.RowReference(0, 2, "source-b"));
        assertEquals(single, session.finish(), issues.toString());
        if (!single) {
          assertTrue(session.errors() >= 2, issues.toString());
          assertTrue(issues.stream().anyMatch(i -> i.row() != null && i.row().number() == 1));
        }
        assertThrows(IllegalStateException.class, session::finish);
      }
    }
  }

  static Iom_jObject row(String id, String key, String start, String end) {
    var row = new Iom_jObject("Rows.Data.Row", id);
    row.setattrvalue("key", key);
    row.setattrvalue("amount", "3");
    row.setattrvalue("start", start);
    row.setattrvalue("finish", end);
    return row;
  }

  @Test
  void emptyTransferAndCancellation() throws Exception {
    var m = model("Rows");
    try (var s = new ValidationSession(m, new ValidationConfig(), false, i -> {}, () -> false)) {
      assertTrue(s.finish());
    }
    var stop = new java.util.concurrent.atomic.AtomicBoolean();
    try (var s = new ValidationSession(m, new ValidationConfig(), false, i -> {}, stop::get)) {
      stop.set(true);
      assertThrows(java.util.concurrent.CancellationException.class, s::finish);
      assertFalse(s.finished());
    }
  }

  @Test
  void lexemesAreNotNormalized() {
    for (String s : List.of("", " 2 ", "2024-02-30", "2024-02-29T12:00:00Z", "a\n\"b"))
      assertEquals(
          s, ScalarConverter.convert(s, ScalarConverter.InputKind.STRING, ScalarKind.DATE, null));
    assertNull(
        ScalarConverter.convert(null, ScalarConverter.InputKind.STRING, ScalarKind.TEXT, null));
    assertEquals(
        "12345678901234567890.123456789",
        ScalarConverter.convert(
            new BigDecimal("12345678901234567890.123456789"),
            ScalarConverter.InputKind.DECIMAL,
            ScalarKind.NUMERIC,
            null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScalarConverter.convert(
                Double.NaN, ScalarConverter.InputKind.NUMBER, ScalarKind.NUMERIC, null));
  }

  @Test
  void timeZonePrecisionAndDaylightSaving() {
    var zone = ZoneId.of("Europe/Zurich");
    var original = TimeZone.getDefault();
    try {
      for (String jvm : List.of("UTC", "Pacific/Honolulu", "Asia/Tokyo")) {
        TimeZone.setDefault(TimeZone.getTimeZone(jvm));
        var t = Timestamp.from(Instant.parse("2024-03-31T01:30:12.123Z"));
        assertEquals(
            "2024-03-31T03:30:12.123",
            ScalarConverter.convert(
                t, ScalarConverter.InputKind.TEMPORAL, ScalarKind.DATETIME, zone));
        assertEquals(
            "03:30:12.123",
            ScalarConverter.convert(t, ScalarConverter.InputKind.TEMPORAL, ScalarKind.TIME, zone));
        assertEquals(
            "20240331",
            ScalarConverter.convert(
                t, ScalarConverter.InputKind.TEMPORAL, ScalarKind.LEGACY_DATE, zone));
        var nano = Timestamp.from(Instant.parse("2024-02-29T23:30:00.123456789Z"));
        assertEquals(
            "2024-03-01",
            ScalarConverter.convert(
                nano, ScalarConverter.InputKind.TEMPORAL, ScalarKind.DATE, zone));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ScalarConverter.convert(
                    nano, ScalarConverter.InputKind.TEMPORAL, ScalarKind.TIME, zone));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ScalarConverter.convert(
                    t, ScalarConverter.InputKind.TEMPORAL, ScalarKind.DATE, null));
      }
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void reportIsStreamingAndEscaped() throws Exception {
    Path file;
    try (var report = new JsonlReport(temp, 1)) {
      file = report.file();
      report.record("run_start", Map.of("schema_version", 1));
      report.issue(new Issue("ERROR", "input", "a\n\"b", null, null, null));
      report.flush();
      assertEquals(2, Files.readAllLines(file).size());
      report.record("run_end", Map.of("status", "validation_failed"));
    }
    String text = Files.readString(file);
    assertTrue(text.contains("a\\n\\\"b"));
    assertEquals(3, Files.readAllLines(file).size());
  }
}
