package com.squareup.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Random;
import okio.Buffer;
import org.junit.Test;

/**
 * ReverseProtoWriter parity against the relocated upstream wire-runtime-jvm 7.1.0 oracle:
 * identical write sequences must produce identical bytes in reverse order, including malformed
 * UTF-16 handling, writeForward, and the byteCount length probe.
 */
public class ReverseProtoWriterParityTest {

  private interface ReverseBlock {
    void write(ReverseProtoWriter writer) throws IOException;
  }

  private interface UpstreamReverseBlock {
    void write(io.apitomy.antiwire.parity.wire.ReverseProtoWriter writer)
        throws IOException;
  }

  private static byte[] reverseBytes(ReverseBlock block) throws IOException {
    ReverseProtoWriter writer = new ReverseProtoWriter();
    block.write(writer);
    Buffer out = new Buffer();
    writer.writeTo(out);
    return out.readByteArray();
  }

  private static byte[] reverseBytesUpstream(UpstreamReverseBlock block) throws IOException {
    io.apitomy.antiwire.parity.wire.ReverseProtoWriter writer =
        new io.apitomy.antiwire.parity.wire.ReverseProtoWriter();
    block.write(writer);
    io.apitomy.antiwire.parity.okio.Buffer out =
        new io.apitomy.antiwire.parity.okio.Buffer();
    writer.writeTo(out);
    return out.readByteArray();
  }

  @Test public void utf8ParityOnSeededCorpus() throws IOException {
    String alphabet = "abcXYZ019 αβγ 😀🚀 € ñ 日本語";
    Random random = new Random(0x5EED5);
    for (int sequence = 0; sequence < 300; sequence++) {
      int length = random.nextInt(40);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < length; i++) {
        sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      // Sprinkle malformed surrogates like the upstream test does.
      if (sequence % 3 == 0) sb.append('\ud800');
      if (sequence % 5 == 0) sb.append('\udc00');
      if (sequence % 7 == 0) sb.insert(0, '\ud800');
      final String value = sb.toString();

      assertArrayEquals("sequence " + sequence,
          reverseBytesUpstream(w -> w.writeString(value)),
          reverseBytes(w -> w.writeString(value)));
    }
  }

  @Test public void varintAndTagParity() throws IOException {
    Random random = new Random(42);
    for (int sequence = 0; sequence < 200; sequence++) {
      final int i = random.nextInt();
      final long l = random.nextLong();
      final int field = 1 + random.nextInt((1 << 28) - 1);
      final FieldEncoding encoding = FieldEncoding.values()[random.nextInt(4)];
      final io.apitomy.antiwire.parity.wire.FieldEncoding upstreamEncoding =
          io.apitomy.antiwire.parity.wire.FieldEncoding.values()[encoding.ordinal()];

      assertArrayEquals("sequence " + sequence,
          reverseBytesUpstream(w -> {
            w.writeVarint32(i);
            w.writeVarint64(l);
            // Kotlin-internal upstream member with the mangled name; '$' is legal in Java.
            w.writeSignedVarint32$wire_runtime(i);
            w.writeFixed32(i);
            w.writeFixed64(l);
            w.writeTag(field, upstreamEncoding);
          }),
          reverseBytes(w -> {
            w.writeVarint32(i);
            w.writeVarint64(l);
            w.writeSignedVarint32(i);
            w.writeFixed32(i);
            w.writeFixed64(l);
            w.writeTag(field, encoding);
          }));
    }
  }

  @Test public void writeForwardParity() throws IOException {
    final String inner = "forward, not reverse";
    assertArrayEquals(
        reverseBytesUpstream(w -> w.writeForward$wire_runtime(
            fw -> {
              try {
                fw.writeString(inner);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
              return kotlin.Unit.INSTANCE;
            })),
        reverseBytes(w -> w.writeForward(fw -> fw.writeString(inner))));
  }

  @Test public void byteCountParity() throws IOException {
    ReverseProtoWriter writer = new ReverseProtoWriter();
    int before = writer.getByteCount();
    writer.writeString("measure me");
    int after = writer.getByteCount();
    assertEquals("measure me".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        after - before);
  }
}
