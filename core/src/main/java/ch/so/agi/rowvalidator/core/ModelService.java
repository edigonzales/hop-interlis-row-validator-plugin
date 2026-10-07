package ch.so.agi.rowvalidator.core;

import ch.ehi.basics.logging.*;
import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.*;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;
import java.nio.file.*;
import java.util.*;

public final class ModelService {
  private static final Object COMPILER_LOCK = new Object();

  private ModelService() {}

  public static TransferDescription compile(String models, String sources) throws Exception {
    synchronized (COMPILER_LOCK) {
      var errors = new ArrayList<String>();
      Thread owner = Thread.currentThread();
      LogListener listener =
          event -> {
            if (Thread.currentThread() == owner && event.getEventKind() == LogEvent.ERROR)
              errors.add(event.getEventMsg());
          };
      EhiLogger.getInstance().addListener(listener);
      try {
        return compileLocked(models, sources, errors);
      } finally {
        EhiLogger.getInstance().removeListener(listener);
      }
    }
  }

  private static TransferDescription compileLocked(
      String models, String sources, List<String> errors) throws Exception {
    List<String> names = split(models);
    if (names.isEmpty()) throw new IllegalArgumentException("Model names are required");
    List<String> dirs = split(sources);
    Configuration config = new Configuration();
    ArrayList<String> remote = new ArrayList<>();
    for (String name : names) {
      Path file = name.endsWith(".ili") ? Path.of(name) : null;
      for (String dir : dirs) {
        if (file == null
            && !dir.contains("://")
            && Files.isRegularFile(Path.of(dir, name + ".ili"))) file = Path.of(dir, name + ".ili");
      }
      if (file != null)
        config.addFileEntry(
            new FileEntry(file.toAbsolutePath().toString(), FileEntryKind.ILIMODELFILE));
      else remote.add(name);
    }
    if (!remote.isEmpty()) {
      if (dirs.isEmpty())
        throw new IllegalArgumentException("Model sources are required to resolve " + remote);
      IliManager manager = new IliManager();
      manager.setRepositories(dirs.toArray(String[]::new));
      Configuration resolved = manager.getConfig(remote, 0.0);
      for (var it = resolved.iteratorFileEntry(); it.hasNext(); )
        config.addFileEntry((FileEntry) it.next());
    }
    config.setAutoCompleteModelList(true);
    Settings settings = new Settings();
    settings.setValue(Ili2cSettings.ILIDIRS, String.join(";", dirs));
    TransferDescription td = Main.runCompiler(config, settings, new Ili2cMetaAttrs());
    if (td == null)
      throw new IllegalArgumentException(
          "Cannot compile models " + models + "; " + String.join("; ", errors));
    for (String name : names)
      if (!name.endsWith(".ili") && td.getElement(name) == null)
        throw new IllegalArgumentException("Requested model not compiled: " + name);
    return td;
  }

  public static List<String> split(String text) {
    if (text == null || text.isBlank()) return List.of();
    return Arrays.stream(text.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  public static List<String> classes(TransferDescription td) {
    List<String> result = new ArrayList<>();
    for (var mi = td.iterator(); mi.hasNext(); )
      for (var ti = mi.next().iterator(); ti.hasNext(); ) {
        if (ti.next() instanceof Topic topic)
          for (var ci = topic.iterator(); ci.hasNext(); ) {
            if (ci.next() instanceof Table t && t.isIdentifiable() && !t.isAbstract())
              result.add(t.getScopedName(null));
          }
      }
    Collections.sort(result);
    return result;
  }
}
