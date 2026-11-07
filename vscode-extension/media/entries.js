/* The host owns entries and field discovery; this page owns browsing controls and tree navigation. */
(() => {
  "use strict";
  const vscode = acquireVsCodeApi();
  const filter = document.getElementById("filter");
  const grouping = document.getElementById("grouping");
  const clear = document.getElementById("clear");
  const collapse = document.getElementById("collapse-all");
  const status = document.getElementById("status");
  const tree = document.getElementById("entries");
  let generation = -1;
  let roots = [];
  let nodes = new Map();
  let parents = new Map();
  let rows = new Map();
  let visible = [];
  let expanded = new Map();
  let requested = new Set();
  let focused = null;
  let allCollapsed = false;
  let filterInitialized = false;
  let filterEdited = false;
  let acceptedFilter = "";
  let groupingInitialized = false;
  let groupingEdited = false;
  let acceptedGrouping = "path";
  let filterTimer;

  function post(type, id) {
    vscode.postMessage({ type, id, generation });
  }

  function setStatus(message, error = false) {
    status.textContent = message;
    status.className = error ? "error" : "";
  }

  function isExpanded(node) {
    return expanded.has(node.id) ? expanded.get(node.id) : !allCollapsed && node.expanded;
  }

  function indexNodes(list, parent = null) {
    for (const node of list) {
      nodes.set(node.id, node);
      parents.set(node.id, parent);
      if (node.children) indexNodes(node.children, node.id);
    }
  }

  function focusRow(id) {
    if (!rows.has(id)) return;
    focused = id;
    for (const [key, row] of rows) row.tabIndex = key === id ? 0 : -1;
    rows.get(id).focus();
  }

  function toggle(node, value = !isExpanded(node)) {
    if (!node.expandable) return;
    expanded.set(node.id, value);
    if (value && !node.children && !requested.has(node.id)) {
      requested.add(node.id);
      post("expand", node.id);
    }
    render();
    focusRow(node.id);
  }

  function activate(node) {
    if (node.kind === "group") toggle(node);
    else if (node.kind !== "note") post("activate", node.id);
  }

  function action(node, type, label, text) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "row-action";
    button.title = label;
    button.setAttribute("aria-label", `${label}: ${node.label}`);
    button.textContent = text;
    button.addEventListener("click", (event) => {
      event.stopPropagation();
      post(type, node.id);
    });
    return button;
  }

  function renderNode(node, level) {
    if (node.kind === "note") {
      const note = document.createElement("div");
      note.className = "note";
      note.textContent = node.label;
      if (node.tooltip) note.title = node.tooltip;
      return note;
    }
    visible.push(node.id);
    const item = document.createElement("div");
    item.className = "tree-item";
    item.setAttribute("role", "treeitem");
    item.setAttribute("aria-level", String(level));
    item.setAttribute("aria-label", `${node.label}${node.description ? `, ${node.description}` : ""}`);
    item.tabIndex = -1;
    if (node.expandable) item.setAttribute("aria-expanded", String(Boolean(isExpanded(node))));
    rows.set(node.id, item);
    item.addEventListener("focus", () => {
      focused = node.id;
      for (const [id, row] of rows) row.tabIndex = id === focused ? 0 : -1;
    });
    item.addEventListener("keydown", (event) => keydown(event, node));
    const row = document.createElement("div");
    row.className = "tree-row";
    if (node.tooltip) row.title = node.tooltip;
    row.addEventListener("click", () => {
      focusRow(node.id);
      activate(node);
    });
    const chevron = document.createElement(node.expandable ? "button" : "span");
    chevron.className = node.expandable ? `chevron${isExpanded(node) ? " expanded" : ""}` : "chevron-space";
    if (node.expandable) {
      chevron.type = "button";
      chevron.tabIndex = -1;
      chevron.title = `${isExpanded(node) ? "Collapse" : "Expand"} ${node.label}`;
      chevron.setAttribute("aria-label", chevron.title);
      chevron.addEventListener("click", (event) => {
        event.stopPropagation();
        toggle(node);
      });
    } else chevron.setAttribute("aria-hidden", "true");
    const label = document.createElement("span");
    label.className = "entry-name";
    label.textContent = node.label;
    row.append(chevron, label);
    if (node.description) {
      const description = document.createElement("span");
      description.className = "entry-description";
      description.textContent = node.description;
      row.append(description);
    }
    if (node.details || node.plotElements) {
      const actions = document.createElement("span");
      actions.className = "row-actions";
      if (node.details) actions.append(action(node, "details", "Show details", "ⓘ"));
      if (node.plotElements) actions.append(action(node, "plotElements", "Plot every element", "Plot all"));
      row.append(actions);
    }
    item.append(row);
    if (node.expandable && isExpanded(node)) {
      const group = document.createElement("div");
      group.className = "tree-group";
      group.setAttribute("role", "group");
      if (node.children) {
        for (const child of node.children) group.append(renderNode(child, level + 1));
      } else if (requested.has(node.id)) {
        const loading = document.createElement("div");
        loading.className = "loading-children";
        loading.textContent = "Loading fields…";
        group.append(loading);
      }
      item.append(group);
    }
    return item;
  }

  function render() {
    const treeHadFocus = tree.contains(document.activeElement);
    rows = new Map();
    visible = [];
    tree.replaceChildren(...roots.map(node => renderNode(node, 1)));
    if (!rows.has(focused)) focused = visible[0] ?? null;
    if (focused) {
      rows.get(focused).tabIndex = 0;
      if (treeHadFocus) focusRow(focused);
    }
  }

  function keydown(event, node) {
    // Buttons retain their native Enter/Space behavior; child rows must not activate ancestors.
    if (event.target !== rows.get(node.id) || event.altKey || event.ctrlKey || event.metaKey) return;
    const index = visible.indexOf(node.id);
    switch (event.key) {
      case "ArrowDown": focusRow(visible[Math.min(visible.length - 1, index + 1)]); break;
      case "ArrowUp": focusRow(visible[Math.max(0, index - 1)]); break;
      case "Home": focusRow(visible[0]); break;
      case "End": focusRow(visible[visible.length - 1]); break;
      case "ArrowRight":
        if (node.expandable && !isExpanded(node)) toggle(node, true);
        else if (node.expandable && node.children) {
          const child = node.children.find(candidate => rows.has(candidate.id));
          if (child) focusRow(child.id);
        }
        break;
      case "ArrowLeft":
        if (node.expandable && isExpanded(node)) toggle(node, false);
        else focusRow(parents.get(node.id));
        break;
      case "Enter":
      case " ": activate(node); break;
      default: return;
    }
    event.preventDefault();
    event.stopPropagation();
  }

  function collapseAll() {
    allCollapsed = true;
    for (const node of nodes.values()) if (node.expandable) expanded.set(node.id, false);
    // Keep focus on the nearest surviving ancestor when invoked from the workbench toolbar.
    while (focused && parents.get(focused)) focused = parents.get(focused);
    render();
  }

  function updateFilter(immediate = false) {
    filterEdited = true;
    clear.disabled = filter.value.length === 0;
    clearTimeout(filterTimer);
    setStatus(filter.value ? "Filtering entries…" : "Loading entries…");
    tree.setAttribute("aria-busy", "true");
    // Bind the request to the context in which typing began. A message already in transit
    // must not reinstate an old log's query after the host has switched logs or cleared it.
    const filterGeneration = generation;
    const send = () => vscode.postMessage({ type: "filter", value: filter.value, generation: filterGeneration });
    if (immediate) send();
    else filterTimer = setTimeout(send, 150);
  }

  filter.addEventListener("input", () => updateFilter());
  filter.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && filter.value) {
      filter.value = "";
      updateFilter(true);
      event.preventDefault();
    } else if (event.key === "ArrowDown" && visible.length) {
      focusRow(visible[0]);
      event.preventDefault();
    } else if (event.key === "Enter") {
      // Enter searches immediately; it never activates whichever row was previously focused.
      updateFilter(true);
      event.preventDefault();
    }
  });
  clear.addEventListener("click", () => {
    filter.value = "";
    updateFilter(true);
    filter.focus();
  });
  collapse.addEventListener("click", () => {
    collapseAll();
    vscode.postMessage({ type: "collapseAll", generation });
  });
  grouping.addEventListener("change", () => {
    groupingEdited = true;
    clearTimeout(filterTimer);
    setStatus("Grouping entries…");
    tree.setAttribute("aria-busy", "true");
    // A switch includes any text still waiting in the search debounce, as one host update.
    vscode.postMessage({ type: "grouping", value: grouping.value, filter: filter.value, generation });
  });

  window.addEventListener("message", ({ data: message }) => {
    if (!message || typeof message.type !== "string") return;
    if (message.type === "focusFilter") {
      filter.focus();
      return;
    }
    if (message.type === "collapseAll") {
      collapseAll();
      return;
    }
    if (!Number.isInteger(message.generation) || message.generation < generation) return;
    if (message.type === "loading" || message.type === "tree") {
      if (typeof message.filter !== "string") return;
      if (message.grouping !== "path" && message.grouping !== "type") return;
      // An explicit workbench clear or a different log may reset the filter. Apply it once
      // per generation, restoring the host's grouping if an old-context switch was pending.
      // A later tree reply must not erase edits begun after its loading message.
      if (message.resetFilter && message.generation !== generation) {
        clearTimeout(filterTimer);
        filter.value = message.filter;
        grouping.value = message.grouping;
      } else {
        if (!groupingInitialized && !groupingEdited) grouping.value = message.grouping;
        // Delayed replies to the previous grouping must not erase the user's newer choice.
        if (message.grouping !== grouping.value) return;
        if (!filterInitialized && !filterEdited) filter.value = message.filter;
      }
      groupingInitialized = true;
      filterInitialized = true;
      if (message.filter !== filter.value) return;
      if (message.generation !== generation) {
        expanded = new Map();
        requested = new Set();
        allCollapsed = false;
      }
      const target = document.getElementById("visualization-target");
      if (target && message.target !== undefined) target.textContent = message.target;
      generation = message.generation;
      acceptedFilter = message.filter;
      acceptedGrouping = message.grouping;
      clear.disabled = filter.value.length === 0;
      if (message.type === "loading") {
        setStatus(filter.value ? "Filtering entries…" : "Loading entries…");
        tree.setAttribute("aria-busy", "true");
        return;
      }
      roots = Array.isArray(message.nodes) ? message.nodes : [];
      nodes = new Map();
      parents = new Map();
      indexNodes(roots);
      setStatus(roots.length ? "" : filter.value ? "No entries match this filter." : "No entries.");
      tree.setAttribute("aria-busy", "false");
      render();
    } else if (message.generation === generation && acceptedFilter === filter.value && acceptedGrouping === grouping.value) {
      if (message.type === "children") {
        const node = nodes.get(message.id);
        if (!node || !Array.isArray(message.nodes)) return;
        requested.delete(node.id);
        node.children = message.nodes;
        indexNodes(node.children, node.id);
        render();
      } else if (message.type === "error") {
        requested.clear();
        setStatus(message.message || "Could not load entries.", true);
        tree.setAttribute("aria-busy", "false");
        render();
      }
    }
  });
  vscode.postMessage({ type: "ready" });
})();
