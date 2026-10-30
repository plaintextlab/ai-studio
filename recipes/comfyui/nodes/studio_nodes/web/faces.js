// "Swap face" asks here which face to replace when a picture has several (see faces.py).
import { app } from "../../scripts/app.js";
import { api } from "../../scripts/api.js";

const open = new Map(); // question id -> overlay element

function close(id) {
    open.get(id)?.remove();
    open.delete(id);
}

async function choose(id, choice) {
    const res = await api.fetchApi("/studio/faces/choose", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id, choice }),
    });
    if (!res.ok) alert((await res.json()).error || "Could not send the choice");
    close(id);
}

function button(content, onClick) {
    const b = document.createElement("button");
    b.style.cssText = "background:#2b2b2b;border:2px solid #555;border-radius:10px;padding:6px;" +
        "color:#eee;cursor:pointer;display:flex;flex-direction:column;align-items:center;gap:4px;font-size:15px";
    b.onmouseenter = () => (b.style.borderColor = "#ffdc00");
    b.onmouseleave = () => (b.style.borderColor = "#555");
    b.append(...content);
    b.onclick = onClick;
    return b;
}

function ask(q) {
    if (open.has(q.id)) return;
    const overlay = document.createElement("div");
    overlay.style.cssText = "position:fixed;inset:0;z-index:10000;background:rgba(0,0,0,.75);" +
        "display:flex;align-items:center;justify-content:center;font-family:sans-serif";
    const box = document.createElement("div");
    box.style.cssText = "background:#1e1e1e;color:#eee;border-radius:14px;padding:20px;max-width:min(920px,94vw);" +
        "max-height:94vh;overflow:auto;display:flex;flex-direction:column;gap:14px";
    const title = document.createElement("div");
    title.style.cssText = "font-size:18px;font-weight:600";
    title.textContent = "Which face should be replaced?" +
        (q.pictures > 1 ? ` (picture ${q.picture} of ${q.pictures})` : "");
    const picture = document.createElement("img");
    picture.src = q.image;
    picture.style.cssText = "max-width:100%;max-height:50vh;object-fit:contain;border-radius:8px;align-self:center";
    const row = document.createElement("div");
    row.style.cssText = "display:flex;flex-wrap:wrap;gap:10px;justify-content:center";
    q.faces.forEach((src, i) => {
        const img = document.createElement("img");
        img.src = src;
        img.style.cssText = "width:120px;height:120px;object-fit:cover;border-radius:8px";
        const label = document.createElement("span");
        label.textContent = `Face ${i + 1}`;
        row.append(button([img, label], () => choose(q.id, i + 1)));
    });
    const keep = document.createElement("span");
    keep.textContent = "Keep all faces";
    const keepButton = button([keep], () => choose(q.id, 0));
    keepButton.style.alignSelf = "center";
    keepButton.style.padding = "10px 18px";
    box.append(title, picture, row, keepButton);
    overlay.append(box);
    document.body.append(overlay);
    open.set(q.id, overlay);
}

app.registerExtension({
    name: "studio.faces",
    async setup() {
        api.addEventListener("studio.faces", ({ detail }) => ask(detail));
        api.addEventListener("studio.faces.done", ({ detail }) => close(detail.id));
        // Questions asked while this page was closed or reloading.
        try {
            const pending = await (await api.fetchApi("/studio/faces/pending")).json();
            pending.filter((q) => q.client_id === api.clientId).forEach(ask);
        } catch (e) {
            console.warn("studio.faces: could not check for questions", e);
        }
    },
});
