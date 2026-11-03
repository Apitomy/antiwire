/*
 * Copyright (C) 2026 the antiwire authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * File fixtures for tests, the Java home of upstream wire-test-utils' testing.add / testing.addZip
 * (package com.squareup.wire.testing, filesJvm.kt). Tests keep their per-file upstream mapping and
 * pull these shared builders instead of pasting them.
 */
public final class TestFiles {
  /** Writes {@code content} to {@code base/relativePath}, creating parent directories. */
  public static Path add(Path base, String relativePath, String content) throws IOException {
    Path file = base.resolve(relativePath);
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  /**
   * Creates a zip at {@code base/relativePath} holding the name/content pairs, alternating:
   * {@code addZip(base, "lib.zip", "a.proto", "...", "b.proto", "...")}.
   */
  public static Path addZip(Path base, String relativePath, String... nameContentPairs)
      throws IOException {
    if (nameContentPairs.length % 2 != 0) {
      throw new IllegalArgumentException("expected name/content pairs");
    }
    Path file = base.resolve(relativePath);
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zipOut = new ZipOutputStream(out)) {
      for (int i = 0; i < nameContentPairs.length; i += 2) {
        zipOut.putNextEntry(new ZipEntry(nameContentPairs[i]));
        zipOut.write(nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();
      }
    }
    return file;
  }

  /** assertk's containsExactlyInAnyOrder / containsOnly, on JUnit 5. */
  public static void assertContainsExactlyInAnyOrder(List<?> expected, List<?> actual) {
    List<Object> remaining = new ArrayList<Object>(expected);
    for (Object value : actual) {
      if (!remaining.remove(value)) {
        throw new AssertionError("unexpected: " + value);
      }
    }
    if (!remaining.isEmpty()) {
      throw new AssertionError("missing: " + remaining);
    }
  }

  /** Kotlin's mapOf, insertion-ordered. */
  public static Map<String, Object> map(Object... pairs) {
    if (pairs.length % 2 != 0) {
      throw new IllegalArgumentException("expected key/value pairs");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      result.put((String) pairs[i], pairs[i + 1]);
    }
    return result;
  }

  /** Kotlin's listOf. */
  public static <T> List<T> list(T... values) {
    return new ArrayList<>(Arrays.asList(values));
  }

  /**
   * Upstream wire-test-utils' {@code FileSystem.findFiles}: every non-directory file under
   * {@code dir}, recursively, as forward-slash paths relative to {@code dir}.
   */
  public static java.util.Set<String> findFiles(java.nio.file.Path dir) throws IOException {
    java.util.Set<String> result = new java.util.LinkedHashSet<>();
    try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(dir)) {
      walk.filter(Files::isRegularFile)
          .forEach(path -> result.add(dir.relativize(path).toString().replace('\\', '/')));
    }
    return result;
  }

  /** Upstream wire-test-utils' {@code FileSystem.readUtf8}. */
  public static String readUtf8(java.nio.file.Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  /**
   * The pinned upstream square/wire clone that tests read pinned goldens and protos from: the
   * {@code ANTIWIRE_UPSTREAM} environment variable (set by CI), defaulting to /tmp/wire, both
   * fetched by scripts/fetch-upstream.sh. Throws when the clone is absent rather than skipping,
   * so a clone-dependent suite cannot silently not run.
   */
  public static Path upstreamClone() {
    return pinnedClone("ANTIWIRE_UPSTREAM", "/tmp/wire", "scripts/fetch-upstream.sh");
  }

  /**
   * A pinned clone looked up by environment variable with a default path, both fetched by the
   * named script (the upstream and apicurio corpus clones of the parity suites). Throws when
   * the clone is absent rather than skipping, so a clone-dependent suite cannot silently not
   * run.
   */
  public static Path pinnedClone(String environmentVariable, String defaultPath,
      String fetchScript) {
    String clone = System.getenv(environmentVariable);
    Path path = Path.of(clone == null || clone.isEmpty() ? defaultPath : clone);
    if (!Files.isDirectory(path)) {
      throw new IllegalStateException(
          "pinned clone not found at " + path + "; run " + fetchScript);
    }
    return path;
  }

  /** Upstream wire-test-utils' {@code containsExactlyInAnyOrderAsRelativePaths}. */
  public static void assertContainsExactlyInAnyOrderAsRelativePaths(
      java.util.Set<String> actual, String... values) {
    assertContainsExactlyInAnyOrder(java.util.Arrays.asList(values), new java.util.ArrayList<>(actual));
  }

  private TestFiles() {
    throw new AssertionError("no instances");
  }
}
