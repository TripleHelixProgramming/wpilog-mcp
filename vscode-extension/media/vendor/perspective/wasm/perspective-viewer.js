/* @ts-self-types="./perspective-viewer.wasm.d.ts" */
import { define_panel_tab } from './snippets/perspective-viewer-6813b568219c6763/inline0.js';
import { ClipboardItem } from './snippets/perspective-viewer-6813b568219c6763/inline1.js';
import { ResizeObserver } from './snippets/perspective-viewer-6813b568219c6763/inline2.js';
import { IntersectionObserver } from './snippets/perspective-viewer-6813b568219c6763/inline3.js';
import { bootstrap } from './snippets/perspective-viewer-6813b568219c6763/inline4.js';
import { psp } from './snippets/perspective-viewer-6813b568219c6763/inline5.js';

/**
 * An instance of a [`Client`] is a connection to a single
 * `perspective_server::Server`, whether locally in-memory or remote over some
 * transport like a WebSocket.
 *
 * The browser and node.js libraries both support the `websocket(url)`
 * constructor, which connects to a remote `perspective_server::Server`
 * instance over a WebSocket transport.
 *
 * In the browser, the `worker()` constructor creates a new Web Worker
 * `perspective_server::Server` and returns a [`Client`] connected to it.
 *
 * In node.js, a pre-instantied [`Client`] connected synhronously to a global
 * singleton `perspective_server::Server` is the default module export.
 *
 * # JavaScript Examples
 *
 * Create a Web Worker `perspective_server::Server` in the browser and return a
 * [`Client`] instance connected for it:
 *
 * ```javascript
 * import perspective from "@perspective-dev/client";
 * const client = await perspective.worker();
 * ```
 *
 * Create a WebSocket connection to a remote `perspective_server::Server`:
 *
 * ```javascript
 * import perspective from "@perspective-dev/client";
 * const client = await perspective.websocket("ws://locahost:8080/ws");
 * ```
 *
 * Access the synchronous client in node.js:
 *
 * ```javascript
 * import { default as client } from "@perspective-dev/client";
 * ```
 */
export class Client {
    static __wrap(ptr) {
        ptr = ptr >>> 0;
        const obj = Object.create(Client.prototype);
        obj.__wbg_ptr = ptr;
        ClientFinalization.register(obj, obj.__wbg_ptr, obj);
        return obj;
    }
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        ClientFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_client_free(ptr, 0);
    }
    /**
     * @returns {string}
     */
    __getClassname() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.client___getClassname(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var v1 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v1;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Unsafely gets a [`View`] by raw ID, useful for JavaScript multi-threaded
     * (via Web Worker) context where a standard `View` cannot otherwise be
     * shared because its wrapper is not serializable.
     *
     * # Safety
     *
     * This method is unsafe because the lifetime of a [`View`] is bound to
     * the [`Client`] which created it.
     *
     * The caller must guarantee that `entity_id` corresponds to a live
     * [`crate::View`] on the connected server (obtained from another
     * [`Client`]'s [`crate::View::get_name`] and forwarded across the
     * serialization boundary).
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const view = client.__unsafe_open_view(name_from_main_thread);
     * const cols = await view.to_columns();
     * ```
     * @param {string} entity_id
     * @returns {View}
     */
    __unsafe_open_view(entity_id) {
        const ptr0 = passStringToWasm0(entity_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.client___unsafe_open_view(this.__wbg_ptr, ptr0, len0);
        return View.__wrap(ret);
    }
    /**
     * Retrieves the names of all tables that this client has access to.
     *
     * `name` is a string identifier unique to the [`Table`] (per [`Client`]),
     * which can be used in conjunction with [`Client::open_table`] to get
     * a [`Table`] instance without the use of [`Client::table`]
     * constructor directly (e.g., one created by another [`Client`]).
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const tables = await client.get_hosted_table_names();
     * ```
     * @returns {Promise<string[]>}
     */
    get_hosted_table_names() {
        const ret = wasm.client_get_hosted_table_names(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * @param {string} error
     * @param {Function | null} [reconnect]
     * @returns {Promise<void>}
     */
    handle_error(error, reconnect) {
        const ptr0 = passStringToWasm0(error, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.client_handle_error(this.__wbg_ptr, ptr0, len0, isLikeNone(reconnect) ? 0 : addHeapObject(reconnect));
        return takeObject(ret);
    }
    /**
     * @param {any} value
     * @returns {Promise<void>}
     */
    handle_response(value) {
        const ret = wasm.client_handle_response(this.__wbg_ptr, addHeapObject(value));
        return takeObject(ret);
    }
    /**
     * Creates a new read-only [`Table`] by performing an INNER JOIN on two
     * source tables. The resulting table is reactive: when either source
     * table is updated, the join is automatically recomputed.
     *
     * # Arguments
     *
     * - `left` - The left source table (a [`Table`] instance or a table name
     *   string).
     * - `right` - The right source table (a [`Table`] instance or a table name
     *   string).
     * - `on` - The column name to join on. Must exist in both tables with the
     *   same type.
     * - `options` - Optional join configuration: `{ join_type?: "inner" |
     *   "left" | "outer", name?: string }`.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const joined = await client.join(orders_table, products_table, "Product ID", { join_type: "left" });
     * const joined = await client.join("orders", "products", "Product ID", { join_type: "left" });
     * ```
     * @param {any} left
     * @param {any} right
     * @param {string} on
     * @param {JoinOptions | null} [options]
     * @returns {Promise<Table>}
     */
    join(left, right, on, options) {
        const ptr0 = passStringToWasm0(on, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.client_join(this.__wbg_ptr, addHeapObject(left), addHeapObject(right), ptr0, len0, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * @param {Function} send_request
     * @param {Function | null} [close]
     */
    constructor(send_request, close) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.client_new(retptr, addHeapObject(send_request), isLikeNone(close) ? 0 : addHeapObject(close));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            this.__wbg_ptr = r0 >>> 0;
            ClientFinalization.register(this, this.__wbg_ptr, this);
            return this;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {Function} on_response
     * @returns {ProxySession}
     */
    new_proxy_session(on_response) {
        try {
            const ret = wasm.client_new_proxy_session(this.__wbg_ptr, addBorrowedObject(on_response));
            return ProxySession.__wrap(ret);
        } finally {
            heap[stack_pointer++] = undefined;
        }
    }
    /**
     * @param {Function} callback
     * @returns {Promise<number>}
     */
    on_error(callback) {
        const ret = wasm.client_on_error(this.__wbg_ptr, addHeapObject(callback));
        return takeObject(ret);
    }
    /**
     * Register a callback which is invoked whenever [`Client::table`] (on this
     * [`Client`]) or [`Table::delete`] (on a [`Table`] belinging to this
     * [`Client`]) are called.
     * @param {Function} on_update_js
     * @returns {Promise<number>}
     */
    on_hosted_tables_update(on_update_js) {
        const ret = wasm.client_on_hosted_tables_update(this.__wbg_ptr, addHeapObject(on_update_js));
        return takeObject(ret);
    }
    /**
     * Opens a [`Table`] that is hosted on the `perspective_server::Server`
     * that is connected to this [`Client`].
     *
     * The `name` property of [`TableInitOptions`] is used to identify each
     * [`Table`]. [`Table`] `name`s can be looked up for each [`Client`]
     * via [`Client::get_hosted_table_names`].
     *
     * # JavaScript Examples
     *
     * Get a virtual [`Table`] named "table_one" from this [`Client`]
     *
     * ```javascript
     * const tables = await client.open_table("table_one");
     * ```
     * @param {string} entity_id
     * @returns {Promise<Table>}
     */
    open_table(entity_id) {
        const ptr0 = passStringToWasm0(entity_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.client_open_table(this.__wbg_ptr, ptr0, len0);
        return takeObject(ret);
    }
    /**
     * Remove a callback previously registered via
     * `Client::on_hosted_tables_update`.
     * @param {number} update_id
     * @returns {Promise<void>}
     */
    remove_hosted_tables_update(update_id) {
        const ret = wasm.client_remove_hosted_tables_update(this.__wbg_ptr, update_id);
        return takeObject(ret);
    }
    /**
     * Provides the [`SystemInfo`] struct, implementation-specific metadata
     * about the [`perspective_server::Server`] runtime such as Memory and
     * CPU usage.
     *
     * For WebAssembly servers, this method includes the WebAssembly heap size.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const info = await client.system_info();
     * ```
     * @returns {Promise<SystemInfo>}
     */
    system_info() {
        const ret = wasm.client_system_info(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Creates a new [`Table`] from either a _schema_ or _data_.
     *
     * The [`Client::table`] factory function can be initialized with either a
     * _schema_ (see [`Table::schema`]), or data in one of these formats:
     *
     * - Apache Arrow
     * - CSV
     * - JSON row-oriented
     * - JSON column-oriented
     * - NDJSON
     *
     * When instantiated with _data_, the schema is inferred from this data.
     * While this is convenient, inferrence is sometimes imperfect e.g.
     * when the input is empty, null or ambiguous. For these cases,
     * [`Client::table`] can first be instantiated with a explicit schema.
     *
     * When instantiated with a _schema_, the resulting [`Table`] is empty but
     * with known column names and column types. When subsqeuently
     * populated with [`Table::update`], these columns will be _coerced_ to
     * the schema's type. This behavior can be useful when
     * [`Client::table`]'s column type inferences doesn't work.
     *
     * The resulting [`Table`] is _virtual_, and invoking its methods
     * dispatches events to the `perspective_server::Server` this
     * [`Client`] connects to, where the data is stored and all calculation
     * occurs.
     *
     * # Arguments
     *
     * - `arg` - Either _schema_ or initialization _data_.
     * - `options` - Optional configuration which provides one of:
     *     - `limit` - The max number of rows the resulting [`Table`] can
     *       store.
     *     - `index` - The column name to use as an _index_ column. If this
     *       `Table` is being instantiated by _data_, this column name must be
     *       present in the data.
     *     - `name` - The name of the table. This will be generated if it is
     *       not provided.
     *     - `format` - The explicit format of the input data, can be one of
     *       `"json"`, `"columns"`, `"csv"` or `"arrow"`. This overrides
     *       language-specific type dispatch behavior, which allows stringified
     *       and byte array alternative inputs.
     *
     * # JavaScript Examples
     *
     * Load a CSV from a `string`:
     *
     * ```javascript
     * const table = await client.table("x,y\n1,2\n3,4");
     * ```
     *
     * Load an Arrow from an `ArrayBuffer`:
     *
     * ```javascript
     * import * as fs from "node:fs/promises";
     * const table2 = await client.table(await fs.readFile("superstore.arrow"));
     * ```
     *
     * Load a CSV from a `UInt8Array` (the default for this type is Arrow)
     * using a format override:
     *
     * ```javascript
     * const enc = new TextEncoder();
     * const table = await client.table(enc.encode("x,y\n1,2\n3,4"), {
     *     format: "csv",
     * });
     * ```
     *
     * Create a table with an `index`:
     *
     * ```javascript
     * const table = await client.table(data, { index: "Row ID" });
     * ```
     * @param {string | ArrayBuffer | Record<string, unknown[]> | Record<string, unknown>[] | Record<string, ColumnType>} value
     * @param {TableInitOptions | null} [options]
     * @returns {Promise<Table>}
     */
    table(value, options) {
        const ret = wasm.client_table(this.__wbg_ptr, addHeapObject(value), isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Terminates this [`Client`], cleaning up any [`crate::View`] handles the
     * [`Client`] has open as well as its callbacks.
     * @returns {any}
     */
    terminate() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.client_terminate(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            return takeObject(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
}
if (Symbol.dispose) Client.prototype[Symbol.dispose] = Client.prototype.free;

/**
 * JavaScript-facing DuckDB SQL query builder.
 *
 * This struct wraps the Rust `DuckDBSqlBuilder` and exposes it to JavaScript
 * via wasm_bindgen.
 */
export class GenericSQLVirtualServerModel {
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        GenericSQLVirtualServerModelFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_genericsqlvirtualservermodel_free(ptr, 0);
    }
    /**
     * Returns the SQL query to list all hosted tables.
     * @returns {string}
     */
    getHostedTables() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.genericsqlvirtualservermodel_getHostedTables(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v1 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v1;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Creates a new `JsDuckDBSqlBuilder` instance.
     * @param {any | null} [args]
     */
    constructor(args) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.genericsqlvirtualservermodel_new(retptr, isLikeNone(args) ? 0 : addHeapObject(args));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            this.__wbg_ptr = r0 >>> 0;
            GenericSQLVirtualServerModelFinalization.register(this, this.__wbg_ptr, this);
            return this;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to create a view from a table with the given
     * configuration.
     * @param {string} table_id
     * @param {string} view_id
     * @param {any} config
     * @param {any} schema
     * @returns {string}
     */
    tableMakeView(table_id, view_id, config, schema) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(table_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            const ptr1 = passStringToWasm0(view_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_tableMakeView(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1, addHeapObject(config), addHeapObject(schema));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v3 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v3;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to describe a table's schema.
     * @param {string} table_id
     * @returns {string}
     */
    tableSchema(table_id) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(table_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_tableSchema(retptr, this.__wbg_ptr, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v2 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v2;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to get the row count of a table.
     * @param {string} table_id
     * @returns {string}
     */
    tableSize(table_id) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(table_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_tableSize(retptr, this.__wbg_ptr, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v2 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v2;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to validate an expression against a table.
     * @param {string} table_id
     * @param {string} expression
     * @returns {string}
     */
    tableValidateExpression(table_id, expression) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(table_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            const ptr1 = passStringToWasm0(expression, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_tableValidateExpression(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v3 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v3;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to get the column count of a view.
     * @param {string} view_id
     * @returns {string}
     */
    viewColumnSize(view_id) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(view_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_viewColumnSize(retptr, this.__wbg_ptr, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v2 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v2;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to delete a view.
     * @param {string} view_id
     * @returns {string}
     */
    viewDelete(view_id) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(view_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_viewDelete(retptr, this.__wbg_ptr, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v2 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v2;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to fetch data from a view with the given viewport.
     * @param {string} view_id
     * @param {any} config
     * @param {any} viewport
     * @param {any} schema
     * @returns {string}
     */
    viewGetData(view_id, config, viewport, schema) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(view_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_viewGetData(retptr, this.__wbg_ptr, ptr0, len0, addHeapObject(config), addHeapObject(viewport), addHeapObject(schema));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v2 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v2;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to get the min and max values of a column.
     * @param {string} view_id
     * @param {string} column_name
     * @param {any} config
     * @returns {string}
     */
    viewGetMinMax(view_id, column_name, config) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(view_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            const ptr1 = passStringToWasm0(column_name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_viewGetMinMax(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1, addHeapObject(config));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v3 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v3;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to describe a view's schema.
     * @param {string} view_id
     * @returns {string}
     */
    viewSchema(view_id) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(view_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_viewSchema(retptr, this.__wbg_ptr, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v2 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v2;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Returns the SQL query to get the row count of a view.
     * @param {string} view_id
     * @returns {string}
     */
    viewSize(view_id) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(view_id, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.genericsqlvirtualservermodel_viewSize(retptr, this.__wbg_ptr, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            var v2 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v2;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
}
if (Symbol.dispose) GenericSQLVirtualServerModel.prototype[Symbol.dispose] = GenericSQLVirtualServerModel.prototype.free;

/**
 * The `<perspective-viewer-plugin>` element.
 *
 * The default perspective plugin which is registered and activated
 * automcatically when a `<perspective-viewer>` is loaded without plugins.
 * While you will not typically instantiate this class directly, it is simple
 * enough to function as a good "default" plugin implementation which can be
 * extended to create custom plugins.
 *
 * # Example
 * ```javascript
 * class MyPlugin extends customElements.get("perspective-viewer-plugin") {
 *    // Custom plugin overrides
 * }
 * ```
 */
export class PerspectiveDebugPluginElement {
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        PerspectiveDebugPluginElementFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_perspectivedebugpluginelement_free(ptr, 0);
    }
    /**
     * @returns {Promise<any>}
     */
    clear() {
        const ret = wasm.perspectivedebugpluginelement_clear(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * @returns {any}
     */
    get config_column_names() {
        const ret = wasm.perspectivedebugpluginelement_config_column_names(this.__wbg_ptr);
        return takeObject(ret);
    }
    connectedCallback() {
        wasm.perspectivedebugpluginelement_connectedCallback(this.__wbg_ptr);
    }
    /**
     * @returns {Promise<any>}
     */
    delete() {
        const ret = wasm.perspectivedebugpluginelement_clear(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * # Notes
     *
     * When you pass a `wasm_bindgen` wrapped type _into_ Rust, it acts like a
     * move. Ergo, if you replace the `&` in the `view` argument, the JS copy
     * of the `View` will be invalid
     * @param {View} view
     * @returns {Promise<any>}
     */
    draw(view) {
        _assertClass(view, View);
        const ret = wasm.perspectivedebugpluginelement_draw(this.__wbg_ptr, view.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * @returns {any}
     */
    get min_config_columns() {
        const ret = wasm.perspectivedebugpluginelement_config_column_names(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * @returns {string}
     */
    get name() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectivedebugpluginelement_name(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var v1 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v1;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {HTMLElement} elem
     */
    constructor(elem) {
        const ret = wasm.perspectivedebugpluginelement_new(addHeapObject(elem));
        this.__wbg_ptr = ret >>> 0;
        PerspectiveDebugPluginElementFinalization.register(this, this.__wbg_ptr, this);
        return this;
    }
    /**
     * @returns {Promise<any>}
     */
    resize() {
        const ret = wasm.perspectivedebugpluginelement_clear(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * @param {any | null} [_config]
     */
    restore(_config) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectivedebugpluginelement_restore(retptr, this.__wbg_ptr, isLikeNone(_config) ? 0 : addHeapObject(_config));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    restyle() {
        wasm.perspectivedebugpluginelement_connectedCallback(this.__wbg_ptr);
    }
    /**
     * @returns {any}
     */
    save() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectivedebugpluginelement_save(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            return takeObject(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @returns {string}
     */
    get select_mode() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectivedebugpluginelement_select_mode(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var v1 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v1;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Delegates to `draw()` VIRTUALLY — through the JS element's `draw`
     * property, never `self.draw(view)` (Rust static dispatch).
     * @param {View} view
     * @returns {Promise<any>}
     */
    update(view) {
        _assertClass(view, View);
        const ret = wasm.perspectivedebugpluginelement_update(this.__wbg_ptr, view.__wbg_ptr);
        return takeObject(ret);
    }
}
if (Symbol.dispose) PerspectiveDebugPluginElement.prototype[Symbol.dispose] = PerspectiveDebugPluginElement.prototype.free;

/**
 * The `<perspective-viewer>` custom element.
 *
 * # JavaScript Examples
 *
 * Create a new `<perspective-viewer>`:
 *
 * ```javascript
 * const viewer = document.createElement("perspective-viewer");
 * window.body.appendChild(viewer);
 * ```
 *
 * Complete example including loading and restoring the [`Table`]:
 *
 * ```javascript
 * import perspective from "@perspective-dev/viewer";
 * import perspective from "@perspective-dev/client";
 *
 * const viewer = document.createElement("perspective-viewer");
 * const worker = await perspective.worker();
 *
 * await worker.table("x\n1", {name: "table_one"});
 * await viewer.load(worker);
 * await viewer.restore({table: "table_one"});
 * ```
 */
export class PerspectiveViewerElement {
    static __wrap(ptr) {
        ptr = ptr >>> 0;
        const obj = Object.create(PerspectiveViewerElement.prototype);
        obj.__wbg_ptr = ptr;
        PerspectiveViewerElementFinalization.register(obj, obj.__wbg_ptr, obj);
        return obj;
    }
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        PerspectiveViewerElementFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_perspectiveviewerelement_free(ptr, 0);
    }
    /**
     * Create a new JavaScript Heap reference for this model instance.
     * @returns {PerspectiveViewerElement}
     */
    __get_model() {
        const ret = wasm.perspectiveviewerelement___get_model(this.__wbg_ptr);
        return PerspectiveViewerElement.__wrap(ret);
    }
    /**
     * Add a new, independent panel to this viewer's layout, rendering the
     * supplied [`ViewerConfigInitial`] into it. Unlike [`Self::restore`]'s
     * update-shaped argument, a new panel has no prior state, so `table`
     * is REQUIRED — a table-less call rejects before the layout is
     * touched. The panel uses the default [`perspective_client::Client`]
     * (the first passed to [`Self::load`]) to resolve its `table`. Returns
     * the generated panel id.
     *
     * The element-level `settings` field does not exist on the argument
     * type (it is shared across the element, not per-panel).
     *
     * # Arguments
     *
     * - `config` - The new panel's initial config, whose `table` is required.
     * - `options.wait_for_table` - when `true`, a `table` no loaded client
     *   hosts yet creates the panel empty and pending until the table is
     *   created, instead of the default rejection.
     * @param {ViewerConfigInitial} config
     * @param {AddPanelOptions | null} [options]
     * @returns {Promise<any>}
     */
    addPanel(config, options) {
        const ret = wasm.perspectiveviewerelement_addPanel(this.__wbg_ptr, addHeapObject(config), isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Configure the embedded LLM agent (see `prompt()`), replacing any prior
     * configuration and conversation.
     *
     * The agent core is provider-agnostic: one OpenAI-chat-completions
     * protocol over primitive connection fields. Exactly one of
     * `config.url` or `config.engine` is required; the `providers` presets
     * exported by this package are plain spreadable collections of these
     * fields (`{...providers.anthropic, apiKey}`).
     *
     * - `config.url` - a full chat-completions endpoint URL (any
     *   OpenAI-compatible service: Anthropic/Gemini compatibility endpoints,
     *   LM Studio, Ollama, OpenRouter, a proxy...).
     * - `config.engine` - an in-page engine object with an OpenAI-compatible
     *   `chat.completions.create(request)` method (e.g. WebLLM's `MLCEngine`);
     *   mutually exclusive with `url`.
     * - `config.headers` - extra request headers, sent verbatim.
     * - `config.apiKey` - sugar for the `Authorization: Bearer` header.
     * - `config.model` - model id sent with each request; local servers and
     *   engines generally answer with whatever model is loaded.
     * - `config.name` - cosmetic label for the chat badge (presets set this).
     * - `config.systemPrompt` - extra system-prompt context appended to the
     *   agent's built-in instructions.
     * - `config.maxTurns` - max model turns (tool-call rounds + the final
     *   answer) per `prompt()` call. Defaults to 16.
     * - `config.docs` - the agent metadata bundle, which supplies the
     *   `search_docs` corpus and the rich tool parameter schemas: the packaged
     *   `dist/docs/perspective-docs.json` asset as a parsed object (`import
     *   docs from "…json" with { type: "json" }`), a `fetch()` `Response`, an
     *   `ArrayBuffer`, a JSON string, or a `Promise` of any of those — and/or
     *   an inline array of `{title?, text}` entries for host data definitions.
     *   Omitted, `search_docs` searches an empty corpus and the parameter
     *   schemas degrade to permissive objects.
     * - `config.systemRole` - where the preamble (plus `systemPrompt`) is
     *   placed: `"system"` (default) or `"user"`. Some engines refuse a system
     *   message alongside `tools` because they substitute their own — WebLLM's
     *   Hermes function calling throws `CustomSystemPromptError` on ANY system
     *   message — and those need `"user"`, which folds the same text into the
     *   opening user turn.
     * - `config.entitlements` - access grants limiting which tools the agent
     *   is offered (and may call): any of `"read_view"`, `"configure_view"`,
     *   `"manage_layout"`, `"read_docs"`, `"read_data"`. Omitted, all but
     *   `"read_data"` are granted; `["read_view", "read_docs"]` yields a
     *   read-only agent.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * import { providers } from "@perspective-dev/viewer";
     *
     * viewer.agentConfig({
     *     ...providers.anthropic,
     *     apiKey: "sk-ant-...",
     *     docs: fetch(
     *         "node_modules/@perspective-dev/viewer/dist/docs/perspective-docs.json",
     *     ),
     * });
     * ```
     * @param {any} config
     */
    agentConfig(config) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectiveviewerelement_agentConfig(retptr, this.__wbg_ptr, addHeapObject(config));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Run one conversational turn of the embedded LLM agent (configured via
     * `agentConfig()`), resolving with the agent's final text response after
     * any tool calls have been applied to this element. Turns share a
     * conversation history (and the chat sidebar's transcript) until
     * `agentReset()`; a call made while a turn is already running rejects.
     * Tool activity is emitted as `perspective-agent-tool` CustomEvents on
     * this element.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await viewer.agentPrompt("Show me sales by region as a bar chart");
     * ```
     * @param {string} prompt
     * @returns {Promise<any>}
     */
    agentPrompt(prompt) {
        const ptr0 = passStringToWasm0(prompt, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.perspectiveviewerelement_agentPrompt(this.__wbg_ptr, ptr0, len0);
        return takeObject(ret);
    }
    /**
     * Clear the agent's conversation (history and chat transcript), keeping
     * its configuration. Cancels any in-flight turn.
     * @returns {Promise<any>}
     */
    agentReset() {
        const ret = wasm.perspectiveviewerelement_agentReset(this.__wbg_ptr);
        return takeObject(ret);
    }
    connectedCallback() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectiveviewerelement_connectedCallback(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Copy this viewer's `View` or `Table` data as CSV to the system
     * clipboard.
     *
     * # Arguments
     *
     * - `method` - The `ExportMethod` (serialized as a `String`) to use to
     *   render the data to the Clipboard.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * myDownloadButton.addEventListener("click", async () => {
     *     await viewer.copy();
     * })
     * ```
     * @param {ExportOptions | null} [options]
     * @returns {Promise<any>}
     */
    copy(options) {
        const ret = wasm.perspectiveviewerelement_copy(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Delete all internal [`View`]s and all associated state, rendering this
     * `<perspective-viewer>` unusable and freeing all associated resources.
     * Does not delete any supplied [`Table`] (as this is constructed by the
     * callee).
     *
     * Calling _any_ method on a `<perspective-viewer>` after [`Self::delete`]
     * will throw.
     *
     * <div class="warning">
     *
     * Allowing a `<perspective-viewer>` to be garbage-collected
     * without calling [`PerspectiveViewerElement::delete`] will leak WASM
     * memory!
     *
     * </div>
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await viewer.delete();
     * ```
     * @returns {Promise<any>}
     */
    delete() {
        const ptr = this.__destroy_into_raw();
        const ret = wasm.perspectiveviewerelement_delete(ptr);
        return takeObject(ret);
    }
    /**
     * Download this viewer's internal [`View`] data via a browser download
     * event.
     *
     * # Arguments
     *
     * - `method` - The `ExportMethod` to use to render the data to download.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * myDownloadButton.addEventListener("click", async () => {
     *     await viewer.download();
     * })
     * ```
     * @param {ExportOptions | null} [options]
     * @returns {Promise<any>}
     */
    download(options) {
        const ret = wasm.perspectiveviewerelement_download(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Remove a [`Client`] from this `<perspective-viewer>` and dispose every
     * panel bound to it (each panel's `View` is deleted and its `Table`
     * reference released).
     *
     * # Arguments
     *
     * - `options` - An optional `{client?: string}` dict naming the client to
     *   eject; the active panel's client when omitted.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await viewer.eject();
     * await viewer.eject({client: "remote"});
     * ```
     * @param {ClientOptions | null} [options]
     * @returns {Promise<any>}
     */
    eject(options) {
        const ret = wasm.perspectiveviewerelement_eject(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Exports this viewer's internal [`View`] as a JavaSript data, the
     * exact type of which depends on the `method` but defaults to `String`
     * in CSV format.
     *
     * This method is only really useful for the `"plugin"` method, which
     * will use the configured plugin's export (e.g. PNG for
     * `@perspective-dev/viewer-charts`). Otherwise, prefer to call the
     * equivalent method on the underlying [`View`] directly.
     *
     * # Arguments
     *
     * - `method` - The `ExportMethod` to use to render the data to download.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const data = await viewer.export("plugin");
     * ```
     * @param {ExportOptions | null} [options]
     * @returns {Promise<any>}
     */
    export(options) {
        const ret = wasm.perspectiveviewerelement_export(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Flush any pending modifications to this `<perspective-viewer>`.  Since
     * `<perspective-viewer>`'s API is almost entirely `async`, it may take
     * some milliseconds before any user-initiated changes to the [`View`]
     * affects the rendered element.  If you want to make sure all pending
     * actions have been rendered, call and await [`Self::flush`].
     *
     * [`Self::flush`] will resolve immediately if there is no [`Table`] set.
     *
     * # JavaScript Examples
     *
     * In this example, [`Self::restore`] is called without `await`, but the
     * eventual render which results from this call can still be awaited by
     * immediately awaiting [`Self::flush`] instead.
     *
     * ```javascript
     * viewer.restore(config);
     * await viewer.flush();
     * ```
     * @returns {Promise<any>}
     */
    flush() {
        const ret = wasm.perspectiveviewerelement_flush(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * The id of the active panel — the one the settings panel and status-bar
     * toolbar target — or `null` when the element has zero panels.
     * @returns {any}
     */
    getActivePanel() {
        const ret = wasm.perspectiveviewerelement_getActivePanel(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Get an `Array` of all of the plugin custom elements registered for this
     * element. This may not include plugins which called
     * [`registerPlugin`] after the host has rendered for the first time.
     * @returns {Array<any>}
     */
    getAllPlugins() {
        const ret = wasm.perspectiveviewerelement_getAllPlugins(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Get the underlying [`Client`] for this viewer (as passed to, or
     * associated with the [`Table`] passed to,
     * [`PerspectiveViewerElement::load`]).
     *
     * # Arguments
     *
     * - `wait_for_client` - whether to wait for
     *   [`PerspectiveViewerElement::load`] to be called, or fail immediately
     *   if [`PerspectiveViewerElement::load`] has not yet been called.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const client = await viewer.getClient();
     * ```
     * @param {GetClientOptions | null} [options]
     * @returns {Promise<any>}
     */
    getClient(options) {
        const ret = wasm.perspectiveviewerelement_getClient(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Get this viewer's edit port for the named panel's [`Table`] (see
     * [`Table::update`] for details on ports), or the active panel when
     * `panel` is omitted.
     * @param {PanelOptions | null} [options]
     * @returns {number}
     */
    getEditPort(options) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectiveviewerelement_getEditPort(retptr, this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
            var r0 = getDataViewMemory0().getFloat64(retptr + 8 * 0, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            var r3 = getDataViewMemory0().getInt32(retptr + 4 * 3, true);
            if (r3) {
                throw takeObject(r2);
            }
            return r0;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Get the ids of all panels in this viewer's layout, in insertion order.
     * @returns {Array<any>}
     */
    getPanelNames() {
        const ret = wasm.perspectiveviewerelement_getPanelNames(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Gets a plugin Custom Element with the `name` field, or get the active
     * plugin if no `name` is provided.
     *
     * # Arguments
     *
     * - `name` - The `name` property of a perspective plugin Custom Element,
     *   or `None` for the active plugin's Custom Element.
     * @param {string | null} [name]
     * @returns {any}
     */
    getPlugin(name) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            var ptr0 = isLikeNone(name) ? 0 : passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            var len0 = WASM_VECTOR_LEN;
            wasm.perspectiveviewerelement_getPlugin(retptr, this.__wbg_ptr, ptr0, len0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            return takeObject(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Get render statistics. Some fields of the returned stats object are
     * relative to the last time [`PerspectiveViewerElement::getRenderStats`]
     * was called, ergo calling this method resets these fields.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const {virtual_fps, actual_fps} = await viewer.getRenderStats();
     * ```
     * @param {PanelOptions | null} [options]
     * @returns {any}
     */
    getRenderStats(options) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectiveviewerelement_getRenderStats(retptr, this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            return takeObject(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Return a [`perspective_js::JsViewWindow`] for the currently selected
     * region of the named panel, or the active panel when `panel` is omitted.
     * @param {PanelOptions | null} [options]
     * @returns {ViewWindow | undefined}
     */
    getSelection(options) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectiveviewerelement_getSelection(retptr, this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            return takeObject(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Get the underlying [`Table`] for this viewer (as passed to
     * [`PerspectiveViewerElement::load`] or as the `table` field to
     * [`PerspectiveViewerElement::restore`]).
     *
     * # Arguments
     *
     * - `wait_for_table` - whether to wait for
     *   [`PerspectiveViewerElement::load`] to be called, or fail immediately
     *   if [`PerspectiveViewerElement::load`] has not yet been called.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const table = await viewer.getTable();
     * ```
     * @param {GetTableOptions | null} [options]
     * @returns {Promise<any>}
     */
    getTable(options) {
        const ret = wasm.perspectiveviewerelement_getTable(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * @returns {Promise<any>}
     */
    getThemes() {
        const ret = wasm.perspectiveviewerelement_getThemes(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Get a [`View`] for a panel of this viewer, as currently configured by
     * the user.
     *
     * # Arguments
     *
     * - `mode` - which [`View`] to return:
     *   - `"live"` (default) - the panel's own [`View`], which the viewer
     *     replaces on every config change and deletes when auto-paused or
     *     [`PerspectiveViewerElement::delete`]d, so calls on it may fail with
     *     "View already deleted", it must never be deleted by the caller, and
     *     it rejects with `No View for panel "<name>"` while none is bound
     *     (auto-paused or not yet rendered).
     *   - `"clone"` - a new [`View`] built from the panel's effective config
     *     (global filter included), independent of the render lifecycle, which
     *     the caller owns and must [`View::delete`].
     *   - `"auto"` - `"live"` when the panel has a bound [`View`], else
     *     `"clone"`.
     * - `panel` - the target panel, or the active panel when omitted.
     *
     * Plugins which rely on the live [`View`] for their
     * [`HTMLPerspectiveViewerPluginElement::draw`] implementations should
     * treat "View already deleted" as a _cancellation_ by silently aborting.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const view = await viewer.getView();
     * ```
     *
     * Export every panel without borrowing the viewer's own [`View`]:
     *
     * ```javascript
     * for (const panel of viewer.getPanelNames()) {
     *     const view = await viewer.getView({ panel, mode: "clone" });
     *     const arrow = await view.to_arrow();
     *     await view.delete();
     * }
     * ```
     * @param {GetViewOptions | null} [options]
     * @returns {Promise<any>}
     */
    getView(options) {
        const ret = wasm.perspectiveviewerelement_getView(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Get a copy of the [`ViewConfig`] for the current [`View`]. This is
     * non-blocking as it does not need to access the plugin (unlike
     * [`PerspectiveViewerElement::save`]), and also makes no API calls to the
     * server (unlike [`PerspectiveViewerElement::getView`] followed by
     * [`View::get_config`])
     * @param {PanelOptions | null} [options]
     * @returns {Promise<any>}
     */
    getViewConfig(options) {
        const ret = wasm.perspectiveviewerelement_getViewConfig(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Loads a [`Client`], or optionally [`Table`], or optionally a Javascript
     * `Promise` which returns a [`Client`] or [`Table`], in this viewer.
     *
     * Loading a [`Client`] does not render, but subsequent calls to
     * [`PerspectiveViewerElement::restore`] will use this [`Client`] to look
     * up the proviced `table` name field for the provided
     * [`ViewerConfigUpdate`].
     *
     * Loading a [`Table`] is equivalent to subsequently calling
     * [`Self::restore`] with the `table` field set to [`Table::get_name`], and
     * will render the UI in its default state when [`Self::load`] resolves.
     * If you plan to call [`Self::restore`] anyway, prefer passing a
     * [`Client`] argument to [`Self::load`] as it will conserve one render.
     *
     * When [`PerspectiveViewerElement::load`] resolves, the first frame of the
     * UI + visualization is guaranteed to have been drawn. Awaiting the result
     * of this method in a `try`/`catch` block will capture any errors
     * thrown during the loading process, or from the [`Client`] `Promise`
     * itself.
     *
     * [`PerspectiveViewerElement::load`] may also be called with a [`Table`],
     * which is equivalent to:
     *
     * ```javascript
     * await viewer.load(await table.get_client());
     * await viewer.restore({name: await table.get_name()})
     * ```
     *
     * If you plan to call [`PerspectiveViewerElement::restore`] immediately
     * after [`PerspectiveViewerElement::load`] yourself, as is commonly
     * done when loading and configuring a new `<perspective-viewer>`, you
     * should use a [`Client`] as an argument and set the `table` field in the
     * restore call as
     *
     * A [`Table`] can be created using the
     * [`@perspective-dev/client`](https://www.npmjs.com/package/@perspective-dev/client)
     * library from NPM (see [`perspective_js`] documentation for details).
     *
     * # JavaScript Examples
     *
     * ```javascript
     * import perspective from "@perspective-dev/client";
     *
     * const worker = await perspective.worker();
     * viewer.load(worker);
     * ```
     *
     * ... or
     *
     * ```javascript
     * const table = await worker.table(data, {name: "superstore"});
     * viewer.load(table);
     * ```
     *
     * Complete example:
     *
     * ```javascript
     * const viewer = document.createElement("perspective-viewer");
     * const worker = await perspective.worker();
     *
     * await worker.table("x\n1", {name: "table_one"});
     * await viewer.load(worker);
     * await viewer.restore({table: "table_one", columns: ["x"]});
     * ```
     *
     * ... or, if you don't want to pass your own arguments to `restore`:
     *
     * ```javascript
     * const viewer = document.createElement("perspective-viewer");
     * const worker = await perspective.worker();
     *
     * const table = await worker.table("x\n1", {name: "table_one"});
     * await viewer.load(table);
     * ```
     * @param {Client | Table | Promise<Client | Table>} client
     * @returns {Promise<any>}
     */
    load(client) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectiveviewerelement_load(retptr, this.__wbg_ptr, addHeapObject(client));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            return takeObject(r0);
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {HTMLElement} elem
     */
    constructor(elem) {
        const ret = wasm.perspectiveviewerelement_new(addHeapObject(elem));
        this.__wbg_ptr = ret >>> 0;
        PerspectiveViewerElementFinalization.register(this, this.__wbg_ptr, this);
        return this;
    }
    /**
     * Remove the panel with id `name` from the layout, disposing its engines
     * (its `View` is deleted and its `Table` reference released). The last
     * remaining panel cannot be removed (resolves as a no-op). Resolves
     * after the panel's teardown run completes, carrying any teardown
     * error — previously fire-and-forget and silently dropped (invariant
     * I6). See also [`Self::addPanel`].
     * @param {string} name
     * @returns {Promise<any>}
     */
    removePanel(name) {
        const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.perspectiveviewerelement_removePanel(this.__wbg_ptr, ptr0, len0);
        return takeObject(ret);
    }
    /**
     * Reset a panel's `ViewerConfig` to its data-relative default.
     *
     * Without a `panel`, this is ELEMENT-LEVEL: EVERY panel is reset and the
     * cross-filter overlay cleared (symmetric with
     * [`Self::saveWorkspace`] / [`Self::restoreWorkspace`]). With `{panel}`,
     * only that panel is reset — the other panels and the overlay are left
     * untouched.
     *
     * # Arguments
     *
     * - `reset_all` - If set, will clear expressions and column settings as
     *   well.
     * - `options` - An optional `{panel?: string}`; the panel to reset, or
     *   every panel when omitted.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await viewer.reset();                     // every panel
     * await viewer.reset(true, {panel: "p1"});  // just "p1", + expressions
     * ```
     * @param {boolean | null} [reset_all]
     * @param {PanelOptions | null} [options]
     * @returns {Promise<any>}
     */
    reset(reset_all, options) {
        const ret = wasm.perspectiveviewerelement_reset(this.__wbg_ptr, isLikeNone(reset_all) ? 0xFFFFFF : reset_all ? 1 : 0, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * If this element is in an _errored_ state, this method will clear it and
     * re-render. Calling this method is equivalent to clicking the error reset
     * button in the UI.
     * @returns {Promise<any>}
     */
    resetError() {
        const ret = wasm.perspectiveviewerelement_resetError(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Set the available theme names available in the status bar UI.
     *
     * Calling [`Self::resetThemes`] may cause the current theme to switch,
     * if e.g. the new theme set does not contain the current theme.
     *
     * # JavaScript Examples
     *
     * Restrict `<perspective-viewer>` theme options to _only_ default light
     * and dark themes, regardless of what is auto-detected from the page's
     * CSS:
     *
     * ```javascript
     * viewer.resetThemes(["Pro Light", "Pro Dark"])
     * ```
     * @param {any[] | null} [themes]
     * @returns {Promise<any>}
     */
    resetThemes(themes) {
        var ptr0 = isLikeNone(themes) ? 0 : passArrayJsValueToWasm0(themes, wasm.__wbindgen_export);
        var len0 = WASM_VECTOR_LEN;
        const ret = wasm.perspectiveviewerelement_resetThemes(this.__wbg_ptr, ptr0, len0);
        return takeObject(ret);
    }
    /**
     * Recalculate the viewer's dimensions and redraw.
     *
     * Use this method to tell `<perspective-viewer>` its dimensions have
     * changed when auto-size mode has been disabled via [`Self::setAutoSize`].
     * [`Self::resize`] resolves when the resize-initiated redraw of this
     * element has completed.
     *
     * # Arguments
     *
     * - `options` - An optional object with the following fields:
     *   - `dimensions` - An optional object `{width, height}` providing
     *     explicit size hints (in pixels) for the plugin container. When
     *     provided, the plugin element will be temporarily sized to these
     *     dimensions during resize, then reset.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await viewer.resize()
     * await viewer.resize({dimensions: {width: 800, height: 600}})
     * ```
     * @param {any | null} [options]
     * @returns {Promise<any>}
     */
    resize(options) {
        const ret = wasm.perspectiveviewerelement_resize(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Restore a single panel from a full/partial
     * [`perspective_js::JsViewConfig`] (its user-configurable state, including
     * the `Table` name) — the active panel, or a specific panel via the
     * optional `{panel}` selector.
     *
     * If `panel` names no existing panel, a NEW panel is created with that id
     * and the config restored into it (an upsert). Creation REQUIRES a
     * `table` — the same rule [`Self::addPanel`] enforces in its argument
     * type — and a would-create call without one REJECTS before any state
     * (including `settings`) is applied: with no panel to target and no
     * `table`, the patch has no data arrival path. In particular, on an
     * element with zero panels every `restore` must carry a `table`.
     *
     * On an empty element with a pending [`Self::load`] whose payload is not
     * yet classified, the active-target form (no `panel`) instead claims and
     * restores into that load's reserved first panel — see [`Self::load`].
     *
     * This restores a SINGLE panel; a workspace config (with a `panels`
     * map) must be applied via [`Self::restoreWorkspace`] — its `panels` /
     * `layout` keys are ignored here.
     *
     * One of the best ways to use [`Self::restore`] is by first configuring
     * a `<perspective-viewer>` as you wish, then using either the `Debug`
     * panel or "Copy" -> "config.json" from the toolbar menu to snapshot
     * the [`Self::restore`] argument as JSON.
     *
     * # Arguments
     *
     * - `update` - The config to restore to, as returned by [`Self::save`] in
     *   either "json", "string" or "arraybuffer" format.
     * - `options.panel` - The panel to target, or the active panel when
     *   omitted.
     * - `options.suppress_errors` - when `true`, a failed restore only rejects
     *   the returned `Promise` and rolls the view config back, leaving the
     *   viewer's visible error state and any element-level state the call
     *   already applied (theme, title, plugin, a successful `table` change)
     *   untouched.
     * - `options.wait_for_table` - when `true`, a `table` no loaded client
     *   hosts yet leaves the panel empty and pending until the table is
     *   created, instead of the default error.
     *
     * # JavaScript Examples
     *
     * Loads a default plugin for the table named `"superstore"`:
     *
     * ```javascript
     * await viewer.restore({table: "superstore"});
     * ```
     *
     * Apply a `group_by` to the same `viewer` element, without
     * modifying/resetting other fields - you can omit the `table` field,
     * this has already been set once and is not modified:
     *
     * ```javascript
     * await viewer.restore({group_by: ["State"]});
     * ```
     * @param {ViewerConfigUpdate} update
     * @param {RestoreOptions | null} [options]
     * @returns {Promise<void>}
     */
    restore(update, options) {
        const ret = wasm.perspectiveviewerelement_restore(this.__wbg_ptr, addHeapObject(update), isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Apply a [`WorkspaceConfigUpdate`] to the element — the multi-panel
     * counterpart of [`Self::restore`] and like it a field-wise update, where
     * an absent field is unchanged, `null` resets and a value replaces.
     *
     * # Arguments
     *
     * - `update` - The workspace config update: a [`Self::saveWorkspace`]
     *   token replaces every panel (`panels` is the whole set), while a token
     *   without `panels` keeps them and `layout`, `active` and `masters` name
     *   them by their existing ids.
     * - `options.wait_for_table` - when `true`, a panel whose `table` no
     *   loaded client hosts yet is created empty and pending until the table
     *   is created, instead of the default error.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await viewer.restoreWorkspace(await otherViewer.saveWorkspace());
     * ```
     *
     * Cross-filter the current panels without re-creating them:
     *
     * ```javascript
     * await viewer.restoreWorkspace({ global_filters: [["Region", "==", "West"]] });
     * ```
     * @param {WorkspaceConfigUpdate} update
     * @param {RestoreWorkspaceOptions | null} [options]
     * @returns {Promise<void>}
     */
    restoreWorkspace(update, options) {
        const ret = wasm.perspectiveviewerelement_restoreWorkspace(this.__wbg_ptr, addHeapObject(update), isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Restyle all plugins from current document.
     *
     * <div class="warning">
     *
     * [`Self::restyleElement`] _must_ be called for many runtime changes to
     * CSS properties to be reflected in an already-rendered
     * `<perspective-viewer>`.
     *
     * </div>
     *
     * # JavaScript Examples
     *
     * ```javascript
     * viewer.style = "--psp--color: red";
     * await viewer.restyleElement();
     * ```
     * @returns {Promise<any>}
     */
    restyleElement() {
        const ret = wasm.perspectiveviewerelement_restyleElement(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Save a single panel's user-configurable state as a [`ViewerConfig`], one
     * which can be restored via [`Self::restore`] — the active panel, or a
     * specific panel via the optional `{panel}` selector.
     *
     * This saves a SINGLE panel; to snapshot the ENTIRE element (every panel +
     * layout + cross-filters) use [`Self::saveWorkspace`].
     *
     * # Arguments
     *
     * - `options` - An optional `{panel?: string}`; the panel to save, or the
     *   active panel when omitted.
     *
     * # JavaScript Examples
     *
     * Get the current `group_by` setting:
     *
     * ```javascript
     * const {group_by} = await viewer.save();
     * ```
     *
     * Reset workflow attached to an external button `myResetButton`:
     *
     * ```javascript
     * const token = await viewer.save();
     * myResetButton.addEventListener("click", async () => {
     *     await viewer.restore(token);
     * });
     * ```
     * @param {PanelOptions | null} [options]
     * @returns {Promise<ViewerConfig>}
     */
    save(options) {
        const ret = wasm.perspectiveviewerelement_save(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Save the ENTIRE element to a [`WorkspaceConfig`]
     * (`{version, active?, layout, panels, palette?, ...}`) — the
     * multi-panel counterpart of [`Self::save`]. Unlike [`Self::save`]
     * (which emits a single `ViewerConfig` for one panel), this ALWAYS
     * emits the workspace format, restorable via
     * [`Self::restoreWorkspace`].
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const token = await viewer.saveWorkspace();
     * await viewer.restoreWorkspace(token);
     * ```
     * @param {SaveWorkspaceOptions | null} [options]
     * @returns {Promise<WorkspaceConfig>}
     */
    saveWorkspace(options) {
        const ret = wasm.perspectiveviewerelement_saveWorkspace(this.__wbg_ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Make the panel with id `name` the active panel, re-targeting the
     * settings panel and status-bar toolbar (and the root's
     * session/renderer subscriptions) to its engines. Resolves after the
     * activation-chrome redraws on both sides of the switch have completed
     * (invariant I6).
     * @param {string} name
     * @returns {Promise<any>}
     */
    setActivePanel(name) {
        const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.perspectiveviewerelement_setActivePanel(this.__wbg_ptr, ptr0, len0);
        return takeObject(ret);
    }
    /**
     * Sets the auto-pause behavior of this component.
     *
     * When `true`, this `<perspective-viewer>` will skip rendering
     * whenever it cannot be seen — tracked via an `IntersectionObserver`
     * on itself (scrolled out of the viewport, `display: none`) combined
     * with the document's page visibility (backgrounded browser tab,
     * minimized window). Auto-pause is enabled by default.
     *
     * # Arguments
     *
     * - `autopause` Whether to enable `auto-pause` behavior or not.
     *
     * # JavaScript Examples
     *
     * Disable auto-size behavior:
     *
     * ```javascript
     * viewer.setAutoPause(false);
     * ```
     * @param {boolean} autopause
     * @returns {Promise<any>}
     */
    setAutoPause(autopause) {
        const ret = wasm.perspectiveviewerelement_setAutoPause(this.__wbg_ptr, autopause);
        return takeObject(ret);
    }
    /**
     * Sets the auto-size behavior of this component.
     *
     * When `true`, this `<perspective-viewer>` will register a
     * `ResizeObserver` on itself and call [`Self::resize`] whenever its own
     * dimensions change. However, when embedded in a larger application
     * context, you may want to call [`Self::resize`] manually to avoid
     * over-rendering; in this case auto-sizing can be disabled via this
     * method. Auto-size behavior is enabled by default.
     *
     * # Arguments
     *
     * - `autosize` - Whether to enable `auto-size` behavior or not.
     *
     * # JavaScript Examples
     *
     * Disable auto-size behavior:
     *
     * ```javascript
     * viewer.setAutoSize(false);
     * ```
     * @param {boolean} autosize
     */
    setAutoSize(autosize) {
        wasm.perspectiveviewerelement_setAutoSize(this.__wbg_ptr, autosize);
    }
    /**
     * Set the selection [`perspective_js::JsViewWindow`] for the named panel,
     * or the active panel when `panel` is omitted.
     * @param {ViewWindow | null} [window]
     * @param {PanelOptions | null} [options]
     */
    setSelection(window, options) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.perspectiveviewerelement_setSelection(retptr, this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window), isLikeNone(options) ? 0 : addHeapObject(options));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Determines the render throttling behavior. Can be an integer, for
     * millisecond window to throttle render event; or, if `None`, adaptive
     * throttling will be calculated from the measured render time of the
     * last 5 frames.
     *
     * # Arguments
     *
     * - `throttle` - The throttle rate in milliseconds (f64), or `None` for
     *   adaptive throttling.
     *
     * # JavaScript Examples
     *
     * Only draws at most 1 frame/sec:
     *
     * ```rust
     * viewer.setThrottle(1000);
     * ```
     * @param {number | null} [val]
     */
    setThrottle(val) {
        wasm.perspectiveviewerelement_setThrottle(this.__wbg_ptr, !isLikeNone(val), isLikeNone(val) ? 0 : val);
    }
    /**
     * Asynchronously opens the column settings for a specific column.
     * When finished, the `<perspective-viewer>` element will emit a
     * "perspective-toggle-column-settings" CustomEvent.
     * The event's details property has two fields: `{open: bool, column_name?:
     * string}`. The CustomEvent is also fired whenever the user toggles the
     * sidebar manually.
     * @param {string} column_name
     * @param {PanelOptions | null} [options]
     * @returns {Promise<any>}
     */
    toggleColumnSettings(column_name, options) {
        const ptr0 = passStringToWasm0(column_name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.perspectiveviewerelement_toggleColumnSettings(this.__wbg_ptr, ptr0, len0, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Toggle (or force) the config panel open/closed.
     *
     * # Arguments
     *
     * - `force` - Force the state of the panel open or closed, or `None` to
     *   toggle.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await viewer.toggleConfig();
     * ```
     * @param {boolean | null} [force]
     * @returns {Promise<any>}
     */
    toggleConfig(force) {
        const ret = wasm.perspectiveviewerelement_toggleConfig(this.__wbg_ptr, isLikeNone(force) ? 0xFFFFFF : force ? 1 : 0);
        return takeObject(ret);
    }
}
if (Symbol.dispose) PerspectiveViewerElement.prototype[Symbol.dispose] = PerspectiveViewerElement.prototype.free;

export class ProxySession {
    static __wrap(ptr) {
        ptr = ptr >>> 0;
        const obj = Object.create(ProxySession.prototype);
        obj.__wbg_ptr = ptr;
        ProxySessionFinalization.register(obj, obj.__wbg_ptr, obj);
        return obj;
    }
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        ProxySessionFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_proxysession_free(ptr, 0);
    }
    /**
     * @returns {Promise<void>}
     */
    close() {
        const ptr = this.__destroy_into_raw();
        const ret = wasm.proxysession_close(ptr);
        return takeObject(ret);
    }
    /**
     * @param {any} value
     * @returns {Promise<void>}
     */
    handle_request(value) {
        const ret = wasm.proxysession_handle_request(this.__wbg_ptr, addHeapObject(value));
        return takeObject(ret);
    }
    /**
     * @param {Client} client
     * @param {Function} on_response
     */
    constructor(client, on_response) {
        try {
            _assertClass(client, Client);
            const ret = wasm.proxysession_new(client.__wbg_ptr, addBorrowedObject(on_response));
            this.__wbg_ptr = ret >>> 0;
            ProxySessionFinalization.register(this, this.__wbg_ptr, this);
            return this;
        } finally {
            heap[stack_pointer++] = undefined;
        }
    }
}
if (Symbol.dispose) ProxySession.prototype[Symbol.dispose] = ProxySession.prototype.free;

export class Table {
    static __wrap(ptr) {
        ptr = ptr >>> 0;
        const obj = Object.create(Table.prototype);
        obj.__wbg_ptr = ptr;
        TableFinalization.register(obj, obj.__wbg_ptr, obj);
        return obj;
    }
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        TableFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_table_free(ptr, 0);
    }
    /**
     * @returns {string}
     */
    __getClassname() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.table___getClassname(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var v1 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v1;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Removes all the rows in the [`Table`], but preserves everything else
     * including the schema, index, and any callbacks or registered
     * [`View`] instances.
     *
     * Calling [`Table::clear`], like [`Table::update`] and [`Table::remove`],
     * will trigger an update event to any registered listeners via
     * [`View::on_update`].
     * @returns {Promise<void>}
     */
    clear() {
        const ret = wasm.table_clear(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Returns the column names of this [`Table`] in "natural" order (the
     * ordering implied by the input format).
     *
     *  # JavaScript Examples
     *
     *  ```javascript
     *  const columns = await table.columns();
     *  ```
     * @returns {Promise<any>}
     */
    columns() {
        const ret = wasm.table_columns(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Delete this [`Table`] and cleans up associated resources.
     *
     * [`Table`]s do not stop consuming resources or processing updates when
     * they are garbage collected in their host language - you must call
     * this method to reclaim these.
     *
     * # Arguments
     *
     * - `options` An options dictionary.
     *     - `lazy` Whether to delete this [`Table`] _lazily_. When false (the
     *       default), the delete will occur immediately, assuming it has no
     *       [`View`] instances registered to it (which must be deleted first,
     *       otherwise this method will throw an error). When true, the
     *       [`Table`] will only be marked for deltion once its [`View`]
     *       dependency count reaches 0.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const table = await client.table("x,y\n1,2\n3,4");
     *
     * // ...
     *
     * await table.delete({ lazy: true });
     * ```
     * @param {DeleteOptions | null} [options]
     * @returns {Promise<void>}
     */
    delete(options) {
        const ptr = this.__destroy_into_raw();
        const ret = wasm.table_delete(ptr, isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Get a copy of the [`Client`] this [`Table`] came from.
     * @returns {Promise<Client>}
     */
    get_client() {
        const ret = wasm.table_get_client(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Returns the name of the index column for the table.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const table = await client.table("x,y\n1,2\n3,4", { index: "x" });
     * const index = table.get_index(); // "x"
     * ```
     * @returns {Promise<string>}
     */
    get_index() {
        const ret = wasm.table_get_index(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Returns the user-specified row limit for this table.
     * @returns {Promise<number | undefined>}
     */
    get_limit() {
        const ret = wasm.table_get_limit(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Returns the user-specified name for this table, or the auto-generated
     * name if a name was not specified when the table was created.
     * @returns {Promise<string>}
     */
    get_name() {
        const ret = wasm.table_get_name(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Create a unique channel ID on this [`Table`], which allows
     * `View::on_update` callback calls to be associated with the
     * `Table::update` which caused them.
     * @returns {Promise<number>}
     */
    make_port() {
        const ret = wasm.table_make_port(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Register a callback which is called exactly once, when this [`Table`] is
     * deleted with the [`Table::delete`] method.
     *
     * [`Table::on_delete`] resolves when the subscription message is sent, not
     * when the _delete_ event occurs.
     * @param {Function} on_delete
     * @returns {Promise<any>}
     */
    on_delete(on_delete) {
        const ret = wasm.table_on_delete(this.__wbg_ptr, addHeapObject(on_delete));
        return takeObject(ret);
    }
    /**
     * Removes rows from this [`Table`] with the `index` column values
     * supplied.
     *
     * # Arguments
     *
     * - `indices` - A list of `index` column values for rows that should be
     *   removed.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await table.remove([1, 2, 3]);
     * ```
     * @param {any} value
     * @param {UpdateOptions | null} [options]
     * @returns {Promise<void>}
     */
    remove(value, options) {
        const ret = wasm.table_remove(this.__wbg_ptr, addHeapObject(value), isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Removes a listener with a given ID, as returned by a previous call to
     * [`Table::on_delete`].
     * @param {number} callback_id
     * @returns {Promise<any>}
     */
    remove_delete(callback_id) {
        const ret = wasm.table_remove_delete(this.__wbg_ptr, callback_id);
        return takeObject(ret);
    }
    /**
     * Replace all rows in this [`Table`] with the input data, coerced to this
     * [`Table`]'s existing [`perspective_client::Schema`], notifying any
     * derived [`View`] and [`View::on_update`] callbacks.
     *
     * Calling [`Table::replace`] is an easy way to replace _all_ the data in a
     * [`Table`] without losing any derived [`View`] instances or
     * [`View::on_update`] callbacks. [`Table::replace`] does _not_ infer
     * data types like [`Client::table`] does, rather it _coerces_ input
     * data to the `Schema` like [`Table::update`]. If you need a [`Table`]
     * with a different `Schema`, you must create a new one.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await table.replace("x,y\n1,2");
     * ```
     * @param {any} input
     * @param {UpdateOptions | null} [options]
     * @returns {Promise<void>}
     */
    replace(input, options) {
        const ret = wasm.table_replace(this.__wbg_ptr, addHeapObject(input), isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Returns a table's [`Schema`], a mapping of column names to column types.
     *
     * The mapping of a [`Table`]'s column names to data types is referred to
     * as a [`Schema`]. Each column has a unique name and a data type, one
     * of:
     *
     * - `"boolean"` - A boolean type
     * - `"date"` - A timesonze-agnostic date type (month/day/year)
     * - `"datetime"` - A millisecond-precision datetime type in the UTC
     *   timezone
     * - `"float"` - A 64 bit float
     * - `"integer"` - A signed 32 bit integer (the integer type supported by
     *   JavaScript)
     * - `"string"` - A [`String`] data type (encoded internally as a
     *   _dictionary_)
     *
     * Note that all [`Table`] columns are _nullable_, regardless of the data
     * type.
     * @returns {Record<string, ColumnType>}
     */
    schema() {
        const ret = wasm.table_schema(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Returns the number of rows in a [`Table`].
     * @returns {Promise<number>}
     */
    size() {
        const ret = wasm.table_size(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Updates the rows of this table and any derived [`View`] instances.
     *
     * Calling [`Table::update`] will trigger the [`View::on_update`] callbacks
     * register to derived [`View`], and the call itself will not resolve until
     * _all_ derived [`View`]'s are notified.
     *
     * When updating a [`Table`] with an `index`, [`Table::update`] supports
     * partial updates, by omitting columns from the update data.
     *
     * # Arguments
     *
     * - `input` - The input data for this [`Table`]. The schema of a [`Table`]
     *   is immutable after creation, so this method cannot be called with a
     *   schema.
     * - `options` - Options for this update step - see [`UpdateOptions`].
     *
     * # JavaScript Examples
     *
     * ```javascript
     * await table.update("x,y\n1,2");
     * ```
     * @param {string | ArrayBuffer | Record<string, unknown[]> | Record<string, unknown>[] | Record<string, ColumnType>} input
     * @param {UpdateOptions | null} [options]
     * @returns {Promise<any>}
     */
    update(input, options) {
        const ret = wasm.table_update(this.__wbg_ptr, addHeapObject(input), isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Validates the given expressions.
     * @param {any} exprs
     * @returns {Promise<any>}
     */
    validate_expressions(exprs) {
        const ret = wasm.table_validate_expressions(this.__wbg_ptr, addHeapObject(exprs));
        return takeObject(ret);
    }
    /**
     * Create a new [`View`] from this table with a specified
     * [`ViewConfigUpdate`].
     *
     * See [`View`] struct.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const view = await table.view({
     *     columns: ["Sales"],
     *     aggregates: { Sales: "sum" },
     *     group_by: ["Region", "Country"],
     *     filter: [["Category", "in", ["Furniture", "Technology"]]],
     * });
     * ```
     * @param {ViewConfigUpdate | null} [config]
     * @returns {Promise<View>}
     */
    view(config) {
        const ret = wasm.table_view(this.__wbg_ptr, isLikeNone(config) ? 0 : addHeapObject(config));
        return takeObject(ret);
    }
}
if (Symbol.dispose) Table.prototype[Symbol.dispose] = Table.prototype.free;

/**
 * The [`View`] struct is Perspective's query and serialization interface. It
 * represents a query on the `Table`'s dataset and is always created from an
 * existing `Table` instance via the [`Table::view`] method.
 *
 * [`View`]s are immutable with respect to the arguments provided to the
 * [`Table::view`] method; to change these parameters, you must create a new
 * [`View`] on the same [`Table`]. However, each [`View`] is _live_ with
 * respect to the [`Table`]'s data, and will (within a conflation window)
 * update with the latest state as its parent [`Table`] updates, including
 * incrementally recalculating all aggregates, pivots, filters, etc. [`View`]
 * query parameters are composable, in that each parameter works independently
 * _and_ in conjunction with each other, and there is no limit to the number of
 * pivots, filters, etc. which can be applied.
 */
export class View {
    static __wrap(ptr) {
        ptr = ptr >>> 0;
        const obj = Object.create(View.prototype);
        obj.__wbg_ptr = ptr;
        ViewFinalization.register(obj, obj.__wbg_ptr, obj);
        return obj;
    }
    static __unwrap(jsValue) {
        if (!(jsValue instanceof View)) {
            return 0;
        }
        return jsValue.__destroy_into_raw();
    }
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        ViewFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_view_free(ptr, 0);
    }
    /**
     * @returns {View}
     */
    __get_model() {
        const ret = wasm.view___get_model(this.__wbg_ptr);
        return View.__wrap(ret);
    }
    /**
     * @returns {string}
     */
    __unsafe_get_name() {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.view___unsafe_get_name(retptr, this.__wbg_ptr);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var v1 = getCachedStringFromWasm0(r0, r1);
            if (r0 !== 0) { wasm.__wbindgen_export4(r0, r1, 1); }
            return v1;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * Collapses the `group_by` row at `row_index`.
     * @param {number} row_index
     * @returns {Promise<number>}
     */
    collapse(row_index) {
        const ret = wasm.view_collapse(this.__wbg_ptr, row_index);
        return takeObject(ret);
    }
    /**
     * Returns an array of strings containing the column paths of the [`View`]
     * without any of the source columns.
     *
     * A column path shows the columns that a given cell belongs to after
     * pivots are applied.
     * @param {ColumnWindow | null} [window]
     * @returns {Promise<any>}
     */
    column_paths(window) {
        const ret = wasm.view_column_paths(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window));
        return takeObject(ret);
    }
    /**
     * Delete this [`View`] and clean up all resources associated with it.
     * [`View`] objects do not stop consuming resources or processing
     * updates when they are garbage collected - you must call this method
     * to reclaim these.
     * @returns {Promise<void>}
     */
    delete() {
        const ptr = this.__destroy_into_raw();
        const ret = wasm.view_delete(ptr);
        return takeObject(ret);
    }
    /**
     * Returns this [`View`]'s _dimensions_, row and column count, as well as
     * those of the [`crate::Table`] from which it was derived.
     *
     * - `num_table_rows` - The number of rows in the underlying
     *   [`crate::Table`].
     * - `num_table_columns` - The number of columns in the underlying
     *   [`crate::Table`] (including the `index` column if this
     *   [`crate::Table`] was constructed with one).
     * - `num_view_rows` - The number of rows in this [`View`]. If this
     *   [`View`] has a `group_by` clause, `num_view_rows` will also include
     *   aggregated rows.
     * - `num_view_columns` - The number of columns in this [`View`]. If this
     *   [`View`] has a `split_by` clause, `num_view_columns` will include all
     *   _column paths_, e.g. the number of `columns` clause times the number
     *   of `split_by` groups.
     * @returns {Promise<any>}
     */
    dimensions() {
        const ret = wasm.view_dimensions(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Expand the `group_by` row at `row_index`.
     * @param {number} row_index
     * @returns {Promise<number>}
     */
    expand(row_index) {
        const ret = wasm.view_expand(this.__wbg_ptr, row_index);
        return takeObject(ret);
    }
    /**
     * The expression schema of this [`View`], which contains only the
     * expressions created on this [`View`]. See [`View::schema`] for
     * details.
     * @returns {Promise<any>}
     */
    expression_schema() {
        const ret = wasm.view_expression_schema(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * A copy of the config object passed to the [`Table::view`] method which
     * created this [`View`].
     * @returns {Promise<any>}
     */
    get_config() {
        const ret = wasm.view_get_config(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Calculates the [min, max] of the leaf nodes of a column `column_name`.
     *
     * # Returns
     *
     * A tuple of [min, max], whose types are column and aggregate dependent.
     * @param {string} name
     * @returns {Promise<Array<any>>}
     */
    get_min_max(name) {
        const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.view_get_min_max(this.__wbg_ptr, ptr0, len0);
        return takeObject(ret);
    }
    /**
     * The number of aggregated columns in this [`View`]. This is affected by
     * the "split_by" configuration parameter supplied to this view's
     * contructor.
     *
     * # Returns
     *
     * The number of aggregated columns.
     * @returns {Promise<number>}
     */
    num_columns() {
        const ret = wasm.view_num_columns(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * The number of aggregated rows in this [`View`]. This is affected by the
     * "group_by" configuration parameter supplied to this view's contructor.
     *
     * # Returns
     *
     * The number of aggregated rows.
     * @returns {Promise<number>}
     */
    num_rows() {
        const ret = wasm.view_num_rows(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Register a callback with this [`View`]. Whenever the [`View`] is
     * deleted, this callback will be invoked.
     * @param {Function} on_delete
     * @returns {Promise<any>}
     */
    on_delete(on_delete) {
        const ret = wasm.view_on_delete(this.__wbg_ptr, addHeapObject(on_delete));
        return takeObject(ret);
    }
    /**
     * Register a callback which is invoked whenever rows are removed from
     * this [`View`]'s [`Table`] by [`Table::remove`], with an object
     * containing `port_id` and `indices`, the removed `index` column
     * values as an Arrow of one column named after the index.
     *
     * [`Table::replace`] reports the keys it does not re-supply and
     * [`Table::clear`] reports every key. `on_remove` never fires for a
     * [`Table`] without an `index`.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const id = await view.on_remove(({ indices, port_id }) => {
     *     replica.remove(indices);
     * });
     * ```
     * @param {(data: OnRemoveData) => void} on_remove_js
     * @returns {Promise<any>}
     */
    on_remove(on_remove_js) {
        const ret = wasm.view_on_remove(this.__wbg_ptr, addHeapObject(on_remove_js));
        return takeObject(ret);
    }
    /**
     * Register a callback with this [`View`]. Whenever the view's underlying
     * table emits an update, this callback will be invoked with an object
     * containing `port_id`, indicating which port the update fired on, and
     * optionally `delta`, which is the new data that was updated for each
     * cell or each row (as an Arrow).
     *
     * # Arguments
     *
     * - `on_update` - A callback function invoked on update, which receives an
     *   object with two keys: `port_id`, indicating which port the update was
     *   triggered on, and `delta`, whose value is dependent on the mode
     *   parameter.
     * - `options` - If this is provided as `{mode: "row"}`, then `delta` is an
     *   Arrow of the updated rows. Otherwise `delta` will be `null`.
     *
     * # JavaScript Examples
     *
     * ```javascript
     * view.on_update((updated) => console.log(updated.port_id));
     * ```
     *
     * ```javascript
     * view.on_update((updated) => console.log(updated.delta), { mode: "row" });
     * ```
     * @param {(data: OnUpdateData) => void} on_update_js
     * @param {OnUpdateOptions | null} [options]
     * @returns {Promise<any>}
     */
    on_update(on_update_js, options) {
        const ret = wasm.view_on_update(this.__wbg_ptr, addHeapObject(on_update_js), isLikeNone(options) ? 0 : addHeapObject(options));
        return takeObject(ret);
    }
    /**
     * Unregister a previously registered [`View::on_delete`] callback.
     * @param {number} callback_id
     * @returns {Promise<any>}
     */
    remove_delete(callback_id) {
        const ret = wasm.view_remove_delete(this.__wbg_ptr, callback_id);
        return takeObject(ret);
    }
    /**
     * Unregister a previously registered [`View::on_remove`] callback.
     *
     * # Arguments
     *
     * - `id` - A callback `id` as returned by a reciprocal call to
     *   [`View::on_remove`].
     * @param {number} callback_id
     * @returns {Promise<void>}
     */
    remove_remove(callback_id) {
        const ret = wasm.view_remove_remove(this.__wbg_ptr, callback_id);
        return takeObject(ret);
    }
    /**
     * Unregister a previously registered update callback with this [`View`].
     *
     * # Arguments
     *
     * - `id` - A callback `id` as returned by a recipricol call to
     *   [`View::on_update`].
     * @param {number} callback_id
     * @returns {Promise<void>}
     */
    remove_update(callback_id) {
        const ret = wasm.view_remove_update(this.__wbg_ptr, callback_id);
        return takeObject(ret);
    }
    /**
     * The schema of this [`View`].
     *
     * The [`View`] schema differs from the `schema` returned by
     * [`Table::schema`]; it may have different column names due to
     * `expressions` or `columns` configs, or it maye have _different
     * column types_ due to the application og `group_by` and `aggregates`
     * config. You can think of [`Table::schema`] as the _input_ schema and
     * [`View::schema`] as the _output_ schema of a Perspective pipeline.
     * @returns {Promise<any>}
     */
    schema() {
        const ret = wasm.view_schema(this.__wbg_ptr);
        return takeObject(ret);
    }
    /**
     * Set expansion `depth` of the `group_by` tree.
     * @param {number} depth
     * @returns {Promise<void>}
     */
    set_depth(depth) {
        const ret = wasm.view_set_depth(this.__wbg_ptr, depth);
        return takeObject(ret);
    }
    /**
     * Serializes a [`View`] to the Apache Arrow data format.
     *
     * # Arguments
     *
     * - `window` - a [`ViewWindow`]; its `compression` key selects Arrow IPC
     *   body compression, `"lz4"` or `"zstd"` (uncompressed when omitted).
     *
     * # JavaScript Examples
     *
     * ```javascript
     * const arrow = await view.to_arrow({ compression: "zstd" });
     * ```
     * @param {ViewWindow | null} [window]
     * @returns {Promise<ArrayBuffer>}
     */
    to_arrow(window) {
        const ret = wasm.view_to_arrow(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window));
        return takeObject(ret);
    }
    /**
     * Serializes this [`View`] to JavaScript objects in a column-oriented
     * format.
     * @param {ViewWindow | null} [window]
     * @returns {Promise<object>}
     */
    to_columns(window) {
        const ret = wasm.view_to_columns(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window));
        return takeObject(ret);
    }
    /**
     * Serializes this [`View`] to a string of JSON data. Useful if you want to
     * save additional round trip serialize/deserialize cycles.
     * @param {ViewWindow | null} [window]
     * @returns {Promise<string>}
     */
    to_columns_string(window) {
        const ret = wasm.view_to_columns_string(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window));
        return takeObject(ret);
    }
    /**
     * Serializes this [`View`] to CSV data in a standard format.
     * @param {ViewWindow | null} [window]
     * @returns {Promise<string>}
     */
    to_csv(window) {
        const ret = wasm.view_to_csv(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window));
        return takeObject(ret);
    }
    /**
     * Serializes this [`View`] to JavaScript objects in a row-oriented
     * format.
     * @param {ViewWindow | null} [window]
     * @returns {Promise<Array<any>>}
     */
    to_json(window) {
        const ret = wasm.view_to_json(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window));
        return takeObject(ret);
    }
    /**
     * Render this `View` as a JSON string.
     * @param {ViewWindow | null} [window]
     * @returns {Promise<string>}
     */
    to_json_string(window) {
        const ret = wasm.view_to_json_string(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window));
        return takeObject(ret);
    }
    /**
     * Renders this [`View`] as an [NDJSON](https://github.com/ndjson/ndjson-spec)
     * formatted [`String`].
     * @param {ViewWindow | null} [window]
     * @returns {Promise<string>}
     */
    to_ndjson(window) {
        const ret = wasm.view_to_ndjson(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window));
        return takeObject(ret);
    }
    /**
     * Fetches columns from the [`View`] in Arrow format, decodes them, and
     * passes typed array views to `callback`. All arrays are only valid for
     * the duration of the callback — if `callback` returns a `Promise`, it
     * is awaited before the backing Arrow buffer is released, so async
     * callbacks may use the views for the full duration of the awaited
     * work (e.g. across an `await requestAnimationFrame`-backed promise).
     *
     * # Arguments
     *
     * - `window` - Optional [`TypedArrayWindow`] controlling row/column
     *   windowing and output options (e.g., `float32` mode).
     * - `callback` - A JS function called with `(names: string[], values:
     *   TypedArray[], validities: (Uint8Array|null)[], dictionaries:
     *   (string[]|null)[]) => void | Promise<void>`.
     * @param {TypedArrayWindow | null | undefined} window
     * @param {Function} callback
     * @returns {Promise<void>}
     */
    with_typed_arrays(window, callback) {
        const ret = wasm.view_with_typed_arrays(this.__wbg_ptr, isLikeNone(window) ? 0 : addHeapObject(window), addHeapObject(callback));
        return takeObject(ret);
    }
}
if (Symbol.dispose) View.prototype[Symbol.dispose] = View.prototype.free;

export class VirtualDataSlice {
    static __wrap(ptr) {
        ptr = ptr >>> 0;
        const obj = Object.create(VirtualDataSlice.prototype);
        obj.__wbg_ptr = ptr;
        VirtualDataSliceFinalization.register(obj, obj.__wbg_ptr, obj);
        return obj;
    }
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        VirtualDataSliceFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_virtualdataslice_free(ptr, 0);
    }
    /**
     * @param {Uint8Array} ipc
     */
    fromArrowIpc(ipc) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.virtualdataslice_fromArrowIpc(retptr, this.__wbg_ptr, addHeapObject(ipc));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {ViewConfigUpdate} config
     */
    constructor(config) {
        const ret = wasm.virtualdataslice_new(addHeapObject(config));
        this.__wbg_ptr = ret >>> 0;
        VirtualDataSliceFinalization.register(this, this.__wbg_ptr, this);
        return this;
    }
    /**
     * @param {string} name
     * @param {number} index
     * @param {any} val
     * @param {number | null} [group_by_index]
     */
    setBooleanCol(name, index, val, group_by_index) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.virtualdataslice_setBooleanCol(retptr, this.__wbg_ptr, ptr0, len0, index, addHeapObject(val), isLikeNone(group_by_index) ? 0x100000001 : (group_by_index) >>> 0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {string} dtype
     * @param {string} name
     * @param {number} index
     * @param {any} val
     * @param {number | null} [group_by_index]
     */
    setCol(dtype, name, index, val, group_by_index) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(dtype, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            const ptr1 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            wasm.virtualdataslice_setCol(retptr, this.__wbg_ptr, ptr0, len0, ptr1, len1, index, addHeapObject(val), isLikeNone(group_by_index) ? 0x100000001 : (group_by_index) >>> 0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {string} name
     * @param {number} index
     * @param {any} val
     * @param {number | null} [group_by_index]
     */
    setDatetimeCol(name, index, val, group_by_index) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.virtualdataslice_setDatetimeCol(retptr, this.__wbg_ptr, ptr0, len0, index, addHeapObject(val), isLikeNone(group_by_index) ? 0x100000001 : (group_by_index) >>> 0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {string} name
     * @param {number} index
     * @param {any} val
     * @param {number | null} [group_by_index]
     */
    setFloatCol(name, index, val, group_by_index) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.virtualdataslice_setFloatCol(retptr, this.__wbg_ptr, ptr0, len0, index, addHeapObject(val), isLikeNone(group_by_index) ? 0x100000001 : (group_by_index) >>> 0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {string} name
     * @param {number} index
     * @param {any} val
     * @param {number | null} [group_by_index]
     */
    setIntegerCol(name, index, val, group_by_index) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.virtualdataslice_setIntegerCol(retptr, this.__wbg_ptr, ptr0, len0, index, addHeapObject(val), isLikeNone(group_by_index) ? 0x100000001 : (group_by_index) >>> 0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
    /**
     * @param {string} name
     * @param {number} index
     * @param {any} val
     * @param {number | null} [group_by_index]
     */
    setStringCol(name, index, val, group_by_index) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len0 = WASM_VECTOR_LEN;
            wasm.virtualdataslice_setStringCol(retptr, this.__wbg_ptr, ptr0, len0, index, addHeapObject(val), isLikeNone(group_by_index) ? 0x100000001 : (group_by_index) >>> 0);
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            if (r1) {
                throw takeObject(r0);
            }
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
}
if (Symbol.dispose) VirtualDataSlice.prototype[Symbol.dispose] = VirtualDataSlice.prototype.free;

export class VirtualServer {
    __destroy_into_raw() {
        const ptr = this.__wbg_ptr;
        this.__wbg_ptr = 0;
        VirtualServerFinalization.unregister(this);
        return ptr;
    }
    free() {
        const ptr = this.__destroy_into_raw();
        wasm.__wbg_virtualserver_free(ptr, 0);
    }
    /**
     * @param {Uint8Array} bytes
     * @returns {Promise<any>}
     */
    handleRequest(bytes) {
        const ptr0 = passArray8ToWasm0(bytes, wasm.__wbindgen_export);
        const len0 = WASM_VECTOR_LEN;
        const ret = wasm.virtualserver_handleRequest(this.__wbg_ptr, ptr0, len0);
        return takeObject(ret);
    }
    /**
     * @param {VirtualServerHandler} handler
     */
    constructor(handler) {
        try {
            const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
            wasm.virtualserver_new(retptr, addHeapObject(handler));
            var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
            var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
            var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
            if (r2) {
                throw takeObject(r1);
            }
            this.__wbg_ptr = r0 >>> 0;
            VirtualServerFinalization.register(this, this.__wbg_ptr, this);
            return this;
        } finally {
            wasm.__wbindgen_add_to_stack_pointer(16);
        }
    }
}
if (Symbol.dispose) VirtualServer.prototype[Symbol.dispose] = VirtualServer.prototype.free;

/**
 * @returns {WebAssembly.Module}
 */
export function get_wasm_module() {
    try {
        const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
        wasm.get_wasm_module(retptr);
        var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
        var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
        var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
        if (r2) {
            throw takeObject(r1);
        }
        return takeObject(r0);
    } finally {
        wasm.__wbindgen_add_to_stack_pointer(16);
    }
}

/**
 * @returns {URL}
 */
export function get_worker_url() {
    try {
        const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
        wasm.get_worker_url(retptr);
        var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
        var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
        var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
        if (r2) {
            throw takeObject(r1);
        }
        return takeObject(r0);
    } finally {
        wasm.__wbindgen_add_to_stack_pointer(16);
    }
}

/**
 * Register this crate's Custom Elements in the browser's current session.
 *
 * This must occur before calling any public API methods on these Custom
 * Elements from JavaScript, as the methods themselves won't be defined yet.
 * By default, this crate does not register `PerspectiveViewerElement` (as to
 * preserve backwards-compatible synchronous API).
 * @param {WebAssembly.Module} module
 * @param {URL} url
 */
export function init(module, url) {
    wasm.init(addHeapObject(module), addHeapObject(url));
}

/**
 * Register a plugin globally.
 * @param {string} name
 */
export function registerPlugin(name) {
    const ptr0 = passStringToWasm0(name, wasm.__wbindgen_export, wasm.__wbindgen_export2);
    const len0 = WASM_VECTOR_LEN;
    wasm.registerPlugin(ptr0, len0);
}

function __wbg_get_imports() {
    const import0 = {
        __proto__: null,
        __wbg_Error_8c4e43fe74559d73: function(arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            const ret = Error(v0);
            return addHeapObject(ret);
        },
        __wbg_Number_04624de7d0e8332d: function(arg0) {
            const ret = Number(getObject(arg0));
            return ret;
        },
        __wbg_String_8f0eb39a4a4c2f66: function(arg0, arg1) {
            const ret = String(getObject(arg1));
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg___wbindgen_bigint_get_as_i64_8fcf4ce7f1ca72a2: function(arg0, arg1) {
            const v = getObject(arg1);
            const ret = typeof(v) === 'bigint' ? v : undefined;
            getDataViewMemory0().setBigInt64(arg0 + 8 * 1, isLikeNone(ret) ? BigInt(0) : ret, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, !isLikeNone(ret), true);
        },
        __wbg___wbindgen_boolean_get_bbbb1c18aa2f5e25: function(arg0) {
            const v = getObject(arg0);
            const ret = typeof(v) === 'boolean' ? v : undefined;
            return isLikeNone(ret) ? 0xFFFFFF : ret ? 1 : 0;
        },
        __wbg___wbindgen_debug_string_0bc8482c6e3508ae: function(arg0, arg1) {
            const ret = debugString(getObject(arg1));
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg___wbindgen_in_47fa6863be6f2f25: function(arg0, arg1) {
            const ret = getObject(arg0) in getObject(arg1);
            return ret;
        },
        __wbg___wbindgen_is_bigint_31b12575b56f32fc: function(arg0) {
            const ret = typeof(getObject(arg0)) === 'bigint';
            return ret;
        },
        __wbg___wbindgen_is_function_0095a73b8b156f76: function(arg0) {
            const ret = typeof(getObject(arg0)) === 'function';
            return ret;
        },
        __wbg___wbindgen_is_null_ac34f5003991759a: function(arg0) {
            const ret = getObject(arg0) === null;
            return ret;
        },
        __wbg___wbindgen_is_object_5ae8e5880f2c1fbd: function(arg0) {
            const val = getObject(arg0);
            const ret = typeof(val) === 'object' && val !== null;
            return ret;
        },
        __wbg___wbindgen_is_string_cd444516edc5b180: function(arg0) {
            const ret = typeof(getObject(arg0)) === 'string';
            return ret;
        },
        __wbg___wbindgen_is_undefined_9e4d92534c42d778: function(arg0) {
            const ret = getObject(arg0) === undefined;
            return ret;
        },
        __wbg___wbindgen_jsval_eq_11888390b0186270: function(arg0, arg1) {
            const ret = getObject(arg0) === getObject(arg1);
            return ret;
        },
        __wbg___wbindgen_jsval_loose_eq_9dd77d8cd6671811: function(arg0, arg1) {
            const ret = getObject(arg0) == getObject(arg1);
            return ret;
        },
        __wbg___wbindgen_number_get_8ff4255516ccad3e: function(arg0, arg1) {
            const obj = getObject(arg1);
            const ret = typeof(obj) === 'number' ? obj : undefined;
            getDataViewMemory0().setFloat64(arg0 + 8 * 1, isLikeNone(ret) ? 0 : ret, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, !isLikeNone(ret), true);
        },
        __wbg___wbindgen_string_get_72fb696202c56729: function(arg0, arg1) {
            const obj = getObject(arg1);
            const ret = typeof(obj) === 'string' ? obj : undefined;
            var ptr1 = isLikeNone(ret) ? 0 : passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            var len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg___wbindgen_throw_be289d5034ed271b: function(arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            throw new Error(v0);
        },
        __wbg__wbg_cb_unref_d9b87ff7982e3b21: function(arg0) {
            getObject(arg0)._wbg_cb_unref();
        },
        __wbg_abort_2f0584e03e8e3950: function(arg0) {
            getObject(arg0).abort();
        },
        __wbg_addEventListener_3acb0aad4483804c: function() { return handleError(function (arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).addEventListener(v0, getObject(arg3));
        }, arguments); },
        __wbg_addEventListener_5ef04ffb1d3af066: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).addEventListener(v0, getObject(arg3), arg4 !== 0);
        }, arguments); },
        __wbg_addEventListener_c917b5aafbcf493f: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).addEventListener(v0, getObject(arg3), getObject(arg4));
        }, arguments); },
        __wbg_add_5be83378df680c25: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).add(v0);
        }, arguments); },
        __wbg_appendChild_dea38765a26d346d: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).appendChild(getObject(arg1));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_append_a992ccc37aa62dc4: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            getObject(arg0).append(v0, v1);
        }, arguments); },
        __wbg_apply_2e22c45cb4f12415: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = Reflect.apply(getObject(arg0), getObject(arg1), getObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_apply_ada2ee1a60ac7b3c: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = getObject(arg0).apply(getObject(arg1), getObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_asyncIterator_58dd45e58aa3721e: function() {
            const ret = Symbol.asyncIterator;
            return addHeapObject(ret);
        },
        __wbg_at_dfc235641cc0e40c: function(arg0, arg1) {
            const ret = getObject(arg0).at(arg1);
            return addHeapObject(ret);
        },
        __wbg_attachShadow_d31cd520620b24d6: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).attachShadow(getObject(arg1));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_blur_07f34335e06e5234: function() { return handleError(function (arg0) {
            getObject(arg0).blur();
        }, arguments); },
        __wbg_body_3a0b4437dadea6bf: function(arg0) {
            const ret = getObject(arg0).body;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_body_f67922363a220026: function(arg0) {
            const ret = getObject(arg0).body;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_bootstrap_bcc629c36202c787: function(arg0, arg1, arg2, arg3, arg4, arg5) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            const ret = bootstrap(getObject(arg0), v0, v1, takeObject(arg5));
            return addHeapObject(ret);
        },
        __wbg_bubbles_ad88192d3c29e6f9: function(arg0) {
            const ret = getObject(arg0).bubbles;
            return ret;
        },
        __wbg_buffer_26d0910f3a5bc899: function(arg0) {
            const ret = getObject(arg0).buffer;
            return addHeapObject(ret);
        },
        __wbg_button_d86841d0a03adc44: function(arg0) {
            const ret = getObject(arg0).button;
            return ret;
        },
        __wbg_cache_key_b02185c5bc89ef2a: function(arg0) {
            const ret = getObject(arg0).__yew_subtree_cache_key;
            return isLikeNone(ret) ? 0x100000001 : (ret) >>> 0;
        },
        __wbg_calculatePath_cd9a0a6d046055b6: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).calculatePath(v0);
            return addHeapObject(ret);
        },
        __wbg_calculatePresizePaths_28421b3816152acf: function(arg0) {
            const ret = getObject(arg0).calculatePresizePaths();
            return addHeapObject(ret);
        },
        __wbg_call_389efe28435a9388: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).call(getObject(arg1));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_call_41bedb84c3e5c0c9: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4, arg5) {
            const ret = getObject(arg0).call(getObject(arg1), getObject(arg2), getObject(arg3), getObject(arg4), getObject(arg5));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_call_4708e0c13bdc8e95: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = getObject(arg0).call(getObject(arg1), getObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_call_812d25f1510c13c8: function() { return handleError(function (arg0, arg1, arg2, arg3) {
            const ret = getObject(arg0).call(getObject(arg1), getObject(arg2), getObject(arg3));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_cancelBubble_d93ec09e9c46cd6f: function(arg0) {
            const ret = getObject(arg0).cancelBubble;
            return ret;
        },
        __wbg_checked_04db83ac6810bc82: function(arg0) {
            const ret = getObject(arg0).checked;
            return ret;
        },
        __wbg_childNodes_75d35de33c9f6fbb: function(arg0) {
            const ret = getObject(arg0).childNodes;
            return addHeapObject(ret);
        },
        __wbg_children_54099e7692eefa29: function(arg0) {
            const ret = getObject(arg0).children;
            return addHeapObject(ret);
        },
        __wbg_classList_1a87c34c6d81421e: function(arg0) {
            const ret = getObject(arg0).classList;
            return addHeapObject(ret);
        },
        __wbg_className_2a1be685f5d518d5: function(arg0, arg1) {
            const ret = getObject(arg1).className;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_clear_e92315b79199e73e: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).clear();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_click_0e9c20848b655ed3: function(arg0) {
            getObject(arg0).click();
        },
        __wbg_clientHeight_6432ff0d61ccfe7d: function(arg0) {
            const ret = getObject(arg0).clientHeight;
            return ret;
        },
        __wbg_clientX_a3c5f4ff30e91264: function(arg0) {
            const ret = getObject(arg0).clientX;
            return ret;
        },
        __wbg_clientY_e28509acb9b4a42a: function(arg0) {
            const ret = getObject(arg0).clientY;
            return ret;
        },
        __wbg_client_new: function(arg0) {
            const ret = Client.__wrap(arg0);
            return addHeapObject(ret);
        },
        __wbg_clipboard_98c5a32249fa8416: function(arg0) {
            const ret = getObject(arg0).clipboard;
            return addHeapObject(ret);
        },
        __wbg_cloneNode_b85e9102a9a31b29: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).cloneNode(arg1 !== 0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_cloneNode_eaf4ea08ebf633a5: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).cloneNode();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_closest_11206fe6e0fb4e4e: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).closest(v0);
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        }, arguments); },
        __wbg_code_dee0dae4730408e1: function(arg0, arg1) {
            const ret = getObject(arg1).code;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_column_config_schema_70ce008fc8aab6f1: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            var v2 = getCachedStringFromWasm0(arg5, arg6);
            const ret = getObject(arg0).column_config_schema(v0, v1, v2, getObject(arg7), getObject(arg8), getObject(arg9));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_composedPath_9154ab2547c668d5: function(arg0) {
            const ret = getObject(arg0).composedPath();
            return addHeapObject(ret);
        },
        __wbg_contains_1056459c33f961e8: function(arg0, arg1) {
            const ret = getObject(arg0).contains(getObject(arg1));
            return ret;
        },
        __wbg_contains_6f4d5df35ef8a13a: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).contains(v0);
            return ret;
        },
        __wbg_contentRect_dfad24916e198802: function(arg0) {
            const ret = getObject(arg0).contentRect;
            return addHeapObject(ret);
        },
        __wbg_createElementNS_ee00621496b30ec2: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            const ret = getObject(arg0).createElementNS(v0, v1);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_createElement_49f60fdcaae809c8: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).createElement(v0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_createObjectURL_918185db6a10a0c8: function() { return handleError(function (arg0, arg1) {
            const ret = URL.createObjectURL(getObject(arg1));
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        }, arguments); },
        __wbg_createTextNode_55029686c9591bf3: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).createTextNode(v0);
            return addHeapObject(ret);
        },
        __wbg_cssRules_554f47f3049cf066: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).cssRules;
            return addHeapObject(ret);
        }, arguments); },
        __wbg_cssText_8f3f27e60c824421: function(arg0, arg1) {
            const ret = getObject(arg1).cssText;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_currentTarget_d1f347b3b9bddd6e: function(arg0) {
            const ret = getObject(arg0).currentTarget;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_dataTransfer_d924a622fbe51b06: function(arg0) {
            const ret = getObject(arg0).dataTransfer;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_dataset_20cb5c72458ae3fc: function(arg0) {
            const ret = getObject(arg0).dataset;
            return addHeapObject(ret);
        },
        __wbg_debug_46a93995fc6f8820: function(arg0, arg1, arg2, arg3) {
            console.debug(getObject(arg0), getObject(arg1), getObject(arg2), getObject(arg3));
        },
        __wbg_debug_a4099fa12db6cd61: function(arg0) {
            console.debug(getObject(arg0));
        },
        __wbg_decode_a28a47c6d83f2594: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            const ret = getObject(arg1).decode(getArrayU8FromWasm0(arg2, arg3), getObject(arg4));
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        }, arguments); },
        __wbg_define_panel_tab_fee5f3fc7762b065: function(arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            define_panel_tab(v0);
        },
        __wbg_delete_48886d8bcf17907c: function(arg0) {
            getObject(arg0).delete();
        },
        __wbg_delete_8318b529ff42aa20: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            delete getObject(arg0)[v0];
        },
        __wbg_deselect_94737f98e0d020fd: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).deselect();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_detail_934cf9545e3214d3: function(arg0) {
            const ret = getObject(arg0).detail;
            return addHeapObject(ret);
        },
        __wbg_dispatchEvent_dc8dcc7ddca11378: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).dispatchEvent(getObject(arg1));
            return ret;
        }, arguments); },
        __wbg_document_ee35a3d3ae34ef6c: function(arg0) {
            const ret = getObject(arg0).document;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_done_57b39ecd9addfe81: function(arg0) {
            const ret = getObject(arg0).done;
            return ret;
        },
        __wbg_draw_f1656ba493338e2f: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            const ret = getObject(arg0).draw(View.__wrap(arg1), arg2 === 0x100000001 ? undefined : arg2, arg3 === 0x100000001 ? undefined : arg3, arg4 !== 0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_entries_58c7934c745daac7: function(arg0) {
            const ret = Object.entries(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_error_3c7d958458bf649b: function(arg0, arg1) {
            var v0 = getArrayJsValueFromWasm0(arg0, arg1).slice();
            wasm.__wbindgen_export4(arg0, arg1 * 4, 4);
            console.error(...v0);
        },
        __wbg_error_7534b8e9a36f1ab4: function(arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            if (arg0 !== 0) { wasm.__wbindgen_export4(arg0, arg1, 1); }
            console.error(v0);
        },
        __wbg_error_794d0ffc9d00d5c3: function(arg0, arg1, arg2, arg3) {
            console.error(getObject(arg0), getObject(arg1), getObject(arg2), getObject(arg3));
        },
        __wbg_error_9a7fe3f932034cde: function(arg0) {
            console.error(getObject(arg0));
        },
        __wbg_error_f852e41c69b0bd84: function(arg0, arg1) {
            console.error(getObject(arg0), getObject(arg1));
        },
        __wbg_family_345f1736ce506756: function(arg0, arg1) {
            const ret = getObject(arg1).family;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_fetch_e6e8e0a221783759: function(arg0, arg1) {
            const ret = getObject(arg0).fetch(getObject(arg1));
            return addHeapObject(ret);
        },
        __wbg_focus_128ff465f65677cc: function() { return handleError(function (arg0) {
            getObject(arg0).focus();
        }, arguments); },
        __wbg_fonts_ba4bc521c555025e: function(arg0) {
            const ret = getObject(arg0).fonts;
            return addHeapObject(ret);
        },
        __wbg_format_7c97ddc401dae52f: function(arg0) {
            const ret = getObject(arg0).format;
            return addHeapObject(ret);
        },
        __wbg_from_bddd64e7d5ff6941: function(arg0) {
            const ret = Array.from(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_getAttribute_b9f6fc4b689c71b0: function(arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg2, arg3);
            const ret = getObject(arg1).getAttribute(v0);
            var ptr2 = isLikeNone(ret) ? 0 : passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            var len2 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len2, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr2, true);
        },
        __wbg_getBoundingClientRect_b5c8c34d07878818: function(arg0) {
            const ret = getObject(arg0).getBoundingClientRect();
            return addHeapObject(ret);
        },
        __wbg_getComputedStyle_2d1f9dfe4ee7e0b9: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).getComputedStyle(getObject(arg1));
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        }, arguments); },
        __wbg_getContext_2a5764d48600bc43: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).getContext(v0);
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        }, arguments); },
        __wbg_getEntriesByName_02488cff0bc7581c: function(arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            const ret = getObject(arg0).getEntriesByName(v0, v1);
            return addHeapObject(ret);
        },
        __wbg_getPropertyValue_d6911b2a1f9acba9: function() { return handleError(function (arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg2, arg3);
            const ret = getObject(arg1).getPropertyValue(v0);
            const ptr2 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len2 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len2, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr2, true);
        }, arguments); },
        __wbg_getRandomValues_1c61fac11405ffdc: function() { return handleError(function (arg0, arg1) {
            globalThis.crypto.getRandomValues(getArrayU8FromWasm0(arg0, arg1));
        }, arguments); },
        __wbg_getReader_804829cfb24eb4dd: function(arg0) {
            const ret = getObject(arg0).getReader();
            return addHeapObject(ret);
        },
        __wbg_getRootNode_193e544ed534e810: function(arg0) {
            const ret = getObject(arg0).getRootNode();
            return addHeapObject(ret);
        },
        __wbg_getSelection_d345357cda220f17: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).getSelection();
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        }, arguments); },
        __wbg_getTime_1e3cd1391c5c3995: function(arg0) {
            const ret = getObject(arg0).getTime();
            return ret;
        },
        __wbg_getTimezoneOffset_81776d10a4ec18a8: function(arg0) {
            const ret = getObject(arg0).getTimezoneOffset();
            return ret;
        },
        __wbg_get_5dd1ee4c9f462865: function(arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg2, arg3);
            const ret = getObject(arg1)[v0];
            var ptr2 = isLikeNone(ret) ? 0 : passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            var len2 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len2, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr2, true);
        },
        __wbg_get_76e04509d1922a04: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0)[v0];
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_get_941633a1d2f510cb: function() { return handleError(function (arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg2, arg3);
            const ret = getObject(arg1).get(v0);
            var ptr2 = isLikeNone(ret) ? 0 : passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            var len2 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len2, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr2, true);
        }, arguments); },
        __wbg_get_9b94d73e6221f75c: function(arg0, arg1) {
            const ret = getObject(arg0)[arg1 >>> 0];
            return addHeapObject(ret);
        },
        __wbg_get_b3ed3ad4be2bc8ac: function() { return handleError(function (arg0, arg1) {
            const ret = Reflect.get(getObject(arg0), getObject(arg1));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_get_d8db2ad31d529ff8: function(arg0, arg1) {
            const ret = getObject(arg0)[arg1 >>> 0];
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_get_static_config_63f724040cd26a11: function(arg0) {
            const ret = getObject(arg0).get_static_config();
            return addHeapObject(ret);
        },
        __wbg_get_with_index_d26da38d2c038e1a: function(arg0, arg1) {
            const ret = getObject(arg0)[arg1 >>> 0];
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_get_with_ref_key_1dc361bd10053bfe: function(arg0, arg1) {
            const ret = getObject(arg0)[getObject(arg1)];
            return addHeapObject(ret);
        },
        __wbg_hasAttribute_5d8f52cb4c739280: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).hasAttribute(v0);
            return ret;
        },
        __wbg_has_d4e53238966c12b6: function() { return handleError(function (arg0, arg1) {
            const ret = Reflect.has(getObject(arg0), getObject(arg1));
            return ret;
        }, arguments); },
        __wbg_headers_59a2938db9f80985: function(arg0) {
            const ret = getObject(arg0).headers;
            return addHeapObject(ret);
        },
        __wbg_height_45209601b4c4ede6: function(arg0) {
            const ret = getObject(arg0).height;
            return ret;
        },
        __wbg_height_c2027cf67d1c9b11: function(arg0) {
            const ret = getObject(arg0).height;
            return ret;
        },
        __wbg_hidden_8ce6a98b8c12451c: function(arg0) {
            const ret = getObject(arg0).hidden;
            return ret;
        },
        __wbg_hidePopover_f52b72171825485d: function() { return handleError(function (arg0) {
            getObject(arg0).hidePopover();
        }, arguments); },
        __wbg_host_fb29f8be35c2517d: function(arg0) {
            const ret = getObject(arg0).host;
            return addHeapObject(ret);
        },
        __wbg_includes_32215c836f1cd3fb: function(arg0, arg1, arg2) {
            const ret = getObject(arg0).includes(getObject(arg1), arg2);
            return ret;
        },
        __wbg_indexOf_e5f91887d9e5023e: function(arg0, arg1, arg2) {
            const ret = getObject(arg0).indexOf(getObject(arg1), arg2);
            return ret;
        },
        __wbg_info_148d043840582012: function(arg0) {
            console.info(getObject(arg0));
        },
        __wbg_info_9e602cf10c5c690b: function(arg0, arg1, arg2, arg3) {
            console.info(getObject(arg0), getObject(arg1), getObject(arg2), getObject(arg3));
        },
        __wbg_innerHeight_54aa104da08becd2: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).innerHeight;
            return addHeapObject(ret);
        }, arguments); },
        __wbg_innerWidth_fa95c57321f4f033: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).innerWidth;
            return addHeapObject(ret);
        }, arguments); },
        __wbg_insertBefore_1468142836e61a51: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = getObject(arg0).insertBefore(getObject(arg1), getObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_insertPanel_ba71bdc751d5e29b: function(arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).insertPanel(v0, takeObject(arg3), takeObject(arg4));
            return addHeapObject(ret);
        },
        __wbg_instanceof_ArrayBuffer_c367199e2fa2aa04: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof ArrayBuffer;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Array_d9eac779cd191cbc: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Array;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_CanvasRenderingContext2d_4bb052fd1c3d134d: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof CanvasRenderingContext2D;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_CssStyleRule_23e59108346af0df: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof CSSStyleRule;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_CustomEvent_7dc010f377596c94: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof CustomEvent;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Date_1b9f15b87f10aa4c: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Date;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Element_9e662f49ab6c6beb: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Element;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Error_8573fe0b0b480f46: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Error;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_FontFace_90d3e62f39397791: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof FontFace;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_HtmlCanvasElement_3f2f6e1edb1c9792: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof HTMLCanvasElement;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_HtmlElement_5abfac207260fd6f: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof HTMLElement;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_HtmlInputElement_c10b7260b4e0710a: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof HTMLInputElement;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_HtmlSelectElement_a1820832b22b0d1e: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof HTMLSelectElement;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Map_53af74335dec57f4: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Map;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Node_da04bd8df43deba3: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Node;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Object_1c6af87502b733ed: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Object;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Promise_0094681e3519d6ec: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Promise;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Response_ee1d54d79ae41977: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Response;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_ShadowRoot_5285adde3587c73e: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof ShadowRoot;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Uint8Array_9b9075935c74707c: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Uint8Array;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_instanceof_Window_ed49b2db8df90359: function(arg0) {
            let result;
            try {
                result = getObject(arg0) instanceof Window;
            } catch (_) {
                result = false;
            }
            const ret = result;
            return ret;
        },
        __wbg_isArray_d314bb98fcf08331: function(arg0) {
            const ret = Array.isArray(getObject(arg0));
            return ret;
        },
        __wbg_isCollapsed_617e9be3f62f5a25: function(arg0) {
            const ret = getObject(arg0).isCollapsed;
            return ret;
        },
        __wbg_isConnected_6110bc2c5462f90e: function(arg0) {
            const ret = getObject(arg0).isConnected;
            return ret;
        },
        __wbg_isIntersecting_ee173c3ac0a789ff: function(arg0) {
            const ret = getObject(arg0).isIntersecting;
            return ret;
        },
        __wbg_isSafeInteger_bfbc7332a9768d2a: function(arg0) {
            const ret = Number.isSafeInteger(getObject(arg0));
            return ret;
        },
        __wbg_isSameNode_3caa48d6eb3c880e: function(arg0, arg1) {
            const ret = getObject(arg0).isSameNode(getObject(arg1));
            return ret;
        },
        __wbg_is_f29129f676e5410c: function(arg0, arg1) {
            const ret = Object.is(getObject(arg0), getObject(arg1));
            return ret;
        },
        __wbg_item_00ae26320b80a11f: function(arg0, arg1, arg2) {
            const ret = getObject(arg1).item(arg2 >>> 0);
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_item_48715d3212ed96cb: function(arg0, arg1) {
            const ret = getObject(arg0).item(arg1 >>> 0);
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_item_5c82f52b014b3056: function(arg0, arg1) {
            const ret = getObject(arg0).item(arg1 >>> 0);
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_item_64b77e6a26f7de76: function(arg0, arg1) {
            const ret = getObject(arg0).item(arg1 >>> 0);
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_item_ca823df8214b9e2b: function(arg0, arg1) {
            const ret = getObject(arg0).item(arg1 >>> 0);
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_iterator_6ff6560ca1568e55: function() {
            const ret = Symbol.iterator;
            return addHeapObject(ret);
        },
        __wbg_keyCode_155291a11654466e: function(arg0) {
            const ret = getObject(arg0).keyCode;
            return ret;
        },
        __wbg_key_d41e8e825e6bb0e9: function(arg0, arg1) {
            const ret = getObject(arg1).key;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_keys_b50a709a76add04e: function(arg0) {
            const ret = Object.keys(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_languages_5a69df7551d1a803: function(arg0) {
            const ret = getObject(arg0).languages;
            return addHeapObject(ret);
        },
        __wbg_lastChild_132d67597d5e4aef: function(arg0) {
            const ret = getObject(arg0).lastChild;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_left_3b7c3c1030d5ca7a: function(arg0) {
            const ret = getObject(arg0).left;
            return ret;
        },
        __wbg_length_25851a48867b2afc: function(arg0) {
            const ret = getObject(arg0).length;
            return ret;
        },
        __wbg_length_32ed9a279acd054c: function(arg0) {
            const ret = getObject(arg0).length;
            return ret;
        },
        __wbg_length_35a7bace40f36eac: function(arg0) {
            const ret = getObject(arg0).length;
            return ret;
        },
        __wbg_length_3f12bc1cab862cc3: function(arg0) {
            const ret = getObject(arg0).length;
            return ret;
        },
        __wbg_length_b5aa6c69c842d6a6: function(arg0) {
            const ret = getObject(arg0).length;
            return ret;
        },
        __wbg_length_e455564ce5cbf46e: function(arg0) {
            const ret = getObject(arg0).length;
            return ret;
        },
        __wbg_length_edae0cd13a8d3b71: function(arg0) {
            const ret = getObject(arg0).length;
            return ret;
        },
        __wbg_listener_id_3f13203bf7de1e4d: function(arg0) {
            const ret = getObject(arg0).__yew_listener_id;
            return isLikeNone(ret) ? 0x100000001 : (ret) >>> 0;
        },
        __wbg_loaded_27fa3bdfd9239e89: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).loaded;
            return addHeapObject(ret);
        }, arguments); },
        __wbg_mark_3b530a64b09ba08a: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).mark(v0);
        }, arguments); },
        __wbg_matches_852ba8b2b4592069: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).matches(v0);
            return ret;
        }, arguments); },
        __wbg_maximize_1565a64f119d9b4b: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).maximize(v0);
        },
        __wbg_measureText_9d64a92333bd05ee: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).measureText(v0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_measure_288b48c082eae0fe: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            getObject(arg0).measure(v0, v1);
        }, arguments); },
        __wbg_message_9ddc4b9a62a7c379: function(arg0) {
            const ret = getObject(arg0).message;
            return addHeapObject(ret);
        },
        __wbg_minimize_06345975e97908b4: function(arg0) {
            getObject(arg0).minimize();
        },
        __wbg_namespaceURI_86e2062c65f0f341: function(arg0, arg1) {
            const ret = getObject(arg1).namespaceURI;
            var ptr1 = isLikeNone(ret) ? 0 : passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            var len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_navigator_43be698ba96fc088: function(arg0) {
            const ret = getObject(arg0).navigator;
            return addHeapObject(ret);
        },
        __wbg_new_16c83877745f8d12: function() { return handleError(function (arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            const ret = new InputEvent(v0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_1d5bd472c706b768: function() { return handleError(function () {
            const ret = new CSSStyleSheet();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_245cd5c49157e602: function(arg0) {
            const ret = new Date(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_new_2ec3ec8c398b20d2: function() { return handleError(function () {
            const ret = new TextDecoder();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_361308b2356cecd0: function() {
            const ret = new Object();
            return addHeapObject(ret);
        },
        __wbg_new_384a230f8673183c: function() { return handleError(function (arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            const ret = new CustomEvent(v0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_3eb36ae241fe6f44: function() {
            const ret = new Array();
            return addHeapObject(ret);
        },
        __wbg_new_64284bd487f9d239: function() { return handleError(function () {
            const ret = new Headers();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_72b49615380db768: function(arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            const ret = new Error(v0);
            return addHeapObject(ret);
        },
        __wbg_new_86c8f0b79e48927d: function(arg0, arg1) {
            const ret = new Intl.NumberFormat(getObject(arg0), getObject(arg1));
            return addHeapObject(ret);
        },
        __wbg_new_88e66413498f3e3d: function(arg0) {
            const ret = new ClipboardItem(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_new_8a6f238a6ece86ea: function() {
            const ret = new Error();
            return addHeapObject(ret);
        },
        __wbg_new_98450e70584646af: function(arg0) {
            const ret = new IntersectionObserver(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_new_b5d9e2fb389fef91: function(arg0, arg1) {
            try {
                var state0 = {a: arg0, b: arg1};
                var cb0 = (arg0, arg1) => {
                    const a = state0.a;
                    state0.a = 0;
                    try {
                        return __wasm_bindgen_func_elem_33507(a, state0.b, arg0, arg1);
                    } finally {
                        state0.a = a;
                    }
                };
                const ret = new Promise(cb0);
                return addHeapObject(ret);
            } finally {
                state0.a = state0.b = 0;
            }
        },
        __wbg_new_b949e7f56150a5d1: function() { return handleError(function () {
            const ret = new AbortController();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_c155239f1b113b68: function(arg0, arg1) {
            const ret = new Intl.DateTimeFormat(getObject(arg0), getObject(arg1));
            return addHeapObject(ret);
        },
        __wbg_new_dca287b076112a51: function() {
            const ret = new Map();
            return addHeapObject(ret);
        },
        __wbg_new_dd2b680c8bf6ae29: function(arg0) {
            const ret = new Uint8Array(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_new_ef89e494c7dddd8a: function(arg0) {
            const ret = new ResizeObserver(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_new_from_slice_a3d2629dc1826784: function(arg0, arg1) {
            const ret = new Uint8Array(getArrayU8FromWasm0(arg0, arg1));
            return addHeapObject(ret);
        },
        __wbg_new_no_args_1c7c842f08d00ebb: function(arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            const ret = new Function(v0);
            return addHeapObject(ret);
        },
        __wbg_new_with_event_init_dict_48877e7ca125f3c1: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            const ret = new CustomEvent(v0, getObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_with_length_1763c527b2923202: function(arg0) {
            const ret = new Array(arg0 >>> 0);
            return addHeapObject(ret);
        },
        __wbg_new_with_str_and_init_a61cbc6bdef21614: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            const ret = new Request(v0, getObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_with_str_sequence_and_options_9b8b0bee99ec6b0f: function() { return handleError(function (arg0, arg1) {
            const ret = new Blob(getObject(arg0), getObject(arg1));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_new_with_u8_array_sequence_08b2096a9f3117c0: function() { return handleError(function (arg0) {
            const ret = new Blob(getObject(arg0));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_nextSibling_2e988d9bbe3e06f0: function(arg0) {
            const ret = getObject(arg0).nextSibling;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_next_3482f54c49e8af19: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).next();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_next_418f80d8f5303233: function(arg0) {
            const ret = getObject(arg0).next;
            return addHeapObject(ret);
        },
        __wbg_next_a74fa8e233e5b7bf: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).next();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_now_a3af9a2f4bbaa4d1: function() {
            const ret = Date.now();
            return ret;
        },
        __wbg_now_ebffdf7e580f210d: function(arg0) {
            const ret = getObject(arg0).now();
            return ret;
        },
        __wbg_observe_54866f50769e08ca: function(arg0, arg1) {
            getObject(arg0).observe(getObject(arg1));
        },
        __wbg_observe_627a58df471ce4f7: function(arg0, arg1) {
            getObject(arg0).observe(getObject(arg1));
        },
        __wbg_of_f915f7cd925b21a5: function(arg0) {
            const ret = Array.of(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_offsetHeight_34f7abc1686733cc: function(arg0) {
            const ret = getObject(arg0).offsetHeight;
            return ret;
        },
        __wbg_offsetParent_9627ebca6b03e8b2: function(arg0) {
            const ret = getObject(arg0).offsetParent;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_offsetWidth_f37b33a53e513101: function(arg0) {
            const ret = getObject(arg0).offsetWidth;
            return ret;
        },
        __wbg_offsetX_b76b9bb1f9235de9: function(arg0) {
            const ret = getObject(arg0).offsetX;
            return ret;
        },
        __wbg_offsetY_db5c1ddb866e1b82: function(arg0) {
            const ret = getObject(arg0).offsetY;
            return ret;
        },
        __wbg_ok_87f537440a0acf85: function(arg0) {
            const ret = getObject(arg0).ok;
            return ret;
        },
        __wbg_parentElement_75863410a8617953: function(arg0) {
            const ret = getObject(arg0).parentElement;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_parentNode_d44bd5ec58601e45: function(arg0) {
            const ret = getObject(arg0).parentNode;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_parse_708461a1feddfb38: function() { return handleError(function (arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            const ret = JSON.parse(v0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_performance_06f12ba62483475d: function(arg0) {
            const ret = getObject(arg0).performance;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_plugin_config_schema_af1a897a0339ced6: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = getObject(arg0).plugin_config_schema(getObject(arg1), getObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_pointerId_466b1bdcaf2fe835: function(arg0) {
            const ret = getObject(arg0).pointerId;
            return ret;
        },
        __wbg_pop_80fa29128215a463: function(arg0) {
            const ret = getObject(arg0).pop();
            return addHeapObject(ret);
        },
        __wbg_prepend_12ed960547d9dfe1: function() { return handleError(function (arg0, arg1) {
            getObject(arg0).prepend(getObject(arg1));
        }, arguments); },
        __wbg_presize_6d1b3d3b70ce9325: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = getObject(arg0).presize(arg1, arg2);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_preventDefault_cdcfcd7e301b9702: function(arg0) {
            getObject(arg0).preventDefault();
        },
        __wbg_prototypesetcall_bdcdcc5842e4d77d: function(arg0, arg1, arg2) {
            Uint8Array.prototype.set.call(getArrayU8FromWasm0(arg0, arg1), getObject(arg2));
        },
        __wbg_push_8ffdcb2063340ba5: function(arg0, arg1) {
            const ret = getObject(arg0).push(getObject(arg1));
            return ret;
        },
        __wbg_querySelectorAll_5875742f4e11f759: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).querySelectorAll(v0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_querySelector_1be4292f202b1597: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).querySelector(v0);
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        }, arguments); },
        __wbg_querySelector_4dc619d02f6cb079: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).querySelector(v0);
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        }, arguments); },
        __wbg_queueMicrotask_0aa0a927f78f5d98: function(arg0) {
            const ret = getObject(arg0).queueMicrotask;
            return addHeapObject(ret);
        },
        __wbg_queueMicrotask_5bb536982f78a56f: function(arg0) {
            queueMicrotask(getObject(arg0));
        },
        __wbg_readText_b9ac81623f153a62: function(arg0) {
            const ret = getObject(arg0).readText();
            return addHeapObject(ret);
        },
        __wbg_read_68fd377df67e19b0: function(arg0) {
            const ret = getObject(arg0).read();
            return addHeapObject(ret);
        },
        __wbg_readyState_cf11f0728fc7b46c: function(arg0, arg1) {
            const ret = getObject(arg1).readyState;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_realCoordinates_95d090434c040179: function(arg0, arg1) {
            const ret = getObject(arg0).realCoordinates(getObject(arg1));
            return addHeapObject(ret);
        },
        __wbg_reject_a2176de7f1212be5: function(arg0) {
            const ret = Promise.reject(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_relatedTarget_1557cded94b97af2: function(arg0) {
            const ret = getObject(arg0).relatedTarget;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_releasePointerCapture_420ef33c7c5fb6f4: function() { return handleError(function (arg0, arg1) {
            getObject(arg0).releasePointerCapture(arg1);
        }, arguments); },
        __wbg_removeAllRanges_39254b77dc6257ab: function() { return handleError(function (arg0) {
            getObject(arg0).removeAllRanges();
        }, arguments); },
        __wbg_removeAttribute_87259aab06d9f286: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).removeAttribute(v0);
        }, arguments); },
        __wbg_removeChild_2f0b06213dbc49ca: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).removeChild(getObject(arg1));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_removeEventListener_0c0902ed5009dd9f: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).removeEventListener(v0, getObject(arg3), arg4 !== 0);
        }, arguments); },
        __wbg_removeEventListener_e63328781a5b9af9: function() { return handleError(function (arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).removeEventListener(v0, getObject(arg3));
        }, arguments); },
        __wbg_removePanel_89f2af880f0f91a2: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).removePanel(v0);
            return addHeapObject(ret);
        },
        __wbg_removeProperty_a0d2ff8a76ffd2b1: function() { return handleError(function (arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg2, arg3);
            const ret = getObject(arg1).removeProperty(v0);
            const ptr2 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len2 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len2, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr2, true);
        }, arguments); },
        __wbg_remove_31c39325eee968fc: function(arg0) {
            getObject(arg0).remove();
        },
        __wbg_remove_f9451697e0bc6ca0: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).remove(v0);
        }, arguments); },
        __wbg_render_73a123e524099b60: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = getObject(arg0).render(View.__wrap(arg1), takeObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_replaceSync_54e9ce897fe6ab6a: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).replaceSync(v0);
        }, arguments); },
        __wbg_requestAnimationFrame_43682f8e1c5e5348: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).requestAnimationFrame(getObject(arg1));
            return ret;
        }, arguments); },
        __wbg_resize_2dfb18995e43d301: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).resize();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_resolve_002c4b7d9d8f6b64: function(arg0) {
            const ret = Promise.resolve(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_resolvedOptions_4c36dbfa1c4ba2bf: function(arg0) {
            const ret = getObject(arg0).resolvedOptions();
            return addHeapObject(ret);
        },
        __wbg_restorePhysics_66df17074d08ef49: function(arg0, arg1) {
            getObject(arg0).restorePhysics(getObject(arg1));
        },
        __wbg_restoreSync_6ea54baceed6f8ca: function(arg0, arg1) {
            getObject(arg0).restoreSync(getObject(arg1));
        },
        __wbg_restore_43960e31bf4c37c7: function() { return handleError(function (arg0, arg1, arg2) {
            getObject(arg0).restore(getObject(arg1), getObject(arg2));
        }, arguments); },
        __wbg_restyle_949fb0d4d0c355a5: function(arg0) {
            getObject(arg0).restyle();
        },
        __wbg_resumeResize_b4054c5d47d4f1b0: function(arg0) {
            getObject(arg0).resumeResize();
        },
        __wbg_right_154af6c2b1bf0c89: function(arg0) {
            const ret = getObject(arg0).right;
            return ret;
        },
        __wbg_run_556972011e45ec00: function() { return handleError(function (arg0, arg1) {
            const ret = getObject(arg0).run(getObject(arg1));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_save_363646e98431790c: function(arg0) {
            const ret = getObject(arg0).save();
            return addHeapObject(ret);
        },
        __wbg_scrollHeight_25e40881bfad55b6: function(arg0) {
            const ret = getObject(arg0).scrollHeight;
            return ret;
        },
        __wbg_scrollLeft_2b817c7719d17438: function(arg0) {
            const ret = getObject(arg0).scrollLeft;
            return ret;
        },
        __wbg_scrollTop_0a3a77f9fcbe038e: function(arg0) {
            const ret = getObject(arg0).scrollTop;
            return ret;
        },
        __wbg_scrollTop_4161d2a08060cb06: function(arg0) {
            const ret = getObject(arg0).scrollTop;
            return ret;
        },
        __wbg_select_312040969b7499fa: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).select(v0);
            return addHeapObject(ret);
        },
        __wbg_select_f96715f412d93f23: function(arg0) {
            getObject(arg0).select();
        },
        __wbg_selectedIndex_b1465d243fcb7142: function(arg0) {
            const ret = getObject(arg0).selectedIndex;
            return ret;
        },
        __wbg_selectionEnd_f3cba543517b4aa8: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).selectionEnd;
            return isLikeNone(ret) ? 0x100000001 : (ret) >>> 0;
        }, arguments); },
        __wbg_selectionStart_9cd0aeff14a21252: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).selectionStart;
            return isLikeNone(ret) ? 0x100000001 : (ret) >>> 0;
        }, arguments); },
        __wbg_selectorText_4ebdf4f8074dafea: function(arg0, arg1) {
            const ret = getObject(arg1).selectorText;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_setAttribute_cc8e4c8a2a008508: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            getObject(arg0).setAttribute(v0, v1);
        }, arguments); },
        __wbg_setDragImage_76defbcc7ff30178: function(arg0, arg1, arg2, arg3) {
            getObject(arg0).setDragImage(getObject(arg1), arg2, arg3);
        },
        __wbg_setPointerCapture_420db6f6826eb74b: function() { return handleError(function (arg0, arg1) {
            getObject(arg0).setPointerCapture(arg1);
        }, arguments); },
        __wbg_setProperty_cbb25c4e74285b39: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            getObject(arg0).setProperty(v0, v1);
        }, arguments); },
        __wbg_setSelectionRange_301eef1c51e01d2a: function() { return handleError(function (arg0, arg1, arg2) {
            getObject(arg0).setSelectionRange(arg1 >>> 0, arg2 >>> 0);
        }, arguments); },
        __wbg_setTimeout_21d8cc2869b34c58: function(arg0, arg1) {
            const ret = setTimeout(getObject(arg0), arg1);
            return ret;
        },
        __wbg_setTimeout_eff32631ea138533: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = getObject(arg0).setTimeout(getObject(arg1), arg2);
            return ret;
        }, arguments); },
        __wbg_set_1eb0999cf5d27fc8: function(arg0, arg1, arg2) {
            const ret = getObject(arg0).set(getObject(arg1), getObject(arg2));
            return addHeapObject(ret);
        },
        __wbg_set_3f1d0b984ed272ed: function(arg0, arg1, arg2) {
            getObject(arg0)[takeObject(arg1)] = takeObject(arg2);
        },
        __wbg_set_6cb8631f80447a67: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = Reflect.set(getObject(arg0), getObject(arg1), getObject(arg2));
            return ret;
        }, arguments); },
        __wbg_set_body_9a7e00afe3cfe244: function(arg0, arg1) {
            getObject(arg0).body = getObject(arg1);
        },
        __wbg_set_bubbles_739f291693b2bbec: function(arg0, arg1) {
            getObject(arg0).bubbles = arg1 !== 0;
        },
        __wbg_set_cache_key_ace1fa875f02b7cd: function(arg0, arg1) {
            getObject(arg0).__yew_subtree_cache_key = arg1 >>> 0;
        },
        __wbg_set_capture_d52e7a585f2933c8: function(arg0, arg1) {
            getObject(arg0).capture = arg1 !== 0;
        },
        __wbg_set_checked_4b2468680005fbf7: function(arg0, arg1) {
            getObject(arg0).checked = arg1 !== 0;
        },
        __wbg_set_className_c1d9e7362164af61: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).className = v0;
        },
        __wbg_set_composed_e4a9826a628ca7f8: function(arg0, arg1) {
            getObject(arg0).composed = arg1 !== 0;
        },
        __wbg_set_cssText_18380c97092caefa: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).cssText = v0;
        },
        __wbg_set_defaultValue_ad528b0a65ceef4a: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).defaultValue = v0;
        }, arguments); },
        __wbg_set_detail_fa3b358526be3a85: function(arg0, arg1) {
            getObject(arg0).detail = getObject(arg1);
        },
        __wbg_set_dropEffect_042402609f0b7c6b: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).dropEffect = v0;
        },
        __wbg_set_f43e577aea94465b: function(arg0, arg1, arg2) {
            getObject(arg0)[arg1 >>> 0] = takeObject(arg2);
        },
        __wbg_set_fb27b296001e2de9: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            var v1 = getCachedStringFromWasm0(arg3, arg4);
            getObject(arg0)[v0] = v1;
        }, arguments); },
        __wbg_set_font_575685c8f7e56957: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).font = v0;
        },
        __wbg_set_headers_cfc5f4b2c1f20549: function(arg0, arg1) {
            getObject(arg0).headers = getObject(arg1);
        },
        __wbg_set_innerHTML_edd39677e3460291: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).innerHTML = v0;
        },
        __wbg_set_innerText_2f1502f2d0780499: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).innerText = v0;
        },
        __wbg_set_listener_id_8b77f7eb9adb712b: function(arg0, arg1) {
            getObject(arg0).__yew_listener_id = arg1 >>> 0;
        },
        __wbg_set_method_c3e20375f5ae7fac: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).method = v0;
        },
        __wbg_set_mode_6a55f972d62a3c88: function(arg0, arg1) {
            getObject(arg0).mode = __wbindgen_enum_ShadowRootMode[arg1];
        },
        __wbg_set_nodeValue_d947eb0a476b80d7: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).nodeValue = v0;
        },
        __wbg_set_passive_f411e67e6f28687b: function(arg0, arg1) {
            getObject(arg0).passive = arg1 !== 0;
        },
        __wbg_set_scrollLeft_8de8fc187e3a6808: function(arg0, arg1) {
            getObject(arg0).scrollLeft = arg1;
        },
        __wbg_set_scrollTop_bebe746cd217a3d1: function(arg0, arg1) {
            getObject(arg0).scrollTop = arg1;
        },
        __wbg_set_scrollTop_fb454582dda63417: function(arg0, arg1) {
            getObject(arg0).scrollTop = arg1;
        },
        __wbg_set_selectionEnd_01e3269db03d5d2e: function() { return handleError(function (arg0, arg1) {
            getObject(arg0).selectionEnd = arg1 === 0x100000001 ? undefined : arg1;
        }, arguments); },
        __wbg_set_selectionStart_d68d6955c07c2be1: function() { return handleError(function (arg0, arg1) {
            getObject(arg0).selectionStart = arg1 === 0x100000001 ? undefined : arg1;
        }, arguments); },
        __wbg_set_signal_f2d3f8599248896d: function(arg0, arg1) {
            getObject(arg0).signal = getObject(arg1);
        },
        __wbg_set_stream_8a7c5f563fa185b4: function(arg0, arg1) {
            getObject(arg0).stream = arg1 !== 0;
        },
        __wbg_set_subtree_id_8be88ea477740786: function(arg0, arg1) {
            getObject(arg0).__yew_subtree_id = arg1 >>> 0;
        },
        __wbg_set_type_148de20768639245: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).type = v0;
        },
        __wbg_set_value_62a965e38b22b38c: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).value = v0;
        },
        __wbg_set_value_c3556fce049236f0: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).value = v0;
        },
        __wbg_set_value_ddc3bd01a8467bf1: function(arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            getObject(arg0).value = v0;
        },
        __wbg_shadowRoot_14828067c5922da7: function(arg0) {
            const ret = getObject(arg0).shadowRoot;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_shiftKey_5558a3288542c985: function(arg0) {
            const ret = getObject(arg0).shiftKey;
            return ret;
        },
        __wbg_shiftKey_564be91ec842bcc4: function(arg0) {
            const ret = getObject(arg0).shiftKey;
            return ret;
        },
        __wbg_showPicker_ba1d36381dae3011: function() { return handleError(function (arg0) {
            getObject(arg0).showPicker();
        }, arguments); },
        __wbg_showPopover_7166cd0808998024: function() { return handleError(function (arg0) {
            getObject(arg0).showPopover();
        }, arguments); },
        __wbg_signal_d1285ecab4ebc5ad: function(arg0) {
            const ret = getObject(arg0).signal;
            return addHeapObject(ret);
        },
        __wbg_stack_0ed75d68575b0f3c: function(arg0, arg1) {
            const ret = getObject(arg1).stack;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_startTime_248495bfbcb427d3: function(arg0) {
            const ret = getObject(arg0).startTime;
            return ret;
        },
        __wbg_static_accessor_GLOBAL_12837167ad935116: function() {
            const ret = typeof global === 'undefined' ? null : global;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_static_accessor_GLOBAL_THIS_e628e89ab3b1c95f: function() {
            const ret = typeof globalThis === 'undefined' ? null : globalThis;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_static_accessor_PSP_d1c7a0dfe6e2f550: function() {
            const ret = psp;
            return addHeapObject(ret);
        },
        __wbg_static_accessor_SELF_a621d3dfbb60d0ce: function() {
            const ret = typeof self === 'undefined' ? null : self;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_static_accessor_WINDOW_f8727f0cf888e0bd: function() {
            const ret = typeof window === 'undefined' ? null : window;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_statusText_556131a02d60f5cd: function(arg0, arg1) {
            const ret = getObject(arg1).statusText;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_status_89d7e803db911ee7: function(arg0) {
            const ret = getObject(arg0).status;
            return ret;
        },
        __wbg_stopPropagation_6e5e2a085214ac63: function(arg0) {
            getObject(arg0).stopPropagation();
        },
        __wbg_stringify_2fdc12d5545a89d9: function() { return handleError(function (arg0, arg1, arg2) {
            const ret = JSON.stringify(getObject(arg0), getObject(arg1), getObject(arg2));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_stringify_8d1cc6ff383e8bae: function() { return handleError(function (arg0) {
            const ret = JSON.stringify(getObject(arg0));
            return addHeapObject(ret);
        }, arguments); },
        __wbg_styleSheets_54de627270238f6f: function(arg0) {
            const ret = getObject(arg0).styleSheets;
            return addHeapObject(ret);
        },
        __wbg_style_0b7c9bd318f8b807: function(arg0) {
            const ret = getObject(arg0).style;
            return addHeapObject(ret);
        },
        __wbg_style_fa56cf251a93275a: function(arg0) {
            const ret = getObject(arg0).style;
            return addHeapObject(ret);
        },
        __wbg_subtree_id_1739fcadc43deeca: function(arg0) {
            const ret = getObject(arg0).__yew_subtree_id;
            return isLikeNone(ret) ? 0x100000001 : (ret) >>> 0;
        },
        __wbg_supportedValuesOf_070cb26b0fcb2822: function(arg0) {
            const ret = Intl.supportedValuesOf(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_table_new: function(arg0) {
            const ret = Table.__wrap(arg0);
            return addHeapObject(ret);
        },
        __wbg_tagName_0cf6d7b647352f04: function(arg0, arg1) {
            const ret = getObject(arg1).tagName;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_target_521be630ab05b11e: function(arg0) {
            const ret = getObject(arg0).target;
            return isLikeNone(ret) ? 0 : addHeapObject(ret);
        },
        __wbg_text_083b8727c990c8c0: function() { return handleError(function (arg0) {
            const ret = getObject(arg0).text();
            return addHeapObject(ret);
        }, arguments); },
        __wbg_then_0d9fe2c7b1857d32: function(arg0, arg1, arg2) {
            const ret = getObject(arg0).then(getObject(arg1), getObject(arg2));
            return addHeapObject(ret);
        },
        __wbg_then_b9e7b3b5f1a9e1b5: function(arg0, arg1) {
            const ret = getObject(arg0).then(getObject(arg1));
            return addHeapObject(ret);
        },
        __wbg_timeStamp_ddfe1bf5c2346d68: function(arg0) {
            const ret = getObject(arg0).timeStamp;
            return ret;
        },
        __wbg_toString_964ff7fe6eca8362: function(arg0) {
            const ret = getObject(arg0).toString();
            return addHeapObject(ret);
        },
        __wbg_toggleAttribute_d89e5c9e9053c9c4: function() { return handleError(function (arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).toggleAttribute(v0, arg3 !== 0);
            return ret;
        }, arguments); },
        __wbg_toggle_52417ca95b874e83: function() { return handleError(function (arg0, arg1, arg2) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).toggle(v0);
            return ret;
        }, arguments); },
        __wbg_toggle_b8e3ebf1a7aaeb11: function() { return handleError(function (arg0, arg1, arg2, arg3) {
            var v0 = getCachedStringFromWasm0(arg1, arg2);
            const ret = getObject(arg0).toggle(v0, arg3 !== 0);
            return ret;
        }, arguments); },
        __wbg_top_3d27ff6f468cf3fc: function(arg0) {
            const ret = getObject(arg0).top;
            return ret;
        },
        __wbg_trace_9007714a6fbee374: function(arg0) {
            console.trace(getObject(arg0));
        },
        __wbg_trace_bd16b570941b54fb: function(arg0, arg1, arg2, arg3) {
            console.trace(getObject(arg0), getObject(arg1), getObject(arg2), getObject(arg3));
        },
        __wbg_type_4edffca24c42b74d: function(arg0, arg1) {
            const ret = getObject(arg1).type;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_unobserve_78e51c229e9cb74c: function(arg0, arg1) {
            getObject(arg0).unobserve(getObject(arg1));
        },
        __wbg_unobserve_84dd6ded6f59337d: function(arg0, arg1) {
            getObject(arg0).unobserve(getObject(arg1));
        },
        __wbg_update_3f59793335531aa0: function() { return handleError(function (arg0, arg1, arg2, arg3, arg4) {
            const ret = getObject(arg0).update(View.__wrap(arg1), arg2 === 0x100000001 ? undefined : arg2, arg3 === 0x100000001 ? undefined : arg3, arg4 !== 0);
            return addHeapObject(ret);
        }, arguments); },
        __wbg_valueAsNumber_5292e7ed56db5658: function(arg0) {
            const ret = getObject(arg0).valueAsNumber;
            return ret;
        },
        __wbg_value_0546255b415e96c1: function(arg0) {
            const ret = getObject(arg0).value;
            return addHeapObject(ret);
        },
        __wbg_value_15684899da869c95: function(arg0, arg1) {
            const ret = getObject(arg1).value;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_value_d402dce7dcb16251: function(arg0, arg1) {
            const ret = getObject(arg1).value;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_value_e506a07878790ca0: function(arg0, arg1) {
            const ret = getObject(arg1).value;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_values_5da93bc719d272cf: function(arg0) {
            const ret = Object.values(getObject(arg0));
            return addHeapObject(ret);
        },
        __wbg_values_725507e0ae05aa44: function(arg0) {
            const ret = getObject(arg0).values();
            return addHeapObject(ret);
        },
        __wbg_view_new: function(arg0) {
            const ret = View.__wrap(arg0);
            return addHeapObject(ret);
        },
        __wbg_view_unwrap: function(arg0) {
            const ret = View.__unwrap(getObject(arg0));
            return ret;
        },
        __wbg_virtualdataslice_new: function(arg0) {
            const ret = VirtualDataSlice.__wrap(arg0);
            return addHeapObject(ret);
        },
        __wbg_warn_a40b971467b219c7: function(arg0, arg1, arg2, arg3) {
            console.warn(getObject(arg0), getObject(arg1), getObject(arg2), getObject(arg3));
        },
        __wbg_warn_f7ae1b2e66ccb930: function(arg0) {
            console.warn(getObject(arg0));
        },
        __wbg_weight_984b87b81f2dde65: function(arg0, arg1) {
            const ret = getObject(arg1).weight;
            const ptr1 = passStringToWasm0(ret, wasm.__wbindgen_export, wasm.__wbindgen_export2);
            const len1 = WASM_VECTOR_LEN;
            getDataViewMemory0().setInt32(arg0 + 4 * 1, len1, true);
            getDataViewMemory0().setInt32(arg0 + 4 * 0, ptr1, true);
        },
        __wbg_which_78ecd90ab7c6bdd6: function(arg0) {
            const ret = getObject(arg0).which;
            return ret;
        },
        __wbg_width_7444cca5dfea0645: function(arg0) {
            const ret = getObject(arg0).width;
            return ret;
        },
        __wbg_width_9bbf873307a2ac4e: function(arg0) {
            const ret = getObject(arg0).width;
            return ret;
        },
        __wbg_width_ae46cb8e98ee102f: function(arg0) {
            const ret = getObject(arg0).width;
            return ret;
        },
        __wbg_write_d429ce72e918e180: function(arg0, arg1) {
            const ret = getObject(arg0).write(getObject(arg1));
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000001: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 4789, function: Function { arguments: [Ref(NamedExternref("Event"))], shim_idx: 4791, ret: Unit, inner_ret: Some(Unit) }, mutable: false }) -> Externref`.
            const ret = makeClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_18919, __wasm_bindgen_func_elem_19039);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000002: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [Externref], shim_idx: 90, ret: Unit, inner_ret: Some(Unit) }, mutable: false }) -> Externref`.
            const ret = makeClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7893);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000003: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [NamedExternref("Array<any>")], shim_idx: 92, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7894);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000004: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [NamedExternref("DragEvent")], shim_idx: 92, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7894);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000005: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [NamedExternref("FocusEvent")], shim_idx: 92, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7894);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000006: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [NamedExternref("KeyboardEvent")], shim_idx: 92, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7894);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000007: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [NamedExternref("MouseEvent")], shim_idx: 90, ret: Unit, inner_ret: Some(Unit) }, mutable: false }) -> Externref`.
            const ret = makeClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7893);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000008: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [NamedExternref("MouseEvent")], shim_idx: 92, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7894);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000009: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [NamedExternref("PointerEvent")], shim_idx: 92, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7894);
            return addHeapObject(ret);
        },
        __wbindgen_cast_000000000000000a: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [], shim_idx: 91, ret: Result(Externref), inner_ret: Some(Result(Externref)) }, mutable: false }) -> Externref`.
            const ret = makeClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7895);
            return addHeapObject(ret);
        },
        __wbindgen_cast_000000000000000b: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 48, function: Function { arguments: [], shim_idx: 93, ret: Result(Unit), inner_ret: Some(Result(Unit)) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_4482, __wasm_bindgen_func_elem_7891);
            return addHeapObject(ret);
        },
        __wbindgen_cast_000000000000000c: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 4873, function: Function { arguments: [NamedExternref("Event")], shim_idx: 4874, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_19628, __wasm_bindgen_func_elem_19633);
            return addHeapObject(ret);
        },
        __wbindgen_cast_000000000000000d: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 4873, function: Function { arguments: [], shim_idx: 4875, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_19628, __wasm_bindgen_func_elem_19632);
            return addHeapObject(ret);
        },
        __wbindgen_cast_000000000000000e: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 4890, function: Function { arguments: [], shim_idx: 4905, ret: NamedExternref("Promise<any>"), inner_ret: Some(NamedExternref("Promise<any>")) }, mutable: false }) -> Externref`.
            const ret = makeClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_20538, __wasm_bindgen_func_elem_21577);
            return addHeapObject(ret);
        },
        __wbindgen_cast_000000000000000f: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 4890, function: Function { arguments: [], shim_idx: 4906, ret: Unit, inner_ret: Some(Unit) }, mutable: false }) -> Externref`.
            const ret = makeClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_20538, __wasm_bindgen_func_elem_21578);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000010: function(arg0, arg1) {
            // Cast intrinsic for `Closure(Closure { dtor_idx: 5450, function: Function { arguments: [Externref], shim_idx: 5451, ret: Unit, inner_ret: Some(Unit) }, mutable: true }) -> Externref`.
            const ret = makeMutClosure(arg0, arg1, wasm.__wasm_bindgen_func_elem_25347, __wasm_bindgen_func_elem_25364);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000011: function(arg0) {
            // Cast intrinsic for `F64 -> Externref`.
            const ret = arg0;
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000012: function(arg0) {
            // Cast intrinsic for `I64 -> Externref`.
            const ret = arg0;
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000013: function(arg0, arg1) {
            var v0 = getCachedStringFromWasm0(arg0, arg1);
            // Cast intrinsic for `Ref(CachedString) -> Externref`.
            const ret = v0;
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000014: function(arg0, arg1) {
            // Cast intrinsic for `Ref(Slice(F32)) -> NamedExternref("Float32Array")`.
            const ret = getArrayF32FromWasm0(arg0, arg1);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000015: function(arg0, arg1) {
            // Cast intrinsic for `Ref(Slice(F64)) -> NamedExternref("Float64Array")`.
            const ret = getArrayF64FromWasm0(arg0, arg1);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000016: function(arg0, arg1) {
            // Cast intrinsic for `Ref(Slice(I32)) -> NamedExternref("Int32Array")`.
            const ret = getArrayI32FromWasm0(arg0, arg1);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000017: function(arg0, arg1) {
            // Cast intrinsic for `Ref(Slice(U32)) -> NamedExternref("Uint32Array")`.
            const ret = getArrayU32FromWasm0(arg0, arg1);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000018: function(arg0, arg1) {
            // Cast intrinsic for `Ref(Slice(U8)) -> NamedExternref("Uint8Array")`.
            const ret = getArrayU8FromWasm0(arg0, arg1);
            return addHeapObject(ret);
        },
        __wbindgen_cast_0000000000000019: function(arg0) {
            // Cast intrinsic for `U64 -> Externref`.
            const ret = BigInt.asUintN(64, arg0);
            return addHeapObject(ret);
        },
        __wbindgen_cast_000000000000001a: function(arg0, arg1) {
            var v0 = getArrayJsValueFromWasm0(arg0, arg1).slice();
            wasm.__wbindgen_export4(arg0, arg1 * 4, 4);
            // Cast intrinsic for `Vector(NamedExternref("string")) -> Externref`.
            const ret = v0;
            return addHeapObject(ret);
        },
        __wbindgen_cast_000000000000001b: function(arg0, arg1) {
            var v0 = getArrayU8FromWasm0(arg0, arg1).slice();
            wasm.__wbindgen_export4(arg0, arg1 * 1, 1);
            // Cast intrinsic for `Vector(U8) -> Externref`.
            const ret = v0;
            return addHeapObject(ret);
        },
        __wbindgen_object_clone_ref: function(arg0) {
            const ret = getObject(arg0);
            return addHeapObject(ret);
        },
        __wbindgen_object_drop_ref: function(arg0) {
            takeObject(arg0);
        },
        __wbindgen_object_is_undefined: function(arg0) {
            const ret = getObject(arg0) === undefined;
            return ret;
        },
    };
    return {
        __proto__: null,
        "./perspective-viewer.wasm_bg.js": import0,
    };
}

function __wasm_bindgen_func_elem_19632(arg0, arg1) {
    wasm.__wasm_bindgen_func_elem_19632(arg0, arg1);
}

function __wasm_bindgen_func_elem_21578(arg0, arg1) {
    wasm.__wasm_bindgen_func_elem_21578(arg0, arg1);
}

function __wasm_bindgen_func_elem_21577(arg0, arg1) {
    const ret = wasm.__wasm_bindgen_func_elem_21577(arg0, arg1);
    return takeObject(ret);
}

function __wasm_bindgen_func_elem_19039(arg0, arg1, arg2) {
    try {
        wasm.__wasm_bindgen_func_elem_19039(arg0, arg1, addBorrowedObject(arg2));
    } finally {
        heap[stack_pointer++] = undefined;
    }
}

function __wasm_bindgen_func_elem_7893(arg0, arg1, arg2) {
    wasm.__wasm_bindgen_func_elem_7893(arg0, arg1, addHeapObject(arg2));
}

function __wasm_bindgen_func_elem_7894(arg0, arg1, arg2) {
    wasm.__wasm_bindgen_func_elem_7894(arg0, arg1, addHeapObject(arg2));
}

function __wasm_bindgen_func_elem_7895(arg0, arg1) {
    try {
        const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
        wasm.__wasm_bindgen_func_elem_7895(retptr, arg0, arg1);
        var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
        var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
        var r2 = getDataViewMemory0().getInt32(retptr + 4 * 2, true);
        if (r2) {
            throw takeObject(r1);
        }
        return takeObject(r0);
    } finally {
        wasm.__wbindgen_add_to_stack_pointer(16);
    }
}

function __wasm_bindgen_func_elem_7891(arg0, arg1) {
    try {
        const retptr = wasm.__wbindgen_add_to_stack_pointer(-16);
        wasm.__wasm_bindgen_func_elem_7891(retptr, arg0, arg1);
        var r0 = getDataViewMemory0().getInt32(retptr + 4 * 0, true);
        var r1 = getDataViewMemory0().getInt32(retptr + 4 * 1, true);
        if (r1) {
            throw takeObject(r0);
        }
    } finally {
        wasm.__wbindgen_add_to_stack_pointer(16);
    }
}

function __wasm_bindgen_func_elem_19633(arg0, arg1, arg2) {
    wasm.__wasm_bindgen_func_elem_19633(arg0, arg1, addHeapObject(arg2));
}

function __wasm_bindgen_func_elem_25364(arg0, arg1, arg2) {
    wasm.__wasm_bindgen_func_elem_25364(arg0, arg1, addHeapObject(arg2));
}

function __wasm_bindgen_func_elem_33507(arg0, arg1, arg2, arg3) {
    wasm.__wasm_bindgen_func_elem_33507(arg0, arg1, addHeapObject(arg2), addHeapObject(arg3));
}


const __wbindgen_enum_ShadowRootMode = ["open", "closed"];
const ClientFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_client_free(ptr >>> 0, 1));
const GenericSQLVirtualServerModelFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_genericsqlvirtualservermodel_free(ptr >>> 0, 1));
const PerspectiveDebugPluginElementFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_perspectivedebugpluginelement_free(ptr >>> 0, 1));
const PerspectiveViewerElementFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_perspectiveviewerelement_free(ptr >>> 0, 1));
const ProxySessionFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_proxysession_free(ptr >>> 0, 1));
const TableFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_table_free(ptr >>> 0, 1));
const ViewFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_view_free(ptr >>> 0, 1));
const VirtualDataSliceFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_virtualdataslice_free(ptr >>> 0, 1));
const VirtualServerFinalization = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(ptr => wasm.__wbg_virtualserver_free(ptr >>> 0, 1));

function addHeapObject(obj) {
    if (heap_next === heap.length) heap.push(heap.length + 1);
    const idx = heap_next;
    heap_next = heap[idx];

    heap[idx] = obj;
    return idx;
}

function _assertClass(instance, klass) {
    if (!(instance instanceof klass)) {
        throw new Error(`expected instance of ${klass.name}`);
    }
}

function addBorrowedObject(obj) {
    if (stack_pointer == 1) throw new Error('out of js stack');
    heap[--stack_pointer] = obj;
    return stack_pointer;
}

const CLOSURE_DTORS = (typeof FinalizationRegistry === 'undefined')
    ? { register: () => {}, unregister: () => {} }
    : new FinalizationRegistry(state => state.dtor(state.a, state.b));

function debugString(val) {
    // primitive types
    const type = typeof val;
    if (type == 'number' || type == 'boolean' || val == null) {
        return  `${val}`;
    }
    if (type == 'string') {
        return `"${val}"`;
    }
    if (type == 'symbol') {
        const description = val.description;
        if (description == null) {
            return 'Symbol';
        } else {
            return `Symbol(${description})`;
        }
    }
    if (type == 'function') {
        const name = val.name;
        if (typeof name == 'string' && name.length > 0) {
            return `Function(${name})`;
        } else {
            return 'Function';
        }
    }
    // objects
    if (Array.isArray(val)) {
        const length = val.length;
        let debug = '[';
        if (length > 0) {
            debug += debugString(val[0]);
        }
        for(let i = 1; i < length; i++) {
            debug += ', ' + debugString(val[i]);
        }
        debug += ']';
        return debug;
    }
    // Test for built-in
    const builtInMatches = /\[object ([^\]]+)\]/.exec(toString.call(val));
    let className;
    if (builtInMatches && builtInMatches.length > 1) {
        className = builtInMatches[1];
    } else {
        // Failed to match the standard '[object ClassName]'
        return toString.call(val);
    }
    if (className == 'Object') {
        // we're a user defined class or Object
        // JSON.stringify avoids problems with cycles, and is generally much
        // easier than looping through ownProperties of `val`.
        try {
            return 'Object(' + JSON.stringify(val) + ')';
        } catch (_) {
            return 'Object';
        }
    }
    // errors
    if (val instanceof Error) {
        return `${val.name}: ${val.message}\n${val.stack}`;
    }
    // TODO we could test for more things here, like `Set`s and `Map`s.
    return className;
}

function dropObject(idx) {
    if (idx < 132) return;
    heap[idx] = heap_next;
    heap_next = idx;
}

function getArrayF32FromWasm0(ptr, len) {
    ptr = ptr >>> 0;
    return getFloat32ArrayMemory0().subarray(ptr / 4, ptr / 4 + len);
}

function getArrayF64FromWasm0(ptr, len) {
    ptr = ptr >>> 0;
    return getFloat64ArrayMemory0().subarray(ptr / 8, ptr / 8 + len);
}

function getArrayI32FromWasm0(ptr, len) {
    ptr = ptr >>> 0;
    return getInt32ArrayMemory0().subarray(ptr / 4, ptr / 4 + len);
}

function getArrayJsValueFromWasm0(ptr, len) {
    ptr = ptr >>> 0;
    const mem = getDataViewMemory0();
    const result = [];
    for (let i = ptr; i < ptr + 4 * len; i += 4) {
        result.push(takeObject(mem.getUint32(i, true)));
    }
    return result;
}

function getArrayU32FromWasm0(ptr, len) {
    ptr = ptr >>> 0;
    return getUint32ArrayMemory0().subarray(ptr / 4, ptr / 4 + len);
}

function getArrayU8FromWasm0(ptr, len) {
    ptr = ptr >>> 0;
    return getUint8ArrayMemory0().subarray(ptr / 1, ptr / 1 + len);
}

function getCachedStringFromWasm0(ptr, len) {
    if (ptr === 0) {
        return getObject(len);
    } else {
        return getStringFromWasm0(ptr, len);
    }
}

let cachedDataViewMemory0 = null;
function getDataViewMemory0() {
    if (cachedDataViewMemory0 === null || cachedDataViewMemory0.buffer.detached === true || (cachedDataViewMemory0.buffer.detached === undefined && cachedDataViewMemory0.buffer !== wasm.memory.buffer)) {
        cachedDataViewMemory0 = new DataView(wasm.memory.buffer);
    }
    return cachedDataViewMemory0;
}

let cachedFloat32ArrayMemory0 = null;
function getFloat32ArrayMemory0() {
    if (cachedFloat32ArrayMemory0 === null || cachedFloat32ArrayMemory0.byteLength === 0) {
        cachedFloat32ArrayMemory0 = new Float32Array(wasm.memory.buffer);
    }
    return cachedFloat32ArrayMemory0;
}

let cachedFloat64ArrayMemory0 = null;
function getFloat64ArrayMemory0() {
    if (cachedFloat64ArrayMemory0 === null || cachedFloat64ArrayMemory0.byteLength === 0) {
        cachedFloat64ArrayMemory0 = new Float64Array(wasm.memory.buffer);
    }
    return cachedFloat64ArrayMemory0;
}

let cachedInt32ArrayMemory0 = null;
function getInt32ArrayMemory0() {
    if (cachedInt32ArrayMemory0 === null || cachedInt32ArrayMemory0.byteLength === 0) {
        cachedInt32ArrayMemory0 = new Int32Array(wasm.memory.buffer);
    }
    return cachedInt32ArrayMemory0;
}

function getStringFromWasm0(ptr, len) {
    ptr = ptr >>> 0;
    return decodeText(ptr, len);
}

let cachedUint32ArrayMemory0 = null;
function getUint32ArrayMemory0() {
    if (cachedUint32ArrayMemory0 === null || cachedUint32ArrayMemory0.byteLength === 0) {
        cachedUint32ArrayMemory0 = new Uint32Array(wasm.memory.buffer);
    }
    return cachedUint32ArrayMemory0;
}

let cachedUint8ArrayMemory0 = null;
function getUint8ArrayMemory0() {
    if (cachedUint8ArrayMemory0 === null || cachedUint8ArrayMemory0.byteLength === 0) {
        cachedUint8ArrayMemory0 = new Uint8Array(wasm.memory.buffer);
    }
    return cachedUint8ArrayMemory0;
}

function getObject(idx) { return heap[idx]; }

function handleError(f, args) {
    try {
        return f.apply(this, args);
    } catch (e) {
        wasm.__wbindgen_export3(addHeapObject(e));
    }
}

let heap = new Array(128).fill(undefined);
heap.push(undefined, null, true, false);

let heap_next = heap.length;

function isLikeNone(x) {
    return x === undefined || x === null;
}

function makeClosure(arg0, arg1, dtor, f) {
    const state = { a: arg0, b: arg1, cnt: 1, dtor };
    const real = (...args) => {

        // First up with a closure we increment the internal reference
        // count. This ensures that the Rust closure environment won't
        // be deallocated while we're invoking it.
        state.cnt++;
        try {
            return f(state.a, state.b, ...args);
        } finally {
            real._wbg_cb_unref();
        }
    };
    real._wbg_cb_unref = () => {
        if (--state.cnt === 0) {
            state.dtor(state.a, state.b);
            state.a = 0;
            CLOSURE_DTORS.unregister(state);
        }
    };
    CLOSURE_DTORS.register(real, state, state);
    return real;
}

function makeMutClosure(arg0, arg1, dtor, f) {
    const state = { a: arg0, b: arg1, cnt: 1, dtor };
    const real = (...args) => {

        // First up with a closure we increment the internal reference
        // count. This ensures that the Rust closure environment won't
        // be deallocated while we're invoking it.
        state.cnt++;
        const a = state.a;
        state.a = 0;
        try {
            return f(a, state.b, ...args);
        } finally {
            state.a = a;
            real._wbg_cb_unref();
        }
    };
    real._wbg_cb_unref = () => {
        if (--state.cnt === 0) {
            state.dtor(state.a, state.b);
            state.a = 0;
            CLOSURE_DTORS.unregister(state);
        }
    };
    CLOSURE_DTORS.register(real, state, state);
    return real;
}

function passArray8ToWasm0(arg, malloc) {
    const ptr = malloc(arg.length * 1, 1) >>> 0;
    getUint8ArrayMemory0().set(arg, ptr / 1);
    WASM_VECTOR_LEN = arg.length;
    return ptr;
}

function passArrayJsValueToWasm0(array, malloc) {
    const ptr = malloc(array.length * 4, 4) >>> 0;
    const mem = getDataViewMemory0();
    for (let i = 0; i < array.length; i++) {
        mem.setUint32(ptr + 4 * i, addHeapObject(array[i]), true);
    }
    WASM_VECTOR_LEN = array.length;
    return ptr;
}

function passStringToWasm0(arg, malloc, realloc) {
    if (realloc === undefined) {
        const buf = cachedTextEncoder.encode(arg);
        const ptr = malloc(buf.length, 1) >>> 0;
        getUint8ArrayMemory0().subarray(ptr, ptr + buf.length).set(buf);
        WASM_VECTOR_LEN = buf.length;
        return ptr;
    }

    let len = arg.length;
    let ptr = malloc(len, 1) >>> 0;

    const mem = getUint8ArrayMemory0();

    let offset = 0;

    for (; offset < len; offset++) {
        const code = arg.charCodeAt(offset);
        if (code > 0x7F) break;
        mem[ptr + offset] = code;
    }
    if (offset !== len) {
        if (offset !== 0) {
            arg = arg.slice(offset);
        }
        ptr = realloc(ptr, len, len = offset + arg.length * 3, 1) >>> 0;
        const view = getUint8ArrayMemory0().subarray(ptr + offset, ptr + len);
        const ret = cachedTextEncoder.encodeInto(arg, view);

        offset += ret.written;
        ptr = realloc(ptr, len, offset, 1) >>> 0;
    }

    WASM_VECTOR_LEN = offset;
    return ptr;
}

let stack_pointer = 128;

function takeObject(idx) {
    const ret = getObject(idx);
    dropObject(idx);
    return ret;
}

let cachedTextDecoder = new TextDecoder('utf-8', { ignoreBOM: true, fatal: true });
cachedTextDecoder.decode();
const MAX_SAFARI_DECODE_BYTES = 2146435072;
let numBytesDecoded = 0;
function decodeText(ptr, len) {
    numBytesDecoded += len;
    if (numBytesDecoded >= MAX_SAFARI_DECODE_BYTES) {
        cachedTextDecoder = new TextDecoder('utf-8', { ignoreBOM: true, fatal: true });
        cachedTextDecoder.decode();
        numBytesDecoded = len;
    }
    return cachedTextDecoder.decode(getUint8ArrayMemory0().subarray(ptr, ptr + len));
}

const cachedTextEncoder = new TextEncoder();

let WASM_VECTOR_LEN = 0;

let wasmModule, wasm;
function __wbg_finalize_init(instance, module) {
    wasm = instance.exports;
    wasmModule = module;
    cachedDataViewMemory0 = null;
    cachedFloat32ArrayMemory0 = null;
    cachedFloat64ArrayMemory0 = null;
    cachedInt32ArrayMemory0 = null;
    cachedUint32ArrayMemory0 = null;
    cachedUint8ArrayMemory0 = null;
    return wasm;
}

async function __wbg_load(module, imports) {
    if (typeof Response === 'function' && module instanceof Response) {
        if (typeof WebAssembly.instantiateStreaming === 'function') {
            try {
                return await WebAssembly.instantiateStreaming(module, imports);
            } catch (e) {
                const validResponse = module.ok && expectedResponseType(module.type);

                if (validResponse && module.headers.get('Content-Type') !== 'application/wasm') {
                    console.warn("`WebAssembly.instantiateStreaming` failed because your server does not serve Wasm with `application/wasm` MIME type. Falling back to `WebAssembly.instantiate` which is slower. Original error:\n", e);

                } else { throw e; }
            }
        }

        const bytes = await module.arrayBuffer();
        return await WebAssembly.instantiate(bytes, imports);
    } else {
        const instance = await WebAssembly.instantiate(module, imports);

        if (instance instanceof WebAssembly.Instance) {
            return { instance, module };
        } else {
            return instance;
        }
    }

    function expectedResponseType(type) {
        switch (type) {
            case 'basic': case 'cors': case 'default': return true;
        }
        return false;
    }
}

function initSync(module) {
    if (wasm !== undefined) return wasm;


    if (module !== undefined) {
        if (Object.getPrototypeOf(module) === Object.prototype) {
            ({module} = module)
        } else {
            console.warn('using deprecated parameters for `initSync()`; pass a single object instead')
        }
    }

    const imports = __wbg_get_imports();
    if (!(module instanceof WebAssembly.Module)) {
        module = new WebAssembly.Module(module);
    }
    const instance = new WebAssembly.Instance(module, imports);
    return __wbg_finalize_init(instance, module);
}

async function __wbg_init(module_or_path) {
    if (wasm !== undefined) return wasm;


    if (module_or_path !== undefined) {
        if (Object.getPrototypeOf(module_or_path) === Object.prototype) {
            ({module_or_path} = module_or_path)
        } else {
            console.warn('using deprecated parameters for the initialization function; pass a single object instead')
        }
    }


    const imports = __wbg_get_imports();

    if (typeof module_or_path === 'string' || (typeof Request === 'function' && module_or_path instanceof Request) || (typeof URL === 'function' && module_or_path instanceof URL)) {
        module_or_path = fetch(module_or_path);
    }

    const { instance, module } = await __wbg_load(await module_or_path, imports);

    return __wbg_finalize_init(instance, module);
}

export { initSync, __wbg_init as default };
