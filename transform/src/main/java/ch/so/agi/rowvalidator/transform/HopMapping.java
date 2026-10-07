package ch.so.agi.rowvalidator.transform;

import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import ch.so.agi.rowvalidator.core.*;
import java.sql.Timestamp;
import java.text.ParsePosition;
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import org.apache.hop.core.row.*;

/** Binding is performed once, before input consumption. Metadata clones isolate lazy decoding. */
public final class HopMapping {
  private record Bound(
      ModelDescriptor.Attribute target,
      int index,
      IValueMeta source,
      ScalarConverter.InputKind kind) {}

  private final ModelDescriptor model;
  private final List<Bound> fields = new ArrayList<>();
  private final ZoneId zone;
  private final IRowMeta input;
  private final int oidIndex, sourceIndex;

  public static final class MappingException extends Exception {
    private final String attribute;

    MappingException(String attribute, Exception cause) {
      super(attribute + ": " + cause.getMessage(), cause);
      this.attribute = attribute;
    }

    public String attribute() {
      return attribute;
    }
  }

  public HopMapping(ModelDescriptor model, RowValidatorMeta meta, IRowMeta input) {
    this.model = model;
    this.input = input.clone();
    zone = meta.getTimeZone().isBlank() ? null : ZoneId.of(meta.getTimeZone());
    oidIndex = index(meta.getObjectIdField());
    sourceIndex = index(meta.getSourceRowField());
    for (int index : new int[] {oidIndex, sourceIndex})
      if (index >= 0) {
        int type = this.input.getValueMeta(index).getType();
        if (type != IValueMeta.TYPE_STRING && type != IValueMeta.TYPE_INTEGER)
          throw new IllegalArgumentException(
              "Identifier field must be String or Integer: "
                  + this.input.getValueMeta(index).getName());
      }
    model.checkAutomaticOid(oidIndex < 0);
    Map<String, String> mapping = new LinkedHashMap<>();
    Set<String> names = new HashSet<>();
    model.attributes().forEach(a -> names.add(a.name()));
    for (FieldMapping item : meta.getMappings()) {
      if (item.getAttribute() == null || item.getAttribute().isBlank()) continue;
      if (!names.contains(item.getAttribute()))
        throw new IllegalArgumentException("Unknown target attribute: " + item.getAttribute());
      if (mapping.containsKey(item.getAttribute()))
        throw new IllegalArgumentException("Duplicate target: " + item.getAttribute());
      mapping.put(item.getAttribute(), item.getField());
    }
    for (var target : model.attributes()) {
      String source = mapping.get(target.name());
      if (source == null || source.isBlank()) {
        if (target.required())
          throw new IllegalArgumentException("Mandatory attribute has no source: " + target.name());
        continue;
      }
      int index = index(source);
      IValueMeta vm = this.input.getValueMeta(index);
      ScalarConverter.InputKind kind = inputKind(vm);
      ScalarConverter.check(kind, target.kind(), zone);
      if (kind == ScalarConverter.InputKind.TEMPORAL) {
        vm.setDateFormatTimeZone(TimeZone.getTimeZone(zone));
        vm.setDateFormatLenient(false);
        vm.setDateFormatLocale(Locale.ROOT);
      }
      fields.add(new Bound(target, index, vm, kind));
    }
  }

  static ScalarConverter.InputKind inputKind(IValueMeta vm) {
    return switch (vm.getType()) {
      case IValueMeta.TYPE_STRING -> ScalarConverter.InputKind.STRING;
      case IValueMeta.TYPE_INTEGER -> ScalarConverter.InputKind.INTEGER;
      case IValueMeta.TYPE_BIGNUMBER -> ScalarConverter.InputKind.DECIMAL;
      case IValueMeta.TYPE_NUMBER -> ScalarConverter.InputKind.NUMBER;
      case IValueMeta.TYPE_BOOLEAN -> ScalarConverter.InputKind.BOOLEAN;
      case IValueMeta.TYPE_DATE, IValueMeta.TYPE_TIMESTAMP -> ScalarConverter.InputKind.TEMPORAL;
      default ->
          throw new IllegalArgumentException(
              "Unsupported Hop field type: " + vm.getName() + " (" + vm.getTypeDesc() + ")");
    };
  }

  private int index(String name) {
    if (name == null || name.isBlank()) return -1;
    int i = input.indexOfValue(name);
    if (i < 0) throw new IllegalArgumentException("Input field does not exist: " + name);
    return i;
  }

  public String objectId(Object[] row) throws Exception {
    if (oidIndex < 0) return UUID.randomUUID().toString();
    String id = identifier(row, oidIndex);
    if (id == null || id.isEmpty())
      throw new IllegalArgumentException("Object-ID field is null or empty");
    return id;
  }

  public String sourceId(Object[] row) throws Exception {
    return sourceIndex < 0 ? null : identifier(row, sourceIndex);
  }

  private String identifier(Object[] row, int index) throws Exception {
    IValueMeta vm = input.getValueMeta(index);
    Object v = vm.convertToNormalStorageType(row[index]);
    return v == null ? null : vm.isString() ? (String) v : Long.toString(((Number) v).longValue());
  }

  public IomObject object(Object[] row, String oid) throws MappingException {
    IomObject object = new Iom_jObject(model.className(), oid);
    for (Bound b : fields)
      try {
        Object value = decode(b.source(), row[b.index()]);
        String lexical = ScalarConverter.convert(value, b.kind(), b.target().kind(), zone);
        if (lexical != null) object.setattrvalue(b.target().name(), lexical);
      } catch (Exception ex) {
        throw new MappingException(b.target().name(), ex);
      }
    return object;
  }

  private static String binaryText(IValueMeta vm, byte[] value) {
    String encoding = vm.getStringEncoding();
    return new String(
        value,
        encoding == null || encoding.isBlank()
            ? java.nio.charset.StandardCharsets.UTF_8
            : java.nio.charset.Charset.forName(encoding));
  }

  private Object decode(IValueMeta vm, Object value) throws Exception {
    if (value != null && vm.isStorageBinaryString() && vm.isString())
      return binaryText(vm, (byte[]) value);
    if (value == null
        || !vm.isStorageBinaryString()
        || !(vm.isDate() || vm.getType() == IValueMeta.TYPE_TIMESTAMP))
      return vm.convertToNormalStorageType(value);
    // Hop Timestamp's binary decoder uses Timestamp.valueOf (JVM zone, lenient calendar).
    // Decode text using Hop's storage metadata, then apply its mask with strict calendar rules.
    String text = binaryText(vm, (byte[]) value);
    String mask = vm.getConversionMask();
    if (vm.getType() == IValueMeta.TYPE_DATE) {
      var format = (java.text.SimpleDateFormat) vm.getDateFormat().clone();
      format.setTimeZone(TimeZone.getTimeZone(zone));
      format.setLenient(false);
      ParsePosition pos = new ParsePosition(0);
      Date date = format.parse(text, pos);
      if (date == null || pos.getIndex() != text.length())
        throw new IllegalArgumentException("Invalid lazy date value: " + text);
      return date;
    }
    var builder = new DateTimeFormatterBuilder().parseCaseSensitive();
    if (mask == null || mask.isBlank())
      builder
          .appendPattern("uuuu-MM-dd HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
          .optionalEnd();
    else builder.appendPattern(mask.replace("yyyy", "uuuu"));
    var parsed =
        builder
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
            .parse(text);
    LocalDateTime local = LocalDateTime.from(parsed);
    Instant instant;
    if (parsed.isSupported(ChronoField.OFFSET_SECONDS))
      instant = OffsetDateTime.from(parsed).toInstant();
    else {
      var offsets = zone.getRules().getValidOffsets(local);
      if (offsets.isEmpty())
        throw new IllegalArgumentException(
            "Local timestamp lies in a daylight-saving gap: " + text);
      instant = local.toInstant(offsets.getFirst());
    }
    return Timestamp.from(instant);
  }
}
