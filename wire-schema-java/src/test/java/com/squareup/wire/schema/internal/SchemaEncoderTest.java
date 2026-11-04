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
package com.squareup.wire.schema.internal;

import com.squareup.wire.Bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.wire.ProtoAdapter;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;
import com.squareup.wire.schema.SchemaLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TASK-12 checks for the descriptor encoder: encode a linked file and decode it back through
 * the schema's dynamic google.protobuf.FileDescriptorProto adapter, asserting the descriptor
 * model round-trips structurally. Byte-for-byte parity against the upstream encoder is exercised
 * by the pinned-tag parity runner (TASK-14).
 */
public class SchemaEncoderTest {
  @TempDir Path tempDir;

  private Schema loadSchema(String... files) throws IOException {
    Path sourcePath = Files.createDirectories(tempDir.resolve("source"));
    for (int i = 0; i < files.length; i += 2) {
      Path file = sourcePath.resolve(files[i]);
      Files.createDirectories(file.getParent());
      Files.write(file, files[i + 1].getBytes(StandardCharsets.UTF_8));
    }
    SchemaLoader loader = new SchemaLoader(okio.FileSystem.SYSTEM);
    loader.initRoots(Collections.singletonList(Location.get(sourcePath.toString())));
    return loader.loadSchema();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decodeFileDescriptorProto(Schema schema, ByteString encoded)
      throws IOException {
    ProtoAdapter<Object> adapter = schema.protoAdapter("google.protobuf.FileDescriptorProto",
        false);
    return (Map<String, Object>) adapter.decode(encoded);
  }

  @Test public void encodesSimpleProto3File() throws Exception {
    Schema schema = loadSchema("message.proto", ""
        + "syntax = \"proto3\";\n"
        + "package pkg;\n"
        + "message Person {\n"
        + "  string name = 1;\n"
        + "  int32 id = 2;\n"
        + "  repeated string emails = 3;\n"
        + "}\n");
    ProtoFile protoFile = schema.protoFile("message.proto");
    assertNotNull(protoFile);

    SchemaEncoder encoder = new SchemaEncoder(schema);
    ByteString encoded = encoder.encode(protoFile);

    Map<String, Object> descriptor = decodeFileDescriptorProto(schema, encoded);
    assertEquals("message.proto", descriptor.get("name"));
    assertEquals("pkg", descriptor.get("package"));
    assertEquals("proto3", descriptor.get("syntax"));

    List<Map<String, Object>> messageTypes =
        (List<Map<String, Object>>) descriptor.get("message_type");
    assertEquals(1, messageTypes.size());
    Map<String, Object> person = messageTypes.get(0);
    assertEquals("Person", person.get("name"));

    List<Map<String, Object>> fields = (List<Map<String, Object>>) person.get("field");
    assertEquals(3, fields.size());

    Map<String, Object> name = fields.get(0);
    assertEquals("name", name.get("name"));
    assertEquals(1, name.get("number"));
    // The dynamic adapter decodes enums to their constant names.
    assertEquals("LABEL_OPTIONAL", name.get("label"));
    assertEquals("TYPE_STRING", name.get("type"));

    Map<String, Object> emails = fields.get(2);
    assertEquals("emails", emails.get("name"));
    assertEquals(3, emails.get("number"));
    assertEquals("LABEL_REPEATED", emails.get("label"));
  }

  /**
   * TASK-40 regression: a bytes-valued custom option used to reach the DEC-14 Bytes-typed
   * options adapter as an engine-layer okio.ByteString and throw ClassCastException at
   * WireBytesAdapter.encode. The option value must encode and round-trip as Bytes.
   */
  @Test public void encodesBytesValuedCustomOption() throws Exception {
    Schema schema = loadSchema("bytes_options.proto", ""
        + "syntax = \"proto2\";\n"
        + "package pkg;\n"
        + "import \"google/protobuf/descriptor.proto\";\n"
        + "extend google.protobuf.FileOptions {\n"
        + "  optional bytes file_bytes = 50001;\n"
        + "}\n"
        + "option (file_bytes) = \"task-40 bytes option value\";\n"
        + "message Msg { optional string name = 1; }\n");
    ProtoFile protoFile = schema.protoFile("bytes_options.proto");

    SchemaEncoder encoder = new SchemaEncoder(schema);
    ByteString encoded = encoder.encode(protoFile);

    Map<String, Object> descriptor = decodeFileDescriptorProto(schema, encoded);
    Map<String, Object> options = (Map<String, Object>) descriptor.get("options");
    Object fileBytes = options.get("file_bytes");
    assertEquals(Bytes.encodeUtf8("task-40 bytes option value"), fileBytes);
  }

  @Test public void encodesProto2DefaultsAndJsonName() throws Exception {
    Schema schema = loadSchema("legacy.proto", ""
        + "syntax = \"proto2\";\n"
        + "package pkg;\n"
        + "message Legacy {\n"
        + "  optional string display_name = 1 [default = \"none\", json_name = \"displayName\"];\n"
        + "  required int32 count = 2;\n"
        + "}\n");
    ProtoFile protoFile = schema.protoFile("legacy.proto");

    SchemaEncoder encoder = new SchemaEncoder(schema);
    ByteString encoded = encoder.encode(protoFile);

    Map<String, Object> descriptor = decodeFileDescriptorProto(schema, encoded);
    // Proto2 files omit the syntax field; the default is proto2.
    assertTrue(!descriptor.containsKey("syntax") || descriptor.get("syntax") == null);

    List<Map<String, Object>> messageTypes =
        (List<Map<String, Object>>) descriptor.get("message_type");
    List<Map<String, Object>> fields =
        (List<Map<String, Object>>) messageTypes.get(0).get("field");

    Map<String, Object> displayName = fields.get(0);
    assertEquals("display_name", displayName.get("name"));
    assertEquals("none", displayName.get("default_value"));
    assertEquals("displayName", displayName.get("json_name"));
    assertEquals("LABEL_OPTIONAL", displayName.get("label"));

    Map<String, Object> count = fields.get(1);
    assertEquals("LABEL_REQUIRED", count.get("label"));
  }

  @Test public void encodesEnumsServicesAndMaps() throws Exception {
    Schema schema = loadSchema("rich.proto", ""
        + "syntax = \"proto3\";\n"
        + "package pkg;\n"
        + "enum Color {\n"
        + "  RED = 0;\n"
        + "  GREEN = 1;\n"
        + "}\n"
        + "message Rich {\n"
        + "  map<string, int32> counts = 1;\n"
        + "  Color color = 2;\n"
        + "}\n"
        + "service RichService {\n"
        + "  rpc Get (Rich) returns (Rich);\n"
        + "}\n");
    ProtoFile protoFile = schema.protoFile("rich.proto");

    SchemaEncoder encoder = new SchemaEncoder(schema);
    ByteString encoded = encoder.encode(protoFile);

    Map<String, Object> descriptor = decodeFileDescriptorProto(schema, encoded);

    List<Map<String, Object>> enumTypes =
        (List<Map<String, Object>>) descriptor.get("enum_type");
    assertEquals(1, enumTypes.size());
    assertEquals("Color", enumTypes.get(0).get("name"));
    List<Map<String, Object>> values =
        (List<Map<String, Object>>) enumTypes.get(0).get("value");
    assertEquals("RED", values.get(0).get("name"));
    assertEquals(0, values.get(0).get("number"));

    List<Map<String, Object>> messageTypes =
        (List<Map<String, Object>>) descriptor.get("message_type");
    assertEquals(1, messageTypes.size());

    // The map field is replaced by a synthetic CountsEntry nested type.
    List<Map<String, Object>> nested =
        (List<Map<String, Object>>) messageTypes.get(0).get("nested_type");
    assertEquals(1, nested.size());
    assertEquals("CountsEntry", nested.get(0).get("name"));
    Map<String, Object> mapEntryOptions =
        (Map<String, Object>) nested.get(0).get("options");
    assertEquals(Boolean.TRUE, mapEntryOptions.get("map_entry"));
    List<Map<String, Object>> entryFields =
        (List<Map<String, Object>>) nested.get(0).get("field");
    assertEquals("key", entryFields.get(0).get("name"));
    assertEquals("value", entryFields.get(1).get("name"));

    // The declared field is typed as the synthetic entry message.
    List<Map<String, Object>> fields =
        (List<Map<String, Object>>) messageTypes.get(0).get("field");
    Map<String, Object> counts = fields.get(0);
    assertEquals("counts", counts.get("name"));
    // The synthetic entry type nests under the declaring message.
    assertEquals(".pkg.Rich.CountsEntry", counts.get("type_name"));
    assertEquals("TYPE_MESSAGE", counts.get("type"));

    Map<String, Object> color = fields.get(1);
    assertEquals(".pkg.Color", color.get("type_name"));
    assertEquals("TYPE_ENUM", color.get("type"));

    List<Map<String, Object>> services =
        (List<Map<String, Object>>) descriptor.get("service");
    assertEquals(1, services.size());
    assertEquals("RichService", services.get(0).get("name"));
    List<Map<String, Object>> methods =
        (List<Map<String, Object>>) services.get(0).get("method");
    assertEquals("Get", methods.get(0).get("name"));
    assertEquals(".pkg.Rich", methods.get(0).get("input_type"));
    assertEquals(".pkg.Rich", methods.get(0).get("output_type"));
  }

  @Test public void encodesOneOfFields() throws Exception {
    // Regression for the code-review crash: oneof fields used to carry a null type.
    Schema schema = loadSchema("choice.proto", ""
        + "syntax = \"proto3\";\n"
        + "message Choice {\n"
        + "  oneof selection {\n"
        + "    string name = 1;\n"
        + "    int32 number = 2;\n"
        + "  }\n"
        + "}\n");
    ProtoFile protoFile = schema.protoFile("choice.proto");
    SchemaEncoder encoder = new SchemaEncoder(schema);
    Map<String, Object> descriptor = decodeFileDescriptorProto(schema, encoder.encode(protoFile));

    List<Map<String, Object>> messageTypes =
        (List<Map<String, Object>>) descriptor.get("message_type");
    List<Map<String, Object>> oneofs =
        (List<Map<String, Object>>) messageTypes.get(0).get("oneof_decl");
    assertEquals(1, oneofs.size());
    assertEquals("selection", oneofs.get(0).get("name"));

    List<Map<String, Object>> fields =
        (List<Map<String, Object>>) messageTypes.get(0).get("field");
    assertEquals(2, fields.size());
    assertEquals("TYPE_STRING", fields.get(0).get("type"));
    assertEquals(0, fields.get(0).get("oneof_index"));
    assertEquals("TYPE_INT32", fields.get(1).get("type"));
    assertEquals(0, fields.get(1).get("oneof_index"));
  }

  @Test public void encodesExtendFieldsWithExtendee() throws Exception {
    // Regression for the code-review crash: extension fields used to carry a null type, and
    // custom options always reach the encoder through an extend block.
    Schema schema = loadSchema("marked.proto", ""
        + "syntax = \"proto2\";\n"
        + "import \"google/protobuf/descriptor.proto\";\n"
        + "extend google.protobuf.FieldOptions {\n"
        + "  optional bool marked = 22300;\n"
        + "}\n"
        + "message Marked {\n"
        + "  optional string s = 1 [deprecated = true];\n"
        + "}\n");
    ProtoFile protoFile = schema.protoFile("marked.proto");
    SchemaEncoder encoder = new SchemaEncoder(schema);
    Map<String, Object> descriptor = decodeFileDescriptorProto(schema, encoder.encode(protoFile));

    List<Map<String, Object>> extensions =
        (List<Map<String, Object>>) descriptor.get("extension");
    assertEquals(1, extensions.size());
    Map<String, Object> marked = extensions.get(0);
    assertEquals("marked", marked.get("name"));
    assertEquals(".google.protobuf.FieldOptions", marked.get("extendee"));
    assertEquals("TYPE_BOOL", marked.get("type"));

    // The deprecated option round-trips through the linked FileOptions adapter.
    List<Map<String, Object>> messageTypes =
        (List<Map<String, Object>>) descriptor.get("message_type");
    List<Map<String, Object>> fields =
        (List<Map<String, Object>>) messageTypes.get(0).get("field");
    Map<String, Object> options = (Map<String, Object>) fields.get(0).get("options");
    assertEquals(Boolean.TRUE, options.get("deprecated"));
  }

  @Test public void encodingIsDeterministic() throws Exception {
    Schema schema = loadSchema("det.proto", ""
        + "syntax = \"proto3\";\n"
        + "message Det { string a = 1; int64 b = 2; }\n");
    ProtoFile protoFile = schema.protoFile("det.proto");
    SchemaEncoder encoder = new SchemaEncoder(schema);
    assertEquals(encoder.encode(protoFile), encoder.encode(protoFile));
  }
}
