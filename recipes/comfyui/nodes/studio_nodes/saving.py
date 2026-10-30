"""Save image with its prompt: every image goes in a folder of its own, next to the
prompt it was made with.

  outputs/comfyui/images/android_z-image_00012/
      android_z-image_00012.png
      prompt.txt    to read
      prompt.json   for the phone app: prompt, prompt used, negative, model, text, time, run

GET /studio/history?source=android lists those folders (newest last), and also the older
images saved as single files (android_*.png, before folders), whose prompt is read back
from the workflow ComfyUI stores in each PNG.
"""
import json
import os
import re
import time

import numpy as np
from PIL import Image
from PIL.PngImagePlugin import PngInfo

# Where the app's images go (see Workflow.kt); history only looks here.
HISTORY_DIRS = {"images": "image", "thumbnails": "thumbnail"}


def _next_number(folder, name):
    """One past the highest number used by `name`, as a folder or an older single file."""
    pattern = re.compile(rf"^{re.escape(name)}_(\d{{5}})(?:_\.png)?$")
    numbers = [int(m.group(1)) for entry in os.listdir(folder) if (m := pattern.match(entry))]
    return max(numbers, default=0) + 1


class SaveImageWithPrompt:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "images": ("IMAGE",),
                "filename_prefix": ("STRING", {"default": "images/studio",
                                               "tooltip": "Folder and name, e.g. images/quick"}),
                "prompt_text": ("STRING", {"forceInput": True, "tooltip": "The prompt as written"}),
            },
            "optional": {
                "prompt_used": ("STRING", {"forceInput": True, "tooltip": "The prompt after enhancing"}),
                "negative": ("STRING", {"forceInput": True}),
                "model": ("STRING", {"forceInput": True}),
                "text": ("STRING", {"forceInput": True, "tooltip": "Words on the image (thumbnails)"}),
            },
            "hidden": {"prompt": "PROMPT", "extra_pnginfo": "EXTRA_PNGINFO"},
        }

    RETURN_TYPES = ()
    FUNCTION = "save"
    OUTPUT_NODE = True
    CATEGORY = "studio"
    DESCRIPTION = "Saves each image in its own folder, with the prompt it was made with (prompt.txt)."

    def save(self, images, filename_prefix, prompt_text, prompt_used=None, negative=None, model=None,
             text=None, prompt=None, extra_pnginfo=None):
        import folder_paths
        from comfy.cli_args import args
        from server import PromptServer

        output = folder_paths.get_output_directory()
        parent = os.path.dirname(os.path.normpath(filename_prefix))
        name = os.path.basename(os.path.normpath(filename_prefix)) or "image"
        base = os.path.abspath(os.path.join(output, parent))
        if os.path.commonpath([base, os.path.abspath(output)]) != os.path.abspath(output):
            raise ValueError("The file name must stay inside the output folder.")
        os.makedirs(base, exist_ok=True)

        info = {"prompt": prompt_text, "prompt_used": prompt_used or prompt_text, "negative": negative or "",
                "model": model or "", "text": text or "", "created": time.strftime("%Y-%m-%d %H:%M:%S"),
                "run": getattr(PromptServer.instance, "last_prompt_id", None)}
        results = []
        number = _next_number(base, name)
        for image in images:
            stem = f"{name}_{number:05}"
            folder = os.path.join(base, stem)
            os.makedirs(folder)
            metadata = None
            if not args.disable_metadata:
                metadata = PngInfo()
                if prompt is not None:
                    metadata.add_text("prompt", json.dumps(prompt))
                for key, value in (extra_pnginfo or {}).items():
                    metadata.add_text(key, json.dumps(value))
            pixels = np.clip(255.0 * image.cpu().numpy(), 0, 255).astype(np.uint8)
            Image.fromarray(pixels).save(os.path.join(folder, f"{stem}.png"), pnginfo=metadata, compress_level=4)
            with open(os.path.join(folder, "prompt.json"), "w", encoding="utf-8") as f:
                json.dump({**info, "image": f"{stem}.png"}, f, indent=1, ensure_ascii=False)
            with open(os.path.join(folder, "prompt.txt"), "w", encoding="utf-8") as f:
                f.write(_readable(info))
            results.append({"filename": f"{stem}.png", "subfolder": os.path.join(parent, stem), "type": "output"})
            number += 1
        return {"ui": {"images": results}}


def _readable(info):
    lines = [f"Prompt:\n{info['prompt']}\n"]
    if info["prompt_used"] and info["prompt_used"] != info["prompt"]:
        lines.append(f"Prompt used (enhanced):\n{info['prompt_used']}\n")
    if info["text"]:
        lines.append(f"Text on the image: {info['text']}\n")
    if info["negative"]:
        lines.append(f"Negative prompt:\n{info['negative']}\n")
    if info["model"]:
        lines.append(f"Model: {info['model']}")
    lines.append(f"Created: {info['created']}")
    return "\n".join(lines) + "\n"


def _legacy_prompt(png_path):
    """Prompt (and thumbnail text) of an older single-file image, from its stored workflow."""
    try:
        with Image.open(png_path) as img:
            graph = json.loads(img.text.get("prompt", "{}"))
    except Exception:
        return "", ""
    texts = []
    for node in graph.values():
        inputs, kind = node.get("inputs", {}), node.get("class_type")
        if kind == "StudioThumbnailPrompt" and isinstance(inputs.get("idea"), str):
            return inputs["idea"], inputs.get("text", "") if isinstance(inputs.get("text"), str) else ""
        if kind == "StudioPickModel" and isinstance(inputs.get("prompt"), str):
            return inputs["prompt"], ""
        if kind == "CLIPTextEncode" and isinstance(inputs.get("text"), str) and inputs["text"].strip():
            texts.append(inputs["text"])
    return (texts[0] if texts else ""), ""


def history(source):
    """What `source` (a file name prefix, e.g. "android") made, oldest first."""
    import folder_paths
    output = folder_paths.get_output_directory()
    entries = []
    for top, mode in HISTORY_DIRS.items():
        root = os.path.join(output, top)
        if not os.path.isdir(root):
            continue
        for entry in os.listdir(root):
            if not entry.startswith(source):
                continue
            path = os.path.join(root, entry)
            meta = os.path.join(path, "prompt.json")
            if os.path.isfile(meta):
                try:
                    with open(meta, encoding="utf-8") as f:
                        info = json.load(f)
                except (OSError, ValueError):
                    continue
                entries.append({**info, "mode": mode, "subfolder": f"{top}/{entry}",
                                "filename": info.get("image", f"{entry}.png"), "time": os.path.getmtime(meta)})
            elif entry.endswith(".png") and os.path.isfile(path):
                prompt, text = _legacy_prompt(path)
                mtime = os.path.getmtime(path)
                entries.append({"prompt": prompt, "prompt_used": prompt, "text": text, "model": "",
                                "run": f"{top}/{entry}", "mode": mode, "subfolder": top, "filename": entry,
                                "created": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(mtime)),
                                "time": mtime})
    return sorted(entries, key=lambda e: e["time"])


def _register_routes():
    from aiohttp import web
    from server import PromptServer

    @PromptServer.instance.routes.get("/studio/history")
    async def list_history(request):
        source = request.query.get("source", "android")
        if not re.fullmatch(r"[\w-]+", source):
            return web.json_response({"error": "Bad source"}, status=400)
        return web.json_response(history(source))


try:
    _register_routes()
except Exception as e:  # loaded outside ComfyUI (tests)
    print(f"[Save image with prompt] routes not registered: {e}")


NODE_CLASS_MAPPINGS = {"StudioSaveImageWithPrompt": SaveImageWithPrompt}
NODE_DISPLAY_NAME_MAPPINGS = {"StudioSaveImageWithPrompt": "Save image with its prompt (own folder)"}
