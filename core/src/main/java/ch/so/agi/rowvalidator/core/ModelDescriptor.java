package ch.so.agi.rowvalidator.core;

import ch.interlis.ili2c.metamodel.*;
import java.util.*;

/** One effective, transferable scalar schema, shared by configuration and runtime. */
public record ModelDescriptor(
    TransferDescription model, Table table, Topic topic, List<Attribute> attributes) {
  public record Attribute(String name, ScalarKind kind, boolean required, String details) {}

  public String className() {
    return table.getScopedName(null);
  }

  public String topicName() {
    return topic.getScopedName(null);
  }

  public static ModelDescriptor of(TransferDescription td, String name) {
    if (!(td.getElement(name) instanceof Table table)
        || !table.isIdentifiable()
        || table.isAbstract()
        || !(table.getContainer() instanceof Topic topic))
      throw new IllegalArgumentException(
          "Select a concrete transferable class in a topic: " + name);
    List<Attribute> attributes = new ArrayList<>();
    for (var it = table.getAttributesAndRoles2(); it.hasNext(); ) {
      Object obj = ((ViewableTransferElement) it.next()).obj;
      if (obj instanceof AttributeDef attr) {
        if (attr.isTransient()) continue;
        Type type = attr.getDomainResolvingAll();
        if (attr.getCardinality().getMaximum() > 1)
          throw unsupported(name, attr.getName(), "repeated attribute");
        ScalarKind kind = kind(attr.getDomainOrDerivedDomain());
        if (kind == null) throw unsupported(name, attr.getName(), type.getClass().getSimpleName());
        String details =
            type instanceof NumericType n
                ? n.getMinimum() + " .. " + n.getMaximum()
                : type instanceof FormattedType f
                    ? f.getMinimum() + " .. " + f.getMaximum()
                    : type instanceof EnumerationType e
                        ? String.join(", ", e.getValues())
                        : type instanceof TextType t ? "TEXT * " + t.getMaxLength() : kind.name();
        attributes.add(
            new Attribute(attr.getName(), kind, attr.getCardinality().getMinimum() > 0, details));
      } else if (obj instanceof RoleDef role) {
        throw unsupported(name, role.getName(), "association role");
      }
    }
    var roles = table.getOpposideRoles();
    if (roles.hasNext())
      throw unsupported(name, ((RoleDef) roles.next()).getName(), "association role");
    return new ModelDescriptor(td, table, topic, List.copyOf(attributes));
  }

  private static IllegalArgumentException unsupported(String cl, String attr, String type) {
    return new IllegalArgumentException(cl + "." + attr + ": unsupported in v1 (" + type + ")");
  }

  private static boolean descends(Type type, Domain domain, Set<Element> seen) {
    if (type == null || !seen.add(type)) return false;
    if (type == domain.getType()) return true;
    if (type instanceof TypeAlias alias) {
      for (Domain d = alias.getAliasing(); d != null; d = d.getExtending()) {
        if (d == domain || descends(d.getType(), domain, seen)) return true;
      }
    }
    if (type instanceof FormattedType f
        && f.getDefinedBaseDomain() != null
        && descends(f.getDefinedBaseDomain().getType(), domain, seen)) return true;
    return type.getExtending() instanceof Type parent && descends(parent, domain, seen);
  }

  private static boolean descends(Type t, Domain d) {
    return descends(t, d, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  public static ScalarKind kind(Type type) {
    var p = PredefinedModel.getInstance();
    Type resolved = type.resolveAliases();
    // DateTime extends Date: test the most specific standard format first.
    Domain[] domains = {p.XmlDateTime, p.XmlTime, p.XmlDate};
    ScalarKind[] kinds = {ScalarKind.DATETIME, ScalarKind.TIME, ScalarKind.DATE};
    for (int i = 0; i < domains.length; i++) {
      if (descends(type, domains[i])
          && resolved instanceof FormattedType f
          && f.getFormat().equals(((FormattedType) domains[i].getType()).getFormat()))
        return kinds[i];
    }
    if (descends(type, p.INTERLIS_1_DATE)) return ScalarKind.LEGACY_DATE;
    if (descends(type, p.BOOLEAN)) return ScalarKind.BOOLEAN;
    if (resolved instanceof NumericType) return ScalarKind.NUMERIC;
    if (resolved instanceof FormattedType) return ScalarKind.FORMAT;
    if (resolved instanceof TextType) return ScalarKind.TEXT;
    if (resolved instanceof AbstractEnumerationType) return ScalarKind.ENUMERATION;
    return null;
  }

  public void checkAutomaticOid(boolean automatic) {
    Domain oid = table.getOid();
    if (automatic && oid != null && !descends(oid.getType(), PredefinedModel.getInstance().UUIDOID))
      throw new IllegalArgumentException(
          "Class OID domain requires an explicit object-ID field: " + oid.getScopedName(null));
    basketId(); // Fail before input consumption for unsupported basket domains.
  }

  public String basketId() {
    Domain oid = topic.getBasketOid();
    var p = PredefinedModel.getInstance();
    if (oid == null || descends(oid.getType(), p.UUIDOID)) return UUID.randomUUID().toString();
    if (descends(oid.getType(), p.I32OID)) return "1";
    if (descends(oid.getType(), p.STANDARDOID))
      return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
    throw new IllegalArgumentException("Unsupported basket OID domain: " + oid.getScopedName(null));
  }
}
