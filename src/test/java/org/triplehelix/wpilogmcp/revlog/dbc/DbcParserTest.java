package org.triplehelix.wpilogmcp.revlog.dbc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for DbcParser.
 */
class DbcParserTest {

  private DbcParser parser;

  @BeforeEach
  void setUp() {
    parser = new DbcParser();
  }

  @Test
  void testParseEmptyContent() {
    DbcDatabase db = parser.parse("");
    assertEquals(0, db.messageCount());
  }

  @Test
  void testParseNullContent() {
    DbcDatabase db = parser.parse(null);
    assertEquals(0, db.messageCount());
  }

  @Test
  void testParseVersion() {
    String content = """
        VERSION "1.0"
        """;
    DbcDatabase db = parser.parse(content);
    assertEquals("1.0", db.version());
  }

  @Test
  void testParseSimpleMessage() {
    String content = """
        VERSION ""

        BO_ 0x100 TestMessage: 8 TestNode
         SG_ Signal1 : 0|8@1+ (1,0) [0|255] "units"
        """;

    DbcDatabase db = parser.parse(content);
    assertEquals(1, db.messageCount());

    var message = db.getMessage(0x100);
    assertTrue(message.isPresent());
    assertEquals("TestMessage", message.get().name());
    assertEquals(8, message.get().dlc());
    assertEquals("TestNode", message.get().transmitter());
    assertEquals(1, message.get().signalCount());
  }

  @Test
  void testParseSignalDetails() {
    String content = """
        BO_ 256 TestMessage: 8 Node
         SG_ MySignal : 0|16@1- (0.001,10) [-100|100] "volts"
        """;

    DbcDatabase db = parser.parse(content);
    var message = db.getMessage(256).orElseThrow();
    var signal = message.getSignal("MySignal");

    assertNotNull(signal);
    assertEquals("MySignal", signal.name());
    assertEquals(0, signal.startBit());
    assertEquals(16, signal.bitLength());
    assertTrue(signal.littleEndian());
    assertTrue(signal.signed());
    assertEquals(0.001, signal.scale(), 0.0001);
    assertEquals(10.0, signal.offset(), 0.0001);
    assertEquals(-100.0, signal.min(), 0.0001);
    assertEquals(100.0, signal.max(), 0.0001);
    assertEquals("volts", signal.unit());
  }

  @Test
  void testParseMultipleSignals() {
    String content = """
        BO_ 0x200 MultiSignal: 8 Node
         SG_ Signal1 : 0|8@1+ (1,0) [0|255] ""
         SG_ Signal2 : 8|8@1+ (1,0) [0|255] ""
         SG_ Signal3 : 16|16@1- (0.1,0) [-100|100] "rpm"
        """;

    DbcDatabase db = parser.parse(content);
    var message = db.getMessage(0x200).orElseThrow();

    assertEquals(3, message.signalCount());
    assertNotNull(message.getSignal("Signal1"));
    assertNotNull(message.getSignal("Signal2"));
    assertNotNull(message.getSignal("Signal3"));
  }

  @Test
  void testParseMultipleMessages() {
    String content = """
        VERSION "2.0"

        BO_ 0x100 Message1: 8 Node1
         SG_ Sig1 : 0|8@1+ (1,0) [0|255] ""

        BO_ 0x200 Message2: 8 Node2
         SG_ Sig2 : 0|16@1+ (1,0) [0|65535] ""

        BO_ 0x300 Message3: 4 Node3
         SG_ Sig3 : 0|32@1- (1,0) [-1000|1000] ""
        """;

    DbcDatabase db = parser.parse(content);
    assertEquals("2.0", db.version());
    assertEquals(3, db.messageCount());

    assertTrue(db.getMessage(0x100).isPresent());
    assertTrue(db.getMessage(0x200).isPresent());
    assertTrue(db.getMessage(0x300).isPresent());
  }

  @Test
  void testParseDecimalMessageId() {
    String content = """
        BO_ 1234 DecimalId: 8 Node
         SG_ Test : 0|8@1+ (1,0) [0|255] ""
        """;

    DbcDatabase db = parser.parse(content);
    assertTrue(db.getMessage(1234).isPresent());
  }

  @Test
  void testParseHexMessageId() {
    String content = """
        BO_ 0x4D2 HexId: 8 Node
         SG_ Test : 0|8@1+ (1,0) [0|255] ""
        """;

    DbcDatabase db = parser.parse(content);
    assertTrue(db.getMessage(0x4D2).isPresent()); // 0x4D2 = 1234
  }

  @Test
  void testParseUnsignedSignal() {
    String content = """
        BO_ 100 Msg: 8 N
         SG_ Unsigned : 0|8@1+ (1,0) [0|255] ""
        """;

    DbcDatabase db = parser.parse(content);
    var signal = db.getMessage(100).orElseThrow().getSignal("Unsigned");
    assertFalse(signal.signed());
  }

  @Test
  void testParseSignedSignal() {
    String content = """
        BO_ 100 Msg: 8 N
         SG_ Signed : 0|8@1- (1,0) [-128|127] ""
        """;

    DbcDatabase db = parser.parse(content);
    var signal = db.getMessage(100).orElseThrow().getSignal("Signed");
    assertTrue(signal.signed());
  }

  @Test
  void testParseLittleEndianSignal() {
    String content = """
        BO_ 100 Msg: 8 N
         SG_ Intel : 0|16@1+ (1,0) [0|65535] ""
        """;

    DbcDatabase db = parser.parse(content);
    var signal = db.getMessage(100).orElseThrow().getSignal("Intel");
    assertTrue(signal.littleEndian());
  }

  @Test
  void testParseBigEndianSignal() {
    String content = """
        BO_ 100 Msg: 8 N
         SG_ Motorola : 7|16@0+ (1,0) [0|65535] ""
        """;

    DbcDatabase db = parser.parse(content);
    var signal = db.getMessage(100).orElseThrow().getSignal("Motorola");
    assertFalse(signal.littleEndian());
  }

  @Test
  void testParseIgnoresComments() {
    String content = """
        VERSION ""
        // This is a comment
        BO_ 100 Msg: 8 N
        // Another comment
         SG_ Test : 0|8@1+ (1,0) [0|255] ""
        """;

    DbcDatabase db = parser.parse(content);
    assertEquals(1, db.messageCount());
  }

  @Test
  void testParseRevSparkStatus0() {
    String content = """
        BO_ 0x02051800 Periodic_Status_0: 8 SparkMax
         SG_ AppliedOutput : 0|16@1- (0.0001,0) [-1|1] "duty_cycle"
         SG_ Faults : 16|16@1+ (1,0) [0|65535] ""
         SG_ StickyFaults : 32|16@1+ (1,0) [0|65535] ""
         SG_ IsFollower : 48|1@1+ (1,0) [0|1] ""
         SG_ IsInverted : 49|1@1+ (1,0) [0|1] ""
        """;

    DbcDatabase db = parser.parse(content);
    var message = db.getMessage(0x02051800).orElseThrow();

    assertEquals("Periodic_Status_0", message.name());
    assertEquals(5, message.signalCount());

    var appliedOutput = message.getSignal("AppliedOutput");
    assertNotNull(appliedOutput);
    assertEquals(0.0001, appliedOutput.scale(), 0.00001);
    assertTrue(appliedOutput.signed());
    assertEquals("duty_cycle", appliedOutput.unit());
  }

  @Test
  void testGetMessageByName() {
    String content = """
        BO_ 0x100 FirstMessage: 8 Node
         SG_ Sig : 0|8@1+ (1,0) [0|255] ""

        BO_ 0x200 SecondMessage: 8 Node
         SG_ Sig : 0|8@1+ (1,0) [0|255] ""
        """;

    DbcDatabase db = parser.parse(content);

    var first = db.getMessageByName("FirstMessage");
    assertTrue(first.isPresent());
    assertEquals(0x100, first.get().id());

    var second = db.getMessageByName("SecondMessage");
    assertTrue(second.isPresent());
    assertEquals(0x200, second.get().id());

    var notFound = db.getMessageByName("NotExists");
    assertFalse(notFound.isPresent());
  }

  @Test
  void testTotalSignalCount() {
    String content = """
        BO_ 0x100 Msg1: 8 N
         SG_ S1 : 0|8@1+ (1,0) [0|255] ""
         SG_ S2 : 8|8@1+ (1,0) [0|255] ""

        BO_ 0x200 Msg2: 8 N
         SG_ S3 : 0|8@1+ (1,0) [0|255] ""
        """;

    DbcDatabase db = parser.parse(content);
    assertEquals(3, db.totalSignalCount());
  }
}
