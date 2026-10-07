package ch.so.agi.rowvalidator.transform;

import static org.junit.jupiter.api.Assertions.*;

import ch.so.agi.rowvalidator.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.row.*;
import org.apache.hop.core.row.value.*;
import org.junit.jupiter.api.*;

class HopMappingTest {
  @BeforeAll
  static void init() throws Exception {
    HopEnvironment.init();
  }

  static ModelDescriptor model() throws Exception {
    return ModelDescriptor.of(
        ModelService.compile(
            "Rows", Path.of("../core/src/test/resources/models").toAbsolutePath().toString()),
        "Rows.Data.Row");
  }

  static RowValidatorMeta config() {
    var c = new RowValidatorMeta();
    c.setMappings(
        new ArrayList<>(
            List.of(new FieldMapping("key", "key"), new FieldMapping("amount", "amount"))));
    return c;
  }

  static RowMeta input() {
    var m = new RowMeta();
    m.addValueMeta(new ValueMetaString("key"));
    m.addValueMeta(new ValueMetaInteger("amount"));
    return m;
  }

  @Test
  void mappingPreservesLexemesAndUndefined() throws Exception {
    var m = input();
    m.addValueMeta(new ValueMetaString("day"));
    m.addValueMeta(new ValueMetaString("extra"));
    var config = config();
    config.getMappings().add(new FieldMapping("day", "day"));
    var mapping = new HopMapping(model(), config, m);
    Object[] row = {" ", 4L, "2024-02-30Z", "extra"};
    var obj = mapping.object(row, "1");
    assertEquals(" ", obj.getattrvalue("key"));
    assertEquals("2024-02-30Z", obj.getattrvalue("day"));
    assertNull(obj.getattrvalue("extra"));
    assertArrayEquals(new Object[] {" ", 4L, "2024-02-30Z", "extra"}, row);
    row[0] = "";
    row[2] = null;
    obj = mapping.object(row, "2");
    assertEquals("", obj.getattrvalue("key"));
    assertNull(obj.getattrvalue("day"));
  }

  @Test
  void missingDuplicateAndIncompatibleMappingsFailBeforeRows() throws Exception {
    var model = model();
    var c = config();
    c.getMappings().add(new FieldMapping("amount", "amount"));
    assertThrows(IllegalArgumentException.class, () -> new HopMapping(model, c, input()));
    var missing = config();
    missing.getMappings().getFirst().setField("absent");
    assertThrows(IllegalArgumentException.class, () -> new HopMapping(model, missing, input()));
    var bad = input();
    bad.addValueMeta(new ValueMetaBoolean("day"));
    var cfg = config();
    cfg.getMappings().add(new FieldMapping("day", "day"));
    assertThrows(IllegalArgumentException.class, () -> new HopMapping(model, cfg, bad));
  }

  @Test
  void lazyTimestampIsStrictZoneIndependentAndDoesNotAlterMetadata() throws Exception {
    var model = model();
    var c = config();
    c.setTimeZone("Europe/Zurich");
    c.getMappings().add(new FieldMapping("moment", "moment"));
    var m = input();
    var timestamp = new ValueMetaTimestamp("moment");
    timestamp.setDateFormatTimeZone(TimeZone.getTimeZone("UTC"));
    timestamp.setStorageType(IValueMeta.STORAGE_TYPE_BINARY_STRING);
    timestamp.setStorageMetadata(new ValueMetaString("moment"));
    timestamp.setConversionMask("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
    m.addValueMeta(timestamp);
    TimeZone before = TimeZone.getDefault();
    try {
      for (String zone : List.of("Pacific/Honolulu", "Asia/Tokyo")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        var mapping = new HopMapping(model, c, m);
        byte[] value = "2024-02-29 23:31:12.123000000".getBytes(StandardCharsets.UTF_8);
        assertEquals(
            "2024-02-29T23:31:12.123",
            mapping.object(new Object[] {"1", 2L, value}, "1").getattrvalue("moment"));
        for (String bad :
            List.of(
                "2024-02-30 12:00:00.000000000",
                "2024-03-31 02:30:00.000000000",
                "2024-02-29 12:00:00.123456789"))
          assertThrows(
              HopMapping.MappingException.class,
              () ->
                  mapping.object(
                      new Object[] {"1", 2L, bad.getBytes(StandardCharsets.UTF_8)}, "1"));
      }
    } finally {
      TimeZone.setDefault(before);
    }
    assertEquals("UTC", timestamp.getDateFormatTimeZone().getID());
    c.setTimeZone("");
    assertThrows(IllegalArgumentException.class, () -> new HopMapping(model, c, m));
  }

  @Test
  void lazyTextPreservesEmptyString() throws Exception {
    var m = input();
    m.getValueMeta(0).setStorageType(IValueMeta.STORAGE_TYPE_BINARY_STRING);
    m.getValueMeta(0).setStorageMetadata(new ValueMetaString("key"));
    var mapping = new HopMapping(model(), config(), m);
    assertEquals("", mapping.object(new Object[] {new byte[0], 1L}, "1").getattrvalue("key"));
  }
}
