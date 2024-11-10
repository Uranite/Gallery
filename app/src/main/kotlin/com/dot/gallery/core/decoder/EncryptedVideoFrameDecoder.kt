package com.dot.gallery.core.decoder

import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.BitmapParams
import androidx.exifinterface.media.ExifInterface
import com.dot.gallery.feature_node.data.data_source.KeychainHolder
import com.dot.gallery.feature_node.domain.model.EncryptedMedia
import com.github.panpf.sketch.Image
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.asSketchImage
import com.github.panpf.sketch.decode.DecodeException
import com.github.panpf.sketch.decode.Decoder
import com.github.panpf.sketch.decode.ImageInfo
import com.github.panpf.sketch.decode.ImageInvalidException
import com.github.panpf.sketch.decode.internal.DecodeHelper
import com.github.panpf.sketch.decode.internal.ExifOrientationHelper
import com.github.panpf.sketch.decode.internal.HelperDecoder
import com.github.panpf.sketch.decode.internal.newDecodeConfigByQualityParams
import com.github.panpf.sketch.fetch.FetchResult
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.request.get
import com.github.panpf.sketch.request.videoFrameMicros
import com.github.panpf.sketch.request.videoFrameOption
import com.github.panpf.sketch.request.videoFramePercent
import com.github.panpf.sketch.source.ContentDataSource
import com.github.panpf.sketch.source.DataSource
import com.github.panpf.sketch.source.FileDataSource
import com.github.panpf.sketch.source.getFileOrNull
import com.github.panpf.sketch.util.Rect
import com.github.panpf.sketch.util.Size
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class EncryptedVideoFrameDecoder(
    private val requestContext: RequestContext,
    private val dataSource: DataSource,
    private val mimeType: String,
) : HelperDecoder(
    requestContext = requestContext,
    dataSource = dataSource,
    decodeHelperFactory = {
        EncryptedVideoFrameDecodeHelper(
            sketch = requestContext.sketch,
            request = requestContext.request,
            dataSource = dataSource,
            mimeType = mimeType
        )
    }
) {

    class Factory : Decoder.Factory {

        override val key: String = "EncryptedVideoFrameDecoder"

        override fun create(
            requestContext: RequestContext,
            fetchResult: FetchResult
        ): EncryptedVideoFrameDecoder? {
            val dataSource = fetchResult.dataSource as? FileDataSource ?: return null
            val mimeType = requestContext.request.extras?.get("realMimeType") as String
            if (mimeType.startsWith("video")) {
                return EncryptedVideoFrameDecoder(
                    requestContext = requestContext,
                    dataSource = dataSource,
                    mimeType = mimeType
                )
            }
            return null
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other != null && this::class == other::class
        }

        override fun hashCode(): Int {
            return this::class.hashCode()
        }

        override fun toString(): String = "EncryptedVideoFrameDecoder"
    }
}

private class EncryptedVideoFrameDecodeHelper(
    val sketch: Sketch,
    val request: ImageRequest,
    val dataSource: DataSource,
    private val mimeType: String,
) : DecodeHelper {

    override val imageInfo: ImageInfo by lazy { readInfo() }
    override val supportRegion: Boolean = false

    private val keychainHolder = KeychainHolder(request.context)
    private val tempFile by lazy {
        dataSource.getFileOrNull(sketch)?.toFile()?.let { createDecryptedVideoFile(it) }
    }
    private val mediaMetadataRetriever by lazy {
        MediaMetadataRetriever().apply {
            if (dataSource is ContentDataSource) {
                setDataSource(request.context, dataSource.contentUri)
            } else {
                tempFile?.let {
                    setDataSource(it.path)
                } ?: throw Exception("Unsupported DataSource: ${dataSource::class}")
            }
        }
    }
    private val exifOrientation: Int by lazy { readExifOrientation() }
    private val exifOrientationHelper by lazy { ExifOrientationHelper(exifOrientation) }

    private fun createDecryptedVideoFile(file: File): File {
        // Create a temporary file
        val tempFile = File.createTempFile("${file.name}.temp", null)
        val encryptedMedia = with(keychainHolder) {
            file.decrypt<EncryptedMedia>()
        }

        // Write the ByteArray to the temporary file
        FileOutputStream(tempFile).use { fileOutputStream ->
            fileOutputStream.write(encryptedMedia.bytes)
            fileOutputStream.flush()
        }

        return tempFile
    }

    override fun decode(sampleSize: Int): Image {
        val config = request.newDecodeConfigByQualityParams(imageInfo.mimeType).apply {
            inSampleSize = sampleSize
        }
        val option = request.videoFrameOption ?: MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val frameMicros = request.videoFrameMicros
            ?: request.videoFramePercent?.let { percentDuration ->
                val duration = mediaMetadataRetriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                (duration * percentDuration * 1000).toLong()
            }
            ?: 0L

        val inSampleSize = config.inSampleSize?.toFloat()
        val dstWidth = if (inSampleSize != null) {
            (imageInfo.width / inSampleSize).roundToInt()
        } else {
            imageInfo.width
        }
        val dstHeight = if (inSampleSize != null) {
            (imageInfo.height / inSampleSize).roundToInt()
        } else {
            imageInfo.height
        }
        val bitmapParams = BitmapParams().apply {
            val inPreferredConfigFromRequest = config.inPreferredConfig
            if (inPreferredConfigFromRequest != null) {
                preferredConfig = inPreferredConfigFromRequest
            }
        }
        val bitmap = mediaMetadataRetriever
            .getScaledFrameAtTime(frameMicros, option, dstWidth, dstHeight, bitmapParams)
            ?: throw DecodeException(
                "Failed to getScaledFrameAtTime. frameMicros=%d, option=%s, dst=%dx%d, image=%dx%d, preferredConfig=%s.".format(
                    frameMicros, optionToName(option), dstWidth, dstHeight,
                    imageInfo.width, imageInfo.height, config.inPreferredConfig
                )
            )

        val image = bitmap.asSketchImage()
        val correctedImage = exifOrientationHelper.applyToImage(image) ?: image
        return correctedImage
    }

    override fun decodeRegion(region: Rect, sampleSize: Int): Image {
        throw UnsupportedOperationException("Unsupported region decode")
    }

    private fun readInfo(): ImageInfo {
        val srcWidth = mediaMetadataRetriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val srcHeight = mediaMetadataRetriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (srcWidth <= 1 || srcHeight <= 1) {
            val message = "Invalid video file. size=${srcWidth}x${srcHeight}"
            throw ImageInvalidException(message)
        }
        val imageSize = Size(width = srcWidth, height = srcHeight)
        val correctedImageSize = exifOrientationHelper.applyToSize(imageSize)
        return ImageInfo(size = correctedImageSize, mimeType = mimeType)
    }

    private fun readExifOrientation(): Int {
        val videoRotation = mediaMetadataRetriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        val exifOrientation = when (videoRotation) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_UNDEFINED
        }
        return exifOrientation
    }

    private fun optionToName(option: Int): String {
        return when (option) {
            MediaMetadataRetriever.OPTION_CLOSEST -> "CLOSEST"
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC -> "CLOSEST_SYNC"
            MediaMetadataRetriever.OPTION_NEXT_SYNC -> "NEXT_SYNC"
            MediaMetadataRetriever.OPTION_PREVIOUS_SYNC -> "PREVIOUS_SYNC"
            else -> "Unknown($option)"
        }
    }

    override fun toString(): String {
        return "VideoFrameDecodeHelper(uri='${request.uri}', dataSource=$dataSource, mimeType=$mimeType)"
    }

    override fun close() {
        mediaMetadataRetriever.close()
        tempFile?.delete()
    }
}