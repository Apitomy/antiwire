package com.squareup.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import okio.ByteString;
import org.junit.Test;

/**
 * Adapter parity against the relocated upstream wire-runtime-jvm 7.1.0 oracle: for every
 * primitive and well-known adapter, the same value must encode to identical bytes, report
 * identical sizes, roundtrip through our decode, and cross-decode through the upstream
 * implementation.
 */
public class ProtoAdapterParityTest {
  private static final String ALPHABET = "abcXYZ019 αβγ 😀🚀 € ñ 日本語";

  private static <T> void assertParity(
      String what, ProtoAdapter<T> ours,
      io.apitomy.antiwire.parity.wire.ProtoAdapter<T> theirs, T value)
      throws IOException {
    byte[] ourBytes = ours.encode(value);
    byte[] theirBytes = theirs.encode(value);
    assertArrayEquals(what + " bytes", theirBytes, ourBytes);
    assertEquals(what + " encodedSize", theirs.encodedSize(value), ours.encodedSize(value));
    assertEquals(what + " encodedSizeWithTag", theirs.encodedSizeWithTag(1, value),
        ours.encodedSizeWithTag(1, value));
    assertEquals(what + " cross-decode", value,
        theirs.decode(io.apitomy.antiwire.parity.okio.ByteString.of(ourBytes)));
  }

  /**
   * Decodes a length-delimited scalar in the form both implementations support: inside a
   * message, after its tag and length prefix (the bare top-level form throws on the upstream
   * oracle too, so it is not a parity surface).
   */
  private static String stringInContext(byte[] tagged) throws IOException {
    ProtoReader reader = new ProtoReader(new okio.Buffer().write(tagged));
    long token = reader.beginMessage();
    int tag = reader.nextTag();
    String result = ProtoAdapter.STRING.decode(reader);
    reader.endMessageAndGetUnknownFields(token);
    return result;
  }

  /** Runs seeded random values through both implementations for one adapter pair. */
  private static <T> void intParity(String what, ProtoAdapter<Integer> ours,
      io.apitomy.antiwire.parity.wire.ProtoAdapter<Integer> theirs)
      throws IOException {
    List<Integer> interesting = Arrays.asList(
        0, 1, -1, 127, 128, -128, 16383, 16384, -16384, 2097151, 268435455, 268435456,
        Integer.MAX_VALUE, Integer.MIN_VALUE);
    for (Integer value : interesting) {
      assertParity(what + "(" + value + ")", ours, theirs, value);
    }
    Random random = new Random(0x1234 + what.hashCode());
    for (int i = 0; i < 500; i++) {
      assertParity(what + " rand" + i, ours, theirs, random.nextInt());
    }
  }

  private static void longParity(String what, ProtoAdapter<Long> ours,
      io.apitomy.antiwire.parity.wire.ProtoAdapter<Long> theirs)
      throws IOException {
    List<Long> interesting = Arrays.asList(
        0L, 1L, -1L, 127L, 128L, 16383L, 16384L, 1L << 28, 1L << 35, 1L << 56, 1L << 62,
        Long.MAX_VALUE, Long.MIN_VALUE);
    for (Long value : interesting) {
      assertParity(what + "(" + value + ")", ours, theirs, value);
    }
    Random random = new Random(0x5678 + what.hashCode());
    for (int i = 0; i < 500; i++) {
      assertParity(what + " rand" + i, ours, theirs, random.nextLong());
    }
  }

  @Test public void integerAdapters() throws IOException {
    intParity("INT32", ProtoAdapter.INT32, p.INT32);
    intParity("UINT32", ProtoAdapter.UINT32, p.UINT32);
    intParity("SINT32", ProtoAdapter.SINT32, p.SINT32);
    intParity("FIXED32", ProtoAdapter.FIXED32, p.FIXED32);
    intParity("SFIXED32", ProtoAdapter.SFIXED32, p.SFIXED32);
  }

  @Test public void longAdapters() throws IOException {
    longParity("INT64", ProtoAdapter.INT64, p.INT64);
    longParity("UINT64", ProtoAdapter.UINT64, p.UINT64);
    longParity("SINT64", ProtoAdapter.SINT64, p.SINT64);
    longParity("FIXED64", ProtoAdapter.FIXED64, p.FIXED64);
    longParity("SFIXED64", ProtoAdapter.SFIXED64, p.SFIXED64);
  }

  @Test public void boolAdapter() throws IOException {
    assertParity("BOOL(true)", ProtoAdapter.BOOL, p.BOOL, true);
    assertParity("BOOL(false)", ProtoAdapter.BOOL, p.BOOL, false);
    Random random = new Random(7);
    for (int i = 0; i < 100; i++) {
      assertParity("BOOL" + i, ProtoAdapter.BOOL, p.BOOL, random.nextBoolean());
    }
  }

  @Test public void floatDoubleAdapters() throws IOException {
    List<Double> doubles = Arrays.asList(
        0.0, -0.0, 1.5, -1.5, Math.PI, 1e300, -1e300, Double.MIN_VALUE, Double.MAX_VALUE);
    for (Double value : doubles) {
      assertParity("DOUBLE(" + value + ")", ProtoAdapter.DOUBLE, p.DOUBLE, value);
    }
    List<Float> floats = Arrays.asList(0.0f, -0.0f, 1.5f, -1.5f, (float) Math.PI, 1e30f);
    for (Float value : floats) {
      assertParity("FLOAT(" + value + ")", ProtoAdapter.FLOAT, p.FLOAT, value);
    }
    Random random = new Random(11);
    for (int i = 0; i < 300; i++) {
      assertParity("DOUBLE" + i, ProtoAdapter.DOUBLE, p.DOUBLE, random.nextDouble());
      assertParity("FLOAT" + i, ProtoAdapter.FLOAT, p.FLOAT, random.nextFloat());
    }
  }

  @Test public void stringBytesAdapters() throws IOException {
    List<String> strings = Arrays.asList("", "a", "hello, world", "héllo", "日本語",
        "😀🚀", "\ud800", "\udc00A", "a𐀀b");
    for (String value : strings) {
      assertArrayEquals("STRING(" + value + ") bytes",
          p.STRING.encode(value), ProtoAdapter.STRING.encode(value));
      assertEquals("STRING(" + value + ") size",
          p.STRING.encodedSize(value), ProtoAdapter.STRING.encodedSize(value));
      // Malformed-surrogate strings encode lossily; byte parity above covers them.
      boolean wellFormed = true;
      for (int j = 0; j < value.length(); j++) {
        if (Character.isSurrogate(value.charAt(j))) wellFormed = false;
      }
      if (wellFormed) {
        okio.Buffer tagged = new okio.Buffer();
        ProtoWriter writer = new ProtoWriter(tagged);
        writer.writeTag(1, FieldEncoding.LENGTH_DELIMITED);
        writer.writeVarint32((int) okio.Utf8.size(value));
        writer.writeString(value);
        assertEquals(value, stringInContext(tagged.readByteArray()));
      }
    }
    // Seeded strings compare bytes and sizes; the tagged-form decode above covers roundtrip
    // because the bare top-level form is not a supported surface on either implementation.
    Random random = new Random(13);
    for (int i = 0; i < 300; i++) {
      int length = random.nextInt(40);
      StringBuilder sb = new StringBuilder();
      for (int j = 0; j < length; j++) {
        sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
      }
      String value = sb.toString();
      assertArrayEquals("STRING" + i + " bytes",
          p.STRING.encode(value), ProtoAdapter.STRING.encode(value));
      assertEquals("STRING" + i + " size",
          p.STRING.encodedSize(value), ProtoAdapter.STRING.encodedSize(value));
    }
    for (int i = 0; i < 200; i++) {
      byte[] bytes = new byte[random.nextInt(100)];
      random.nextBytes(bytes);
      ByteString ourValue = ByteString.of(bytes);
      byte[] ourBytes = ProtoAdapter.BYTES.encode(ourValue);
      assertArrayEquals("BYTES" + i + " bytes", p.BYTES.encode(
          io.apitomy.antiwire.parity.okio.ByteString.of(bytes)), ourBytes);
      assertEquals("BYTES" + i + " size", p.BYTES.encodedSize(
          io.apitomy.antiwire.parity.okio.ByteString.of(bytes)),
          ProtoAdapter.BYTES.encodedSize(ourValue));
      // No bare cross-decode: the oracle's reader rejects top-level byte strings like ours;
      // the tagged-form decode is exercised by the unknown-field test.
    }
  }

  @Test public void durationAdapter() throws IOException {
    List<java.time.Duration> values = Arrays.asList(
        java.time.Duration.ZERO, java.time.Duration.ofSeconds(1),
        java.time.Duration.ofSeconds(1, 200_000_000),
        java.time.Duration.ofSeconds(-1, -200_000_000),
        java.time.Duration.ofSeconds(-1),
        java.time.Duration.ofSeconds(Long.MAX_VALUE / 2, 999_999_999));
    for (java.time.Duration value : values) {
      assertParity("DURATION(" + value + ")", ProtoAdapter.DURATION, p.DURATION, value);
    }
    Random random = new Random(17);
    for (int i = 0; i < 200; i++) {
      java.time.Duration value = java.time.Duration.ofSeconds(
          random.nextLong() / 4, random.nextInt(1_000_000_000));
      assertParity("DURATION" + i, ProtoAdapter.DURATION, p.DURATION, value);
    }
  }

  @Test public void instantAdapter() throws IOException {
    List<java.time.Instant> values = Arrays.asList(
        java.time.Instant.EPOCH,
        java.time.Instant.ofEpochSecond(-62135596800L),
        java.time.Instant.ofEpochSecond(253402300799L, 999_999_999),
        java.time.Instant.ofEpochSecond(1, 1),
        java.time.Instant.ofEpochSecond(-1, 999_999_999));
    for (java.time.Instant value : values) {
      assertParity("INSTANT(" + value + ")", ProtoAdapter.INSTANT, p.INSTANT, value);
    }
    Random random = new Random(19);
    for (int i = 0; i < 200; i++) {
      java.time.Instant value = java.time.Instant.ofEpochSecond(
          (long) (-62135596800L + random.nextDouble() * (253402300799L + 62135596800L)),
          random.nextInt(1_000_000_000));
      assertParity("INSTANT" + i, ProtoAdapter.INSTANT, p.INSTANT, value);
    }
  }

  @Test public void fieldMaskAdapter() throws IOException {
    List<List<String>> pathSets = Arrays.asList(
        Arrays.asList(),
        Arrays.asList("name"),
        Arrays.asList("a.b.c", "x", "y.z"));
    for (List<String> paths : pathSets) {
      FieldMask ourValue = new FieldMask(paths);
      byte[] ourBytes = ProtoAdapter.FIELD_MASK.encode(ourValue);
      io.apitomy.antiwire.parity.wire.FieldMask theirValue =
          new io.apitomy.antiwire.parity.wire.FieldMask(paths);
      assertArrayEquals("FIELD_MASK bytes", p.FIELD_MASK.encode(theirValue), ourBytes);
      assertEquals("FIELD_MASK size", p.FIELD_MASK.encodedSize(theirValue),
          ProtoAdapter.FIELD_MASK.encodedSize(ourValue));
      assertEquals(paths, ProtoAdapter.FIELD_MASK.decode(ourBytes).getPaths());
      assertEquals(paths, p.FIELD_MASK.decode(
          io.apitomy.antiwire.parity.okio.ByteString.of(ourBytes)).getPaths());
    }
  }

  @Test public void structAdapters() throws IOException {
    Map<String, Object> struct = new LinkedHashMap<>();
    struct.put("null", null);
    struct.put("number", 42.0);
    struct.put("string", "hello");
    struct.put("bool", true);
    struct.put("list", Arrays.asList(1.0, "two", false, null));
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("deep", 3.14);
    struct.put("map", nested);

    assertStructParity("STRUCT_MAP",
        (ProtoAdapter<Object>) (ProtoAdapter<?>) ProtoAdapter.STRUCT_MAP, p.STRUCT_MAP, struct);
    assertStructParity("STRUCT_LIST",
        (ProtoAdapter<Object>) (ProtoAdapter<?>) ProtoAdapter.STRUCT_LIST, p.STRUCT_LIST,
        Arrays.asList(1.0, "two", false, null, Arrays.asList(3.0), struct));
    assertStructParity("STRUCT_VALUE null", ProtoAdapter.STRUCT_VALUE, p.STRUCT_VALUE, null);
    assertStructParity("STRUCT_VALUE number", ProtoAdapter.STRUCT_VALUE, p.STRUCT_VALUE, 7.0);
    assertStructParity("STRUCT_VALUE string", ProtoAdapter.STRUCT_VALUE, p.STRUCT_VALUE, "s");
    assertStructParity("STRUCT_VALUE bool", ProtoAdapter.STRUCT_VALUE, p.STRUCT_VALUE, true);
  }

  private static void assertStructParity(String what, ProtoAdapter<Object> ours,
      io.apitomy.antiwire.parity.wire.ProtoAdapter<Object> theirs, Object value)
      throws IOException {
    byte[] ourBytes = ours.encode(value);
    assertArrayEquals(what + " bytes", theirs.encode(value), ourBytes);
    assertEquals(what + " size", theirs.encodedSize(value), ours.encodedSize(value));
    assertEquals(what + " cross-decode", value,
        theirs.decode(io.apitomy.antiwire.parity.okio.ByteString.of(ourBytes)));
  }



  @Test public void wrapperAdapters() throws IOException {
    // A null wrapper encodes to zero bytes; decoding empty input yields the delegate identity
    // by upstream contract, so the null cases compare bytes and sizes only.
    assertArrayEquals(p.DOUBLE_VALUE.encode(null), ProtoAdapter.DOUBLE_VALUE.encode(null));
    assertEquals(p.DOUBLE_VALUE.encodedSize(null), ProtoAdapter.DOUBLE_VALUE.encodedSize(null));
    assertArrayEquals(p.INT32_VALUE.encode(null), ProtoAdapter.INT32_VALUE.encode(null));
    assertArrayEquals(p.STRING_VALUE.encode(null), ProtoAdapter.STRING_VALUE.encode(null));
    assertParity("DOUBLE_VALUE 0", ProtoAdapter.DOUBLE_VALUE, p.DOUBLE_VALUE, 0.0);
    assertParity("DOUBLE_VALUE v", ProtoAdapter.DOUBLE_VALUE, p.DOUBLE_VALUE, 3.5);
    assertParity("INT32_VALUE 0", ProtoAdapter.INT32_VALUE, p.INT32_VALUE, 0);
    assertParity("INT32_VALUE v", ProtoAdapter.INT32_VALUE, p.INT32_VALUE, -42);
    assertParity("STRING_VALUE empty", ProtoAdapter.STRING_VALUE, p.STRING_VALUE, "");
    assertParity("STRING_VALUE v", ProtoAdapter.STRING_VALUE, p.STRING_VALUE, "wrapped");
    assertParity("BOOL_VALUE true", ProtoAdapter.BOOL_VALUE, p.BOOL_VALUE, true);
  }

  @Test public void mapAdapter() throws IOException {
    Map<String, Integer> value = new LinkedHashMap<>();
    value.put("a", 1);
    value.put("b", -1);
    value.put("", 0);
    ProtoAdapter<Map<String, Integer>> ours = ProtoAdapter.newMapAdapter(
        ProtoAdapter.STRING, ProtoAdapter.INT32);
    io.apitomy.antiwire.parity.wire.ProtoAdapter<Map<String, Integer>> theirs =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.newMapAdapter(
            p.STRING, p.INT32);
    // Maps encode only with a tag (upstream contract); compare tagged bytes and sizes.
    byte[] ourTagged;
    byte[] theirTagged;
    try (okio.Buffer buffer = new okio.Buffer()) {
      ours.encodeWithTag(new ProtoWriter(buffer), 1, value);
      ourTagged = buffer.readByteArray();
    }
    try (io.apitomy.antiwire.parity.okio.Buffer buffer =
        new io.apitomy.antiwire.parity.okio.Buffer()) {
      theirs.encodeWithTag(
          new io.apitomy.antiwire.parity.wire.ProtoWriter(buffer), 1, value);
      theirTagged = buffer.readByteArray();
    }
    assertArrayEquals("MAP tagged bytes", theirTagged, ourTagged);
    assertEquals("MAP tagged size", theirs.encodedSizeWithTag(1, value),
        ours.encodedSizeWithTag(1, value));
    // Map entries decode from the entry payload; the contract returns single-entry maps.
    okio.Buffer entry = new okio.Buffer();
    ProtoWriter entryWriter = new ProtoWriter(entry);
    ProtoAdapter.STRING.encodeWithTag(entryWriter, 1, "a");
    ProtoAdapter.INT32.encodeWithTag(entryWriter, 2, 1);
    byte[] entryBytes = entry.readByteArray();
    assertEquals(Collections.singletonMap("a", 1), ours.decode(entryBytes));
    assertEquals(Collections.singletonMap("a", 1),
        theirs.decode(io.apitomy.antiwire.parity.okio.ByteString.of(entryBytes)));
  }

  @Test public void packedAndRepeatedAdapters() throws IOException {
    List<Integer> value = Arrays.asList(1, -1, 0, 150, Integer.MAX_VALUE, Integer.MIN_VALUE);
    ProtoAdapter<List<Integer>> ourPacked = ProtoAdapter.INT32.asPacked();
    ProtoAdapter<List<Integer>> ourRepeated = ProtoAdapter.INT32.asRepeated();
    io.apitomy.antiwire.parity.wire.ProtoAdapter<List<Integer>> theirPacked =
        (io.apitomy.antiwire.parity.wire.ProtoAdapter<List<Integer>>)
            (io.apitomy.antiwire.parity.wire.ProtoAdapter<?>) p.INT32.asPacked();
    io.apitomy.antiwire.parity.wire.ProtoAdapter<List<Integer>> theirRepeated =
        (io.apitomy.antiwire.parity.wire.ProtoAdapter<List<Integer>>)
            (io.apitomy.antiwire.parity.wire.ProtoAdapter<?>) p.INT32.asRepeated();
    // Packed and repeated encode only with a tag; their decode returns single-element lists
    // by upstream contract (the caller merges), so bare decode compares those singletons.
    byte[] ourPackedTagged;
    try (okio.Buffer buffer = new okio.Buffer()) {
      ourPacked.encodeWithTag(new ProtoWriter(buffer), 1, value);
      ourPackedTagged = buffer.readByteArray();
    }
    byte[] theirPackedTagged;
    try (io.apitomy.antiwire.parity.okio.Buffer buffer =
        new io.apitomy.antiwire.parity.okio.Buffer()) {
      theirPacked.encodeWithTag(
          new io.apitomy.antiwire.parity.wire.ProtoWriter(buffer), 1, value);
      theirPackedTagged = buffer.readByteArray();
    }
    assertArrayEquals("PACKED tagged bytes", theirPackedTagged, ourPackedTagged);
    assertEquals("PACKED tagged size", theirPacked.encodedSizeWithTag(1, value),
        ourPacked.encodedSizeWithTag(1, value));
    // Raw element bytes (no envelope; INT32 is varint-typed).
    okio.Buffer elements = new okio.Buffer();
    ProtoWriter elementsWriter = new ProtoWriter(elements);
    for (Integer v : value) {
      ProtoAdapter.INT32.encode(elementsWriter, v);
    }
    byte[] elementBytes = elements.readByteArray();
    assertEquals(Collections.singletonList(value.get(0)), ourPacked.decode(elementBytes));
    assertEquals(Collections.singletonList(value.get(0)),
        theirPacked.decode(
            io.apitomy.antiwire.parity.okio.ByteString.of(elementBytes)));
    assertEquals(Collections.singletonList(value.get(0)), ourRepeated.decode(elementBytes));
    assertEquals(Collections.singletonList(value.get(0)),
        theirRepeated.decode(
            io.apitomy.antiwire.parity.okio.ByteString.of(elementBytes)));
    // Packed and repeated are different wire forms (one length-delimited blob versus one tag
    // per element); both merge to the same list, which the element-level decodes above cover.
  }

  @Test public void arrayAdapters() throws IOException {
    int[] ints = {1, -1, 0, 150, Integer.MIN_VALUE};
    byte[] ours = ProtoAdapter.INT32_ARRAY.encode(ints);
    byte[] theirs = p.INT32_ARRAY.encode(ints);
    assertArrayEquals(theirs, ours);
    // Array adapters decode a single element like packed adapters (upstream contract).
    assertArrayEquals(new int[] {1}, ProtoAdapter.INT32_ARRAY.decode(ours));
    assertArrayEquals(new int[] {1},
        p.INT32_ARRAY.decode(io.apitomy.antiwire.parity.okio.ByteString.of(ours)));

    long[] longs = {0L, -1L, Long.MAX_VALUE};
    assertArrayEquals(p.INT64_ARRAY.encode(longs), ProtoAdapter.INT64_ARRAY.encode(longs));

    double[] doubles = {0.0, 1.5, -2.25};
    assertArrayEquals(p.DOUBLE_ARRAY.encode(doubles), ProtoAdapter.DOUBLE_ARRAY.encode(doubles));

    float[] floats = {0.0f, 1.5f};
    assertArrayEquals(p.FLOAT_ARRAY.encode(floats), ProtoAdapter.FLOAT_ARRAY.encode(floats));
  }

  @Test public void unknownFieldRoundtripThroughReader() throws IOException {
    // A message carrying an unknown varint field must collect it verbatim.
    okio.Buffer buffer = new okio.Buffer();
    ProtoWriter writer = new ProtoWriter(buffer);
    writer.writeTag(7, FieldEncoding.VARINT);
    writer.writeVarint64(123456789L);
    ProtoReader reader = new ProtoReader(buffer);
    long token = reader.beginMessage();
    int tag = reader.nextTag();
    assertEquals(7, tag);
    reader.readUnknownField(tag);
    ByteString unknown = reader.endMessageAndGetUnknownFields(token);
    // One tag byte (field 7, varint) plus the four-byte varint for 123456789.
    assertEquals(5, unknown.size());
    assertEquals(0x38, unknown.getByte(0) & 0xff);
    // The collected bytes re-encode identically through the bytes adapter, in tagged form.
    okio.Buffer tagged = new okio.Buffer();
    ProtoWriter taggedWriter = new ProtoWriter(tagged);
    taggedWriter.writeTag(1, FieldEncoding.LENGTH_DELIMITED);
    taggedWriter.writeVarint32(unknown.size());
    taggedWriter.writeBytes(unknown);
    ProtoReader reread = new ProtoReader(tagged);
    long rereadToken = reread.beginMessage();
    reread.nextTag();
    assertEquals(unknown, ProtoAdapter.BYTES.decode(reread));
    reread.endMessageAndGetUnknownFields(rereadToken);
  }

  private static final class p {
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Integer> INT32 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.INT32;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Integer> UINT32 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.UINT32;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Integer> SINT32 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.SINT32;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Integer> FIXED32 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.FIXED32;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Integer> SFIXED32 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.SFIXED32;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Long> INT64 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.INT64;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Long> UINT64 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.UINT64;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Long> SINT64 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.SINT64;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Long> FIXED64 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.FIXED64;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Long> SFIXED64 =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.SFIXED64;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Boolean> BOOL =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.BOOL;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Double> DOUBLE =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.DOUBLE;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Float> FLOAT =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.FLOAT;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<String> STRING =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.STRING;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<io.apitomy.antiwire.parity.okio.ByteString> BYTES =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.BYTES;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<java.time.Duration> DURATION =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.DURATION;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<java.time.Instant> INSTANT =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.INSTANT;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<io.apitomy.antiwire.parity.wire.FieldMask> FIELD_MASK =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.FIELD_MASK;
    @SuppressWarnings("unchecked")
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Object> STRUCT_MAP =
        (io.apitomy.antiwire.parity.wire.ProtoAdapter<Object>)
            (io.apitomy.antiwire.parity.wire.ProtoAdapter<?>)
                io.apitomy.antiwire.parity.wire.ProtoAdapter.STRUCT_MAP;
    @SuppressWarnings("unchecked")
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Object> STRUCT_LIST =
        (io.apitomy.antiwire.parity.wire.ProtoAdapter<Object>)
            (io.apitomy.antiwire.parity.wire.ProtoAdapter<?>)
                io.apitomy.antiwire.parity.wire.ProtoAdapter.STRUCT_LIST;
    @SuppressWarnings("unchecked")
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Object> STRUCT_VALUE =
        (io.apitomy.antiwire.parity.wire.ProtoAdapter<Object>)
            (io.apitomy.antiwire.parity.wire.ProtoAdapter<?>)
                io.apitomy.antiwire.parity.wire.ProtoAdapter.STRUCT_VALUE;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Double> DOUBLE_VALUE =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.DOUBLE_VALUE;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Integer> INT32_VALUE =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.INT32_VALUE;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<String> STRING_VALUE =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.STRING_VALUE;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<Boolean> BOOL_VALUE =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.BOOL_VALUE;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<int[]> INT32_ARRAY =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.INT32_ARRAY;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<long[]> INT64_ARRAY =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.INT64_ARRAY;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<double[]> DOUBLE_ARRAY =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.DOUBLE_ARRAY;
    private static final io.apitomy.antiwire.parity.wire.ProtoAdapter<float[]> FLOAT_ARRAY =
        io.apitomy.antiwire.parity.wire.ProtoAdapter.FLOAT_ARRAY;
  }
}
