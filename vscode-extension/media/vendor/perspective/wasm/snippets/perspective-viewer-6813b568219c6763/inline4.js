
    export function bootstrap(psp, name, clsname, statics) {
        if (customElements.get(name)) {
            console.warn(`Custom element "${name}" is already registered; skipping duplicate registration (Perspective was loaded more than once on this page).`);
            return;
        }

        const cls = psp[clsname];
        const proto = cls.prototype;
        class x extends HTMLElement {
            constructor() {
                super();
                this._instance = new cls(this);
            }
        }

        const names = Object.getOwnPropertyNames(proto);
        for (const key of names) {
            if ('get' in Object.getOwnPropertyDescriptor(proto, key)) {
                Object.defineProperty(x.prototype, key, {
                    get: function() {
                        return this._instance[key];
                    }
                });
            } else {
                Object.defineProperty(x.prototype, key, {
                    value: function(...args) {
                        return this._instance[key].call(this._instance, ...args);
                    }
                });
            }
        }

        for (const key of statics) {
            Object.defineProperty(x, key, {
                value: function(...args) {
                    return psp[key].call(psp, ...args);
                }
            });
        }

        Object.defineProperty(x, '__wasm_module__', {
            get() {
                return psp;
            },
        });

        customElements.define(name, x);
    }
