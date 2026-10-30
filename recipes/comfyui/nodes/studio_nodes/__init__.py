"""Studio nodes for ComfyUI (loaded from recipes/comfyui/nodes by launch.py).

Swap main faces: puts each model's face on the right person in a scene. ReActor
numbers every face it detects, so in a scene with people in the background its
"left-right" index 0 can be a bystander. This node first keeps only the main faces
(the largest ones, as many as there are models), orders those left to right, and then
runs ReActor's own swap on each of them, image by image.

Swap face (faces.py): one face is replaced right away; with several, it asks which one
(a dialog in ComfyUI's page, web/faces.js, or the phone app).

Save image with its prompt (saving.py): each image in its own folder with prompt.txt.

Pick model (model_picker.py): chooses the specialist model for a prompt.
"""
import sys

import numpy as np
import torch

SWAP_MODEL = "inswapper_128.onnx"
FACE_DETECTION = "retinaface_resnet50"
RESTORE_MODEL = "codeformer-v0.1.0.pth"


def _reactor():
    """ReActor's swap node and face analysis, as loaded by ComfyUI."""
    import nodes
    swap_node = nodes.NODE_CLASS_MAPPINGS.get("ReActorFaceSwapOpt")
    swapper = next((m for name, m in list(sys.modules.items())
                    if name.endswith("reactor_swapper") and hasattr(m, "analyze_faces")), None)
    if swap_node is None or swapper is None:
        raise RuntimeError("Swap main faces needs the ReActor nodes (comfyui-reactor).")
    return swap_node, swapper


def _main_face_index(swapper, image, count, which):
    """Index, in ReActor's left-right order of all faces, of the `which`-th main face
    from the left (main faces: the `count` largest), or None when there is none."""
    rgb = (image.cpu().numpy() * 255).clip(0, 255).astype(np.uint8)
    faces = swapper.analyze_faces(np.ascontiguousarray(rgb[..., ::-1]))
    left_right = swapper.sort_by_order(faces, "left-right")
    main = swapper.sort_by_order(faces, "large-small")[:count]
    main = sorted(main, key=lambda f: f.bbox[0])
    if which >= len(main):
        return None
    return next(i for i, f in enumerate(left_right) if f is main[which])


class SwapMainFaces:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "image": ("IMAGE",),
                "model_1_face": ("IMAGE",),
                "main_people": ("INT", {"default": 2, "min": 1, "max": 6, "tooltip": "How many people the scene is about; their faces are the largest ones."}),
                "swap_model_1": ("BOOLEAN", {"default": True, "label_on": "yes", "label_off": "no"}),
                "swap_model_2": ("BOOLEAN", {"default": True, "label_on": "yes", "label_off": "no"}),
                "face_restore_visibility": ("FLOAT", {"default": 1.0, "min": 0.1, "max": 1.0, "step": 0.05}),
                "codeformer_weight": ("FLOAT", {"default": 0.5, "min": 0.0, "max": 1.0, "step": 0.05}),
            },
            "optional": {
                "model_2_face": ("IMAGE",),
            },
        }

    RETURN_TYPES = ("IMAGE",)
    FUNCTION = "swap"
    CATEGORY = "studio"
    DESCRIPTION = ("Puts Model 1's face on the left main person and Model 2's on the right one. "
                   "Main people are the largest faces, so people in the background are left alone.")

    def swap(self, image, model_1_face, main_people, swap_model_1, swap_model_2, face_restore_visibility,
             codeformer_weight, model_2_face=None):
        swap_node, swapper = _reactor()
        sources = [model_1_face if swap_model_1 else None,
                   model_2_face if swap_model_2 else None]
        out = []
        for i in range(image.shape[0]):
            current = image[i:i + 1]
            for which, source in enumerate(sources):
                if source is None:
                    continue
                index = _main_face_index(swapper, current[0], main_people, which)
                if index is None:
                    print(f"[Swap main faces] image {i + 1}: no face found for model {which + 1}")
                    continue
                options = {"input_faces_order": "left-right", "input_faces_index": str(index),
                           "source_faces_order": "large-small", "source_faces_index": "0",
                           "detect_gender_input": "no", "detect_gender_source": "no",
                           "console_log_level": 1, "restore_swapped_only": True}
                current = swap_node().execute(True, current, SWAP_MODEL, FACE_DETECTION, RESTORE_MODEL,
                                              face_restore_visibility, codeformer_weight,
                                              source_image=source, options=options)[0]
            out.append(current.to(image.device))
        return (torch.cat(out, dim=0),)


from .model_picker import NODE_CLASS_MAPPINGS as _PICK, NODE_DISPLAY_NAME_MAPPINGS as _PICK_NAMES

from .faces import NODE_CLASS_MAPPINGS as _FACES, NODE_DISPLAY_NAME_MAPPINGS as _FACES_NAMES
from .saving import NODE_CLASS_MAPPINGS as _SAVE, NODE_DISPLAY_NAME_MAPPINGS as _SAVE_NAMES

NODE_CLASS_MAPPINGS = {"StudioSwapMainFaces": SwapMainFaces, **_PICK, **_FACES, **_SAVE}
NODE_DISPLAY_NAME_MAPPINGS = {"StudioSwapMainFaces": "Swap main faces (left to right)", **_PICK_NAMES,
                              **_FACES_NAMES, **_SAVE_NAMES}
WEB_DIRECTORY = "./web"
