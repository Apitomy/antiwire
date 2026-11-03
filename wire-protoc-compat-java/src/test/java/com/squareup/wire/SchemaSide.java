package com.squareup.wire;

import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One implementation side of the TASK-39 dual-run identity harness. The same root and file
 * paths go to both sides, so messages and locations match by construction; a load failure
 * IS a result: {@link #load} never throws.
 */
interface SchemaSide {
  /** "port" or "upstream"; appears in every label and ledger row. */
  String name();

  /** Loads root/relativePath with sourcePath=[file], protoPath=[root]. */
  DualRunOutput load(Path root, String relativePath);

  /** Decides DEC-15 value shapes and bounds for one side's Extensions/Reserved values. */
  static int[] boundsOf(Object value) {
    if (value instanceof Integer) {
      return new int[] {(Integer) value, (Integer) value};
    }
    if (value instanceof int[]) {
      int[] range = (int[]) value;
      return new int[] {range[0], range[1]};
    }
    // Upstream kotlin.ranges.IntRange: accessed reflectively to keep this class free of a
    // compile-time reference the port side cannot see.
    try {
      int first = (Integer) value.getClass().getMethod("getFirst").invoke(value);
      int last = (Integer) value.getClass().getMethod("getLast").invoke(value);
      return new int[] {first, last};
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("unknown range value type: " + value.getClass(), e);
    }
  }

  static String shapeOf(Object value) {
    if (value instanceof Integer) return "Integer";
    if (value instanceof int[]) return "int[]";
    return value.getClass().getName();
  }

  static List<ChainEntry> chainOf(Throwable thrown, Path root) {
    Throwable cause = thrown;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    String message = String.valueOf(cause.getMessage());
    int newline = message.indexOf('\n');
    if (newline >= 0) message = message.substring(0, newline);
    return Collections.singletonList(new ChainEntry(cause.getClass().getSimpleName(),
        message.replace(root.toString(), "<corpus-root>")));
  }

  /**
   * Walks one loaded file's type tree collecting the DEC-15 range probes, symmetric over
   * both sides' APIs: each side hands in its own list of proto files plus per-type
   * extensions/reserved values already extracted as plain Java objects.
   */
  final class RangeProbes {
    private RangeProbes() {
    }

    interface TypeTree {
      String qualifiedName();

      List<Object> extensionsValues();

      List<TypeTree> nested();
    }

    static List<RangeProbe> collect(List<? extends TypeTree> types) {
      List<RangeProbe> probes = new ArrayList<>();
      for (TypeTree type : types) {
        collectInto(probes, type);
      }
      return probes;
    }

    private static void collectInto(List<RangeProbe> probes, TypeTree type) {
      addAll(probes, "extensions@" + type.qualifiedName(), type.extensionsValues());
      for (TypeTree nested : type.nested()) {
        collectInto(probes, nested);
      }
    }

    private static void addAll(List<RangeProbe> probes, String owner, List<Object> values) {
      if (values == null || values.isEmpty()) return;
      List<int[]> bounds = new ArrayList<>();
      List<String> shapes = new ArrayList<>();
      for (Object value : values) {
        bounds.add(SchemaSide.boundsOf(value));
        shapes.add(SchemaSide.shapeOf(value));
      }
      probes.add(new RangeProbe(owner, bounds, String.join(",", shapes)));
    }
  }
}

/** The port side: com.squareup.wire.* from wire-schema-java. */
final class PortSide implements SchemaSide {
  @Override public String name() {
    return "port";
  }

  @Override public DualRunOutput load(Path root, String relativePath) {
    Path file = root.resolve(relativePath);
    try {
      com.squareup.wire.schema.SchemaLoader loader =
          new com.squareup.wire.schema.SchemaLoader(FileSystems.getDefault());
      loader.initRoots(
          Collections.singletonList(com.squareup.wire.schema.Location.get(file.toString())),
          Collections.singletonList(com.squareup.wire.schema.Location.get(root.toString())));
      com.squareup.wire.schema.Schema schema = loader.loadSchema();
      return loaded(schema, file);
    } catch (Exception e) {
      // The port surfaces checked IOExceptions as UncheckedIOException (DEC-16); the
      // root-cause walk makes the wrapper invisible to the comparison.
      return DualRunOutput.threw(SchemaSide.chainOf(e, root));
    }
  }

  private static DualRunOutput loaded(com.squareup.wire.schema.Schema schema, Path file) {
    com.squareup.wire.schema.ProtoFile protoFile = schema.protoFile(file.toString());
    byte[] descriptorBytes = new com.squareup.wire.schema.internal.SchemaEncoder(schema)
        .encode(protoFile).toByteArray();
    return DualRunOutput.loaded(descriptorBytes, protoFile.toSchema(),
        RangeProbes.collect(treeOf(protoFile, protoFile.getTypes())));
  }

  private static List<RangeProbes.TypeTree> treeOf(
      com.squareup.wire.schema.ProtoFile protoFile, List<com.squareup.wire.schema.Type> types) {
    List<RangeProbes.TypeTree> trees = new ArrayList<>();
    for (com.squareup.wire.schema.Type type : types) {
      trees.add(nodeOf(protoFile, type));
    }
    return trees;
  }

  private static RangeProbes.TypeTree nodeOf(
      com.squareup.wire.schema.ProtoFile protoFile, com.squareup.wire.schema.Type type) {
    final com.squareup.wire.schema.Type theType = type;
    final List<RangeProbes.TypeTree> nested =
        type instanceof com.squareup.wire.schema.MessageType
            ? treeOf(protoFile, ((com.squareup.wire.schema.MessageType) type).getNestedTypes())
            : Collections.<RangeProbes.TypeTree>emptyList();
    return new RangeProbes.TypeTree() {
      @Override public String qualifiedName() {
        return theType.getType().toString();
      }

      @Override public List<Object> extensionsValues() {
        if (!(theType instanceof com.squareup.wire.schema.MessageType)) {
          return Collections.emptyList();
        }
        List<Object> values = new ArrayList<>();
        for (com.squareup.wire.schema.Extensions extensions
            : ((com.squareup.wire.schema.MessageType) theType).getExtensionsList()) {
          values.addAll(extensions.getValues());
        }
        return values;
      }

      @Override public List<RangeProbes.TypeTree> nested() {
        return nested;
      }
    };
  }
}

/** The upstream side: real Wire 7.1.0 under io.apitomy.antiwire.parity.wire.*. */
final class UpstreamSide implements SchemaSide {
  @Override public String name() {
    return "upstream";
  }

  @Override public DualRunOutput load(Path root, String relativePath) {
    Path file = root.resolve(relativePath);
    try {
      io.apitomy.antiwire.parity.wire.schema.SchemaLoader loader =
          new io.apitomy.antiwire.parity.wire.schema.SchemaLoader(FileSystems.getDefault());
      loader.initRoots(
          Collections.singletonList(
              io.apitomy.antiwire.parity.wire.schema.Location.get(file.toString())),
          Collections.singletonList(
              io.apitomy.antiwire.parity.wire.schema.Location.get(root.toString())));
      io.apitomy.antiwire.parity.wire.schema.Schema schema = loader.loadSchema();
      return loaded(schema, file);
    } catch (Exception e) {
      // Real Wire propagates its raw Kotlin IOException here; the root-cause walk
      // normalizes it the same way the port's wrapper is unwrapped (the OutcomeSignature
      // rule absorbs DEC-16 without a special case).
      return DualRunOutput.threw(SchemaSide.chainOf(e, root));
    }
  }

  private static DualRunOutput loaded(io.apitomy.antiwire.parity.wire.schema.Schema schema,
      Path file) {
    io.apitomy.antiwire.parity.wire.schema.ProtoFile protoFile =
        schema.protoFile(file.toString());
    byte[] descriptorBytes = new io.apitomy.antiwire.parity.wire.schema.internal.SchemaEncoder(
        schema).encode(protoFile).toByteArray();
    return DualRunOutput.loaded(descriptorBytes, protoFile.toSchema(),
        RangeProbes.collect(treeOf(protoFile.getTypes())));
  }

  private static List<RangeProbes.TypeTree> treeOf(
      List<io.apitomy.antiwire.parity.wire.schema.Type> types) {
    List<RangeProbes.TypeTree> trees = new ArrayList<>();
    for (io.apitomy.antiwire.parity.wire.schema.Type type : types) {
      trees.add(nodeOf(type));
    }
    return trees;
  }

  private static RangeProbes.TypeTree nodeOf(io.apitomy.antiwire.parity.wire.schema.Type type) {
    final io.apitomy.antiwire.parity.wire.schema.Type theType = type;
    final List<RangeProbes.TypeTree> nested =
        type instanceof io.apitomy.antiwire.parity.wire.schema.MessageType
            ? treeOf(((io.apitomy.antiwire.parity.wire.schema.MessageType) type).getNestedTypes())
            : Collections.<RangeProbes.TypeTree>emptyList();
    return new RangeProbes.TypeTree() {
      @Override public String qualifiedName() {
        return theType.getType().toString();
      }

      @Override public List<Object> extensionsValues() {
        if (!(theType instanceof io.apitomy.antiwire.parity.wire.schema.MessageType)) {
          return Collections.emptyList();
        }
        List<Object> values = new ArrayList<>();
        for (io.apitomy.antiwire.parity.wire.schema.Extensions extensions
            : ((io.apitomy.antiwire.parity.wire.schema.MessageType) theType).getExtensionsList()) {
          values.addAll(extensions.getValues());
        }
        return values;
      }

      @Override public List<RangeProbes.TypeTree> nested() {
        return nested;
      }
    };
  }
}
