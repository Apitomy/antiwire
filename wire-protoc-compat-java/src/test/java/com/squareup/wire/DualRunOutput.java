package com.squareup.wire;

import java.util.List;

/**
 * TASK-39 dual-run identity harness value types. The compare boundary passes only bytes,
 * strings, ints and type tags as strings; no port or upstream object crosses it.
 * Java 11: no records (the build targets --release 11); final fields, like TestFiles.
 */
final class DualRunOutput {
  enum Kind { LOADED, THREW }

  final Kind kind;
  /** SchemaEncoder.encode raw FileDescriptorProto bytes; null when THREW. */
  final byte[] descriptorBytes;
  /** ProtoFile.toSchema() text; null when THREW. */
  final String canonicalText;
  /** Normalized failure chain (root cause simple name, message); empty when LOADED. */
  final List<ChainEntry> failureChain;
  /** DEC-15 element-type facts for every extensions/reserved range; empty when THREW. */
  final List<RangeProbe> rangeProbes;

  DualRunOutput(Kind kind, byte[] descriptorBytes, String canonicalText,
      List<ChainEntry> failureChain, List<RangeProbe> rangeProbes) {
    this.kind = kind;
    this.descriptorBytes = descriptorBytes;
    this.canonicalText = canonicalText;
    this.failureChain = failureChain;
    this.rangeProbes = rangeProbes;
  }

  static DualRunOutput loaded(byte[] descriptorBytes, String canonicalText,
      List<RangeProbe> rangeProbes) {
    return new DualRunOutput(Kind.LOADED, descriptorBytes, canonicalText,
        java.util.Collections.emptyList(), rangeProbes);
  }

  static DualRunOutput threw(List<ChainEntry> failureChain) {
    return new DualRunOutput(Kind.THREW, null, null, failureChain,
        java.util.Collections.emptyList());
  }

  /** Compact, ledger-friendly signature of this output for one compare operation. */
  String shape(String op) {
    switch (op) {
      case "load-outcome":
        return kind.name();
      case "failure-chain":
        if (kind == Kind.LOADED) return "LOADED";
        StringBuilder sb = new StringBuilder();
        for (ChainEntry entry : failureChain) {
          if (sb.length() > 0) sb.append("<<");
          sb.append(entry.className).append("|").append(entry.message);
        }
        return sb.toString();
      case "descriptor-bytes":
        return "len=" + descriptorBytes.length + " sha256=" + DualRunIdentityTest.sha256(descriptorBytes);
      case "canonical-text":
        return "len=" + canonicalText.length() + " sha256="
            + DualRunIdentityTest.sha256(canonicalText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      default:
        throw new IllegalArgumentException("unknown op: " + op);
    }
  }
}

/** One normalized failure-chain entry: root cause simple name plus first message line. */
final class ChainEntry {
  /** Root cause simple class name (the port's UncheckedIOException wrapper vanishes here). */
  final String className;
  /** First line of the root cause's message; clone paths replaced by <corpus-root>. */
  final String message;

  ChainEntry(String className, String message) {
    this.className = className;
    this.message = message;
  }
}

/** DEC-15 element-type facts for one extensions/reserved range declaration. */
final class RangeProbe {
  /** Stable label, e.g. "extensions@probe.substrate.M". */
  final String owner;
  /** Flattened {start, endInclusive} pairs, identical on both sides. */
  final List<int[]> bounds;
  /**
   * Value shapes of the declaration as data, one entry per value in declaration order:
   * "Integer", "int[]" (port) or "kotlin.ranges.IntRange" (upstream). One declaration can
   * carry several values ("extensions 100 to 199, 1000 to max;"), so the comparison is
   * element-wise.
   */
  final List<String> elementTypes;

  RangeProbe(String owner, List<int[]> bounds, List<String> elementTypes) {
    this.owner = owner;
    this.bounds = bounds;
    this.elementTypes = elementTypes;
  }
}

/** One corpus file to load through both implementations. */
final class CorpusFile {
  /** "upstream" or "apicurio"; the clone half the file lives in. */
  final String half;
  /**
   * Corpus root from config/dual-run-corpus.tsv, clone-relative (for example
   * utils/protobuf-schema-utilities/src/test/proto): the physical-file identity inside the
   * pinned clone, because relativePath alone is ambiguous where roots repeat file names.
   */
  final String root;
  /** Path of the file relative to its corpus root (forward slashes). */
  final String relativePath;

  CorpusFile(String half, String root, String relativePath) {
    this.half = half;
    this.root = root;
    this.relativePath = relativePath;
  }

  /** The corpus root directory inside its half's pinned clone. */
  java.nio.file.Path absoluteRoot() {
    return (half.equals("upstream")
        ? com.squareup.wire.testing.TestFiles.upstreamClone()
        : DualRunIdentityTest.apicurioClone()).resolve(root);
  }

  @Override public String toString() {
    return half + "|" + relativePath;
  }
}
