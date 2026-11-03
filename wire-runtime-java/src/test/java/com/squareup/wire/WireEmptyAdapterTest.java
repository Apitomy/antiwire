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
package com.squareup.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.IOException;
import org.junit.Test;

/**
 * The dynamic model's Empty adapter (docs/api-surface.md, TASK-26): {@code WIRE_EMPTY} is
 * the {@link ProtoAdapter.UnitValue}-valued twin of the deprecated Void-typed {@code EMPTY}.
 */
@SuppressWarnings("deprecation") // The Void twin is the comparison oracle by design.
public class WireEmptyAdapterTest {
  @Test public void decodeReturnsTheUnitValue() throws IOException {
    assertSame(ProtoAdapter.UnitValue.INSTANCE, ProtoAdapter.WIRE_EMPTY.decode(new byte[0]));
  }

  @Test public void decodeReadsUnexpectedFieldsAsUnknown() throws IOException {
    // google.protobuf.Empty declares no fields, so any payload is unknown; upstream's
    // commonEmpty reads every tag as an unknown field and still yields the unit.
    assertSame(ProtoAdapter.UnitValue.INSTANCE,
        ProtoAdapter.WIRE_EMPTY.decode(new byte[] { 8, 1 }));
  }

  @Test public void encodeWritesNothing() throws IOException {
    assertArrayEquals(new byte[0], ProtoAdapter.WIRE_EMPTY.encode(ProtoAdapter.UnitValue.INSTANCE));
    assertEquals(0, ProtoAdapter.WIRE_EMPTY.encodedSize(ProtoAdapter.UnitValue.INSTANCE));
  }

  @Test public void wireEmptyMatchesTheVoidTwinByteForByte() throws IOException {
    // The Void twin is the comparison oracle: same empty encoding, and where its decode
    // loses presence (null), the unit value keeps it.
    assertNull(ProtoAdapter.EMPTY.decode(ProtoAdapter.WIRE_EMPTY.encode(ProtoAdapter.UnitValue.INSTANCE)));
    assertArrayEquals(
        ProtoAdapter.EMPTY.encode(null),
        ProtoAdapter.WIRE_EMPTY.encode(ProtoAdapter.UnitValue.INSTANCE));
  }

  /** Field-framing parity against the relocated upstream 7.1.0 oracle. */
  @Test public void wireEmptyMatchesTheUpstreamUnitOracle() throws IOException {
    // A present Empty field is its tag and a zero length, on both sides (ProtoAdapterParityTest
    // style): upstream writes it for kotlin.Unit, the port for the UnitValue singleton.
    io.apitomy.antiwire.parity.okio.Buffer upstreamBuffer =
        new io.apitomy.antiwire.parity.okio.Buffer();
    io.apitomy.antiwire.parity.wire.ProtoAdapter.EMPTY.encodeWithTag(
        new io.apitomy.antiwire.parity.wire.ProtoWriter(upstreamBuffer), 3, kotlin.Unit.INSTANCE);
    byte[] upstreamBytes = upstreamBuffer.readByteArray();

    okio.Buffer ourBuffer = new okio.Buffer();
    ProtoAdapter.WIRE_EMPTY.encodeWithTag(new ProtoWriter(ourBuffer), 3,
        ProtoAdapter.UnitValue.INSTANCE);
    byte[] ourBytes = ourBuffer.readByteArray();

    assertArrayEquals(upstreamBytes, ourBytes);
    assertSame(ProtoAdapter.UnitValue.INSTANCE, ProtoAdapter.WIRE_EMPTY.decode(upstreamBytes));
  }
}
