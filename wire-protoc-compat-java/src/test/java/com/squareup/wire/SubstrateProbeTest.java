package com.squareup.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.apitomy.antiwire.parity.okio.ByteString;
import io.apitomy.antiwire.parity.wire.schema.ProtoFile;
import io.apitomy.antiwire.parity.wire.schema.Schema;
import io.apitomy.antiwire.parity.wire.schema.SchemaLoader;
import io.apitomy.antiwire.parity.wire.schema.internal.SchemaEncoder;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TASK-39 substrate probe: proves the extended wire-upstream-shaded fixture can load a small
 * inline proto through the RELOCATED real Wire 7.1.0 schema stack and encode it with the
 * relocated SchemaEncoder, entirely in-process next to the port. Folded into the
 * DualRunIdentityTest substrate once the harness lands; kept until then as the gate on the
 * fixture extension alone.
 */
public final class SubstrateProbeTest {
  private static final String INLINE_PROTO = String.join("\n",
      "syntax = \"proto2\";",
      "package probe.substrate;",
      "message M {",
      "  optional string name = 1;",
      "  extensions 100 to 199;",
      "}");

  @TempDir Path tempDir;

  @Test public void relocatedUpstreamStackLoadsAndEncodes() throws Exception {
    Path root = tempDir.resolve("corpus");
    Path file = root.resolve("probe/substrate/probe.proto");
    Files.createDirectories(file.getParent());
    Files.write(file, INLINE_PROTO.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    SchemaLoader loader = new SchemaLoader(FileSystems.getDefault());
    loader.initRoots(Collections.singletonList(io.apitomy.antiwire.parity.wire.schema.Location.get(file.toString())),
        Collections.singletonList(io.apitomy.antiwire.parity.wire.schema.Location.get(root.toString())));
    Schema schema = loader.loadSchema();

    
    ProtoFile protoFile = schema.protoFile(file.toString());
    assertTrue(protoFile != null, "probe.proto missing from the loaded schema");

    byte[] descriptorBytes = new SchemaEncoder(schema).encode(protoFile).toByteArray();
    assertTrue(descriptorBytes.length > 0, "descriptor encoding came out empty");

    // DEC-15 probe substrate: the range element type on real Wire 7.1.0 is the Kotlin
    // stdlib IntRange, while the port stores int[] pairs.
    io.apitomy.antiwire.parity.wire.schema.MessageType message =
        (io.apitomy.antiwire.parity.wire.schema.MessageType) schema.getType("probe.substrate.M");
    Object[] values = message.getExtensionsList().get(0).getValues().toArray();
    assertEquals("kotlin.ranges.IntRange", values[0].getClass().getName());

    String canonicalText = protoFile.toSchema();
    assertTrue(canonicalText.contains("message M"), "canonical text lost the message");

    // The port must produce byte-identical descriptor bytes for the same file.
    com.squareup.wire.schema.Schema portSchema = loadWithPort(file, root);
    com.squareup.wire.schema.ProtoFile portProtoFile = portSchema.protoFile(file.toString());
    byte[] portBytes = new com.squareup.wire.schema.internal.SchemaEncoder(portSchema)
        .encode(portProtoFile).toByteArray();
    org.junit.jupiter.api.Assertions.assertArrayEquals(descriptorBytes, portBytes,
        "port and upstream descriptor bytes diverge on the substrate probe");
    assertEquals(canonicalText, portProtoFile.toSchema(), "canonical text diverges");
  }

  private static com.squareup.wire.schema.Schema loadWithPort(Path file, Path root)
      throws java.io.IOException {
    com.squareup.wire.schema.SchemaLoader loader =
        new com.squareup.wire.schema.SchemaLoader(FileSystems.getDefault());
    loader.initRoots(Collections.singletonList(com.squareup.wire.schema.Location.get(file.toString())),
        Collections.singletonList(com.squareup.wire.schema.Location.get(root.toString())));
    return loader.loadSchema();
  }
}
