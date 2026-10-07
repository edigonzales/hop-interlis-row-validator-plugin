package ch.so.agi.rowvalidator.transform;

import java.util.*;
import org.apache.hop.core.annotations.Transform;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.pipeline.transform.BaseTransformMeta;

@Transform(
    id = "INTERLIS_ROW_VALIDATOR_TRANSFORM",
    name = "i18n::Plugin.Name",
    description = "i18n::Plugin.Description",
    image = "ch/so/agi/rowvalidator/transform/row-validator.svg",
    categoryDescription =
        "i18n:org.apache.hop.pipeline.transform:BaseTransform.Category.Validation",
    supportedEngines = {"Local"},
    keywords = {"interlis", "validate", "row", "stream"},
    documentationUrl =
        "https://edigonzales.github.io/hop-interlis-row-validator-plugin/row-validator/main/index.html#row-validator")
public class RowValidatorMeta extends BaseTransformMeta<RowValidator, RowValidatorData> {
  @HopMetadataProperty private String modelNames = "";
  @HopMetadataProperty private String modelSources = "";
  @HopMetadataProperty private String className = "";
  @HopMetadataProperty private String configFile = "";
  @HopMetadataProperty private boolean singlePassOnly = false;
  @HopMetadataProperty private String timeZone = "";
  @HopMetadataProperty private String objectIdField = "";
  @HopMetadataProperty private String sourceRowField = "";
  @HopMetadataProperty private String reportDirectory = "";
  @HopMetadataProperty private int cacheRows = 10000;
  @HopMetadataProperty private String tempDirectory = "";
  @HopMetadataProperty private boolean compress = false;

  @HopMetadataProperty(groupKey = "mappings", key = "mapping")
  private List<FieldMapping> mappings = new ArrayList<>();

  public RowValidatorMeta() {}

  @Override
  public void setDefault() {
    modelNames = "";
    modelSources = "";
    className = "";
    configFile = "";
    singlePassOnly = false;
    timeZone = "";
    objectIdField = "";
    sourceRowField = "";
    reportDirectory = "";
    cacheRows = 10000;
    tempDirectory = "";
    compress = false;
    mappings = new ArrayList<>();
  }

  @Override
  public RowValidatorMeta clone() {
    RowValidatorMeta copy = (RowValidatorMeta) super.clone();
    copy.mappings =
        mappings.stream()
            .map(m -> new FieldMapping(m.getAttribute(), m.getField()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    return copy;
  }

  @Override
  public String getDialogClassName() {
    return RowValidatorDialog.class.getName();
  }

  @Override
  public void check(
      java.util.List<org.apache.hop.core.ICheckResult> remarks,
      org.apache.hop.pipeline.PipelineMeta pipelineMeta,
      org.apache.hop.pipeline.transform.TransformMeta transformMeta,
      org.apache.hop.core.row.IRowMeta prev,
      String[] input,
      String[] output,
      org.apache.hop.core.row.IRowMeta info,
      org.apache.hop.core.variables.IVariables variables,
      org.apache.hop.metadata.api.IHopMetadataProvider provider) {
    int result = org.apache.hop.core.ICheckResult.TYPE_RESULT_OK;
    String message = "Configuration checked. No data were validated.";
    try {
      if (input == null || input.length == 0)
        throw new IllegalArgumentException("An incoming row stream is required");
      ConfigurationCheck.check(this, prev, transformMeta, variables);
    } catch (ConfigurationCheck.UnresolvedVariableException
        | ConfigurationCheck.MetadataUnavailableException ex) {
      result = org.apache.hop.core.ICheckResult.TYPE_RESULT_WARNING;
      message = ex.getMessage();
    } catch (Exception ex) {
      result = org.apache.hop.core.ICheckResult.TYPE_RESULT_ERROR;
      message = ex.getMessage();
    }
    remarks.add(new org.apache.hop.core.CheckResult(result, message, transformMeta));
  }

  public List<FieldMapping> getMappings() {
    return mappings;
  }

  public void setMappings(List<FieldMapping> value) {
    mappings = value;
  }

  public String getModelNames() {
    return modelNames;
  }

  public void setModelNames(String value) {
    modelNames = value;
  }

  public String getModelSources() {
    return modelSources;
  }

  public void setModelSources(String value) {
    modelSources = value;
  }

  public String getClassName() {
    return className;
  }

  public void setClassName(String value) {
    className = value;
  }

  public String getConfigFile() {
    return configFile;
  }

  public void setConfigFile(String value) {
    configFile = value;
  }

  public boolean isSinglePassOnly() {
    return singlePassOnly;
  }

  public void setSinglePassOnly(boolean value) {
    singlePassOnly = value;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public void setTimeZone(String value) {
    timeZone = value;
  }

  public String getObjectIdField() {
    return objectIdField;
  }

  public void setObjectIdField(String value) {
    objectIdField = value;
  }

  public String getSourceRowField() {
    return sourceRowField;
  }

  public void setSourceRowField(String value) {
    sourceRowField = value;
  }

  public String getReportDirectory() {
    return reportDirectory;
  }

  public void setReportDirectory(String value) {
    reportDirectory = value;
  }

  public int getCacheRows() {
    return cacheRows;
  }

  public void setCacheRows(int value) {
    cacheRows = value;
  }

  public String getTempDirectory() {
    return tempDirectory;
  }

  public void setTempDirectory(String value) {
    tempDirectory = value;
  }

  public boolean isCompress() {
    return compress;
  }

  public void setCompress(boolean value) {
    compress = value;
  }
}
