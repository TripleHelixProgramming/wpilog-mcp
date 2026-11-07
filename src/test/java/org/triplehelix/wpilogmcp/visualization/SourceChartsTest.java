/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.*;

/** Expected bins and pairs are enumerated from generated records, independently of server code. */
class SourceChartsTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance(); Set<Path> original;
  VisualizationWorkspace workspace; RecordingFixture fixture;
  @BeforeEach void start() throws Exception {
    temp = temp.toRealPath(); original = logs.getConfiguredDirectories(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    fixture = new RecordingFixture(temp.resolve("store"), 4); workspace = new VisualizationWorkspace(ServerIdentity.ephemeral(), logs);
  }
  @AfterEach void stop() throws Exception {
    workspace.close(); logs.addAllowedDirectory(temp); assertTrue(logs.release(temp).released()); logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
  }
  JsonObject request() { var r = new JsonObject(); r.add("source_ref", fixture.reference); r.addProperty("entry", "/n"); return r; }
  JsonObject pair() { var r = request(); r.remove("entry"); r.addProperty("x_entry", "/n"); r.addProperty("y_entry", "/y"); return r; }
  SourceCharts owner() { return new SourceCharts(workspace.sources); }
  void numbers() throws Exception {
    fixture.rewrite(0, w -> { int x=w.start("/n","double","",0),y=w.start("/y","double","",0);
      w.append(x,2_000_000,WpilogWriter.encodeDouble(4)); w.append(x,1_000_000,WpilogWriter.encodeDouble(2)); w.append(x,2_000_000,WpilogWriter.encodeDouble(6));
      w.append(y,2_000_000,WpilogWriter.encodeDouble(40));w.append(y,1_000_000,WpilogWriter.encodeDouble(20)); });
    fixture.rewrite(1, w -> { int x=w.start("/n","double","",0),y=w.start("/y","double","",0);
      w.append(x,3_000_000,WpilogWriter.encodeDouble(10));w.append(y,2_000_000,WpilogWriter.encodeDouble(60));w.append(y,4_000_000,WpilogWriter.encodeDouble(90)); });
  }
  @Test void histogramAndPairsKeepAllMembersDuplicatesAndBackwardTimes() throws Exception {
    numbers(); var histogram=owner().read(request(),false,()->true);
    assertEquals(JsonParser.parseString("[{\"low\":2,\"high\":6,\"count\":2},{\"low\":6,\"high\":10,\"count\":2}]"),histogram.get("bins"));
    var pair=owner().read(pair(),true,()->true);
    assertEquals(JsonParser.parseString("[[2,20,1],[4,40,2],[6,60,2]]"),pair.get("points"));
    assertEquals(3,pair.get("matched").getAsInt());assertEquals(1,pair.get("unpaired_x").getAsInt());assertEquals(1,pair.get("unpaired_y").getAsInt());
    assertEquals(8,pair.get("records_visited").getAsInt());assertEquals(5.5,pair.getAsJsonArray("summary").get(0).getAsJsonObject().get("mean").getAsDouble());
    var same=pair();same.addProperty("y_entry","/n");var result=owner().read(same,true,()->true);assertEquals(4,result.get("matched").getAsInt());assertEquals(4,result.get("records_visited").getAsInt(),"One entry serving both axes is read once");
  }
  @Test void inclusiveBoundsAndRelativeClockNeverCountOtherRecords() throws Exception {
    numbers();var r=request();r.addProperty("start_time",2);r.addProperty("end_time",2);
    assertEquals(JsonParser.parseString("[{\"low\":4,\"high\":5,\"count\":1},{\"low\":5,\"high\":6,\"count\":1}]"),owner().read(r,false,()->true).get("bins"));
    r=request();r.addProperty("last_seconds",1);var result=owner().read(r,false,()->true);
    assertEquals(JsonParser.parseString("[{\"low\":9.5,\"high\":10.5,\"count\":1}]"),result.get("bins"));
    assertEquals(1,result.getAsJsonObject("inputs").get("last_seconds").getAsInt());assertFalse(r.has("start_time"));
  }
  @Test void constantEmptyNonfiniteAndDecodeProblemsHaveExactCounts() throws Exception {
    fixture.rewrite(0,w->{int n=w.start("/n","double","",0);w.append(n,1,WpilogWriter.encodeDouble(3));w.append(n,2,WpilogWriter.encodeDouble(3));w.append(n,3,WpilogWriter.encodeDouble(Double.NaN));w.append(n,4,new byte[]{1});});
    var result=owner().read(request(),false,()->true);
    assertEquals(JsonParser.parseString("[{\"low\":2.5,\"high\":3.5,\"count\":2}]"),result.get("bins"));assertEquals("partial",result.get("status").getAsString());assertTrue(result.has("skipped"));
    assertEquals(1,result.get("problem_count").getAsInt());assertEquals("capture.wpilog",result.getAsJsonArray("problems").get(0).getAsJsonObject().get("member_id").getAsString());
    assertEquals(1,result.getAsJsonObject("summary").get("non_finite_count").getAsInt());
    var r=request();r.addProperty("start_time",10);r.addProperty("end_time",20);var empty=owner().read(r,false,()->true);
    assertEquals("no_match",empty.get("status").getAsString());assertEquals(0,empty.get("problem_count").getAsInt());assertEquals(4,empty.get("records_visited").getAsInt());
  }
  @Test void anglesUseTheImageChartsProjectionAndUnwrapping() throws Exception {
    fixture.rewrite(0,w->{int n=w.start("/n","struct:Rotation2d","",0);w.append(n,1_000_000,WpilogWriter.encodeDouble(Math.toRadians(179)));w.append(n,2_000_000,WpilogWriter.encodeDouble(Math.toRadians(-179)));});
    var r=request();r.addProperty("field","_derived.degrees");var bins=owner().read(r,false,()->true).getAsJsonArray("bins");
    assertEquals(179,bins.get(0).getAsJsonObject().get("low").getAsDouble(),1e-9);assertEquals(180,bins.get(0).getAsJsonObject().get("high").getAsDouble(),1e-9);
    assertEquals(181,bins.get(1).getAsJsonObject().get("high").getAsDouble(),1e-9);
  }
  @Test void outOfRangeScatterPageIsNoMatchButKeepsTrueTotals() throws Exception {
    numbers();var r=pair();r.addProperty("offset",Integer.MAX_VALUE);var result=owner().read(r,true,()->true);
    assertEquals("no_match",result.get("status").getAsString());assertTrue(result.has("looked_for"));assertTrue(result.has("hint"));assertEquals(3,result.get("matched").getAsInt());assertEquals(3,result.get("next_offset").getAsInt());assertTrue(result.get("complete").getAsBoolean());assertEquals(3,result.getAsJsonObject("limits").getAsJsonObject("points").get("total").getAsInt());
  }
  @ParameterizedTest @ValueSource(strings={"records","samples","retained","record","result"})
  void eachResourceBoundRefusesAndReleasesThePrefix(String bound) throws Exception {
    numbers();var d=RecordingAnalysis.Limits.defaults();var limits=new RecordingAnalysis.Limits(bound.equals("records")?3:d.records(),bound.equals("samples")?3:d.samples(),bound.equals("retained")?767:d.retainedBytes(),bound.equals("record")?4:d.recordBytes(),bound.equals("result")?128:d.resultBytes(),d.nanos());
    var charts=new SourceCharts(new RecordingBindings(workspace.sources::admitChart),limits,System::nanoTime);
    var refusal=assertThrows(ReadException.class,()->charts.read(request(),false,()->true));assertEquals(ReadProblem.Code.RESOURCE_LIMIT,refusal.code());assertFalse(refusal.retryable());assertTrue(logs.release(temp).released());
  }
  @Test void equalLimitsAndDuplicateAxesDoNotDoubleChargeHistory() throws Exception {
    numbers();var d=RecordingAnalysis.Limits.defaults();var charts=new SourceCharts(new RecordingBindings(workspace.sources::admitChart),new RecordingAnalysis.Limits(4,4,768,d.recordBytes(),d.resultBytes(),d.nanos()),System::nanoTime);
    var r=pair();r.addProperty("y_entry","/n");assertEquals(4,charts.read(r,true,()->true).get("matched").getAsInt());
  }
  @Test void twoChartSlotsRefuseWithoutAQueueAndRecoverAfterFailure() throws Exception {
    numbers();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var charts=new SourceCharts(new RecordingBindings(source->{var a=workspace.sources.admitChart(source);entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Fixture timeout");return a;}catch(InterruptedException e){a.close();throw new InterruptedIOException();}}),RecordingAnalysis.Limits.defaults(),System::nanoTime);
    var pool=Executors.newFixedThreadPool(2);
    try {var a=pool.submit(()->charts.read(request(),false,()->true));var b=pool.submit(()->charts.read(pair(),true,()->true));assertTrue(entered.await(3,TimeUnit.SECONDS));
      var refusal=assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->assertThrows(ReadException.class,()->charts.read(request(),false,()->true)));assertTrue(refusal.retryable());release.countDown();assertTrue(a.get(3,TimeUnit.SECONDS).has("bins"));assertTrue(b.get(3,TimeUnit.SECONDS).has("points"));assertTrue(charts.read(request(),false,()->true).has("bins"));
    }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
  }
  @Test void cancelledExpiredAndDeadlineReadsNeverPublishOrLeakSlots() throws Exception {
    numbers();var opens=new AtomicInteger();var clock=new AtomicLong();var charts=new SourceCharts(new RecordingBindings(source->{opens.incrementAndGet();var a=workspace.sources.admitChart(source);clock.addAndGet(11_000_000_000L);return a;}),RecordingAnalysis.Limits.defaults(),clock::get);
    assertThrows(AccessDeniedException.class,()->charts.read(request(),false,()->false));assertEquals(0,opens.get());
    try{Thread.currentThread().interrupt();assertThrows(InterruptedIOException.class,()->charts.read(request(),false,()->true));}finally{Thread.interrupted();}assertEquals(0,opens.get());
    for(int i=0;i<3;i++){var refusal=assertThrows(ReadException.class,()->charts.read(request(),false,()->true));assertFalse(refusal.retryable());assertTrue(refusal.getMessage().contains("work budget"));}
    assertEquals(3,opens.get());
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void grantAndBoundaryChangesBeforePublicationDiscardResults(boolean boundary) throws Exception {
    numbers();var checks=new AtomicInteger();var charts=new SourceCharts(new RecordingBindings(source->{var a=workspace.sources.admitChart(source);return new RecordedSources.Admission(a.prefix(),()->{if(checks.incrementAndGet()==2){if(boundary)Files.setLastModifiedTime(fixture.directory.resolve("capture.wpilog"),java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+30_000));else throw new AccessDeniedException("revoked");}a.access().check();});}),RecordingAnalysis.Limits.defaults(),System::nanoTime);
    var failure=assertThrows(IOException.class,()->charts.read(pair(),true,()->true));if(boundary)assertEquals(ReadProblem.Code.FILE_CHANGED,((ReadException)failure).code());else assertInstanceOf(AccessDeniedException.class,failure);assertEquals(2,checks.get());
  }
  @Test void conflictingSecondAxisCannotBeSilentlyPreferredOrSkipped() throws Exception {
    numbers();fixture.rewrite(1,w->{int y=w.start("/y","int64","",0);w.append(y,1,WpilogWriter.encodeInt64(2));});
    assertTrue(assertThrows(IOException.class,()->owner().read(pair(),true,()->true)).getMessage().contains("SOURCE_UNAVAILABLE"));
  }
  @Test void invalidArgumentsRefuseInsteadOfCoercingOrGuessing() throws Exception {
    numbers();
    for(var value:List.of("null","true","\"1\"","1e300")){var r=request();r.add("start_time",JsonParser.parseString(value));assertThrows(IllegalArgumentException.class,()->owner().read(r,false,()->true));}
    for(var path:List.of(" ","[*]","[no]")){var r=request();r.addProperty("field",path);assertThrows(IllegalArgumentException.class,()->owner().read(r,false,()->true));}
    var reverse=request();reverse.addProperty("start_time",2);reverse.addProperty("end_time",1);assertThrows(IllegalArgumentException.class,()->owner().read(reverse,false,()->true));
    for(var seconds:List.of(0,-1)){var r=request();r.addProperty("last_seconds",seconds);assertThrows(IllegalArgumentException.class,()->owner().read(r,false,()->true));}
    var mixed=request();mixed.addProperty("last_seconds",2);mixed.addProperty("start_time",1);assertThrows(IllegalArgumentException.class,()->owner().read(mixed,false,()->true));
  }
  @ParameterizedTest @ValueSource(longs={9007199253999999L,Long.MAX_VALUE,Long.MIN_VALUE})
  void unrepresentableTimestampPrecisionRefusesRatherThanPairingDifferentNativeTimes(long time) throws Exception {
    fixture.rewrite(0,w->{int n=w.start("/n","double","",0);w.append(n,time,WpilogWriter.encodeDouble(1));});
    var failure=assertThrows(IllegalArgumentException.class,()->owner().read(request(),false,()->true));assertTrue(failure.getMessage().contains("microseconds"));
  }
  @Test void unrepresentableHistogramEdgesRefuseRatherThanInventCounts() throws Exception {
    fixture.rewrite(0,w->{int n=w.start("/n","double","",0);w.append(n,1,WpilogWriter.encodeDouble(Double.MAX_VALUE));});
    assertTrue(assertThrows(IllegalArgumentException.class,()->owner().read(request(),false,()->true)).getMessage().contains("bin edges"));
  }
  @Test void liveWindowsAreBoundedByRequestedDurationAndReadOneFixedPrefix() throws Exception {
    var path = fixture.directory.resolve("capture.wpilog"); Files.delete(path);
    var topic = new org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce("/x", 1, "int", null, new JsonObject());
    var clock = new AtomicReference<Double>(1_200_000.0);
    var index = new org.triplehelix.wpilogmcp.capture.CaptureIndex((address,start)->path,logs,10_000_000,clock::get);
    try(var writer=new org.triplehelix.wpilogmcp.capture.CaptureWriter(java.time.Clock.fixed(java.time.Instant.parse(RecordingFixture.WHEN),java.time.ZoneOffset.UTC),
        new org.triplehelix.wpilogmcp.nt4.client.ManualScheduler(),org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL,index)) {
      writer.connected(org.triplehelix.wpilogmcp.nt4.client.RobotAddress.uri("127.0.0.1",5810,"chart-fixture"),"networktables.first.wpi.edu");writer.timeSync(1_000_000,0);writer.announce(topic);
      writer.value(topic,new org.triplehelix.wpilogmcp.nt4.ValueFrame(1,1_000_001,2,41L),0);
      var manifest=fixture.directory.resolve("session.json");var state=JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      var captured=state.getAsJsonArray("files").remove(0).getAsJsonObject();
      var provenance=org.triplehelix.wpilogmcp.store.StoreJson.JSON.fromJson(captured.get("provenance"),org.triplehelix.wpilogmcp.store.StoreManifest.Provenance.class);
      state.add("open_capture",org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJsonTree(new org.triplehelix.wpilogmcp.store.StoreManifest.OpenCapture("capture.wpilog",provenance,Files.size(path),1,1)));
      Files.writeString(manifest,state.toString());logs.catalogPublished();
      var r=request();r.addProperty("entry","NT:/x");
      assertThrows(IllegalArgumentException.class,()->owner().read(r,false,()->true));
      for(double seconds:List.of(.5,301.0)) {var bad=r.deepCopy();bad.addProperty("last_seconds",seconds);assertThrows(IllegalArgumentException.class,()->owner().read(bad,false,()->true));}
      r.addProperty("last_seconds",30);
      var opens=new AtomicInteger();var charts=new SourceCharts(new RecordingBindings(source->{var a=workspace.sources.admitChart(source);opens.incrementAndGet();writer.value(topic,new org.triplehelix.wpilogmcp.nt4.ValueFrame(1,1_100_000,2,99L),0);return a;}),RecordingAnalysis.Limits.defaults(),System::nanoTime);
      var first=charts.read(r,false,()->true);assertEquals(1,opens.get());assertEquals(1,first.getAsJsonObject("summary").get("count").getAsInt());assertEquals(41,first.getAsJsonObject("summary").get("mean").getAsDouble());assertFalse(first.getAsJsonObject("prefix").get("finalized").getAsBoolean());
      var next=owner().read(r,false,()->true);assertEquals(2,next.getAsJsonObject("summary").get("count").getAsInt());assertEquals(70,next.getAsJsonObject("summary").get("mean").getAsDouble());
      clock.set(5_000_000.0);r.addProperty("last_seconds",1);assertEquals("no_match",owner().read(r,false,()->true).get("status").getAsString());
    }
  }
  @Test void histogramsCapBinsAndKeepEveryBoundarySample() throws Exception {
    fixture.rewrite(0,w->{int n=w.start("/n","double","",0);for(int i=0;i<4097;i++)w.append(n,i,WpilogWriter.encodeDouble(i));});
    var bins=owner().read(request(),false,()->true).getAsJsonArray("bins");assertEquals(64,bins.size());
    for(int i=0;i<64;i++){var bin=bins.get(i).getAsJsonObject();assertEquals(i*64,bin.get("low").getAsDouble());assertEquals((i+1)*64,bin.get("high").getAsDouble());assertEquals(i==63?65:64,bin.get("count").getAsInt());}
  }
  @Test void problemDetailsAreBoundedWithTrueTotalAndBothAxisNames() throws Exception {
    fixture.rewrite(0,w->{int n=w.start("/n","double","",0),y=w.start("/y","double","",0);for(int i=0;i<105;i++)w.append(n,i,new byte[]{1});w.append(n,106,WpilogWriter.encodeDouble(2));w.append(y,106,WpilogWriter.encodeDouble(3));});
    var result=owner().read(pair(),true,()->true);assertEquals(105,result.get("problem_count").getAsInt());assertEquals(100,result.getAsJsonArray("problems").size());assertTrue(result.get("problems_truncated").getAsBoolean());assertEquals(1,result.get("matched").getAsInt());assertEquals("/n",result.getAsJsonArray("problems").get(0).getAsJsonObject().get("entry").getAsString());
  }

  @Test void singleFileAdmissionRechecksGrantsBeforePublication() throws Exception {
    var file=temp.resolve("single.wpilog");try(var w=new WpilogWriter(file,"")){int id=w.start("/n","double","",0);w.append(id,1,WpilogWriter.encodeDouble(2));}
    var ref=workspace.sources.identify(org.triplehelix.wpilogmcp.mcp.CallerContext.Authority.STDIO_LOCAL,file).getAsJsonObject("source_ref");
    var checks=new AtomicInteger();var charts=new SourceCharts(new RecordingBindings(source->{var a=workspace.sources.admitChart(source);return new RecordedSources.Admission(a.prefix(),()->{if(checks.incrementAndGet()==2){logs.clearAllowedDirectories();logs.addAllowedDirectory(temp.resolve("other"));}a.access().check();});}),RecordingAnalysis.Limits.defaults(),System::nanoTime);
    var r=request();r.add("source_ref",ref);assertThrows(IOException.class,()->charts.read(r,false,()->true));assertEquals(2,checks.get());
  }

}
