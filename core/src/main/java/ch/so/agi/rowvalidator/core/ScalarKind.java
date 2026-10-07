package ch.so.agi.rowvalidator.core;

public enum ScalarKind {
  TEXT,
  NUMERIC,
  BOOLEAN,
  ENUMERATION,
  DATE,
  TIME,
  DATETIME,
  LEGACY_DATE,
  FORMAT;

  public boolean temporal() {
    return this == DATE || this == TIME || this == DATETIME || this == LEGACY_DATE;
  }
}
