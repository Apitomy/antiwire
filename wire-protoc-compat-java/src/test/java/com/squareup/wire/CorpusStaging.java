package com.squareup.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TASK-39 corpus staging: what a consumer like Apicurio Registry does before handing proto
 * sources to Wire. Each corpus file is copied into a fresh per-file java.nio temp root at a
 * path mirroring its declared package (Wire rejects a source-path file whose path does not
 * end with its import path, the package-vs-path rule), and its transitive import closure is
 * staged alongside, each dependency at exactly its import path, with content resolved from
 * the corpus root plus the vendored well-known protos of the upstream clone. An import that
 * resolves nowhere is left unstaged on purpose: both sides then fail to find it identically
 * and the file compares as an outcome (unresolvable imports are outcome-parity material).
 */
final class CorpusStaging {
  private static final Pattern PACKAGE = Pattern.compile(
      "^\\s*package\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*;",
      Pattern.MULTILINE);
  private static final Pattern IMPORT = Pattern.compile(
      "^\\s*import\\s+(?:(?:public|weak)\\s+)?\"([^\"]+)\"\\s*;",
      Pattern.MULTILINE);
  private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
  private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

  /** One staged corpus file: the per-file temp root and the target's path inside it. */
  static final class Staged {
    /** Temp root holding the target plus its staged import closure; shared by both sides. */
    final Path root;
    /** Path of the corpus file inside {@link #root}, mirroring its declared package. */
    final String relativePath;

    Staged(Path root, String relativePath) {
      this.root = root;
      this.relativePath = relativePath;
    }
  }

  /** Stages {@code corpusRoot/relativePath} and its import closure into a fresh temp root. */
  static Staged stage(Path corpusRoot, String relativePath, Path vendoredWellKnown)
      throws IOException {
    byte[] source = Files.readAllBytes(corpusRoot.resolve(relativePath));
    String stripped = stripComments(new String(source, StandardCharsets.UTF_8));
    String pkg = packageName(stripped);
    String basename = relativePath.substring(relativePath.lastIndexOf('/') + 1);
    String stagedPath = pkg == null ? basename : pkg.replace('.', '/') + "/" + basename;
    Path root = Files.createTempDirectory("antiwire-dual-run");
    write(root, stagedPath, source);
    Deque<String> work = new ArrayDeque<>(imports(stripped));
    Set<String> stagedImports = new LinkedHashSet<>();
    while (!work.isEmpty()) {
      String importPath = work.poll();
      if (!stagedImports.add(importPath) || importPath.equals(stagedPath)) {
        continue;
      }
      byte[] content = resolve(corpusRoot, vendoredWellKnown, importPath);
      if (content == null) {
        continue;
      }
      write(root, importPath, content);
      for (String transitive : imports(stripComments(new String(content,
          StandardCharsets.UTF_8)))) {
        work.add(transitive);
      }
    }
    return new Staged(root, stagedPath);
  }

  /**
   * Deterministic proto-source scans over comment-stripped text: the first package
   * declaration (null for the default package) and every import statement, in file order.
   * The scans only decide WHERE a file is staged; both sides always parse the original
   * bytes verbatim, so a scan quirk can never make the two sides disagree.
   */
  static String packageName(String stripped) {
    Matcher matcher = PACKAGE.matcher(stripped);
    return matcher.find() ? matcher.group(1) : null;
  }

  static List<String> imports(String stripped) {
    List<String> result = new ArrayList<>();
    Matcher matcher = IMPORT.matcher(stripped);
    while (matcher.find()) {
      result.add(matcher.group(1));
    }
    return result;
  }

  /** Block comments collapse to one space (tokens must not fuse); line comments vanish. */
  static String stripComments(String source) {
    return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll("");
  }

  /** Import content: the corpus root first, then the vendored well-known protos. */
  private static byte[] resolve(Path corpusRoot, Path vendoredWellKnown, String importPath)
      throws IOException {
    Path inRoot = corpusRoot.resolve(importPath);
    if (Files.isRegularFile(inRoot)) {
      return Files.readAllBytes(inRoot);
    }
    if (importPath.startsWith("google/protobuf/")) {
      Path vendored = vendoredWellKnown.resolve(importPath.substring("google/protobuf/".length()));
      if (Files.isRegularFile(vendored)) {
        return Files.readAllBytes(vendored);
      }
    }
    return null;
  }

  private static void write(Path root, String path, byte[] content) throws IOException {
    Path target = root.resolve(path);
    if (Files.exists(target)) {
      return;
    }
    Files.createDirectories(target.getParent());
    Files.write(target, content);
  }

  /** Deletes a staged root tree; a best-effort cleanup, never a test concern. */
  static void deleteRecursively(Path root) {
    try {
      Files.walk(root)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // Temp-dir cleanup on a best-effort basis only.
    }
  }

  private CorpusStaging() {
    throw new AssertionError("no instances");
  }
}
