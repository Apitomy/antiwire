package com.squareup.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import okio.ByteString;
import org.junit.Test;

/**
 * ByteArrayProtoReader32 parity against both the long-cursor ProtoReader and the relocated
 * upstream oracle: identical structured corpora must produce identical transcripts through the
 * int-cursor reader, the wrapper view, and upstream's own 32-bit reader.
 */
public class ProtoReader32ParityTest {

  private static List<String> readAll32(ProtoReader32 reader) throws IOException {
    List<String> transcript = new ArrayList<>();
    int token = reader.beginMessage();
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

  private static List<String> readAllUpstream32(byte[] bytes) throws IOException {
    byte[] source = io.apitomy.antiwire.parity.okio.ByteString.of(bytes).toByteArray();
    io.apitomy.antiwire.parity.wire.ProtoReader32 reader =
        new io.apitomy.antiwire.parity.wire.ByteArrayProtoReader32(
            source, 0, source.length);
    List<String> transcript = new ArrayList<>();
    int token = reader.beginMessage();
    int tag;
    while ((tag = reader.nextTag()) != -1) {
      io.apitomy.antiwire.parity.wire.FieldEncoding encoding =
          reader.peekFieldEncoding();
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

  /** Same random well-formed corpus as the long-cursor test: transcripts must match exactly. */
  @Test public void structuredCorpusParityWithLongReader() throws IOException {
    Random random = new Random(0xBAD5EED);
    for (int message = 0; message < 200; message++) {
      byte[] encoded = randomWellFormedMessage(random, 3);
      List<String> longReader =
          readAllThroughLong(new ProtoReader(new okio.Buffer().write(encoded)));
      List<String> intReader = readAll32(new ByteArrayProtoReader32(encoded));
      List<String> wrapper =
          readAllThroughLong(new ByteArrayProtoReader32(encoded).asProtoReader());
      assertEquals("message " + message, longReader, intReader);
      assertEquals("message " + message, longReader, wrapper);
    }
  }

  private static List<String> readAllThroughLong(ProtoReader reader) throws IOException {
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

  /** Parity with upstream's own ByteArray reader on the same corpus. */
  @Test public void structuredCorpusParityWithUpstream32() throws IOException {
    Random random = new Random(0xBAD5EED);
    for (int message = 0; message < 200; message++) {
      byte[] encoded = randomWellFormedMessage(random, 3);
      assertEquals("message " + message,
          readAllUpstream32(encoded), readAll32(new ByteArrayProtoReader32(encoded)));
    }
  }

  /** The error vectors must match the long reader's behavior. */
  @Test public void errorParity() throws IOException {
    byte[] tagZero = {0x00};
    IOException e1 = assertThrows(IOException.class,
        () -> readFirstTag32(new ByteArrayProtoReader32(tagZero)));
    assertEquals("Unexpected tag 0. Reader position: 1. Last read tag: -1.", e1.getMessage());

    byte[] negativeLength = {
        0x0A, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F,
    };
    IOException e2 = assertThrows(IOException.class,
        () -> readFirstTag32(new ByteArrayProtoReader32(negativeLength)));
    assertEquals("Negative length: -1. Reader position: 6. Last read tag: 1.", e2.getMessage());

    byte[] groupSkip = {
        0x0B, 0x10, 0x2A, 0x0C, // group 1 { field 2 varint 42 }, skipped whole
        0x28, 0x2A, // trailing field 5 varint 42
    };
    List<String> transcript = new ArrayList<>();
    ProtoReader32 reader = new ByteArrayProtoReader32(groupSkip);
    int token = reader.beginMessage();
    assertEquals(5, reader.nextTag());
    transcript.add(reader.readVarint64() + "");
    reader.endMessageAndGetUnknownFields(token);
    assertEquals("42", transcript.get(0));
  }

  private static void readFirstTag32(ProtoReader32 reader) throws IOException {
    reader.beginMessage();
    reader.nextTag();
  }

  /** Positions and limits: a sub-range read stops where told. */
  @Test public void subRange() throws IOException {
    byte[] bytes = {0x08, 0x2A, 0x10, 0x2B};
    ProtoReader32 reader = new ByteArrayProtoReader32(bytes, 0, 2);
    int token = reader.beginMessage();
    assertEquals(1, reader.nextTag());
    assertEquals(42, reader.readVarint32());
    assertEquals(-1, reader.nextTag());
    reader.endMessageAndGetUnknownFields(token);

    assertThrows(IllegalArgumentException.class,
        () -> new ByteArrayProtoReader32(bytes, 3, 2));
  }

  /** Unknown fields collected through the int reader round-trip identically. */
  @Test public void unknownFieldCollection() throws IOException {
    okio.Buffer buffer = new okio.Buffer();
    ProtoWriter writer = new ProtoWriter(buffer);
    writer.writeTag(7, FieldEncoding.VARINT);
    writer.writeVarint64(123456789L);
    writer.writeTag(8, FieldEncoding.LENGTH_DELIMITED);
    writer.writeVarint32(3);
    writer.writeBytes(ByteString.encodeUtf8("abc"));
    byte[] bytes = buffer.readByteArray();

    ProtoReader32 reader = new ByteArrayProtoReader32(bytes);
    int token = reader.beginMessage();
    assertEquals(7, reader.nextTag());
    reader.readUnknownField(7);
    assertEquals(8, reader.nextTag());
    reader.readUnknownField(8);
    assertEquals(-1, reader.nextTag());
    ByteString unknown = reader.endMessageAndGetUnknownFields(token);
    // 0x38 varint(123456789) then tag 8, length 3, "abc".
    assertEquals("38959aef3a4203616263", unknown.hex());
    assertEquals(10, unknown.size());
  }

  private static byte[] randomWellFormedMessage(Random random, int depth) throws IOException {
    okio.Buffer out = new okio.Buffer();
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
          String alphabet = "abcXYZ019 αβγ 😀 € ñ 日本語";
          int length = random.nextInt(12);
          StringBuilder sb = new StringBuilder();
          for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
          }
          writer.writeTag(tag, FieldEncoding.LENGTH_DELIMITED);
          byte[] utf8 = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          writer.writeVarint32(utf8.length);
          writer.writeString(sb.toString());
          break;
        }
        case 5: {
          byte[] raw = new byte[random.nextInt(50)];
          random.nextBytes(raw);
          writer.writeTag(tag, FieldEncoding.LENGTH_DELIMITED);
          writer.writeVarint32(raw.length);
          writer.writeBytes(ByteString.of(raw));
          break;
        }
        default: {
          byte[] nested = randomWellFormedMessage(random, depth - 1);
          writer.writeTag(tag, FieldEncoding.LENGTH_DELIMITED);
          writer.writeVarint32(nested.length);
          writer.writeBytes(ByteString.of(nested));
          break;
        }
      }
    }
    return out.readByteArray();
  }
}
