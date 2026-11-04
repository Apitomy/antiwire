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
package com.squareup.wire.schema;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TASK-33.5: {@link JdkSchemaLoader#forFileSystem(FileSystem)} over an in-memory Jimfs
 * filesystem. The imported file sits on the proto path but not the source path, so every test
 * here exercises import resolution against the supplied filesystem, the route Apicurio's loader
 * depends on; a schema whose imports are all also source files would take the source-path cache
 * and prove nothing about resolution.
 */
public class JdkSchemaLoaderInMemoryTest {
  private FileSystem fs;

  @BeforeEach public void setUp() {
    fs = Jimfs.newFileSystem();
  }

  @AfterEach public void tearDown() throws IOException {
    fs.close();
  }

  private Path write(FileSystem target, String path, String content) throws IOException {
    Path file = target.getPath(path);
    Files.createDirectories(file.getParent());
    Files.write(file, content.getBytes(UTF_8));
    return file;
  }

  private void writePet(FileSystem target, String root) throws IOException {
    write(target, root + "/com/example/cased/pet.proto", ""
        + "syntax = \"proto3\";\n"
        + "package com.example.cased;\n"
        + "message Pet { string name = 1; }\n");
  }

  private Path writeOwner(FileSystem target, String root, String importPath) throws IOException {
    return write(target, root + "/com/example/cased/owner.proto", ""
        + "syntax = \"proto3\";\n"
        + "package com.example.cased;\n"
        + "import \"" + importPath + "\";\n"
        + "message Owner { Pet pet = 1; }\n");
  }

  /**
   * A schema loads and its import resolves entirely from memory: the source path is the single
   * owner file, the imported common file exists only on the supplied filesystem's proto path and
   * nowhere on the host, so a linked Common proves resolution went through the supplied
   * filesystem.
   */
  @Test public void resolvesImportsEntirelyFromMemory() throws Exception {
    write(fs, "/protos/com/example/inmem/common.proto", ""
        + "syntax = \"proto3\";\n"
        + "package com.example.inmem;\n"
        + "message Common { string id = 1; }\n");
    Path source = write(fs, "/protos/com/example/inmem/messages.proto", ""
        + "syntax = \"proto3\";\n"
        + "package com.example.inmem;\n"
        + "import \"com/example/inmem/common.proto\";\n"
        + "message Messages { Common common = 1; }\n");

    try (JdkSchemaLoader loader = JdkSchemaLoader.forFileSystem(fs)) {
      loader.initRoots(Collections.singletonList(source),
          Collections.singletonList(fs.getPath("/protos")));
      Schema schema = loader.loadSchema();

      assertNotNull(schema.getType("com.example.inmem.Messages"));
      assertNotNull(schema.getType("com.example.inmem.Common"), "the import did not resolve");
      assertNotNull(schema.protoFile("com/example/inmem/common.proto"));
    }
  }

  /**
   * Resolution follows the supplied filesystem's rules, not the host's. The same wrong-case
   * import fails on a case-sensitive Jimfs and loads on a case-insensitive one: the opposite
   * outcome per filesystem is only possible if the decision is made inside the supplied
   * filesystem. This is the macOS APFS leak of the Apicurio migration made mechanical: staging
   * into a host temp directory let the same wrong-case import resolve locally and fail on
   * case-sensitive CI.
   *
   * <p>Both filesystems must be built from explicit configurations: the no-arg
   * {@code Jimfs.newFileSystem()} derives its configuration from the current platform
   * (jimfs 1.3.2 {@code Configuration.forCurrentPlatform()}, case-insensitive on macOS), so the
   * case-sensitive half pins the unix configuration. Relying on the no-arg factory was this
   * test's platform-dependent birth defect: it never passed on macOS.
   */
  @Test public void caseSensitivityComesFromTheSuppliedFileSystem() throws Exception {
    try (FileSystem caseSensitive = Jimfs.newFileSystem(Configuration.unix())) {
      writePet(caseSensitive, "/protos");
      Path wrongCase = writeOwner(caseSensitive, "/protos", "com/example/cased/Pet.proto");

      try (JdkSchemaLoader loader = JdkSchemaLoader.forFileSystem(caseSensitive)) {
        loader.initRoots(Collections.singletonList(wrongCase),
            Collections.singletonList(caseSensitive.getPath("/protos")));
        assertThrows(SchemaException.class, loader::loadSchema);
      }
    }

    try (FileSystem macLike = Jimfs.newFileSystem(Configuration.osX())) {
      writePet(macLike, "/protos");
      Path wrongCase = writeOwner(macLike, "/protos", "com/example/cased/Pet.proto");
      try (JdkSchemaLoader loader = JdkSchemaLoader.forFileSystem(macLike)) {
        loader.initRoots(Collections.singletonList(wrongCase),
            Collections.singletonList(macLike.getPath("/protos")));
        assertNotNull(loader.loadSchema().getType("com.example.cased.Owner"),
            "a case-insensitive supplied filesystem must resolve the wrong-case import");
      }
    }
  }
}
