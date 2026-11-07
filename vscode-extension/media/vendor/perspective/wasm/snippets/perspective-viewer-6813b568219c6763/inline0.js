
    export function define_panel_tab(name) {
        if (!customElements.get(name)) {
            customElements.define(name, class extends HTMLElement {});
        }
    }
