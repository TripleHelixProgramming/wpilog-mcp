import { dataUrl } from "./dataRequest";
import { Selection } from "./selection";

/** A notebook is a reproducible request and its evidence, never a copy of a robot's values. */
export function notebook(selection: Selection, endpoint: string): Record<string, unknown> {
  if (!selection.entries.length) throw new Error("Select at least one entry for the notebook");
  const requests = selection.entries.map(name => {
    const url = new URL(dataUrl(endpoint, { path: selection.path, names: [name],
      startTime: selection.start, endTime: selection.end, format: "arrow" }));
    url.searchParams.set("format", "arrow"); return [name, url.toString()];
  });
  // Separate streams preserve each entry's type and name: Arrow batches carry the name in
  // metadata, which read_all().to_pandas() alone would lose when concatenating several entries.
  const read = `import urllib.request\nimport pyarrow as pa\nimport pyarrow.ipc as ipc\nimport pandas as pd\n\nrequests = ${JSON.stringify(requests)}\nframes = {}\nfor name, url in requests:\n    with urllib.request.urlopen(url) as response:\n        table = ipc.open_stream(response.read()).read_all()\n    while any(pa.types.is_struct(field.type) for field in table.schema):\n        table = table.flatten()\n    # Arrow timestamps are robot-clock microseconds, not calendar dates.\n    column = table.schema.get_field_index('timestamp')\n    table = table.set_column(column, 'timestamp', table.column(column).cast(pa.int64()))\n    frames[name] = table.to_pandas()\n    # import polars as pl; frame = pl.from_arrow(table)\ndf = pd.concat([frame.assign(entry=name) for name, frame in frames.items()], ignore_index=True)\ndf\n`;
  const plot = "import matplotlib.pyplot as plt\nfig, ax = plt.subplots()\nfor name, frame in frames.items():\n    for column in frame.select_dtypes(include='number').columns:\n        if column != 'timestamp':\n            ax.plot(frame['timestamp'], frame[column], label=f'{name}: {column}')\nax.set_xlabel('Robot time (microseconds)')\nax.legend()\n";
  const markdown = `# WPILog selection\n\nLog: ${JSON.stringify(selection.path)}\n\nEntries: ${selection.entries.map(n => JSON.stringify(n)).join(", ")}\n\nWindow (seconds): ${selection.start ?? "log start"} to ${selection.end ?? "log end"}\n\nServer inputs:\n\n\`\`\`json\n${JSON.stringify(selection.inputs ?? {}, null, 2)}\n\`\`\`\n`;
  const code = (source: string) => ({ cell_type: "code", metadata: {}, execution_count: null, outputs: [], source });
  return { nbformat: 4, nbformat_minor: 5, metadata: { kernelspec: { display_name: "Python 3", language: "python", name: "python3" } },
    cells: [{ ...code(read), id: "read" }, { ...code(plot), id: "plot" }, { cell_type: "markdown", id: "inputs", metadata: {}, source: markdown }] };
}
