package com.squareup.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import okio.Buffer;
import org.junit.Test;

/**
 * TASK-5 spike evidence: byte-for-byte and exception-for-exception parity between this port's
 * {@link ProtoReader}/{@link ProtoWriter} and upstream wire-runtime-jvm 7.1.0 (relocated to
 * {@code io.apitomy.antiwire.parity.wire} by the wire-upstream-shaded fixture).
 *
 * <p>Known intentional asymmetry: upstream's {@code internal.ProtocolException} is a JVM
 * typealias of {@link java.net.ProtocolException}; Java has no typealias, so this port throws a
 * subclass of it. Error parity is asserted as: both throw, same message, and ours is a
 * {@link java.net.ProtocolException}.
 */
public class ProtoReaderWriterParityTest {

  static final String ALPHABET = "abcXYZ019 αβγγδ 😀🚀 € ñ 日本語 😀";

  @Test public void writerParityOnSeededCorpus() throws Exception {
    Random random = new Random(0xC0FFEE);
    for (int sequence = 0; sequence < 500; sequence++) {
      Buffer ours = new Buffer();
      io.apitomy.antiwire.parity.okio.Buffer theirs =
          new io.apitomy.antiwire.parity.okio.Buffer();
      ProtoWriter ourWriter = new ProtoWriter(ours);
      io.apitomy.antiwire.parity.wire.ProtoWriter theirWriter =
          new io.apitomy.antiwire.parity.wire.ProtoWriter(theirs);

      int ops = 1 + random.nextInt(200);
      for (int op = 0; op < ops; op++) {
        int kind = random.nextInt(8);
        switch (kind) {
          case 0:
            int i = random.nextInt();
            ourWriter.writeVarint32(i);
            theirWriter.writeVarint32(i);
            break;
          case 1:
            int si = random.nextInt();
            ourWriter.writeSignedVarint32(si);
            // Upstream writeSignedVarint32 is Kotlin-internal, compiled with the mangled name
            // writeSignedVarint32$wire_runtime ('$' is a legal Java identifier character); call
            // the real upstream method instead of emulating its branch structure.
            theirWriter.writeSignedVarint32$wire_runtime(si);
            break;
          case 2:
            long l = random.nextLong();
            ourWriter.writeVarint64(l);
            theirWriter.writeVarint64(l);
            break;
          case 3:
            int f = random.nextInt();
            ourWriter.writeFixed32(f);
            theirWriter.writeFixed32(f);
            break;
          case 4:
            long g = random.nextLong();
            ourWriter.writeFixed64(g);
            theirWriter.writeFixed64(g);
            break;
          case 5:
            int field = 1 + random.nextInt((1 << 28) - 1);
            FieldEncoding encoding = FieldEncoding.values()[random.nextInt(4)];
            ourWriter.writeTag(field, encoding);
            theirWriter.writeTag(field,
                io.apitomy.antiwire.parity.wire.FieldEncoding.values()[encoding.ordinal()]);
            break;
          case 6:
            String s = randomString(random);
            ourWriter.writeString(s);
            theirWriter.writeString(s);
            break;
          default:
            byte[] bytes = new byte[random.nextInt(101)];
            random.nextBytes(bytes);
            ourWriter.writeBytes(okio.ByteString.of(bytes));
            theirWriter.writeBytes(io.apitomy.antiwire.parity.okio.ByteString.of(bytes));
            break;
        }
      }
      byte[] ourBytes = ours.readByteArray();
      byte[] theirBytes = theirs.readByteArray();
      assertArrayEquals("sequence " + sequence, theirBytes, ourBytes);
    }
  }

  @Test public void readerParityOnStructuredCorpus() throws Exception {
    Random random = new Random(0xBAD5EED);
    for (int message = 0; message < 200; message++) {
      byte[] encoded = randomWellFormedMessage(random, 3);

      List<String> ourTranscript = readAll(new ProtoReader(new Buffer().write(encoded)));
      List<String> theirTranscript = readAllUpstream(
          new io.apitomy.antiwire.parity.wire.ProtoReader(
              new io.apitomy.antiwire.parity.okio.Buffer().write(encoded)));
      assertEquals("message " + message, theirTranscript, ourTranscript);
    }
  }

  /** Deterministic read loop: consume every tag by its encoding and record the values. */
  private static List<String> readAll(ProtoReader reader) throws IOException {
    List<String> transcript = new ArrayList<>();
    long token = reader.beginMessage();
    int tag;
    while ((tag = reader.nextTag()) != -1) {
      FieldEncoding encoding = reader.peekFieldEncoding();
      switch (encoding) {
        case VARINT:
          transcript.add(tag + ":varint:" + reader.readVarint64());
          break;
        case FIXED32:
          transcript.add(tag + ":fixed32:" + reader.readFixed32());
          break;
        case FIXED64:
          transcript.add(tag + ":fixed64:" + reader.readFixed64());
          break;
        case LENGTH_DELIMITED:
          transcript.add(tag + ":bytes:" + reader.readBytes().hex());
          break;
        default:
          throw new AssertionError(encoding);
      }
    }
    transcript.add("unknown:" + reader.endMessageAndGetUnknownFields(token).size());
    return transcript;
  }

  private static List<String> readAllUpstream(
      io.apitomy.antiwire.parity.wire.ProtoReader reader) throws IOException {
    List<String> transcript = new ArrayList<>();
    long token = reader.beginMessage();
    int tag;
    while ((tag = reader.nextTag()) != -1) {
      io.apitomy.antiwire.parity.wire.FieldEncoding encoding = reader.peekFieldEncoding();
      switch (encoding) {
        case VARINT:
          transcript.add(tag + ":varint:" + reader.readVarint64());
          break;
        case FIXED32:
          transcript.add(tag + ":fixed32:" + reader.readFixed32());
          break;
        case FIXED64:
          transcript.add(tag + ":fixed64:" + reader.readFixed64());
          break;
        case LENGTH_DELIMITED:
          transcript.add(tag + ":bytes:" + reader.readBytes().hex());
          break;
        default:
          throw new AssertionError(encoding);
      }
    }
    transcript.add("unknown:" + reader.endMessageAndGetUnknownFields(token).size());
    return transcript;
  }

  /** Builds a well-formed message: scalars, strings, bytes, nested messages, packed varints. */
  private static byte[] randomWellFormedMessage(Random random, int depth) throws IOException {
    Buffer out = new Buffer();
    ProtoWriter writer = new ProtoWriter(out);
    int fields = random.nextInt(8);
    for (int f = 0; f < fields; f++) {
      int tag = 1 + random.nextInt(100);
      int kind = random.nextInt(depth == 0 ? 6 : 7);
      switch (kind) {
        case 0:
          writer.writeTag(tag, FieldEncoding.VARINT);
          writer.writeVarint32(random.nextInt());
          break;
        case 1:
          writer.writeTag(tag, FieldEncoding.VARINT);
          writer.writeVarint64(random.nextLong());
          break;
        case 2:
          writer.writeTag(tag, FieldEncoding.FIXED32);
          writer.writeFixed32(random.nextInt());
          break;
        case 3:
          writer.writeTag(tag, FieldEncoding.FIXED64);
          writer.writeFixed64(random.nextLong());
          break;
        case 4: {
          String s = randomString(random);
          writer.writeTag(tag, FieldEncoding.LENGTH_DELIMITED);
          byte[] utf8 = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          writer.writeVarint32(utf8.length);
          writer.writeString(s);
          break;
        }
        case 5: {
          byte[] bytes = new byte[random.nextInt(50)];
          random.nextBytes(bytes);
          writeLengthDelimited(writer, tag, bytes);
          break;
        }
        default: {
          if (random.nextBoolean()) {
            // Nested message.
            writeLengthDelimited(writer, tag, randomWellFormedMessage(random, depth - 1));
          } else {
            // Packed varints.
            Buffer packed = new Buffer();
            ProtoWriter packedWriter = new ProtoWriter(packed);
            int n = random.nextInt(5);
            for (int i = 0; i < n; i++) packedWriter.writeVarint32(random.nextInt());
            writeLengthDelimited(writer, tag, packed.readByteArray());
          }
          break;
        }
      }
    }
    return out.readByteArray();
  }

  private static void writeLengthDelimited(ProtoWriter writer, int tag, byte[] bytes)
      throws IOException {
    writer.writeTag(tag, FieldEncoding.LENGTH_DELIMITED);
    writer.writeVarint32(bytes.length);
    writer.writeBytes(okio.ByteString.of(bytes));
  }

  @Test public void errorParityTagZero() throws Exception {
    // A lone zero byte is tag 0.
    assertErrorParity(new byte[] {0x00}, "Unexpected tag 0. Reader position: 1. Last read tag: -1.");
  }

  @Test public void errorParityMalformedVarint() throws Exception {
    byte[] bytes = new byte[10];
    java.util.Arrays.fill(bytes, (byte) 0xFF);
    // Fresh reader is in STATE_LENGTH_DELIMITED, so readVarint32 is legal; 10 continuation bytes
    // exceed the varint limit.
    assertErrorParity(bytes, "Malformed VARINT. Reader position: 10. Last read tag: -1.");
  }

  /**
   * GHSA-7xpr-hc2w-34m9 regression: a length-delimited field whose length varint decodes
   * negative must be rejected (was CVE-2024-era crash class; upstream fixed in wire 6.3.0).
   * FF FF FF FF 0F is a five-byte varint whose int32 value is -1.
   */
  @Test public void errorParityNegativeLengthTopLevel() throws Exception {
    byte[] bytes = {
        0x0A, // tag 1, LENGTH_DELIMITED
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F, // length = -1
    };
    assertErrorParity(bytes, "Negative length: -1. Reader position: 6. Last read tag: 1.");
  }

  /** Same regression through the group-skip path, which is where the GHSA fix landed. */
  @Test public void errorParityNegativeLengthInsideGroupSkip() throws Exception {
    byte[] bytes = {
        0x0B, // tag 1, START_GROUP
        0x0A, // tag 1, LENGTH_DELIMITED
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F, // length = -1
    };
    assertErrorParity(bytes, "Negative length: -1. Reader position: 7. Last read tag: 1.");
  }

  @Test public void errorParityUnexpectedEndGroup() throws Exception {
    assertErrorParity(new byte[] {0x0C},
        "Unexpected end group. Reader position: 1. Last read tag: 1.");
  }

  @Test public void errorParityUnexpectedFieldEncoding() throws Exception {
    assertErrorParity(new byte[] {0x0E},
        "Unexpected field encoding: 6. Reader position: 1. Last read tag: 1.");
  }

  private void assertErrorParity(byte[] bytes, String expectedMessage) {
    // Ours.
    IOException ours = assertThrows(IOException.class,
        () -> readFirstTag(new ProtoReader(new Buffer().write(bytes))));
    // Upstream (relocated).
    IOException theirs = assertThrows(IOException.class, () -> readFirstTagUpstream(
        new io.apitomy.antiwire.parity.wire.ProtoReader(
            new io.apitomy.antiwire.parity.okio.Buffer().write(bytes))));
    assertEquals(expectedMessage, theirs.getMessage());
    assertEquals(expectedMessage, ours.getMessage());
    assertTrue("ours must be a java.net.ProtocolException like upstream's typealias",
        ours instanceof java.net.ProtocolException);
  }

  private static void readFirstTag(ProtoReader reader) throws IOException {
    reader.beginMessage();
    reader.nextTag();
  }

  private static void readFirstTagUpstream(
      io.apitomy.antiwire.parity.wire.ProtoReader reader) throws IOException {
    reader.beginMessage();
    reader.nextTag();
  }

  private static String randomString(Random random) {
    int length = random.nextInt(12);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }

  // Static helpers are internal in upstream (name-mangled), so parity for them is proven
  // behaviorally: sizes equal the byte counts actually written, and zigzag vectors are pinned.

  @Test public void sizesMatchWrittenBytes() throws Exception {
    Random random = new Random(42);
    int[] interesting = {
        0, 1, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456,
        -1, -128, -16384, Integer.MIN_VALUE, Integer.MAX_VALUE,
    };
    for (int value : interesting) {
      assertEquals("varint32Size(" + value + ")", writtenVarint32Size(value),
          ProtoWriter.varint32Size(value));
      assertEquals("int32Size(" + value + ")", writtenSignedVarint32Size(value),
          ProtoWriter.int32Size(value));
    }
    for (int i = 0; i < 10000; i++) {
      int value = random.nextInt();
      assertEquals("varint32Size(" + value + ")", writtenVarint32Size(value),
          ProtoWriter.varint32Size(value));
      assertEquals("int32Size(" + value + ")", writtenSignedVarint32Size(value),
          ProtoWriter.int32Size(value));
    }
    long[] longs = { 0L, 1L, 127L, 128L, 16383L, 16384L, Long.MAX_VALUE, Long.MIN_VALUE, -1L };
    for (long value : longs) {
      assertEquals("varint64Size(" + value + ")", writtenVarint64Size(value),
          ProtoWriter.varint64Size(value));
    }
  }

  private static int writtenVarint32Size(int value) throws IOException {
    Buffer buffer = new Buffer();
    new ProtoWriter(buffer).writeVarint32(value);
    return (int) buffer.size();
  }

  private static int writtenSignedVarint32Size(int value) throws IOException {
    Buffer buffer = new Buffer();
    new ProtoWriter(buffer).writeSignedVarint32(value);
    return (int) buffer.size();
  }

  private static int writtenVarint64Size(long value) throws IOException {
    Buffer buffer = new Buffer();
    new ProtoWriter(buffer).writeVarint64(value);
    return (int) buffer.size();
  }

  @Test public void zigZagVectors() {
    assertEquals(0, ProtoWriter.encodeZigZag32(0));
    assertEquals(1, ProtoWriter.encodeZigZag32(-1));
    assertEquals(2, ProtoWriter.encodeZigZag32(1));
    assertEquals(0xFFFFFFFE, ProtoWriter.encodeZigZag32(Integer.MAX_VALUE));
    assertEquals(0xFFFFFFFF, ProtoWriter.encodeZigZag32(Integer.MIN_VALUE));
    assertEquals(0, ProtoWriter.encodeZigZag64(0L));
    assertEquals(1, ProtoWriter.encodeZigZag64(-1L));
    assertEquals(2, ProtoWriter.encodeZigZag64(1L));
    assertEquals(0xFFFFFFFFFFFFFFFEL, ProtoWriter.encodeZigZag64(Long.MAX_VALUE));
    assertEquals(0xFFFFFFFFFFFFFFFFL, ProtoWriter.encodeZigZag64(Long.MIN_VALUE));
    assertEquals(-1, ProtoWriter.decodeZigZag32(1));
    assertEquals(1L, ProtoWriter.decodeZigZag64(2L));
  }

  /**
   * Packed-scalar replay driven the way upstream adapters drive it
   * (ProtoAdapter.commonTryDecode): one beforePossiblyPackedScalar check per nextTag; the
   * STATE_PACKED_TAG replay happens inside nextTag. Also covers the packed-empty branch where
   * beforePossiblyPackedScalar returns false and pops the limit.
   */
  @Test public void packedScalarParity() throws Exception {
    Buffer buffer = new Buffer();
    ProtoWriter writer = new ProtoWriter(buffer);
    Buffer packed = new Buffer();
    ProtoWriter packedWriter = new ProtoWriter(packed);
    packedWriter.writeVarint32(150);
    packedWriter.writeVarint32(-7);
    packedWriter.writeVarint32(0);
    writeLengthDelimited(writer, 1, packed.readByteArray());
    writer.writeTag(2, FieldEncoding.VARINT);
    writer.writeVarint64(Long.MAX_VALUE);
    writeLengthDelimited(writer, 3, new byte[0]); // empty packed
    byte[] bytes = buffer.readByteArray();

    List<String> ours = readPacked(new ProtoReader(new Buffer().write(bytes)));
    List<String> theirs = readPackedUpstream(
        new io.apitomy.antiwire.parity.wire.ProtoReader(
            new io.apitomy.antiwire.parity.okio.Buffer().write(bytes)));
    assertEquals(theirs, ours);
    // Three packed values, one plain varint, and the empty packed contributes nothing.
    assertEquals(4, ours.size() - 1);
  }

  private static List<String> readPacked(ProtoReader reader) throws IOException {
    List<String> transcript = new ArrayList<>();
    long token = reader.beginMessage();
    int tag;
    while ((tag = reader.nextTag()) != -1) {
      if (tag != 2) {
        // Packed field: one try-decode per tag replay.
        if (reader.beforePossiblyPackedScalar()) {
          transcript.add(tag + ":packed:" + reader.readVarint32());
        }
      } else {
        transcript.add(tag + ":varint:" + reader.readVarint64());
      }
    }
    transcript.add("unknown:" + reader.endMessageAndGetUnknownFields(token).size());
    return transcript;
  }

  private static List<String> readPackedUpstream(
      io.apitomy.antiwire.parity.wire.ProtoReader reader) throws IOException {
    List<String> transcript = new ArrayList<>();
    long token = reader.beginMessage();
    int tag;
    while ((tag = reader.nextTag()) != -1) {
      if (tag != 2) {
        if (reader.beforePossiblyPackedScalar$wire_runtime()) {
          transcript.add(tag + ":packed:" + reader.readVarint32());
        }
      } else {
        transcript.add(tag + ":varint:" + reader.readVarint64());
      }
    }
    transcript.add("unknown:" + reader.endMessageAndGetUnknownFields(token).size());
    return transcript;
  }

  /** Group skipping on the happy path: nested groups with fields inside, then a trailing field. */
  @Test public void groupSkipParity() throws Exception {
    Buffer buffer = new Buffer();
    ProtoWriter writer = new ProtoWriter(buffer);
    writer.writeVarint32((1 << 3) | 3); // start group 1
    writer.writeTag(2, FieldEncoding.VARINT);
    writer.writeVarint32(42);
    writer.writeVarint32((3 << 3) | 3); // start nested group 3
    writeLengthDelimited(writer, 4, new byte[] {1, 2, 3});
    writer.writeVarint32((3 << 3) | 4); // end nested group 3
    writer.writeVarint32((1 << 3) | 4); // end group 1
    writer.writeTag(5, FieldEncoding.VARINT);
    writer.writeVarint64(7L);
    byte[] bytes = buffer.readByteArray();

    List<String> ours = readAll(new ProtoReader(new Buffer().write(bytes)));
    List<String> theirs = readAllUpstream(
        new io.apitomy.antiwire.parity.wire.ProtoReader(
            new io.apitomy.antiwire.parity.okio.Buffer().write(bytes)));
    assertEquals(theirs, ours);
    // The groups are skipped: only the trailing field appears.
    assertEquals(1, ours.size() - 1);
  }

  /** An unterminated group ends with EOFException from skipGroup on both sides. */
  @Test public void unterminatedGroupParity() {
    byte[] bytes = {
        (byte) ((1 << 3) | 3), // start group 1
        0x10, 0x2A, // field 2 varint 42
        // no end-group
    };
    IOException ours = assertThrows(IOException.class,
        () -> readFirstTag(new ProtoReader(new Buffer().write(bytes))));
    IOException theirs = assertThrows(IOException.class, () -> readFirstTagUpstream(
        new io.apitomy.antiwire.parity.wire.ProtoReader(
            new io.apitomy.antiwire.parity.okio.Buffer().write(bytes))));
    // Upstream's okio.EOFException is a JVM typealias of java.io.EOFException, so both sides
    // throw java.io.EOFException here.
    assertEquals(java.io.EOFException.class, theirs.getClass());
    assertEquals(java.io.EOFException.class, ours.getClass());
  }

  /** Nesting 101 messages deep exceeds RECURSION_LIMIT with an identical message. */
  @Test public void recursionLimitParity() throws Exception {
    byte[] bytes = new byte[0];
    for (int i = 0; i < 101; i++) {
      Buffer buffer = new Buffer();
      writeLengthDelimited(new ProtoWriter(buffer), 1, bytes);
      bytes = buffer.readByteArray();
    }

    final byte[] payload = bytes;
    IOException ours = assertThrows(IOException.class,
        () -> readDeep(new ProtoReader(new Buffer().write(payload))));
    IOException theirs = assertThrows(IOException.class, () -> readDeepUpstream(
        new io.apitomy.antiwire.parity.wire.ProtoReader(
            new io.apitomy.antiwire.parity.okio.Buffer().write(payload))));
    assertEquals("Wire recursion limit exceeded", theirs.getMessage());
    assertEquals("Wire recursion limit exceeded", ours.getMessage());
  }

  /** Descends into every length-delimited field so the reader's recursion depth actually grows. */
  private static void readDeep(ProtoReader reader) throws IOException {
    long token = reader.beginMessage();
    int tag;
    while ((tag = reader.nextTag()) != -1) {
      if (reader.peekFieldEncoding() == FieldEncoding.LENGTH_DELIMITED) {
        readDeep(reader);
      } else {
        reader.skip();
      }
    }
    reader.endMessageAndGetUnknownFields(token);
  }

  private static void readDeepUpstream(
      io.apitomy.antiwire.parity.wire.ProtoReader reader) throws IOException {
    long token = reader.beginMessage();
    int tag;
    while ((tag = reader.nextTag()) != -1) {
      if (reader.peekFieldEncoding()
          == io.apitomy.antiwire.parity.wire.FieldEncoding.LENGTH_DELIMITED) {
        readDeepUpstream(reader);
      } else {
        reader.skip();
      }
    }
    reader.endMessageAndGetUnknownFields(token);
  }

  /**
   * nextLengthDelimited plus readString outside a tag context: the input is a bare
   * length-prefixed value (length varint then UTF-8 bytes), the way a length-delimited stream
   * is read without tags.
   */
  @Test public void lengthDelimitedAndStringParity() throws Exception {
    byte[] utf8 = "héllo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Buffer buffer = new Buffer();
    ProtoWriter writer = new ProtoWriter(buffer);
    writer.writeVarint32(utf8.length);
    writer.writeString("héllo");
    byte[] bytes = buffer.readByteArray();

    String ours = readDelimitedString(new ProtoReader(new Buffer().write(bytes)));
    String theirs = readDelimitedStringUpstream(
        new io.apitomy.antiwire.parity.wire.ProtoReader(
            new io.apitomy.antiwire.parity.okio.Buffer().write(bytes)));
    assertEquals("6:héllo", theirs); // é is two bytes in UTF-8
    assertEquals(theirs, ours);
  }

  private static String readDelimitedString(ProtoReader reader) throws IOException {
    long token = reader.beginMessage();
    int length = reader.nextLengthDelimited();
    String value = reader.readString();
    reader.endMessageAndGetUnknownFields(token);
    return length + ":" + value;
  }

  private static String readDelimitedStringUpstream(
      io.apitomy.antiwire.parity.wire.ProtoReader reader) throws IOException {
    long token = reader.beginMessage();
    int length = reader.nextLengthDelimited();
    String value = reader.readString();
    reader.endMessageAndGetUnknownFields(token);
    return length + ":" + value;
  }
}
