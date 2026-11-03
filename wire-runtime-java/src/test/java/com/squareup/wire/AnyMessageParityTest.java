package com.squareup.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.squareup.wire.internal.Internal;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import okio.ByteString;
import org.junit.Test;

/**
 * AnyMessage parity against the relocated upstream oracle: pack/encode/decode/unpack through
 * the same adapters on both sides, plus the hashCode caching contract.
 */
public class AnyMessageParityTest {
  private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<io.apitomy.antiwire.parity.wire.FieldMask>
      THEIR_FIELD_MASK = io.apitomy.antiwire.parity.wire.ProtoAdapter.FIELD_MASK;

  @Test public void fieldMaskRoundTrip() throws IOException {
    FieldMask mask = new FieldMask(Arrays.asList("a.b", "c"));
    AnyMessage any = AnyMessage.pack(ProtoAdapter.FIELD_MASK, mask);
    io.apitomy.antiwire.parity.wire.AnyMessage theirAny =
        io.apitomy.antiwire.parity.wire.AnyMessage.Companion.pack(
            THEIR_FIELD_MASK,
            new io.apitomy.antiwire.parity.wire.FieldMask(Arrays.asList("a.b", "c")));

    assertArrayEquals("Any bytes", theirAny.encode(), any.encode());
    assertEquals("Any toString", theirAny.toString(), any.toString());

    AnyMessage decoded = AnyMessage.ADAPTER.decode(any.encodeByteString());
    assertEquals(any, decoded);
    FieldMask unpacked = decoded.unpack(ProtoAdapter.FIELD_MASK);
    assertEquals(Arrays.asList("a.b", "c"), unpacked.getPaths());
  }

  @Test public void structRoundTrip() throws IOException {
    Map<String, Object> struct = new LinkedHashMap<>();
    struct.put("n", 1.5);
    struct.put("s", "v");
    struct.put("l", Arrays.asList(1.0, null));
    AnyMessage any = AnyMessage.pack(ProtoAdapter.STRUCT_MAP, struct);
    AnyMessage decoded = AnyMessage.ADAPTER.decode(any.encodeByteString());
    @SuppressWarnings("unchecked")
    Map<String, Object> unpacked = (Map<String, Object>) (Object) decoded.unpack(
        ProtoAdapter.STRUCT_MAP);
    assertEquals(1.5, unpacked.get("n"));
    assertEquals("v", unpacked.get("s"));
  }

  @Test public void unpackTypeMismatch() throws IOException {
    FieldMask mask = new FieldMask(Arrays.asList("x"));
    AnyMessage any = AnyMessage.pack(ProtoAdapter.FIELD_MASK, mask);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> any.unpack(ProtoAdapter.DURATION));
    assertTrue(e.getMessage().startsWith("type mismatch: "));
    // The null-returning variant stays quiet instead.
    assertEquals(null, any.unpackOrNull(ProtoAdapter.DURATION));
  }

  @Test public void hashCodeCaching() throws IOException {
    FieldMask mask = new FieldMask(Arrays.asList("a"));
    AnyMessage any = AnyMessage.pack(ProtoAdapter.FIELD_MASK, mask);
    int first = any.hashCode();
    assertEquals(first, any.hashCode());
    AnyMessage same = AnyMessage.pack(ProtoAdapter.FIELD_MASK,
        new FieldMask(Arrays.asList("a")));
    assertEquals(any.hashCode(), same.hashCode());
    assertEquals(any, same);
  }

  @Test public void adapterWithoutTypeUrlCannotPack() {
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> AnyMessage.pack(ProtoAdapter.STRING, "x"));
    assertTrue(e.getMessage().contains("no type URL"));
  }

  /** A minimal concrete message proves pack(Message) through the adapter accessor. */
  private static final class PackedMessage
      extends Message<PackedMessage, PackedMessage.Builder> {
    static final ProtoAdapter<PackedMessage> ADAPTER =
        new ProtoAdapter<PackedMessage>(FieldEncoding.LENGTH_DELIMITED, PackedMessage.class,
            "type.googleapis.com/antiwire.PackedMessage", Syntax.PROTO_3, null, null) {
          @Override public int encodedSize(PackedMessage value) {
            return STRING.encodedSizeWithTag(1, value.text);
          }

          @Override public void encode(ProtoWriter writer, PackedMessage value)
              throws IOException {
            STRING.encodeWithTag(writer, 1, value.text);
          }

          @Override public PackedMessage decode(ProtoReader reader) throws IOException {
            throw new UnsupportedOperationException();
          }

          @Override public PackedMessage redact(PackedMessage value) {
            throw new UnsupportedOperationException();
          }
        };

    final String text;

    PackedMessage(String text) {
      super(ADAPTER, ByteString.EMPTY);
      this.text = text;
    }

    @Override public int hashCode() {
      return text.hashCode();
    }

    @Override public boolean equals(Object other) {
      return other instanceof PackedMessage && ((PackedMessage) other).text.equals(text);
    }

    static final class Builder extends Message.Builder<PackedMessage, Builder> {
      @Override public PackedMessage build() {
        throw new UnsupportedOperationException();
      }
    }

    @Override public Builder newBuilder() {
      throw new UnsupportedOperationException();
    }
  }

  @Test public void packMessageThroughAdapterAccessor() throws IOException {
    AnyMessage any = AnyMessage.pack(new PackedMessage("hello"));
    assertEquals("type.googleapis.com/antiwire.PackedMessage", any.getTypeUrl());
    // The packed value is the message encoding: tag 1, length 5, "hello".
    assertEquals("0a0568656c6c6f", any.getValue().hex());
  }

  @Test public void oneOfStringValueSanitizes() {
    // OneOf.toString sanitizes STRING and STRING_VALUE adapters (upstream branch).
    OneOf.Key<String> key = new OneOf.Key<String>(1, ProtoAdapter.STRING_VALUE, "sv") {
    };
    OneOf<OneOf.Key<String>, String> oneOf = new OneOf<>(key, "a,b[c]{d}\\e");
    assertEquals("sv=a\\,b\\[c\\]\\{d\\}\\\\e", oneOf.toString());
  }

  @Test public void messageSerializationRoundTrip() throws Exception {
    FieldMask mask = new FieldMask(Arrays.asList("ser"));
    AnyMessage any = AnyMessage.pack(ProtoAdapter.FIELD_MASK, mask);
    // MessageSerializedForm replaces the message in the stream; the ADAPTER field is public.
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
      out.writeObject(any);
    }
    Object read;
    try (java.io.ObjectInputStream in =
        new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
      read = in.readObject();
    }
    assertEquals(any, read);
  }
}
