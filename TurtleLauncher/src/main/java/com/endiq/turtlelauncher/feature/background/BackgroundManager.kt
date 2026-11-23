package com.endiq.turtlelauncher.feature.background

import android.content.Context
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.endiq.turtlelauncher.InfoDistributor
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.file.FileTools.Companion.mkdirs
import com.endiq.turtlelauncher.utils.image.ImageUtils.Companion.isImage
import net.endiq.launcher.Tools
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.Properties

object BackgroundManager {
    private val FILE_BACKGROUND_PROPERTIES: File = File(PathManager.DIR_DATA, "background.properties")
    const val NULL: String = "null"

    /** Property suffixes for the optional looping background video of each surface. */
    private const val VIDEO_SUFFIX = "_VIDEO"
    private const val VIDEO_LOOP_SUFFIX = "_VIDEO_LOOP"
    private val VIDEO_EXTENSIONS = listOf("mp4", "webm", "mkv", "m4v", "3gp", "mov")

    private val defaultProperties: Properties
        get() {
            val properties = Properties()
            BackgroundType.values().forEach { type ->
                properties.setProperty(type.name, NULL)
                properties.setProperty(type.name + VIDEO_SUFFIX, NULL)
                properties.setProperty(type.name + VIDEO_LOOP_SUFFIX, "true")
            }
            return properties
        }

    val properties: Properties
        get() {
            val properties = defaultProperties
            if (!FILE_BACKGROUND_PROPERTIES.isFile) return properties
            runCatching {
                FileReader(FILE_BACKGROUND_PROPERTIES).use { fileReader -> properties.load(fileReader) }
            }.onFailure { e ->
                Logging.e("BackgroundManager", Tools.printToString(e))
            }
            return properties
        }

    @JvmStatic
    fun setBackgroundImage(
        context: Context,
        backgroundType: BackgroundType,
        backgroundView: ImageView,
        callback: CallbackDrawableImageViewTarget.Callback? = null
    ) {
        Glide.with(backgroundView).clear(backgroundView)
        // No dark placeholder: an opaque fill here was a second black layer over the video
        // whenever no still wallpaper had been chosen. Nothing is drawn unless an image is.
        backgroundView.setImageDrawable(null)

        val backgroundImage = getBackgroundModel(backgroundType) ?: run {
            callback?.callback(false)
            return
        }

        Glide.with(backgroundView).load(backgroundImage)
            .centerCrop()
            // A cross-fade makes a wallpaper change read as one scene turning into the
            // next instead of a hard cut between two bitmaps.
            .transition(DrawableTransitionOptions.withCrossFade(320))
            .into(CallbackDrawableImageViewTarget(backgroundView, callback))
    }

    @JvmStatic
    fun clearBackgroundImage(
        backgroundView: ImageView
    ) {
        Glide.with(backgroundView).clear(backgroundView)
        backgroundView.background = null
        backgroundView.setImageDrawable(null)
    }

    /** Stable keys, not translated labels or spinner indices, are persisted. */
    val builtInBackgrounds: Map<String, Int> = linkedMapOf(
        "builtin:turtle_coast" to R.drawable.image_background_turtle_coast,
        "builtin:amethyst_night" to R.drawable.image_background_amethyst_night,
        "builtin:ember_peaks" to R.drawable.image_background_ember_peaks,
        "builtin:error_art" to R.drawable.image_error_background
    )

    @JvmStatic
    fun getBackgroundModel(backgroundType: BackgroundType): Any? {
        val selection = properties.getProperty(backgroundType.name) ?: return null
        return builtInBackgrounds[selection] ?: getBackgroundImage(backgroundType)
    }

    @JvmStatic
    fun hasBackgroundImage(backgroundType: BackgroundType): Boolean =
        getBackgroundModel(backgroundType) != null

    /** Imported files remain supported exactly as before, alongside built-in choices. */
    fun getBackgroundImage(backgroundType: BackgroundType): File? {
        val name = properties.getProperty(backgroundType.name) ?: return null
        if (name == NULL || name.startsWith("builtin:")) return null
        val image = File(PathManager.DIR_BACKGROUND, name)
        if (image.parentFile?.canonicalFile != PathManager.DIR_BACKGROUND.canonicalFile) return null
        return image.takeIf { it.isFile && isImage(it) }
    }

    // ================================ Video ================================

    /** The imported looping video for [backgroundType], if one was selected. */
    @JvmStatic
    fun getBackgroundVideo(backgroundType: BackgroundType): File? {
        val name = properties.getProperty(backgroundType.name + VIDEO_SUFFIX) ?: return null
        if (name.isBlank() || name == NULL) return null
        val video = File(PathManager.DIR_BACKGROUND, name)
        if (video.parentFile?.canonicalFile != PathManager.DIR_BACKGROUND.canonicalFile) return null
        return video.takeIf { it.isFile && isVideo(it) }
    }

    @JvmStatic
    fun hasBackgroundVideo(backgroundType: BackgroundType): Boolean =
        getBackgroundVideo(backgroundType) != null

    /** Defaults to looping, which is what a background video is expected to do. */
    @JvmStatic
    fun isVideoLoopEnabled(backgroundType: BackgroundType): Boolean =
        properties.getProperty(backgroundType.name + VIDEO_LOOP_SUFFIX)?.toBooleanStrictOrNull() ?: true

    @JvmStatic
    fun setVideoLoop(backgroundType: BackgroundType, enabled: Boolean) {
        val props = properties
        props.setProperty(backgroundType.name + VIDEO_LOOP_SUFFIX, enabled.toString())
        saveProperties(props)
    }

    /** Pass `null` (or a blank name) to remove the video background of [backgroundType]. */
    @JvmStatic
    fun setBackgroundVideo(backgroundType: BackgroundType, fileName: String?) {
        val props = properties
        props.setProperty(
            backgroundType.name + VIDEO_SUFFIX,
            fileName?.takeIf { it.isNotBlank() } ?: NULL
        )
        saveProperties(props)
    }

    /** Extension check only: the file is played by the platform decoder, not by Glide. */
    @JvmStatic
    fun isVideo(file: File?): Boolean =
        file != null && file.isFile && VIDEO_EXTENSIONS.contains(file.extension.lowercase())

    // ==================== Launch panel picture / video ====================

    /** Property keys of the optional picture or clip shown in the home launch panel. */
    private const val PANEL_MEDIA_TYPE = "PANEL_MEDIA_TYPE"
    private const val PANEL_MEDIA_FILE = "PANEL_MEDIA_FILE"

    const val PANEL_MEDIA_IMAGE: String = "image"
    const val PANEL_MEDIA_VIDEO: String = "video"

    /** [PANEL_MEDIA_IMAGE], [PANEL_MEDIA_VIDEO] or `null` when the slot is empty. */
    @JvmStatic
    fun getPanelMediaType(): String? = when (properties.getProperty(PANEL_MEDIA_TYPE)) {
        PANEL_MEDIA_IMAGE, PANEL_MEDIA_VIDEO -> properties.getProperty(PANEL_MEDIA_TYPE)
        else -> null
    }

    /** The picture or clip of the launch panel, or `null` when nothing usable is stored. */
    @JvmStatic
    fun getPanelMedia(): File? {
        val type = getPanelMediaType() ?: return null
        val name = properties.getProperty(PANEL_MEDIA_FILE) ?: return null
        if (name.isBlank() || name == NULL) return null
        val media = File(PathManager.DIR_BACKGROUND, name)
        if (media.parentFile?.canonicalFile != PathManager.DIR_BACKGROUND.canonicalFile) return null
        if (!media.isFile) return null
        return if (type == PANEL_MEDIA_VIDEO) media.takeIf { isVideo(it) } else media
    }

    /** Pass `null` for either argument to clear the launch panel slot. */
    @JvmStatic
    fun setPanelMedia(type: String?, fileName: String?) {
        val props = properties
        val validType = type == PANEL_MEDIA_IMAGE || type == PANEL_MEDIA_VIDEO
        if (!validType || fileName.isNullOrBlank()) {
            props.setProperty(PANEL_MEDIA_TYPE, NULL)
            props.setProperty(PANEL_MEDIA_FILE, NULL)
        } else {
            props.setProperty(PANEL_MEDIA_TYPE, type)
            props.setProperty(PANEL_MEDIA_FILE, fileName)
        }
        saveProperties(props)
    }

    /**
     * Copies a picked [uri] into the background folder and stores it as the launch panel
     * picture or clip. Throws when the file is not something the panel can show.
     */
    @JvmStatic
    fun importPanelMedia(context: Context, uri: Uri, video: Boolean): File {
        PathManager.DIR_BACKGROUND.apply { if (!exists()) mkdirs(this) }
        val copied = FileTools.copyFileInBackground(context, uri, PathManager.DIR_BACKGROUND.absolutePath)
        val usable = if (video) isVideo(copied) else isImage(copied)
        if (!usable) {
            copied.delete()
            throw IllegalArgumentException(copied.name)
        }
        setPanelMedia(if (video) PANEL_MEDIA_VIDEO else PANEL_MEDIA_IMAGE, copied.name)
        return copied
    }

    private fun saveProperties(properties: Properties) {
        PathManager.DIR_BACKGROUND.apply {
            if (!exists()) mkdirs(this)
        }

        runCatching {
            FileWriter(FILE_BACKGROUND_PROPERTIES).use { writer ->
                properties.store(writer, "${InfoDistributor.APP_NAME} Background Properties File")
            }
        }.getOrElse { e -> Logging.e("saveProperties", Tools.printToString(e)) }
    }

    /**
     * Saves the image selections. The video keys already stored for the same surfaces are
     * kept, so picking a new image never silently forgets the user's video choice.
     */
    fun saveProperties(map: Map<BackgroundType, String>) {
        val props = properties
        map.forEach { (type, value) -> props.setProperty(type.name, value) }
        saveProperties(props)
    }
}
