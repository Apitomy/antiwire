package com.squareup.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.squareup.wire.testing.TestFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TASK-39 dual-run output-identity harness: every corpus .proto is loaded once per file
 * into a fresh schema on both sides, real Wire 7.1.0 (relocated fixture under
 * io.apitomy.antiwire.parity.wire.*) and the port (com.squareup.wire.*), and asserted
 * identical: raw descriptor bytes (SchemaEncoder.encode, no stripper: this is Wire versus
 * Wire), canonical text (ProtoFile.toSchema()), load outcome with normalized failure
 * chains, and the DEC-15 range element-type divergence in exactly the recorded shape.
 * A difference with no row in config/dual-run-divergences.tsv fails with the row to
 * record; a committed row nothing observed, or whose doc_ref anchor is missing, fails the
 * census tests. Nothing is skipped and nothing is ignored.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DualRunIdentityTest {
  static final SchemaSide PORT = new PortSide();
  static final SchemaSide UPSTREAM = new UpstreamSide();

  /** (half|path|op) keys observed this run; feeds the stale-row census. */
  static final Set<String> OBSERVED = new LinkedHashSet<>();

  static List<CorpusFile> corpusCache;

  /** Corpus file -> its root, filled once by corpus() (enumerate-once invariant). */
  static final Map<CorpusFile, Path> ROOTS = new java.util.LinkedHashMap<>();

  /** Enumerates config/dual-run-corpus.tsv roots over both pinned clones, ONCE per run
   *  into the shared list both sides consume (enumerate-once invariant). Clone absent
   *  -> IllegalStateException, never a skip. */
  static synchronized List<CorpusFile> corpus() {
    if (corpusCache != null) {
      return corpusCache;
    }
    List<CorpusFile> files = new ArrayList<>();
    try (Stream<String> lines = Files.lines(repoRoot().resolve("config/dual-run-corpus.tsv"),
        StandardCharsets.UTF_8)) {
      List<String[]> roots = new ArrayList<>();
      lines.filter(l -> !l.isEmpty())
          .map(l -> l.split("\t", -1))
          .forEach(roots::add);
      boolean first = true;
      for (String[] fields : roots) {
        if (first) {
          if (!fields[0].equals("half") || !fields[1].equals("root")) {
            throw new IllegalStateException("corpus TSV header mismatch: " + Arrays.toString(fields));
          }
          first = false;
          continue;
        }
        Path clone = fields[0].equals("upstream") ? TestFiles.upstreamClone() : apicurioClone();
        Path root = clone.resolve(fields[1]);
        if (!Files.isDirectory(root)) {
          throw new IllegalStateException("corpus root missing in " + fields[0] + " clone: " + root);
        }
        // TestFiles.findFiles is upstream wire-test-utils' recursive walker; the .proto
        // filter and the deterministic sort sit on top of it.
        for (String relative : TestFiles.findFiles(root)
            .stream()
            .filter(p -> p.endsWith(".proto"))
            .sorted()
            .collect(java.util.stream.Collectors.toList())) {
          CorpusFile corpusFile = new CorpusFile(fields[0], relative);
          files.add(corpusFile);
          ROOTS.put(corpusFile, root);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot read config/dual-run-corpus.tsv", e);
    }
    corpusCache = files;
    return files;
  }

  @Order(1)
  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("corpus")
  void identity(CorpusFile file) {
    Path root = corpusRoot(file);
    DualRunOutput port = PORT.load(root, file.relativePath);
    DualRunOutput upstream = UPSTREAM.load(root, file.relativePath);
    assertOutcomeKind(file, port, upstream);
    assertFailureChain(file, port, upstream);
    assertDescriptorBytes(file, port, upstream);
    assertCanonicalText(file, port, upstream);
    assertRangeProbe(file, port, upstream);
  }

  /** Census: every corpus root from the TSV was enumerated non-empty; the pins moved with
   *  the corpus, not under it. */
  @Order(2)
  @Test void corpusCensus() {
    List<CorpusFile> files = corpus();
    if (files.isEmpty()) {
      throw new AssertionError("dual-run corpus enumerated empty");
    }
    int upstream = 0;
    int apicurio = 0;
    for (CorpusFile file : files) {
      if (file.half.equals("upstream")) {
        upstream++;
      } else {
        apicurio++;
      }
    }
    if (upstream == 0 || apicurio == 0) {
      throw new AssertionError("a corpus half enumerated empty: upstream=" + upstream
          + " apicurio=" + apicurio);
    }
  }

  /** Census: every committed ledger row was observed this run. A stale row fails. */
  @Order(3)
  @Test void ledgerRowsAllObserved() {
    for (DualRunDivergences.DivergenceRow row : DualRunDivergences.all()) {
      if (!OBSERVED.contains(row.key())) {
        throw new AssertionError("stale ledger row, no observation matched it this run: "
            + rowToString(row));
      }
    }
  }

  // ---- the five named assert methods ----
  // Each consults the ledger for its own (half, path, op) row through
  // assertNarrowedOrDiverge; a covered divergence becomes a narrowed assertion (shapes
  // must equal the row exactly), an uncovered one throws with the exact TSV row to record.

  static void assertOutcomeKind(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    assertNarrowedOrDiverge("load-outcome", f, p, u, p.kind == u.kind);
  }

  static void assertFailureChain(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String op = "failure-chain";
    // Both sides must have thrown for a chain comparison; mixed outcomes are the
    // load-outcome op and were already reported there.
    if (p.kind != DualRunOutput.Kind.THREW || u.kind != DualRunOutput.Kind.THREW) {
      return;
    }
    assertNarrowedOrDiverge(op, f, p, u,
        p.shape(op).equals(u.shape(op)));
  }

  static void assertDescriptorBytes(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String op = "descriptor-bytes";
    if (p.kind != DualRunOutput.Kind.LOADED || u.kind != DualRunOutput.Kind.LOADED) {
      return;
    }
    // RAW-BYTE, no UnwantedValueStripper: that stripper is the protoc oracle's
    // knowingly-lossy lens; Wire-vs-Wire needs none.
    assertNarrowedOrDiverge(op, f, p, u, Arrays.equals(p.descriptorBytes, u.descriptorBytes));
  }

  static void assertCanonicalText(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String op = "canonical-text";
    if (p.kind != DualRunOutput.Kind.LOADED || u.kind != DualRunOutput.Kind.LOADED) {
      return;
    }
    assertNarrowedOrDiverge(op, f, p, u, p.canonicalText.equals(u.canonicalText));
  }

  /**
   * DEC-15 narrowed assertion, inline rationale, not a ledger row: bounds equal AND
   * element types equal unless the range case differs exactly as port="int[]" versus
   * upstream="kotlin.ranges.IntRange". If upstream ever returns int[] this fails loudly.
   */
  static void assertRangeProbe(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String op = "range-probe";
    if (p.kind != DualRunOutput.Kind.LOADED || u.kind != DualRunOutput.Kind.LOADED) {
      return;
    }
    assertEquals(p.rangeProbes.size(), u.rangeProbes.size(),
        "range probe count diverged on " + f.half + "|" + f.relativePath);
    for (int i = 0; i < p.rangeProbes.size(); i++) {
      RangeProbe port = p.rangeProbes.get(i);
      RangeProbe upstream = u.rangeProbes.get(i);
      if (!port.owner.equals(upstream.owner)) {
        throw new AssertionError("range probe owners misaligned on "
            + f.half + "|" + f.relativePath + ": " + port.owner + " vs " + upstream.owner);
      }
      assertBoundsEqual(port, upstream, f, port.owner);
      if (port.elementType.contains("int[]")) {
        // DEC-15: the port stores int[] pairs where real Wire stores kotlin.ranges.IntRange.
        assertEquals("kotlin.ranges.IntRange", upstream.elementType,
            "DEC-15 drifted on " + f.half + "|" + f.relativePath + " " + port.owner
                + ": port=" + port.elementType + " upstream=" + upstream.elementType);
      } else {
        assertEquals(port.elementType, upstream.elementType,
            "range element type diverged on " + f.half + "|" + f.relativePath
                + " " + port.owner);
      }
    }
    OBSERVED.add(DualRunDivergences.key(f.half, f.relativePath, op));
  }

  // ---- helpers ----

  /** The shared ledger consultation: equal passes and is recorded, a covered divergence
   *  is narrowed to the row's exact shapes (recorded), an uncovered one throws the row. */
  static void assertNarrowedOrDiverge(String op, CorpusFile f, DualRunOutput p,
      DualRunOutput u, boolean equal) {
    if (equal) {
      OBSERVED.add(DualRunDivergences.key(f.half, f.relativePath, op));
      return;
    }
    java.util.Optional<DualRunDivergences.DivergenceRow> row =
        DualRunDivergences.match(f.half, f.relativePath, op);
    if (row.isPresent()) {
      assertEquals(row.get().portShape, p.shape(op), narrowLabel(f, op, "port"));
      assertEquals(row.get().upstreamShape, u.shape(op), narrowLabel(f, op, "upstream"));
      OBSERVED.add(DualRunDivergences.key(f.half, f.relativePath, op));
    } else {
      throw divergence(f, op, p, u);
    }
  }

  static void assertBoundsEqual(RangeProbe port, RangeProbe upstream, CorpusFile f,
      String owner) {
    assertEquals(port.bounds.size(), upstream.bounds.size(),
        "bounds count diverged on " + f.half + "|" + f.relativePath + " " + owner);
    for (int i = 0; i < port.bounds.size(); i++) {
      if (!Arrays.equals(port.bounds.get(i), upstream.bounds.get(i))) {
        throw new AssertionError("bounds diverged on " + f.half + "|" + f.relativePath
            + " " + owner + " #" + i + ": " + Arrays.toString(port.bounds.get(i))
            + " vs " + Arrays.toString(upstream.bounds.get(i)));
      }
    }
  }

  static AssertionError divergence(CorpusFile f, String op, DualRunOutput p, DualRunOutput u) {
    String portShape = p.shape(op);
    String upstreamShape = u.shape(op);
    return new AssertionError("DUAL-RUN DIVERGENCE [" + f.half + "|" + f.relativePath
        + "] op=" + op
        + " port=" + portShape
        + " upstream=" + upstreamShape
        + " : record this row in config/dual-run-divergences.tsv (with a reason and a"
        + " docs/compatibility-matrix.md anchor):\n"
        + f.half + "\t" + f.relativePath + "\t" + op + "\t" + portShape + "\t"
        + upstreamShape + "\tTODO-reason\tdocs/compatibility-matrix.md: TODO-anchor");
  }

  static String narrowLabel(CorpusFile f, String op, String side) {
    return f.half + "|" + f.relativePath + " op=" + op + " " + side
        + " shape (narrowed by a ledger row)";
  }

  static String rowToString(DualRunDivergences.DivergenceRow row) {
    return String.join("\t", row.half, row.path, row.op, row.portShape, row.upstreamShape,
        row.reason, row.docRef);
  }

  static Path repoRoot() {
    return DualRunDivergences.repoRoot();
  }

  static Path corpusRoot(CorpusFile file) {
    Path root = ROOTS.get(file);
    if (root == null) {
      throw new IllegalStateException("corpus file was never enumerated: " + file);
    }
    return root;
  }

  static Path apicurioClone() {
    String clone = System.getenv("ANTIWIRE_APICURIO");
    Path path = java.nio.file.Paths.get(
        clone == null || clone.isEmpty() ? "/tmp/apicurio-registry" : clone);
    if (!Files.isDirectory(path)) {
      throw new IllegalStateException(
          "pinned apicurio clone not found at " + path + "; run scripts/fetch-apicurio.sh");
    }
    return path;
  }

  static String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      StringBuilder sb = new StringBuilder();
      for (byte b : digest.digest(bytes)) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
