package ch.so.agi.rowvalidator.core;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Lexical conversion only. All model validation remains in iox-ili. */
public final class ScalarConverter {
  public enum InputKind {
    STRING,
    INTEGER,
    DECIMAL,
    NUMBER,
    BOOLEAN,
    TEMPORAL
  }

  private ScalarConverter() {}

  public static void check(InputKind input, ScalarKind target, ZoneId zone) {
    boolean compatible =
        input == InputKind.STRING
            || target == ScalarKind.NUMERIC
                && (input == InputKind.INTEGER
                    || input == InputKind.DECIMAL
                    || input == InputKind.NUMBER)
            || target == ScalarKind.BOOLEAN && input == InputKind.BOOLEAN
            || target.temporal() && input == InputKind.TEMPORAL;
    if (!compatible) throw new IllegalArgumentException("Cannot map " + input + " to " + target);
    if (input == InputKind.TEMPORAL && zone == null)
      throw new IllegalArgumentException(
          "An explicit conversion time zone is required for typed temporal fields");
  }

  public static String convert(Object value, InputKind input, ScalarKind target, ZoneId zone) {
    check(input, target, zone);
    if (value == null) return null;
    return switch (input) {
      case STRING -> (String) value;
      case INTEGER -> Long.toString(((Number) value).longValue());
      case DECIMAL -> ((BigDecimal) value).toPlainString();
      case NUMBER -> {
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n))
          throw new IllegalArgumentException("Non-finite number cannot be mapped");
        yield BigDecimal.valueOf(n).toPlainString();
      }
      case BOOLEAN -> ((Boolean) value) ? "true" : "false";
      case TEMPORAL -> {
        Instant instant =
            value instanceof Timestamp t
                ? t.toInstant()
                : Instant.ofEpochMilli(((Date) value).getTime());
        var local = instant.atZone(zone);
        if ((target == ScalarKind.TIME || target == ScalarKind.DATETIME)
            && local.getNano() % 1_000_000 != 0)
          throw new IllegalArgumentException(
              "Temporal value has sub-millisecond precision; refusing to truncate");
        String pattern =
            switch (target) {
              case DATE -> "uuuu-MM-dd";
              case LEGACY_DATE -> "uuuuMMdd";
              case TIME -> "HH:mm:ss.SSS";
              case DATETIME -> "uuuu-MM-dd'T'HH:mm:ss.SSS";
              default -> throw new IllegalArgumentException("Not a temporal target: " + target);
            };
        yield DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(local);
      }
    };
  }
}
