package ch.so.agi.rowvalidator.transform;

import org.apache.hop.metadata.api.HopMetadataProperty;

public class FieldMapping {
  @HopMetadataProperty private String attribute;
  @HopMetadataProperty private String field;

  public FieldMapping() {}

  public FieldMapping(String attribute, String field) {
    this.attribute = attribute;
    this.field = field;
  }

  public String getAttribute() {
    return attribute;
  }

  public void setAttribute(String value) {
    attribute = value;
  }

  public String getField() {
    return field;
  }

  public void setField(String value) {
    field = value;
  }
}
