"""Picks the model that suits a prompt, for the Quick image workflow and the phone app.

Each model is a specialist:
  FLUX.2 Klein   edits, and anything not covered below (detailed scenes, objects, text)
  Z-Image Turbo  realistic people, also several people interacting (wrestling, dancing)
  Lustify        explicit adult content (realistic SDXL)
  Animagine      anime, manga and illustration (SDXL)
The workflow has one branch per model behind lazy switches, so only the picked one runs.

It also passes on the enhanced prompt (written by Qwen3-4B from the user's) when
enhancing is on: a detailed scene for new images (ENHANCE_SYSTEM), a precise instruction
for edits (ENHANCE_EDIT_SYSTEM). The model is still picked from what the user typed.
"""
import re

import folder_paths
import nodes

MODELS = ["Auto", "FLUX.2 Klein", "Z-Image Turbo", "Lustify (adult)", "Animagine (anime)"]

# Newest first: the first one present in models/comfyui/checkpoints is used, so a newer
# version downloaded by hand (e.g. ZENITH from Civitai) is picked up without changes.
LUSTIFY = [
    "lustifyNSFWCheckpoint_zenithV9.safetensors",
    "lustifyNSFWCheckpoint_apexV8.safetensors",
    "lustifyNSFWCheckpoint_ggwpV7.safetensors",
    "lustifySDXLNSFW_oltFIXEDTEXTURES.safetensors",
    "lustifySDXLNSFW_endgame.safetensors",
    "lustifySDXLNSFWSFW_v40.safetensors",
    "lustifySDXLNSFWSFW_v20.safetensors",
]
ANIMAGINE = "animagine-xl-4.0-opt.safetensors"


def _words(*words):
    return re.compile(r"\b(" + "|".join(words) + r")\b", re.IGNORECASE)


ANIME = _words(r"anime", r"manga", r"cartoon\w*", r"illustrat\w*", r"chibi", r"waifu", r"hentai",
               r"2d", r"cel[- ]shad\w*", r"comic\w*", r"ghibli", r"visual novel", r"vtuber")
ADULT = _words(r"nsfw", r"nud(e|es|ity)", r"naked", r"topless", r"bottomless", r"nipples?",
               r"genitals?", r"penis", r"vagina", r"pussy", r"sex", r"sexual", r"intercourse",
               r"erotic\w*", r"porn\w*", r"explicit", r"uncensored", r"orgasm\w*", r"masturbat\w*",
               r"cum\w*", r"bdsm", r"fetish\w*", r"undress\w*", r"strip(ping|tease)")
PEOPLE = _words(r"wom[ae]n", r"m[ae]n", r"girls?", r"boys?", r"guys?", r"lad(y|ies)", r"person",
                r"people", r"couple", r"portrait", r"selfie", r"model", r"athletes?", r"wrestl\w*",
                r"fight\w*", r"box(er|ers|ing)", r"danc\w*", r"hug\w*", r"kiss\w*", r"crowd",
                r"family", r"friends", r"bride", r"groom", r"face", r"he", r"she", r"they",
                r"teen\w*", r"adults?", r"fitness", r"bodybuild\w*", r"instructor", r"actress",
                r"actor")

# System prompt for the prompt enhancer (EnhancePrompt, Qwen3-4B).
ENHANCE_SYSTEM = (
    "You rewrite a short or rough image request into one detailed prompt for an AI image model. "
    "Write one paragraph of 60 to 120 words in plain English: no lists, no quotes, no preamble, no title. "
    "Rules: keep every detail the user gave exactly as given: the subject, how many people and who they "
    "are, what they do, their clothing, the place (a bar stays a bar, a beach stays a beach), the time, "
    "the style and the mood. Never replace, soften or contradict any of it, and do not add text, captions "
    "or extra main characters. Then add concrete visual detail: appearance, expressions, poses and how "
    "people touch or interact, clothing materials, the setting and background, lighting, camera angle and "
    "lens, composition, colors and atmosphere. If the user names a style (anime, painting, 3D), describe "
    "it in that style; otherwise describe a realistic photograph. Output only the prompt.")

# For edits: one precise instruction, not a scene (FLUX.2 Klein sees the picture, the
# enhancer does not), saying what must stay as it is so the editor changes only that.
ENHANCE_EDIT_SYSTEM = (
    "You rewrite a short or rough request to edit a picture into one clear instruction for an AI image "
    "editor that can see the picture. Write 1 to 3 sentences, 20 to 60 words, in plain English: no lists, "
    "no quotes, no preamble. Start with the change itself as a direct command (Change, Add, Remove, "
    "Replace, Make, Turn). You cannot see the picture: never describe or guess what is in it now. "
    "Keep exactly what the user asked for, including where and to whom; when the change is for one "
    "person or thing, say that only that one changes (\"give only the woman a hat\"). Make the change "
    "specific: what the new or changed things look like (colors, materials, size, placement) and that "
    "their lighting and shadows match the picture. Do not invent other changes. End by saying that "
    "everything else stays exactly the same, including people's faces, bodies, poses and clothing, the "
    "background, the composition and the framing, unless the user asked to change them. "
    "Output only the instruction.")

# For YouTube thumbnails (Z-Image Turbo, which renders text well).
THUMBNAIL_SYSTEM = (
    "You write the prompt for one YouTube thumbnail for an AI image model. Write one paragraph of 60 to "
    "110 words, only in English words: no lists, no preamble. Keep the user's subject and idea. Make it a "
    "click-worthy thumbnail: one clear focal subject, large in the frame (usually a person with a strong, "
    "readable emotion, or the key object), a simple bold background with saturated colors and strong "
    "contrast, rim lighting and depth so the subject pops. If text for the thumbnail is given: put the "
    "subject in the left or right third of the frame and the text in the free space on the other side, "
    "so nothing overlaps it; write the text exactly once, word for word, in double quotes, as huge bold "
    "sans-serif letters with a thick dark outline, on at most two lines. If no text is given, the "
    "thumbnail has no text. No logos, watermarks or other words. Output only the prompt.")


def english_only(text):
    """Small Qwen models sometimes slip a Chinese word into English; Z-Image would draw it."""
    return re.sub(r"\s{2,}", " ", re.sub(r"[　-鿿가-힯＀-￯]+", " ", text)).strip()

# The chat assistant (Chat node, Qwen3.5-4B, which also sees pictures).
CHAT_SYSTEM = (
    "You are the creative assistant in AI Studio, a private image studio running on the user's own "
    "computer. You help the user develop characters, scenes and ideas, and write prompts for image models. "
    "Be friendly and direct, and answer in the user's language. When the user asks for a prompt, or one "
    "would help, write it as a single paragraph on its own after a line that says exactly 'Prompt:', with "
    "rich visual detail (appearance, body, clothing, pose, expression, setting, lighting, camera and lens, "
    "style), ready to paste into an image generator. When the user shares a picture, look at it carefully "
    "and use what you see. Keep a character's details consistent across the conversation.")

ANIME_TAGS = "masterpiece, high score, great score, absurdres"
ANIME_NEGATIVE = ("lowres, bad anatomy, bad hands, text, error, missing finger, extra digits, "
                  "fewer digits, cropped, worst quality, low quality, low score, bad score, "
                  "average score, signature, watermark, username, blurry")


def pick(prompt, model="Auto", editing=False):
    """The model for a prompt: 'klein', 'zimage', 'lustify' or 'anime'."""
    if editing:
        return "klein"  # the only one that edits pictures
    chosen = {"FLUX.2 Klein": "klein", "Z-Image Turbo": "zimage",
              "Lustify (adult)": "lustify", "Animagine (anime)": "anime"}.get(model)
    if chosen:
        return chosen
    if ANIME.search(prompt):
        return "anime"
    if ADULT.search(prompt):
        return "lustify"
    if PEOPLE.search(prompt):
        return "zimage"
    return "klein"


def checkpoint(route):
    """The SDXL file for a route. Loaded by name, so a missing model only stops its own
    route (ComfyUI checks every file named in a workflow before running any of it)."""
    present = set(folder_paths.get_filename_list("checkpoints"))
    for name in LUSTIFY if route == "lustify" else [ANIMAGINE]:
        if name in present:
            return name
    raise RuntimeError(f"No {route} model in models/comfyui/checkpoints yet "
                       "(restart the studio to download it).")


class PickModel:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "prompt": ("STRING", {"multiline": True}),
                "negative": ("STRING", {"multiline": True}),
                "editing": ("BOOLEAN", {"default": False}),
                "model": (MODELS, {"default": "Auto", "tooltip": "Auto picks from the prompt."}),
                "enhance": ("BOOLEAN", {"default": True,
                                        "tooltip": "Rewrite the prompt in detail first (an edit as a precise instruction)."}),
            },
            "optional": {
                # Lazy: only computed (the enhancer only runs) when it will be used.
                "enhanced_prompt": ("STRING", {"lazy": True, "forceInput": True}),
            },
        }

    def check_lazy_status(self, prompt, negative, editing, model, enhance, enhanced_prompt=None):
        return ["enhanced_prompt"] if enhance and enhanced_prompt is None else []

    RETURN_TYPES = ("BOOLEAN", "BOOLEAN", "BOOLEAN", "STRING", "STRING", "STRING", "STRING")
    RETURN_NAMES = ("use_zimage", "use_lustify", "use_anime", "sdxl_checkpoint",
                    "positive", "negative", "label")
    FUNCTION = "choose"
    CATEGORY = "studio"
    DESCRIPTION = ("Picks the best model for the prompt: Z-Image Turbo for realistic people, "
                   "Lustify for explicit adult content, Animagine for anime, FLUX.2 Klein for "
                   "edits and everything else.")

    def choose(self, prompt, negative, editing, model, enhance, enhanced_prompt=None):
        route = pick(prompt, model, editing)
        positive = enhanced_prompt if enhance and enhanced_prompt else prompt
        if route in ("lustify", "anime") and positive != prompt:
            # SDXL's CLIP weighs the start of a prompt most (77-token chunks): lead with the
            # user's own words so the detail cannot crowd them out.
            positive = f"{prompt}. {positive}"
        if route == "anime":
            positive = f"{positive}, {ANIME_TAGS}"
            negative = ANIME_NEGATIVE
        sdxl = checkpoint(route) if route in ("lustify", "anime") else ""
        label = {"klein": "flux2-klein", "zimage": "z-image", "lustify": "lustify",
                 "anime": "animagine"}[route]
        print(f"[Pick model] {label}" + (f" ({sdxl})" if sdxl else ""))
        return (route == "zimage", route == "lustify", route == "anime", sdxl,
                positive, negative, label)


class LoadCheckpointByName:
    """CheckpointLoaderSimple, but the file name comes in as text (from Pick model)."""

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"ckpt_name": ("STRING", {"default": ""})}}

    RETURN_TYPES = ("MODEL", "CLIP", "VAE")
    FUNCTION = "load"
    CATEGORY = "studio"

    def load(self, ckpt_name):
        return nodes.CheckpointLoaderSimple().load_checkpoint(ckpt_name)


class EnhancePrompt:
    """Rewrites a rough prompt in detail with a Qwen3 text encoder used as a language model
    (what ComfyUI's Generate Text node does, with these instructions and settings fixed).
    The same prompt gives the same text, so ComfyUI reuses it when only the seed changes."""

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"clip": ("CLIP",), "prompt": ("STRING", {"multiline": True}),
                             "editing": ("BOOLEAN", {"default": False})}}

    RETURN_TYPES = ("STRING",)
    RETURN_NAMES = ("enhanced_prompt",)
    FUNCTION = "enhance"
    CATEGORY = "studio"

    def enhance(self, clip, prompt, editing):
        system = ENHANCE_EDIT_SYSTEM if editing else ENHANCE_SYSTEM
        tokens = clip.tokenize(prompt, min_length=1, thinking=False, system_prompt=system)
        ids = clip.generate(tokens, do_sample=True, max_length=256, temperature=0.6, top_k=64,
                            top_p=0.95, min_p=0.05, repetition_penalty=1.05, seed=0)
        text = english_only(clip.decode(ids).split("</think>")[-1]).strip('"').strip()
        print(f"[Enhance prompt] {text}")
        return (text or prompt,)


class ThumbnailPrompt:
    """Writes a YouTube thumbnail prompt from an idea and the text to show on it. The text
    always ends up word for word in the prompt: it is added back if the writer changed it."""

    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {
            "clip": ("CLIP",),
            "idea": ("STRING", {"multiline": True}),
            "text": ("STRING", {"default": "", "tooltip": "Words on the thumbnail (empty: none)."}),
            "enhance": ("BOOLEAN", {"default": True}),
        }}

    RETURN_TYPES = ("STRING",)
    RETURN_NAMES = ("prompt",)
    FUNCTION = "write"
    CATEGORY = "studio"

    def write(self, clip, idea, text, enhance):
        text = text.strip().strip('"').strip()
        if enhance:
            request = f"Idea: {idea.strip()}\nText on the thumbnail: {text or 'none'}"
            tokens = clip.tokenize(request, min_length=1, thinking=False, system_prompt=THUMBNAIL_SYSTEM)
            ids = clip.generate(tokens, do_sample=True, max_length=256, temperature=0.6, top_k=64,
                                top_p=0.95, min_p=0.05, repetition_penalty=1.05, seed=0)
            prompt = english_only(clip.decode(ids).split("</think>")[-1])
        else:
            prompt = f"YouTube thumbnail, 16:9, bold and high contrast. {idea.strip()}"
            if not text:
                prompt += " No text."
        if text and f'"{text}"'.lower() not in prompt.lower():
            prompt += (f' Huge bold sans-serif text "{text}" with a thick dark outline, '
                       "placed where it does not cover any face.")
        print(f"[Thumbnail prompt] {prompt}")
        return (prompt,)


class Chat:
    """One reply from the chat assistant. The earlier conversation comes in as text
    ("User: ..." / "Assistant: ..." lines, as the phone app keeps it); a picture, when
    given, is shown to the model with the message."""

    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "clip": ("CLIP",),
                "message": ("STRING", {"multiline": True}),
                "history": ("STRING", {"multiline": True, "default": ""}),
                "max_length": ("INT", {"default": 1024, "min": 64, "max": 4096}),
                # A new seed gives a new answer (and keeps ComfyUI from reusing the last one).
                "seed": ("INT", {"default": 0, "min": 0, "max": 0xffffffffffffffff, "control_after_generate": True}),
            },
            "optional": {"image": ("IMAGE",)},
        }

    RETURN_TYPES = ("STRING",)
    RETURN_NAMES = ("reply",)
    FUNCTION = "reply"
    CATEGORY = "studio"

    def reply(self, clip, message, history, max_length, seed, image=None):
        text = message.strip()
        if history.strip():
            text = f"Our conversation so far:\n{history.strip()}\n\nMy new message:\n{text}"
        tokens = clip.tokenize(text, image=image, min_length=1, thinking=False, system_prompt=CHAT_SYSTEM)
        ids = clip.generate(tokens, do_sample=True, max_length=max_length, temperature=0.7, top_k=64,
                            top_p=0.95, min_p=0.05, repetition_penalty=1.05, seed=seed)
        return (clip.decode(ids).split("</think>")[-1].strip(),)


NODE_CLASS_MAPPINGS = {"StudioPickModel": PickModel, "StudioLoadCheckpointByName": LoadCheckpointByName,
                       "StudioEnhancePrompt": EnhancePrompt, "StudioThumbnailPrompt": ThumbnailPrompt,
                       "StudioChat": Chat}
NODE_DISPLAY_NAME_MAPPINGS = {"StudioPickModel": "Pick model (from the prompt)",
                              "StudioLoadCheckpointByName": "Load checkpoint by name",
                              "StudioEnhancePrompt": "Enhance prompt (Qwen3)",
                              "StudioThumbnailPrompt": "YouTube thumbnail prompt (Qwen3)",
                              "StudioChat": "Chat (Qwen3.5)"}
