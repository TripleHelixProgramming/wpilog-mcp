/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogScan;
import org.triplehelix.wpilogmcp.log.RecordOffsets;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;

/** An independent fixed-layout writer and literal index facts exercise optional cache input. */
class ScanIndexSerializerTest {
  private static final String HASH = "a".repeat(64);
  private static final String IDENTITY = "attributes:" + "b".repeat(64);
  @TempDir Path directory;
  private Api api;

  @BeforeEach void serializerApi() throws Exception {
    // The feature did not exist when these tests were written. Its absence is setup evidence,
    // not a corrupt-cache regression catch; behavior-changing plants prove the checks below.
    api = new Api();
  }

  @Test void independentWireRestoresLiteralDeclarationsOffsetsAndOrdering() throws Exception {
    var path = write("independent.idx", independent());
    var result = api.read(path, 128, HASH);
    assertNotNull(result);
    assertEquals(List.of("/Value", "/Empty"), new ArrayList<>(result.entries().keySet()));
    assertEquals(new EntryInfo(7, "/Value", "double", "changed"), result.entries().get("/Value"));
    assertEquals(3, result.dataRecords());
    assertEquals(0.000001, result.minTimestamp());
    assertEquals(0.000003, result.maxTimestamp());
    assertFalse(result.truncated());
    assertFalse(result.damaged());
    assertNull(result.truncationMessage());
    assertNull(result.continuation(), "A persisted index must not pretend it retained append state");
    assertEquals(128, result.resumePoint());
    assertEquals(12, result.scannedFrom());
    var offsets = result.offsets().get("/Value");
    assertEquals(3, offsets.size());
    assertEquals(List.of(24L,40L,56L), offsets(offsets));
    assertFalse(offsets.monotonic(), "The duplicate/backward sequence3,1,1 is not monotonic");
    assertEquals(new RecordOffsets.OrderState(1,1,true), offsets.orderState());
    assertEquals(12, offsets.storageBytes());
    assertTrue(result.offsets().get("/Empty").isEmpty());
    assertEquals(0, result.offsets().get("/Empty").storageBytes());
    assertNotNull(api.inspect(path));
  }

  @Test void writingUsesTheDocumentedWireAndDoesNotMutateTheScan() throws Exception {
    var scan = sample();
    var path = directory.resolve("written.idx");
    assertTrue(api.write(path, 128, HASH, scan));
    assertArrayEquals(independent(), Files.readAllBytes(path));
    assertEquals(List.of(24L,40L,56L), offsets(scan.offsets().get("/Value")));
    assertFalse(scan.offsets().get("/Value").monotonic());
    var before = Files.readAllBytes(path);
    assertThrows(java.io.IOException.class, () -> api.write(path, 128, HASH, scan));
    assertArrayEquals(before, Files.readAllBytes(path), "CREATE_NEW must not overwrite an owned temp");
  }

  @Test void realScanKeepsEmptyDeclarationsAndSameTypeRedeclarations() throws Exception {
    var source = directory.resolve("declarations.wpilog");
    try (var writer = new WpilogWriter(source, "")) {
      writer.start("/Empty", "string", "", 0);
      int first = writer.start("/Value", "double", "old", 0);
      writer.append(first, 2, WpilogWriter.encodeDouble(20));
      writer.finish(first, 3);
      int second = writer.start("/Value", "double", "ignored", 3);
      writer.setMetadata(second, "new", 3);
      writer.append(second, 1, WpilogWriter.encodeDouble(10));
    }
    // Header12 + starts33/36 + data12 + finish9 + restart40 + metadata16 + data12.
    assertEquals(170, Files.size(source));
    try (var open = new ScopedLogReader(source)) {
      var scan = LogScan.of(open.reader(), source);
      assertEquals(scan.entries().keySet(), scan.offsets().keySet());
      var path = directory.resolve("declarations.idx");
      assertTrue(api.write(path, 170, HASH, scan));
      var restored = api.read(path, 170, HASH);
      assertNotNull(restored);
      assertEquals(List.of("/Empty", "/Value"), List.copyOf(restored.entries().keySet()));
      assertEquals(new EntryInfo(1, "/Empty", "string", ""), restored.entries().get("/Empty"));
      assertEquals(new EntryInfo(2, "/Value", "double", "new"), restored.entries().get("/Value"));
      assertTrue(restored.offsets().get("/Empty").isEmpty());
      var values = restored.offsets().get("/Value");
      assertEquals(List.of(81L, 158L), offsets(values));
      assertFalse(values.monotonic());
      assertEquals(new RecordOffsets.OrderState(1, 1, true), values.orderState());
      assertEquals(2, restored.dataRecords());
      assertEquals(0.000001, restored.minTimestamp());
      assertEquals(0.000002, restored.maxTimestamp());
      assertEquals(2, open.reader().recordEntry(values.get(0)));
      assertEquals(3, open.reader().recordEntry(values.get(1)));
      assertEquals(20.0, open.reader().getRecord(values.get(0)).getDouble());
      assertEquals(10.0, open.reader().getRecord(values.get(1)).getDouble());
    }
  }

  @Test void wideOffsetsRemainLongAndDuplicateTimestampsRemainMonotonic() throws Exception {
    var offsets = new RecordOffsets();
    offsets.add(3_000_000_000L, -2);
    offsets.add(3_000_000_016L, -2);
    var entries = Map.of("/Wide", new EntryInfo(1,"/Wide","int64",""));
    var scan = new LogScan(entries, Map.of("/Wide",offsets), -0.000002,-0.000002,2,
        false,false,null,3_000_000_100L,12,null);
    var path = directory.resolve("wide.idx");
    assertTrue(api.write(path, 3_000_000_100L, HASH, scan));
    var restored = api.read(path, 3_000_000_100L, HASH);
    assertNotNull(restored);
    assertEquals(List.of(3_000_000_000L,3_000_000_016L), offsets(restored.offsets().get("/Wide")));
    assertEquals(16, restored.offsets().get("/Wide").storageBytes());
    assertTrue(restored.offsets().get("/Wide").monotonic());
  }

  @Test void sourceIdentitySizeAndCurrentHashMustAllMatch() throws Exception {
    var path = write("proof.idx", independent());
    assertNull(api.read(path, 127, HASH));
    assertNull(api.read(path, 128, "c".repeat(64)));
    assertNull(api.read(path, "attributes:" + "d".repeat(64), 128, HASH));
  }

  @Test void truncatedDamageFactsSurviveWithoutInventingResumeState() throws Exception {
    var original = sample();
    var damaged = new LogScan(original.entries(),original.offsets(),0.000001,0.000003,3,
        true,true,"Synthetic damage at byte100",100,12,null);
    var path=directory.resolve("damage.idx");
    assertTrue(api.write(path,128,HASH,damaged));
    var result=api.read(path,128,HASH);
    assertNotNull(result);
    assertTrue(result.truncated());
    assertTrue(result.damaged());
    assertEquals("Synthetic damage at byte100",result.truncationMessage());
    assertEquals(100,result.resumePoint());
    assertNull(result.continuation());
  }

  @Test void narrowBoundaryAndExactCapacityDoNotWidenOrCopy() throws Exception {
    var positions=new RecordOffsets();positions.add(Integer.MAX_VALUE,1);positions.compact();
    var field=RecordOffsets.class.getDeclaredField("narrow");field.setAccessible(true);
    var exact=field.get(positions);positions.compact();
    assertSame(exact,field.get(positions),"An already compact persisted array needs no second copy");
    var scan=new LogScan(Map.of("/Narrow",new EntryInfo(1,"/Narrow","double","")),
        Map.of("/Narrow",positions),0.000001,0.000001,1,false,false,null,3_000_000_000L,12,null);
    var path=directory.resolve("narrow.idx");
    assertTrue(api.write(path,3_000_000_000L,HASH,scan));
    var result=api.read(path,3_000_000_000L,HASH);
    assertNotNull(result);
    var restored=result.offsets().get("/Narrow");
    assertEquals(Integer.MAX_VALUE,restored.get(0));
    assertFalse(restored.wide());
    assertEquals(4,restored.storageBytes());
  }

  @Test void corruptionTruncationAndTrailingBytesNeverPublishAnIndex() throws Exception {
    byte[] valid = independent();
    byte[] changed = valid.clone(); changed[changed.length - 10] ^= 1;
    assertRejected("checksum", changed);
    for (int length : new int[]{0,7,16,valid.length/2,valid.length-1})
      assertRejected("truncated-" + length, Arrays.copyOf(valid,length));
    byte[] trailing = Arrays.copyOf(valid,valid.length+1);
    assertRejected("trailing", trailing);
    byte[] extraPayload = Arrays.copyOf(valid,valid.length+1);
    checksum(extraPayload);
    assertRejected("valid-crc-trailing", extraPayload);
  }

  @Test void validChecksumsDoNotExcuseCraftedSizesFlagsOrOffsets() throws Exception {
    byte[] valid = independent();
    var layout = layout(valid);
    Map<String,Consumer<ByteBuffer>> cases = new LinkedHashMap<>();
    cases.put("format", b -> b.putInt(8,2));
    cases.put("recipe", b -> b.putInt(12,2));
    cases.put("identity-length", b -> b.putInt(16,Integer.MAX_VALUE));
    cases.put("nonfinite-min", b -> b.putDouble(layout.get("min"),Double.NaN));
    cases.put("reversed-range", b -> b.putDouble(layout.get("max"),-1));
    cases.put("count-mismatch", b -> b.putInt(layout.get("records"),4));
    cases.put("negative-count", b -> b.putInt(layout.get("records"),-1));
    cases.put("flags", b -> b.put(layout.get("flags"),(byte)4));
    cases.put("message-length", b -> b.putInt(layout.get("message"),-2));
    cases.put("resume", b -> b.putLong(layout.get("resume"),129));
    cases.put("scanned", b -> b.putLong(layout.get("scanned"),129));
    cases.put("entries", b -> b.putInt(layout.get("entries"),200_001));
    cases.put("name-length", b -> b.putInt(layout.get("name"),1_048_577));
    cases.put("bad-utf8", b -> b.put(layout.get("name")+4,(byte)0xc0));
    cases.put("offset-count", b -> b.putInt(layout.get("offset-count"),Integer.MAX_VALUE));
    cases.put("wide-tag", b -> b.put(layout.get("wide"),(byte)2));
    cases.put("order-zero", b -> b.putInt(layout.get("order"),0));
    cases.put("order-past-end", b -> b.putInt(layout.get("order"),3));
    cases.put("known-tag", b -> b.put(layout.get("known"),(byte)2));
    cases.put("negative-offset", b -> b.putInt(layout.get("offsets"),-1));
    cases.put("offset-outside-source", b -> b.putInt(layout.get("offsets"),128));
    cases.put("offset-decreases", b -> b.putInt(layout.get("offsets")+4,23));
    cases.put("duplicate-name", b -> {
      int at=layout.get("second-name")+4;
      for (byte v : "/Value".getBytes(StandardCharsets.UTF_8)) b.put(at++,v);
    });
    for (var item : cases.entrySet()) {
      byte[] crafted = valid.clone(); item.getValue().accept(ByteBuffer.wrap(crafted)); checksum(crafted);
      assertRejected(item.getKey(),crafted);
    }
  }

  @Test void encodedSizeRefusesSparseOversizedFilesWithoutReadingThemWhole() throws Exception {
    var path = directory.resolve("oversized.idx");
    try (var file = new java.io.RandomAccessFile(path.toFile(),"rw")) {
      file.setLength((64L << 20)+1);
    }
    assertNull(api.read(path,128,HASH));
    assertNull(api.inspect(path));
    var noContent = new ScanIndexSerializer() {
      @Override java.io.InputStream openInput(Path ignored) {
        throw new AssertionError("Oversized indexes must refuse before reading content");
      }
    };
    assertNull(noContent.read(path, new ScanIndexSerializer.Source(IDENTITY,128,HASH)));
    assertNull(noContent.inspect(path));
  }

  @Test void decodedBudgetCountsSharedStringOccurrencesBeforeWriting() throws Exception {
    var entries = new LinkedHashMap<String,EntryInfo>();
    var positions = new LinkedHashMap<String,RecordOffsets>();
    String metadata = "x".repeat(1000);
    for (int i=0;i<30_000;i++) {
      String name="/Entry"+i;
      entries.put(name,new EntryInfo(i+1,name,"double",metadata));
      positions.put(name,new RecordOffsets());
    }
    var scan = new LogScan(entries,positions,0,0,0,false,false,null,128,12,null);
    var path=directory.resolve("decoded-over-budget.idx");
    assertFalse(api.write(path,128,HASH,scan),
        "30000 declarations each charge512B plus repeated1000B UTF-8 metadata as2000B strings (>64MiB)");
    assertFalse(Files.exists(path),"Preflight must decline before creating the temporary file");
  }

  @Test void decodedBudgetRefusesIndependentlyEncodedInputAndCleanupInspection() throws Exception {
    var path = directory.resolve("crafted-decoded-budget.idx");
    var crc = new CRC32();
    try (var raw = new java.io.BufferedOutputStream(Files.newOutputStream(path))) {
      var out = new DataOutputStream(new java.util.zip.CheckedOutputStream(raw, crc));
      out.write(new byte[]{'W','P','I','S','C','A','N',0}); out.writeInt(1); out.writeInt(1);
      string(out, IDENTITY); out.writeLong(128); out.write(HexFormat.of().parseHex(HASH));
      out.writeDouble(0); out.writeDouble(0); out.writeInt(0); out.writeByte(0);
      out.writeInt(-1); out.writeLong(128); out.writeLong(12); out.writeInt(30_000);
      String metadata = "x".repeat(1000);
      for (int i = 0; i < 30_000; i++) {
        out.writeInt(i + 1); string(out, "/Entry" + i); string(out, "double"); string(out, metadata);
        out.writeInt(0); out.writeByte(0); out.writeInt(Integer.MAX_VALUE);
        out.writeLong(0); out.writeByte(0);
      }
      out.flush();
      new DataOutputStream(raw).writeInt((int) crc.getValue());
    }
    // 184B framing + 30000*(1040B + name length); name lengths sum to318890.
    assertEquals(31_519_074, Files.size(path));
    // 694B base/identity + 30000*2620B +2*318890B names =79,238,474B accounted.
    assertNull(api.read(path, 128, HASH), "Decoded accounting must reject a valid31MiB encoding");
    assertNull(api.inspect(path), "Cleanup must enforce the same decoded representation budget");
  }

  @Test void inspectHasTheSameValidityRulesWithoutReturningAnIndex() throws Exception {
    var path=write("inspected.idx",independent());
    var header=api.inspect(path);
    assertNotNull(header);
    assertEquals(1,header.getClass().getMethod("formatVersion").invoke(header));
    assertEquals(1,header.getClass().getMethod("recipeVersion").invoke(header));
    assertEquals(Files.size(path),header.getClass().getMethod("encodedBytes").invoke(header));
  }

  private void assertRejected(String name,byte[] bytes) throws Exception {
    var path=write(name+".idx",bytes);
    assertNull(api.read(path,128,HASH),name);
    assertNull(api.inspect(path),name+" cleanup inspection");
  }
  private Path write(String name,byte[] bytes) throws Exception {
    var path=directory.resolve(name);Files.write(path,bytes);return path;
  }
  private static List<Long> offsets(RecordOffsets values) {
    var out=new ArrayList<Long>();for(int i=0;i<values.size();i++)out.add(values.get(i));return out;
  }
  private static LogScan sample() {
    var entries=new LinkedHashMap<String,EntryInfo>();
    entries.put("/Value",new EntryInfo(7,"/Value","double","changed"));
    entries.put("/Empty",new EntryInfo(8,"/Empty","string",""));
    var values=new RecordOffsets();values.add(24,3);values.add(40,1);values.add(56,1);
    return new LogScan(entries,Map.of("/Value",values,"/Empty",new RecordOffsets()),
        0.000001,0.000003,3,false,false,null,128,12,null);
  }
  private static byte[] independent() throws Exception {
    var bytes=new ByteArrayOutputStream();
    try(var out=new DataOutputStream(bytes)) {
      out.write(new byte[]{'W','P','I','S','C','A','N',0});out.writeInt(1);out.writeInt(1);
      string(out,IDENTITY);out.writeLong(128);out.write(HexFormat.of().parseHex(HASH));
      out.writeDouble(0.000001);out.writeDouble(0.000003);out.writeInt(3);out.writeByte(0);
      out.writeInt(-1);out.writeLong(128);out.writeLong(12);out.writeInt(2);
      out.writeInt(7);string(out,"/Value");string(out,"double");string(out,"changed");
      out.writeInt(3);out.writeByte(0);out.writeInt(1);out.writeLong(1);out.writeByte(1);
      out.writeInt(24);out.writeInt(40);out.writeInt(56);
      out.writeInt(8);string(out,"/Empty");string(out,"string");string(out,"");
      out.writeInt(0);out.writeByte(0);out.writeInt(Integer.MAX_VALUE);out.writeLong(0);out.writeByte(0);
      out.writeInt(0);
    }
    byte[] result=bytes.toByteArray();checksum(result);return result;
  }
  private static void string(DataOutputStream out,String value) throws Exception {
    byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
  }
  private static void checksum(byte[] bytes) {
    var crc=new CRC32();crc.update(bytes,0,bytes.length-4);
    ByteBuffer.wrap(bytes).putInt(bytes.length-4,(int)crc.getValue());
  }
  private static Map<String,Integer> layout(byte[] bytes) {
    var b=ByteBuffer.wrap(bytes);var at=new LinkedHashMap<String,Integer>();
    b.position(16);skipString(b);b.position(b.position()+8+32);
    at.put("min",b.position());b.getDouble();at.put("max",b.position());b.getDouble();
    at.put("records",b.position());b.getInt();at.put("flags",b.position());b.get();
    at.put("message",b.position());b.getInt();at.put("resume",b.position());b.getLong();
    at.put("scanned",b.position());b.getLong();at.put("entries",b.position());b.getInt();
    b.getInt();at.put("name",b.position());skipString(b);skipString(b);skipString(b);
    at.put("offset-count",b.position());b.getInt();at.put("wide",b.position());b.get();
    at.put("order",b.position());b.getInt();b.getLong();at.put("known",b.position());b.get();
    at.put("offsets",b.position());b.position(b.position()+12);b.getInt();at.put("second-name",b.position());
    return at;
  }
  private static void skipString(ByteBuffer b) {int size=b.getInt();b.position(b.position()+size);}

  /** Reflection keeps old-source test compilation valid; missing API is never a behavioral catch. */
  private static final class Api {
    final Class<?> type;
    final Class<?> sourceType;
    final Object instance;
    Api() throws Exception {
      type=Class.forName("org.triplehelix.wpilogmcp.cache.ScanIndexSerializer");
      sourceType=Class.forName(type.getName()+"$Source");
      instance=type.getConstructor().newInstance();
    }
    Object source(String identity,long size,String hash) throws Exception {
      return sourceType.getConstructor(String.class,long.class,String.class).newInstance(identity,size,hash);
    }
    LogScan read(Path path,long size,String hash) throws Exception {return read(path,IDENTITY,size,hash);}
    LogScan read(Path path,String identity,long size,String hash) throws Exception {
      return (LogScan)invoke("read",new Class<?>[]{Path.class,sourceType},path,source(identity,size,hash));
    }
    Object inspect(Path path) throws Exception {return invoke("inspect",new Class<?>[]{Path.class},path);}
    boolean write(Path path,long size,String hash,LogScan scan) throws Exception {
      return (Boolean)invoke("write",new Class<?>[]{Path.class,sourceType,LogScan.class},path,source(IDENTITY,size,hash),scan);
    }
    private Object invoke(String name,Class<?>[] parameters,Object... arguments) throws Exception {
      try {return type.getMethod(name,parameters).invoke(instance,arguments);}
      catch(InvocationTargetException failure) {
        if(failure.getCause() instanceof Exception exception)throw exception;
        if(failure.getCause() instanceof Error error)throw error;
        throw failure;
      }
    }
  }
}
