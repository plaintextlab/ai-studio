package com.plaintextlab.aistudio

import org.json.JSONArray
import org.json.JSONObject

/**
 * The studio's "3 - Quick image" workflow in ComfyUI's API format. The studio's
 * "Pick model" node chooses the model for the prompt on the server, and only that
 * branch runs:
 *   FLUX.2 Klein   edits, and anything not covered below (4 steps, negative through NAG)
 *   Z-Image Turbo  realistic people, also several interacting
 *   Lustify        explicit adult content (newest version on the server)
 *   Animagine      anime and illustration
 * The saved file is named after the model ([modelName] reads it back). A prompt
 * enhancer (Qwen3-4B, on the server) can rewrite the prompt in detail first.
 */
object Workflow {
    private const val NEGATIVE =
        "extra arms, extra legs, extra hands, extra fingers, missing fingers, fused fingers, " +
            "too many fingers, deformed hands, mutated hands, bad anatomy, deformed body, " +
            "distorted limbs, disfigured face, distorted face, asymmetrical eyes, cross-eyed, " +
            "cartoon, anime, painting, illustration, 3d render, cgi, plastic skin, waxy skin, " +
            "oversaturated, blurry, low quality, jpeg artifacts, watermark, text"

    private val MODEL_NAMES = mapOf(
        "flux2-klein" to "FLUX.2 Klein",
        "z-image" to "Z-Image Turbo",
        "lustify" to "Lustify",
        "animagine" to "Animagine",
    )

    /** "android_z-image_00001_.png" -> "Z-Image Turbo" */
    fun modelName(filename: String): String? =
        Regex("^android_(.+?)_\\d+_\\.png$").find(filename)?.groupValues?.get(1)?.let { MODEL_NAMES[it] ?: it }

    /** Node whose output is the prompt actually used (shown under the image). */
    const val PROMPT_USED_NODE = "74"

    /**
     * [editImage]: name of an uploaded picture to edit, or null for a new image.
     * [enhance]: rewrite the prompt in detail first (new images only), with the studio's
     * Qwen3-4B prompt enhancer.
     */
    fun build(prompt: String, width: Int, height: Int, editImage: String?, seed: Long, enhance: Boolean): JSONObject {
        val w = JSONObject()
        fun node(id: String, type: String, vararg inputs: Pair<String, Any>) {
            val i = JSONObject()
            for ((k, v) in inputs) i.put(k, v)
            w.put(id, JSONObject().put("class_type", type).put("inputs", i))
        }
        fun link(id: String, slot: Int = 0) = JSONArray().put(id).put(slot)

        // Pick model: 0 use_zimage, 1 use_lustify, 2 use_anime, 3 sdxl_checkpoint,
        // 4 positive (the enhanced prompt when enhancing), 5 negative, 6 label. It only
        // asks the enhancer (35, sharing Z-Image's Qwen3-4B) for text when it will use it.
        node("30", "StudioPickModel", "prompt" to prompt, "negative" to NEGATIVE,
            "editing" to (editImage != null), "model" to "Auto", "enhance" to enhance,
            "enhanced_prompt" to link("35"))
        node("35", "StudioEnhancePrompt", "clip" to link("41"), "prompt" to prompt)
        node(PROMPT_USED_NODE, "PreviewAny", "source" to link("30", 4))

        // FLUX.2 Klein.
        node("1", "UNETLoader", "unet_name" to "flux-2-klein-4b.safetensors", "weight_dtype" to "default")
        node("2", "CLIPLoader", "clip_name" to "qwen_3_4b.safetensors", "type" to "flux2", "device" to "default")
        node("3", "VAELoader", "vae_name" to "flux2-vae.safetensors")
        node("4", "CLIPTextEncode", "clip" to link("2"), "text" to link("30", 4))
        node("5", "CLIPTextEncode", "clip" to link("2"), "text" to NEGATIVE)
        node("6", "NAGuidance", "model" to link("1"), "nag_scale" to 5.0, "nag_alpha" to 0.5, "nag_tau" to 1.5)

        var positive = link("4")
        var negative = link("5")
        var outWidth: Any = width
        var outHeight: Any = height
        if (editImage != null) {
            // The picture (about 1 megapixel) is the model's reference; the result takes
            // its size. The negative gets it too: NAG needs both to have the same shape.
            node("10", "LoadImage", "image" to editImage)
            node("11", "ImageScaleToTotalPixels", "image" to link("10"), "upscale_method" to "lanczos",
                "megapixels" to 1.0, "resolution_steps" to 16)
            node("12", "VAEEncode", "pixels" to link("11"), "vae" to link("3"))
            node("13", "ReferenceLatent", "conditioning" to link("4"), "latent" to link("12"))
            node("14", "ReferenceLatent", "conditioning" to link("5"), "latent" to link("12"))
            node("15", "GetImageSize", "image" to link("11"))
            positive = link("13")
            negative = link("14")
            outWidth = link("15", 0)
            outHeight = link("15", 1)
        }
        node("20", "CFGGuider", "model" to link("6"), "positive" to positive, "negative" to negative, "cfg" to 1.0)
        node("21", "RandomNoise", "noise_seed" to seed)
        node("22", "KSamplerSelect", "sampler_name" to "euler")
        node("23", "Flux2Scheduler", "steps" to 4, "width" to outWidth, "height" to outHeight)
        node("24", "EmptyFlux2LatentImage", "width" to outWidth, "height" to outHeight, "batch_size" to 1)
        node("25", "SamplerCustomAdvanced", "noise" to link("21"), "guider" to link("20"),
            "sampler" to link("22"), "sigmas" to link("23"), "latent_image" to link("24"))
        node("26", "VAEDecode", "samples" to link("25"), "vae" to link("3"))

        // Z-Image Turbo (CFG 1, so its negative is not used).
        node("40", "UNETLoader", "unet_name" to "z_image_turbo_bf16.safetensors", "weight_dtype" to "default")
        node("41", "CLIPLoader", "clip_name" to "qwen_3_4b.safetensors", "type" to "lumina2", "device" to "default")
        node("42", "VAELoader", "vae_name" to "ae.safetensors")
        node("43", "ModelSamplingAuraFlow", "model" to link("40"), "shift" to 3)
        node("44", "CLIPTextEncode", "clip" to link("41"), "text" to link("30", 4))
        node("45", "CLIPTextEncode", "clip" to link("41"), "text" to link("30", 5))
        node("46", "EmptySD3LatentImage", "width" to width, "height" to height, "batch_size" to 1)
        node("47", "KSampler", "model" to link("43"), "positive" to link("44"), "negative" to link("45"),
            "latent_image" to link("46"), "seed" to seed, "steps" to 8, "cfg" to 1.0,
            "sampler_name" to "res_multistep", "scheduler" to "simple", "denoise" to 1.0)
        node("48", "VAEDecode", "samples" to link("47"), "vae" to link("42"))

        // SDXL specialists, loaded by the file name Pick model gives.
        fun sdxl(base: Int, steps: Int, cfg: Double, sampler: String, scheduler: String): JSONArray {
            val id = { n: Int -> (base + n).toString() }
            node(id(0), "StudioLoadCheckpointByName", "ckpt_name" to link("30", 3))
            node(id(1), "CLIPTextEncode", "clip" to link(id(0), 1), "text" to link("30", 4))
            node(id(2), "CLIPTextEncode", "clip" to link(id(0), 1), "text" to link("30", 5))
            node(id(3), "EmptyLatentImage", "width" to width, "height" to height, "batch_size" to 1)
            node(id(4), "KSampler", "model" to link(id(0)), "positive" to link(id(1)), "negative" to link(id(2)),
                "latent_image" to link(id(3)), "seed" to seed, "steps" to steps, "cfg" to cfg,
                "sampler_name" to sampler, "scheduler" to scheduler, "denoise" to 1.0)
            node(id(5), "VAEDecode", "samples" to link(id(4)), "vae" to link(id(0), 2))
            return link(id(5))
        }
        val lustify = sdxl(50, 30, 4.5, "dpmpp_2m_sde", "karras")
        val anime = sdxl(60, 28, 5.0, "euler_ancestral", "normal")

        // Only the picked branch runs: each switch asks for one of its inputs.
        node("70", "ComfySwitchNode", "switch" to link("30", 2), "on_false" to link("26"), "on_true" to anime)
        node("71", "ComfySwitchNode", "switch" to link("30", 1), "on_false" to link("70"), "on_true" to lustify)
        node("72", "ComfySwitchNode", "switch" to link("30", 0), "on_false" to link("71"), "on_true" to link("48"))
        node("73", "StringConcatenate", "string_a" to "images/android_", "string_b" to link("30", 6), "delimiter" to "")
        node("27", "SaveImage", "images" to link("72"), "filename_prefix" to link("73"))
        return w
    }
}
