package com.squareup.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.squareup.wire.testing.TestFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
 * TASK-39 dual-run output-identity harness: every corpus .proto is staged the way a
 * consumer like Apicurio Registry stages it (a per-file temp root with the file at a path
 * mirroring its declared package plus its staged import closure, CorpusStaging) and loaded
 * once per file into a fresh schema on both sides, real Wire 7.1.0 (relocated fixture under
 * io.apitomy.antiwire.parity.wire.*) and the port (com.squareup.wire.*), asserting
 * identical: raw descriptor bytes (SchemaEncoder.encode, no stripper: this is Wire versus
 * Wire), canonical text (ProtoFile.toSchema()), load outcome with normalized failure
 * chains, and the DEC-15 range element-type divergence in exactly the recorded shape.
 * A difference with no row in config/dual-run-divergences.tsv fails with the row to
 * record; a committed row nothing observed, or whose doc_ref anchor is missing from the
 * cited doc, fails the census tests. Nothing is skipped and nothing is ignored. The
 * corpus census floors the per-half both-LOADED coverage (staged loading measured
 * 2026-11-03: upstream 141, apicurio 31) so a green run can never mean empty coverage.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DualRunIdentityTest {
  static final SchemaSide PORT = new PortSide();
  static final SchemaSide UPSTREAM = new UpstreamSide();

  /** (half|root|path|op) keys observed this run; feeds the stale-row census. */
  static final Set<String> OBSERVED = new LinkedHashSet<>();

  /** Per-file load outcome this run (both-LOADED / both-THREW / divergent); feeds the
   *  coverage census. Keyed on the file object itself: the enumerate-once cache hands the
   *  same instances to the parameterized test and this census, so identity keys work. */
  static final Map<CorpusFile, String> OUTCOMES = new LinkedHashMap<>();

  /** Both-LOADED coverage floors per half, at the values measured on 2026-11-03 over the
   *  pinned corpora (wire 9f62097dfe4995b5709d001ca0187e30ca0530ef, apicurio
   *  23a09ab4e128e8a308efbcc333c5318734126b55) once package-mirroring staging landed:
   *  exact measurements, not margins, so coverage can grow but never silently shrink. */
  static final int MIN_BOTH_LOADED_UPSTREAM = 141;
  static final int MIN_BOTH_LOADED_APICURIO = 31;

  static List<CorpusFile> corpusCache;

  /** Enumerates config/dual-run-corpus.tsv roots over both pinned clones, ONCE per run
   *  into the shared list both sides consume (enumerate-once invariant). Clone absent
   *  -> IllegalStateException, never a skip. */
  static synchronized List<CorpusFile> corpus() {
    if (corpusCache != null) {
      return corpusCache;
    }
    List<CorpusFile> files = new ArrayList<>();
    try (Stream<String> lines = Files.lines(
        DualRunDivergences.repoRoot().resolve("config/dual-run-corpus.tsv"),
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
        Path root = cloneOf(fields[0]).resolve(fields[1]);
        if (!Files.isDirectory(root)) {
          throw new IllegalStateException("corpus root missing in " + fields[0] + " clone: "
              + root);
        }
        // TestFiles.findFiles is upstream wire-test-utils' recursive walker; the .proto
        // filter and the deterministic sort sit on top of it.
        for (String relative : TestFiles.findFiles(root)
            .stream()
            .filter(p -> p.endsWith(".proto"))
            .sorted()
            .collect(java.util.stream.Collectors.toList())) {
          files.add(new CorpusFile(fields[0], fields[1], relative, root));
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
  void identity(CorpusFile file) throws Exception {
    CorpusStaging.Staged staged =
        CorpusStaging.stage(file.rootPath, file.relativePath, vendoredWellKnownRoot());
    DualRunOutput port;
    DualRunOutput upstream;
    try {
      port = PORT.load(staged.root, staged.relativePath);
      upstream = UPSTREAM.load(staged.root, staged.relativePath);
    } finally {
      CorpusStaging.deleteRecursively(staged.root);
    }
    recordOutcome(file, port, upstream);
    assertOutcomeKind(file, port, upstream);
    // The other four ops compare like outcomes only: a mixed pair is the load-outcome
    // op's business, where a ledger row legitimizes the outcome difference.
    if (port.kind == DualRunOutput.Kind.THREW && upstream.kind == DualRunOutput.Kind.THREW) {
      assertFailureChain(file, port, upstream);
    } else if (port.kind == DualRunOutput.Kind.LOADED
        && upstream.kind == DualRunOutput.Kind.LOADED) {
      assertDescriptorBytes(file, port, upstream);
      assertCanonicalText(file, port, upstream);
      assertRangeProbe(file, port, upstream);
    }
  }

  /** Census: every corpus half enumerated non-empty (the pins moved with the corpus, not
   *  under it), every enumerated file loaded this run, and the measured both-LOADED
   *  coverage per half at or above its floor, so green cannot mean empty coverage. Prints
   *  the DUAL-RUN-COVERAGE line (per-half invocation and outcome counts) the suite script
   *  checks against its own per-half corpus enumeration, so a stale report from a
   *  same-total corpus cannot pass. */
  @Order(2)
  @Test void corpusCensus() {
    int upstream = 0;
    int apicurio = 0;
    int loadedUpstream = 0;
    int loadedApicurio = 0;
    int threwUpstream = 0;
    int threwApicurio = 0;
    int divergentUpstream = 0;
    int divergentApicurio = 0;
    for (CorpusFile file : corpus()) {
      String outcome = OUTCOMES.get(file);
      if (outcome == null) {
        throw new AssertionError("corpus file was never loaded this run: " + file);
      }
      boolean isUpstream = file.half.equals("upstream");
      if (isUpstream) {
        upstream++;
      } else {
        apicurio++;
      }
      switch (outcome) {
        case "both-LOADED":
          if (isUpstream) loadedUpstream++; else loadedApicurio++;
          break;
        case "both-THREW":
          if (isUpstream) threwUpstream++; else threwApicurio++;
          break;
        default:
          if (isUpstream) divergentUpstream++; else divergentApicurio++;
      }
    }
    if (upstream == 0 || apicurio == 0) {
      throw new AssertionError("a corpus half enumerated empty: upstream=" + upstream
          + " apicurio=" + apicurio);
    }
    if (loadedUpstream < MIN_BOTH_LOADED_UPSTREAM || loadedApicurio < MIN_BOTH_LOADED_APICURIO) {
      throw new AssertionError("both-LOADED coverage below floor: upstream=" + loadedUpstream
          + " (floor " + MIN_BOTH_LOADED_UPSTREAM + "), apicurio=" + loadedApicurio
          + " (floor " + MIN_BOTH_LOADED_APICURIO + "); staging regression or corpus change,"
          + " re-measure and move the floors with the corpus");
    }
    System.out.println("DUAL-RUN-COVERAGE"
        + " invocationsUpstream=" + upstream
        + " invocationsApicurio=" + apicurio
        + " loadedUpstream=" + loadedUpstream
        + " loadedApicurio=" + loadedApicurio
        + " threwUpstream=" + threwUpstream
        + " threwApicurio=" + threwApicurio
        + " divergentUpstream=" + divergentUpstream
        + " divergentApicurio=" + divergentApicurio);
  }

  /** Census: every committed ledger row was observed this run (a stale row fails) and its
   *  doc_ref anchor exists in the cited doc (DualRunDivergences.assertDocRefAnchor, the
   *  one Java implementation of the format), so the compatibility-matrix paragraph a row
   *  leans on cannot rot away from the assertion that enforces it. */
  @Order(3)
  @Test void ledgerRowsAllObserved() {
    for (DualRunDivergences.DivergenceRow row : DualRunDivergences.all()) {
      if (!OBSERVED.contains(row.key())) {
        throw new AssertionError("stale ledger row, no observation matched it this run: "
            + rowToString(row));
      }
      DualRunDivergences.assertDocRefAnchor(row);
    }
  }

  // ---- the five named assert methods ----
  // Each consults the ledger for its own (half, root, path, op) row through
  // assertNarrowedOrDiverge; a covered divergence becomes a narrowed assertion (shapes
  // must equal the row exactly), an uncovered one throws with the exact TSV row to record.

  static void assertOutcomeKind(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    assertNarrowedOrDiverge("load-outcome", f, p, u, p.kind == u.kind);
  }

  static void assertFailureChain(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String op = "failure-chain";
    assertNarrowedOrDiverge(op, f, p, u,
        p.shape(op).equals(u.shape(op)));
  }

  static void assertDescriptorBytes(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String op = "descriptor-bytes";
    // RAW-BYTE, no UnwantedValueStripper: that stripper is the protoc oracle's
    // knowingly-lossy lens; Wire-vs-Wire needs none.
    assertNarrowedOrDiverge(op, f, p, u, Arrays.equals(p.descriptorBytes, u.descriptorBytes));
  }

  static void assertCanonicalText(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String op = "canonical-text";
    assertNarrowedOrDiverge(op, f, p, u, p.canonicalText.equals(u.canonicalText));
  }

  /**
   * DEC-15 narrowed assertion, inline rationale, not a ledger row: bounds equal AND
   * element types equal, compared element-wise because one extensions declaration can
   * carry several values ("extensions 100 to 199, 1000 to max;" is ONE declaration with
   * two values); each port "int[]" pairs with an upstream "kotlin.ranges.IntRange". If
   * upstream ever returns int[] this fails loudly.
   */
  static void assertRangeProbe(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String op = "range-probe";
    assertEquals(p.rangeProbes.size(), u.rangeProbes.size(),
        "range probe count diverged on " + f);
    for (int i = 0; i < p.rangeProbes.size(); i++) {
      RangeProbe port = p.rangeProbes.get(i);
      RangeProbe upstream = u.rangeProbes.get(i);
      if (!port.owner.equals(upstream.owner)) {
        throw new AssertionError("range probe owners misaligned on "
            + f + ": " + port.owner + " vs " + upstream.owner);
      }
      assertBoundsEqual(port, upstream, f, port.owner);
      assertEquals(port.elementTypes.size(), upstream.elementTypes.size(),
          "range value count diverged on " + f + " " + port.owner);
      for (int v = 0; v < port.elementTypes.size(); v++) {
        String portShape = port.elementTypes.get(v);
        if (portShape.equals("int[]")) {
          // DEC-15: the port stores int[] pairs where real Wire stores kotlin.ranges.IntRange.
          assertEquals("kotlin.ranges.IntRange", upstream.elementTypes.get(v),
              "DEC-15 drifted on " + f + " " + port.owner
                  + " #" + v + ": port=" + portShape
                  + " upstream=" + upstream.elementTypes.get(v));
        } else {
          assertEquals(portShape, upstream.elementTypes.get(v),
              "range element type diverged on " + f + " " + port.owner + " #" + v);
        }
      }
    }
    OBSERVED.add(DualRunDivergences.key(f.half, f.root, f.relativePath, op));
  }

  // ---- helpers ----

  /** The shared ledger consultation, stale detection in BOTH directions: equal passes and
   *  is recorded, unless a committed row covers the key, in which case the recorded
   *  difference was fixed and the row is stale; a covered divergence is narrowed to the
   *  row's exact shapes (recorded); an uncovered one throws the row to record. */
  static void assertNarrowedOrDiverge(String op, CorpusFile f, DualRunOutput p,
      DualRunOutput u, boolean equal) {
    java.util.Optional<DualRunDivergences.DivergenceRow> row =
        DualRunDivergences.match(f.half, f.root, f.relativePath, op);
    if (equal) {
      if (row.isPresent()) {
        throw new AssertionError("stale divergence row, the shapes are equal this run (the"
            + " recorded difference was fixed or the corpus moved); delete the row: "
            + rowToString(row.get()));
      }
      OBSERVED.add(DualRunDivergences.key(f.half, f.root, f.relativePath, op));
      return;
    }
    if (row.isPresent()) {
      assertEquals(row.get().portShape, p.shape(op), narrowLabel(f, op, "port"));
      assertEquals(row.get().upstreamShape, u.shape(op), narrowLabel(f, op, "upstream"));
      OBSERVED.add(DualRunDivergences.key(f.half, f.root, f.relativePath, op));
    } else {
      throw divergence(f, op, p, u);
    }
  }

  static void recordOutcome(CorpusFile f, DualRunOutput p, DualRunOutput u) {
    String outcome = p.kind != u.kind
        ? "divergent"
        : p.kind == DualRunOutput.Kind.LOADED ? "both-LOADED" : "both-THREW";
    OUTCOMES.put(f, outcome);
  }

  static void assertBoundsEqual(RangeProbe port, RangeProbe upstream, CorpusFile f,
      String owner) {
    assertEquals(port.bounds.size(), upstream.bounds.size(),
        "bounds count diverged on " + f + " " + owner);
    for (int i = 0; i < port.bounds.size(); i++) {
      if (!Arrays.equals(port.bounds.get(i), upstream.bounds.get(i))) {
        throw new AssertionError("bounds diverged on " + f
            + " " + owner + " #" + i + ": " + Arrays.toString(port.bounds.get(i))
            + " vs " + Arrays.toString(upstream.bounds.get(i)));
      }
    }
  }

  static AssertionError divergence(CorpusFile f, String op, DualRunOutput p, DualRunOutput u) {
    String portShape = p.shape(op);
    String upstreamShape = u.shape(op);
    return new AssertionError("DUAL-RUN DIVERGENCE [" + f + "] op=" + op
        + " port=" + portShape
        + " upstream=" + upstreamShape
        + " : record this row in config/dual-run-divergences.tsv (with a reason and a"
        + " docs/compatibility-matrix.md anchor):\n"
        + f.half + "\t" + f.root + "\t" + f.relativePath + "\t" + op + "\t" + portShape + "\t"
        + upstreamShape + "\tTODO-reason\tdocs/compatibility-matrix.md: TODO-anchor");
  }

  static String narrowLabel(CorpusFile f, String op, String side) {
    return f + " op=" + op + " " + side
        + " shape (narrowed by a ledger row)";
  }

  static String rowToString(DualRunDivergences.DivergenceRow row) {
    return String.join("\t", row.half, row.root, row.path, row.op, row.portShape,
        row.upstreamShape, row.reason, row.docRef);
  }

  /** Wire's vendored well-known protos: the import-resolution fallback for both halves.
   *  Resolved once per run, not once per corpus file. */
  static Path vendoredWellKnownRoot() {
    if (vendoredWellKnown == null) {
      vendoredWellKnown = TestFiles.upstreamClone()
          .resolve("wire-schema/src/jvmMain/resources/google/protobuf");
      if (!Files.isDirectory(vendoredWellKnown)) {
        throw new IllegalStateException("vendored well-known protos missing from the upstream"
            + " clone: " + vendoredWellKnown);
      }
    }
    return vendoredWellKnown;
  }

  private static Path vendoredWellKnown;

  static Path cloneOf(String half) {
    return half.equals("upstream") ? TestFiles.upstreamClone() : apicurioClone();
  }

  static Path apicurioClone() {
    return TestFiles.pinnedClone("ANTIWIRE_APICURIO", "/tmp/apicurio-registry",
        "scripts/fetch-apicurio.sh");
  }
}
