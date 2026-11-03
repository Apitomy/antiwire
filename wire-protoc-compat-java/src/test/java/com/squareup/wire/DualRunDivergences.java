package com.squareup.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TASK-39 divergence ledger: config/dual-run-divergences.tsv. A row makes the observed
 * difference a NARROWED assertion (the shapes must equal the row exactly, so drift in
 * either direction fails); a difference with no row fails the build and prints the row to
 * record; a row whose shapes went back to equal fails as stale. Rows are keyed by
 * (half, root, path, op) with the corpus root from config/dual-run-corpus.tsv, because
 * relativePath alone is ambiguous where roots repeat file names (apicurio carries
 * byte-identical decimal.proto and metadata.proto under both src/main/proto and
 * src/test/proto): a row names exactly which physical file it covers. This file is the
 * ledger and never config/surface-baseline.tsv, which is a generated artifact of
 * scripts/surface-check.py (write_baseline opens it mode "w"): hand rows there would be
 * destroyed.
 */
final class DualRunDivergences {
  static final String[] HEADER =
      {"half", "root", "path", "op", "port_shape", "upstream_shape", "reason", "doc_ref"};

  static final class DivergenceRow {
    final String half;
    final String root;
    final String path;
    final String op;
    final String portShape;
    final String upstreamShape;
    final String reason;
    final String docRef;

    DivergenceRow(String[] f) {
      this.half = f[0];
      this.root = f[1];
      this.path = f[2];
      this.op = f[3];
      this.portShape = f[4];
      this.upstreamShape = f[5];
      this.reason = f[6];
      this.docRef = f[7];
    }

    String key() {
      return DualRunDivergences.key(half, root, path, op);
    }
  }

  /** The single ledger key format; a drift here breaks the stale-row census silently. */
  static String key(String half, String root, String path, String op) {
    return half + "|" + root + "|" + path + "|" + op;
  }

  private static Map<String, DivergenceRow> rows;

  private DualRunDivergences() {
  }

  /** Row for (half, root, path, op), or empty. Loaded once from config/dual-run-divergences.tsv. */
  static synchronized Optional<DivergenceRow> match(String half, String root, String path,
      String op) {
    if (rows == null) {
      rows = load();
    }
    return Optional.ofNullable(rows.get(key(half, root, path, op)));
  }

  /** All committed rows, for the stale-row census. */
  static synchronized List<DivergenceRow> all() {
    if (rows == null) {
      rows = load();
    }
    return new ArrayList<>(rows.values());
  }

  private static Map<String, DivergenceRow> load() {
    Path ledger = ledgerFile();
    Map<String, DivergenceRow> loaded = new LinkedHashMap<>();
    List<String> lines;
    try {
      lines = Files.readAllLines(ledger, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("divergence ledger missing: " + ledger, e);
    }
    boolean first = true;
    for (String line : lines) {
      if (line.isEmpty()) continue;
      String[] fields = line.split("\t", -1);
      if (first) {
        first = false;
        if (!Arrays.equals(fields, HEADER)) {
          throw new IllegalStateException("divergence ledger header mismatch in " + ledger
              + ": expected " + String.join("<TAB>", HEADER));
        }
        continue;
      }
      if (fields.length != HEADER.length) {
        throw new IllegalStateException("divergence ledger row with wrong field count in "
            + ledger + ": " + line);
      }
      DivergenceRow row = new DivergenceRow(fields);
      if (loaded.put(row.key(), row) != null) {
        // Two rows for one key is ambiguous (which row narrows the assertion?), never last-wins.
        throw new IllegalStateException("duplicate divergence ledger key in " + ledger + ": "
            + row.key());
      }
    }
    return Collections.unmodifiableMap(loaded);
  }

  /** The repo root holding config/, found by walking up from the working directory. */
  static Path repoRoot() {
    Path dir = java.nio.file.Paths.get("").toAbsolutePath();
    while (dir != null) {
      if (Files.exists(dir.resolve("config").resolve("dual-run-divergences.tsv"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("could not locate config/dual-run-divergences.tsv "
        + "above " + java.nio.file.Paths.get("").toAbsolutePath());
  }

  static Path ledgerFile() {
    return repoRoot().resolve("config").resolve("dual-run-divergences.tsv");
  }
}
