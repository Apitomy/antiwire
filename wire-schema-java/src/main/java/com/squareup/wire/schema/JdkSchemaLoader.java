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

import com.squareup.wire.schema.internal.CommonSchemaLoader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okio.FileSystem;

/**
 * The JDK-typed, consumer-facing schema loading facade (TASK-25, DEC-2). Every public signature
 * uses only JDK types: source and proto paths are {@link java.nio.file.Path}, classpath loading
 * takes a {@link ClassLoader}, and results are {@link Schema}, {@link ProtoFile} and
 * {@link ProtoType}. The vendored okio layer is used internally, exactly like the engine, but it
 * never leaks through this surface; {@code JdkSchemaLoaderValidationTest} enforces that
 * mechanically by reflection.
 *
 * <p>Consumers currently choreographing okio by hand, like Apicurio's {@code ProtobufSchemaLoader}
 * with its {@code FakeFileSystem} + {@code setWorkingDirectory} + {@code setAllowSymlinks} dance,
 * should replace that with this facade. Before and after:
 *
 * <pre>{@code
 * // Before (Apicurio, against okio):
 * FileSystem fs = new FakeFileSystem(singletonMap("/google/protobuf/descriptor.proto", bytes));
 * SchemaLoader loader = new SchemaLoader(fs);
 * loader.setWorkingDirectory("/");
 * loader.setAllowSymlinks(true);
 * loader.initRoots(singletonList(Location.get("/")), emptyList());
 * Schema schema = loader.loadSchema();
 *
 * // After (JDK-typed):
 * try (JdkSchemaLoader loader =
 *     JdkSchemaLoader.forClasspath(ProtobufSchemaLoader.class.getClassLoader(),
 *         "my/messages.proto")) {
 *   Schema schema = loader.loadSchema();
 * }
 * }</pre>
 *
 * <p>Descriptor.proto and the well-known Google protos resolve without any wiring of their own:
 * proto paths that miss them fall through to the bundled core loader, and when this facade loads
 * from a classpath they are also ordinary resources under {@code google/protobuf/}.
 */
public final class JdkSchemaLoader implements AutoCloseable {
  private final CommonSchemaLoader delegate;

  /** The okio file system this facade created and must close, or null. */
  private final FileSystem ownedFileSystem;

  /**
   * A loader over the default {@link java.nio.file.FileSystem}. Call {@link #initRoots(List,
   * List)} before {@link #loadSchema()}. Source path entries may be directories, single .proto
   * files, or .zip/.jar archives.
   */
  public JdkSchemaLoader() {
    this(FileSystem.asOkioFileSystem(java.nio.file.FileSystems.getDefault()), null);
  }

  private JdkSchemaLoader(FileSystem fileSystem, FileSystem ownedFileSystem) {
    this.delegate = new CommonSchemaLoader(fileSystem);
    this.ownedFileSystem = ownedFileSystem;
  }

  /**
   * A loader over a caller-supplied {@link java.nio.file.FileSystem} (TASK-33.5), which is how
   * schemas held in memory are loaded: pass an in-memory filesystem such as Jimfs and the
   * schema's own files are read from it, with that filesystem's semantics. Build Jimfs from an
   * explicit configuration: its no-arg factory derives the configuration from the current
   * platform (jimfs 1.3.2 {@code Configuration.forCurrentPlatform()}), which is
   * case-insensitive on macOS, so {@code Jimfs.newFileSystem(Configuration.unix())} is what
   * makes an import with the wrong case fail the same way on every host. Two details work
   * exactly like the default route rather than the supplied filesystem: the bundled well-known
   * Google protos fall through to the classpath when not present in it, and
   * {@code .zip}/{@code .jar} source-path entries are materialized to a host temp file.
   * {@link #initRoots(List, List)} paths must come from the same filesystem and should be
   * absolute, like {@code fs.getPath("/protos")}. The loader does not close the supplied
   * filesystem; its lifecycle is the caller's.
   *
   * <pre>{@code
   * java.nio.file.FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
   * Path proto = fs.getPath("/protos/com/example/messages.proto");
   * Files.createDirectories(proto.getParent());
   * Files.write(proto, bytes);
   * try (JdkSchemaLoader loader = JdkSchemaLoader.forFileSystem(fs)) {
   *   loader.initRoots(singletonList(proto), singletonList(fs.getPath("/protos")));
   *   Schema schema = loader.loadSchema();
   * }
   * }</pre>
   *
   * <p>The factory itself does no I/O, so it declares no checked exception; DEC-17 otherwise
   * applies to this facade: {@code initRoots}, {@code loadSchema} and {@code close} keep
   * {@code throws IOException}.
   */
  public static JdkSchemaLoader forFileSystem(java.nio.file.FileSystem fileSystem) {
    return new JdkSchemaLoader(FileSystem.asOkioFileSystem(fileSystem), null);
  }

  /**
   * A loader that reads .proto resources from {@code classLoader}. Each element of {@code
   * sourceResources} is the import path of one .proto on the classpath, relative to its root,
   * like {@code "com/example/messages.proto"}; those files form the source path. The whole
   * classpath root forms the proto path, so imports resolve from any resource directory, and
   * {@code google/protobuf/descriptor.proto} plus the well-knowns are always available.
   *
   * <p>This is the drop-in replacement for the {@code FakeFileSystem} choreography: no working
   * directory, no symlink flags, no synthetic in-memory file contents.
   */
  public static JdkSchemaLoader forClasspath(ClassLoader classLoader, String... sourceResources)
      throws IOException {
    FileSystem resources = FileSystem.asResourceFileSystem(classLoader);
    JdkSchemaLoader loader = new JdkSchemaLoader(resources, resources);
    List<Location> sourcePath = new ArrayList<>();
    for (String resource : sourceResources) {
      sourcePath.add(Location.get(resource));
    }
    loader.delegate.initRoots(sourcePath, Collections.singletonList(Location.get("/")));
    return loader;
  }

  /** Strict by default. Note that golang cannot build protos with package cycles. */
  public boolean permitPackageCycles() {
    return delegate.getPermitPackageCycles();
  }

  public JdkSchemaLoader setPermitPackageCycles(boolean permitPackageCycles) {
    delegate.setPermitPackageCycles(permitPackageCycles);
    return this;
  }

  /**
   * All qualified named Protobuf types in {@code opaqueTypes} will be evaluated as being of type
   * {@code bytes}. On code generation, the fields of such types will be using the platform
   * equivalent of {@code bytes}, like {@code ByteString} for the JVM. Note that scalar types
   * cannot be opaqued.
   */
  public List<ProtoType> opaqueTypes() {
    return delegate.getOpaqueTypes();
  }

  public JdkSchemaLoader setOpaqueTypes(List<ProtoType> opaqueTypes) {
    delegate.setOpaqueTypes(opaqueTypes);
    return this;
  }

  /**
   * If true, the schema loader will load the whole graph, including files and types not used by
   * anything in the source path.
   */
  public boolean loadExhaustively() {
    return delegate.getLoadExhaustively();
  }

  public JdkSchemaLoader setLoadExhaustively(boolean loadExhaustively) {
    delegate.setLoadExhaustively(loadExhaustively);
    return this;
  }

  /** Subset of the schema that was loaded from the source path. */
  public List<ProtoFile> sourcePathFiles() {
    return delegate.getSourcePathFiles();
  }

  /**
   * Initialize the source path and proto path from {@code java.nio.file.Path} entries. Each
   * entry may be a directory, a single {@code .proto} file, or a {@code .zip}/{@code .jar}
   * archive of protos. Import paths must still match the proto's {@code package} and directory
   * layout, exactly like protoc.
   */
  public JdkSchemaLoader initRoots(List<java.nio.file.Path> sourcePath,
      List<java.nio.file.Path> protoPath) throws IOException {
    delegate.initRoots(toLocations(sourcePath), toLocations(protoPath));
    return this;
  }

  /** Initialize the source path, with no proto path. */
  public JdkSchemaLoader initRoots(List<java.nio.file.Path> sourcePath) throws IOException {
    return initRoots(sourcePath, Collections.emptyList());
  }

  /** Parses, links and validates the schema declared by the source and proto paths. */
  public Schema loadSchema() throws IOException {
    return delegate.loadSchema();
  }

  /**
   * Releases resources this loader owns, including ZIP file systems opened for archive entries
   * on the source or proto path.
   */
  @Override public void close() throws IOException {
    delegate.close();
    if (ownedFileSystem != null) {
      ownedFileSystem.close();
    }
  }

  private static List<Location> toLocations(List<java.nio.file.Path> paths) {
    List<Location> result = new ArrayList<>();
    for (java.nio.file.Path path : paths) {
      String absolute = path.toAbsolutePath().toString().replace(File.separatorChar, '/');
      result.add(Location.get(absolute));
    }
    return result;
  }
}
