package ch.so.agi.rowvalidator.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.apache.hop.core.row.*;
import org.apache.hop.core.row.value.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RowSpoolTest {
  @TempDir Path temp;

  @Test
  void forcedSpoolingPreservesOrderPrecisionAndLazyBytes() throws Exception {
    var meta = new RowMeta();
    meta.addValueMeta(new ValueMetaInteger("n"));
    meta.addValueMeta(new ValueMetaTimestamp("ts"));
    meta.addValueMeta(new ValueMetaBigNumber("decimal"));
    var lazy = new ValueMetaString("lazy");
    lazy.setStorageType(IValueMeta.STORAGE_TYPE_BINARY_STRING);
    lazy.setStorageMetadata(new ValueMetaString("raw"));
    meta.addValueMeta(lazy);
    for (boolean gzip : List.of(false, true)) {
      try (var spool = new RowSpool(meta, 2, temp, gzip)) {
        var ts = Timestamp.from(Instant.parse("2024-02-29T23:31:12.123456789Z"));
        var decimal = new BigDecimal("12345678901234567890.123456789");
        for (long n = 0; n < 7; n++) {
          byte[] bytes = ("row" + n).getBytes(StandardCharsets.UTF_8);
          spool.add(new Object[] {n, ts, decimal, bytes});
          bytes[0] = 'X';
        }
        spool.seal();
        for (long n = 0; n < 7; n++) {
          Object[] row = spool.next();
          assertEquals(n, row[0]);
          assertEquals(ts, row[1]);
          assertEquals(decimal, row[2]);
          assertArrayEquals(("row" + n).getBytes(StandardCharsets.UTF_8), (byte[]) row[3]);
        }
        assertNull(spool.next());
      }
      try (var files = Files.list(temp)) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  void truncationAndBadDirectoryAreFatalAndCleanedUp() throws Exception {
    var meta = HopMappingTest.input();
    try (var spool = new RowSpool(meta, 1, temp, false)) {
      spool.add(new Object[] {"a", 1L});
      spool.add(new Object[] {"b", 2L});
      spool.seal();
      Path file;
      try (var files = Files.list(temp)) {
        file = files.findFirst().orElseThrow();
      }
      Files.write(file, new byte[] {0});
      assertThrows(
          Exception.class,
          () -> {
            while (spool.next() != null) {}
          });
    }
    try (var files = Files.list(temp)) {
      assertEquals(0, files.count());
    }
    Path notDir = temp.resolve("file");
    Files.writeString(notDir, "x");
    assertThrows(Exception.class, () -> new RowSpool(meta, 1, notDir, false));
  }
}
