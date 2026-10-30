"""Swap face, asking which one when there are several.

With one face in the picture it is replaced right away. With several, the node pauses
after the picture is made, shows the faces it found (numbered left to right) and waits
for an answer: in ComfyUI's page a dialog pops up (web/faces.js), and the phone app asks
through the same routes:

  GET  /studio/faces/pending[?prompt_id=...]  questions waiting for an answer
  POST /studio/faces/choose {"id", "choice"}  choice: a face's number, or 0 to keep them all

With no answer in FACE_CHOICE_TIMEOUT seconds the main (largest) face is replaced.
"""
import base64
import io
import threading
import time
import uuid

import numpy as np
import torch
from PIL import Image, ImageDraw, ImageFont

FACE_CHOICE_TIMEOUT = 15 * 60
# Faces narrower than this (pixels) are too small to swap well: background specks.
MIN_FACE_WIDTH = 24

_pending = {}  # id -> {"question": {...}, "event": Event, "choice": int | None}
_lock = threading.Lock()


def _data_url(img, size):
    img = img.copy()
    img.thumbnail((size, size))
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=85)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


def _numbered(img, faces):
    """The picture with each face boxed and numbered (1 = leftmost)."""
    out = img.copy()
    draw = ImageDraw.Draw(out)
    line = max(3, out.width // 250)
    try:
        font = ImageFont.load_default(size=max(24, out.width // 18))
    except TypeError:
        font = ImageFont.load_default()
    for n, f in enumerate(faces, 1):
        x0, y0, x1, y1 = [int(v) for v in f.bbox]
        draw.rectangle([x0, y0, x1, y1], outline=(255, 220, 0), width=line)
        label = str(n)
        box = draw.textbbox((0, 0), label, font=font)
        w, h = box[2] - box[0] + 2 * line, box[3] - box[1] + 2 * line
        ty = max(0, y0 - h - line)
        draw.rectangle([x0, ty, x0 + w + line, ty + h + line], fill=(255, 220, 0))
        draw.text((x0 + line, ty + line - box[1]), label, fill=(0, 0, 0), font=font)
    return out


def _crop(img, face):
    x0, y0, x1, y1 = face.bbox
    m = 0.35 * max(x1 - x0, y1 - y0)
    return img.crop((max(0, int(x0 - m)), max(0, int(y0 - m)),
                     min(img.width, int(x1 + m)), min(img.height, int(y1 + m))))


def _server():
    from server import PromptServer
    return PromptServer.instance


def _ask(img, faces, picture, pictures):
    """Waits for the user to pick a face; returns its number, 0 for none, None on timeout."""
    import comfy.model_management
    server = _server()
    rid = uuid.uuid4().hex
    question = {
        "id": rid,
        "prompt_id": getattr(server, "last_prompt_id", None),
        "client_id": server.client_id,
        "picture": picture,
        "pictures": pictures,
        "image": _data_url(_numbered(img, faces), 1024),
        "faces": [_data_url(_crop(img, f), 256) for f in faces],
        "created": time.time(),
    }
    entry = {"question": question, "event": threading.Event(), "choice": None}
    with _lock:
        _pending[rid] = entry
    try:
        server.send_sync("studio.faces", question, server.client_id)
        deadline = time.time() + FACE_CHOICE_TIMEOUT
        while not entry["event"].wait(0.5):
            comfy.model_management.throw_exception_if_processing_interrupted()
            if time.time() > deadline:
                print(f"[Swap face] no answer in {FACE_CHOICE_TIMEOUT // 60} min: replacing the main face")
                return None
        return entry["choice"]
    finally:
        with _lock:
            _pending.pop(rid, None)
        server.send_sync("studio.faces.done", {"id": rid}, None)


def _register_routes():
    from aiohttp import web
    routes = _server().routes

    @routes.get("/studio/faces/pending")
    async def pending(request):
        prompt_id = request.query.get("prompt_id")
        with _lock:
            questions = [e["question"] for e in _pending.values()
                         if not prompt_id or e["question"]["prompt_id"] == prompt_id]
        return web.json_response(questions)

    @routes.post("/studio/faces/choose")
    async def choose(request):
        body = await request.json()
        with _lock:
            entry = _pending.get(body.get("id"))
        if entry is None:
            return web.json_response({"error": "That question was already answered or has expired."}, status=404)
        choice = int(body.get("choice", 0))
        if not 0 <= choice <= len(entry["question"]["faces"]):
            return web.json_response({"error": "No such face."}, status=400)
        entry["choice"] = choice
        entry["event"].set()
        return web.json_response({"ok": True})


try:
    _register_routes()
except Exception as e:  # loaded outside ComfyUI (tests)
    print(f"[Swap face] routes not registered: {e}")


class SwapFace:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "image": ("IMAGE",),
                "face": ("IMAGE", {"tooltip": "A clear photo of the face to put in."}),
                "enabled": ("BOOLEAN", {"default": True, "label_on": "yes", "label_off": "no"}),
                "face_restore_visibility": ("FLOAT", {"default": 1.0, "min": 0.1, "max": 1.0, "step": 0.05}),
                "codeformer_weight": ("FLOAT", {"default": 0.5, "min": 0.0, "max": 1.0, "step": 0.05}),
            },
        }

    RETURN_TYPES = ("IMAGE",)
    FUNCTION = "swap"
    CATEGORY = "studio"
    DESCRIPTION = ("Puts the face on the person in the picture. When the picture has several faces, "
                   "it shows them and asks which one to replace.")

    def swap(self, image, face, enabled, face_restore_visibility, codeformer_weight):
        if not enabled:
            return (image,)
        from . import _reactor, SWAP_MODEL, FACE_DETECTION, RESTORE_MODEL
        swap_node, swapper = _reactor()
        out = []
        for i in range(image.shape[0]):
            current = image[i:i + 1]
            rgb = (current[0].cpu().numpy() * 255).clip(0, 255).astype(np.uint8)
            found = swapper.sort_by_order(swapper.analyze_faces(np.ascontiguousarray(rgb[..., ::-1])), "left-right")
            faces = [f for f in found if f.bbox[2] - f.bbox[0] >= MIN_FACE_WIDTH]
            if not faces:
                print(f"[Swap face] image {i + 1}: no face found")
                out.append(current)
                continue
            target = faces[0]
            if len(faces) > 1:
                choice = _ask(Image.fromarray(rgb), faces, i + 1, image.shape[0])
                if choice == 0:
                    out.append(current)
                    continue
                target = faces[choice - 1] if choice else swapper.sort_by_order(faces, "large-small")[0]
            # Faces are dicts holding arrays: find ReActor's index by identity, not ==.
            index = next(k for k, f in enumerate(found) if f is target)
            options = {"input_faces_order": "left-right", "input_faces_index": str(index),
                       "source_faces_order": "large-small", "source_faces_index": "0",
                       "detect_gender_input": "no", "detect_gender_source": "no",
                       "console_log_level": 1, "restore_swapped_only": True}
            current = swap_node().execute(True, current, SWAP_MODEL, FACE_DETECTION, RESTORE_MODEL,
                                          face_restore_visibility, codeformer_weight,
                                          source_image=face, options=options)[0]
            out.append(current.to(image.device))
        return (torch.cat(out, dim=0),)


NODE_CLASS_MAPPINGS = {"StudioSwapFace": SwapFace}
NODE_DISPLAY_NAME_MAPPINGS = {"StudioSwapFace": "Swap face (asks which when several)"}
