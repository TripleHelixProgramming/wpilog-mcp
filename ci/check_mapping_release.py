"""The import must release actual mappings, even where the OS permits a mapped-file rename."""
from pathlib import Path
import os
import subprocess
import xml.etree.ElementTree as ET

source = Path("src/main/java/org/triplehelix/wpilogmcp/log/MappedLogBytes.java")
original = source.read_text()
needle = "clean.invoke(cleaner, windows[i]);"
assert original.count(needle) == 1
planted = original.replace("private boolean closed;", "private boolean closed;\n  private static final java.util.List<MappedByteBuffer> LEAK = new java.util.ArrayList<>();")
planted = planted.replace(needle, "LEAK.add(windows[i]); if (false) clean.invoke(cleaner, windows[i]);")
report = Path("build/reports/mapping-release-plant")
report.mkdir(parents=True, exist_ok=True)
try:
    source.write_text(planted)
    with (report / "failure.log").open("w") as output:
        result = subprocess.run(["gradlew.bat" if os.name == "nt" else "./gradlew", "test", "--tests", "*WindowedImportTest"], stdout=output, stderr=subprocess.STDOUT)
    xml = Path("build/test-results/test/TEST-org.triplehelix.wpilogmcp.store.WindowedImportTest.xml")
    assert result.returncode != 0 and xml.exists(), "Unreleased-window plant survived (or produced no test evidence)"
    (report / xml.name).write_bytes(xml.read_bytes())
    failures = ET.parse(xml).findall(".//failure")
    assert any("refused" in f.get("message", "") or "Mapped windows retained at import completion" in f.get("message", "")
               for f in failures), "Expected retained mappings or an import rename refusal, not another failure"
    print("Unreleased-window plant caught on the real import path")
finally:
    source.write_text(original)
