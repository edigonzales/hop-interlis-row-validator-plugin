package ch.so.agi.rowvalidator.transform;

import ch.so.agi.rowvalidator.core.*;
import java.util.*;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.pipeline.transform.TransformMeta;

public final class ConfigurationCheck {
  private ConfigurationCheck() {}

  public static RowValidatorMeta resolve(RowValidatorMeta meta, IVariables variables) {
    RowValidatorMeta r = meta.clone();
    r.setModelNames(value(variables, meta.getModelNames()));
    r.setModelSources(value(variables, meta.getModelSources()));
    r.setClassName(value(variables, meta.getClassName()));
    r.setConfigFile(value(variables, meta.getConfigFile()));
    r.setTimeZone(value(variables, meta.getTimeZone()));
    r.setObjectIdField(value(variables, meta.getObjectIdField()));
    r.setSourceRowField(value(variables, meta.getSourceRowField()));
    r.setReportDirectory(value(variables, meta.getReportDirectory()));
    r.setTempDirectory(value(variables, meta.getTempDirectory()));
    r.getMappings()
        .forEach(
            m -> {
              m.setAttribute(value(variables, m.getAttribute()));
              m.setField(value(variables, m.getField()));
            });
    return r;
  }

  public static final class UnresolvedVariableException extends IllegalArgumentException {
    UnresolvedVariableException(String value) {
      super("Unchecked: unresolved runtime variable: " + value);
    }
  }

  public static final class MetadataUnavailableException extends IllegalArgumentException {
    MetadataUnavailableException() {
      super("Unchecked: input metadata unavailable; mapping not checked");
    }
  }

  private static String value(IVariables variables, String raw) {
    String value = variables.resolve(raw == null ? "" : raw);
    if (value.contains("${") || value.contains("%%")) throw new UnresolvedVariableException(value);
    return value;
  }

  public static void environment(
      RowValidatorMeta meta, TransformMeta transform, IVariables variables) {
    if (!meta.isSinglePassOnly()
        && (transform.getCopies(variables) != 1 || transform.isPartitioned()))
      throw new IllegalArgumentException(
          "Full validation requires exactly one transform copy and no partitioning");
    if (!meta.isSinglePassOnly() && meta.getCacheRows() < 1)
      throw new IllegalArgumentException("Row cache must be at least 1");
  }

  public static ModelDescriptor model(RowValidatorMeta meta) throws Exception {
    return ModelDescriptor.of(
        ModelService.compile(meta.getModelNames(), meta.getModelSources()), meta.getClassName());
  }

  public static void check(
      RowValidatorMeta meta, IRowMeta input, TransformMeta transform, IVariables variables)
      throws Exception {
    RowValidatorMeta r = resolve(meta, variables);
    if (transform != null) environment(r, transform, variables);
    ModelDescriptor model = model(r);
    ValidationSession.configuration(
        model, r.getConfigFile().isBlank() ? null : java.nio.file.Path.of(r.getConfigFile()));
    if (input == null) throw new MetadataUnavailableException();
    new HopMapping(model, r, input);
  }
}
