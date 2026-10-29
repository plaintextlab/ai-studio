package com.plaintextlab.aistudio

import org.json.JSONArray
import org.json.JSONObject

/**
 * The studio's "3 - Quick image" workflow in ComfyUI's API format: FLUX.2 Klein 4B
 * (4 steps) makes an image from the prompt, or edits a picture following it. The
 * negative prompt works through Normalized Attention Guidance, since the model runs at
 * CFG 1. Uses the models the studio downloads with ComfyUI.
 */
object Workflow {
    private const val NEGATIVE =
        "extra arms, extra legs, extra hands, extra fingers, missing fingers, fused fingers, " +
            "too many fingers, deformed hands, mutated hands, bad anatomy, deformed body, " +
            "distorted limbs, disfigured face, distorted face, asymmetrical eyes, cross-eyed, " +
            "cartoon, anime, painting, illustration, 3d render, cgi, plastic skin, waxy skin, " +
            "oversaturated, blurry, low quality, jpeg artifacts, watermark, text"

    /** [editImage]: name of an uploaded picture to edit, or null for a new image. */
    fun build(prompt: String, width: Int, height: Int, editImage: String?, seed: Long): JSONObject {
        val w = JSONObject()
        fun node(id: String, type: String, vararg inputs: Pair<String, Any>) {
            val i = JSONObject()
            for ((k, v) in inputs) i.put(k, v)
            w.put(id, JSONObject().put("class_type", type).put("inputs", i))
        }
        fun link(id: String, slot: Int = 0) = JSONArray().put(id).put(slot)

        node("1", "UNETLoader", "unet_name" to "flux-2-klein-4b.safetensors", "weight_dtype" to "default")
        node("2", "CLIPLoader", "clip_name" to "qwen_3_4b.safetensors", "type" to "flux2", "device" to "default")
        node("3", "VAELoader", "vae_name" to "flux2-vae.safetensors")
        node("4", "CLIPTextEncode", "clip" to link("2"), "text" to prompt)
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
        node("27", "SaveImage", "images" to link("26"), "filename_prefix" to "images/android")
        return w
    }
}
