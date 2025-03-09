package dev.techtitans.pose_estimation.camera

import android.graphics.*
import android.view.SurfaceView
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.techtitans.pose_estimation.data.BodyPart
import dev.techtitans.pose_estimation.data.Person
import dev.techtitans.pose_estimation.ml.PoseDetector
import java.io.ByteArrayOutputStream

/**
 * Interface for receiving processed frames with pose overlay
 */
interface FrameProcessorListener {
    fun onFrameProcessed(bitmap: Bitmap)
}

/**
 * Analyzer class for handling camera input and pose estimation processing
 */
class PoseEstimationAnalyzer(
    private val poseDetector: PoseDetector,
    private val surfaceView: SurfaceView,
    private val isFrontCamera: Boolean = false,
    private val frameListener: FrameProcessorListener? = null
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val rotatedBitmap = image.toRotatedBitmap(isFrontCamera)
        processImage(rotatedBitmap, poseDetector, surfaceView, frameListener)
        image.close()
    }
}

/**
 * This function processes camera input images to estimate human poses and draw lines on them
 */
private fun processImage(
    bitmap: Bitmap, 
    detector: PoseDetector, 
    surfaceView: SurfaceView,
    frameListener: FrameProcessorListener? = null
) {
    val person: Person = detector.estimateSinglePose(bitmap)
    visualize(person, bitmap, surfaceView, frameListener)
}

private const val MIN_CONFIDENCE = .3f

/**
 * Draw recognized pose on the surface view
 */
private fun visualize(
    person: Person, 
    bitmap: Bitmap, 
    surfaceView: SurfaceView,
    frameListener: FrameProcessorListener? = null
) {
    var outputBitmap = bitmap

    if (person.score > MIN_CONFIDENCE) {
        outputBitmap = VisualizationUtils.drawBodyKeypoints(bitmap, person)
    }
    
    // Notify listener about processed frame with pose overlay
    frameListener?.onFrameProcessed(outputBitmap)

    val holder = surfaceView.holder
    val surfaceCanvas = holder.lockCanvas()
    surfaceCanvas?.let { canvas ->
        val screenWidth: Int
        val screenHeight: Int
        val left: Int
        val top: Int

        if (canvas.height > canvas.width) {
            val ratio = outputBitmap.height.toFloat() / outputBitmap.width
            screenWidth = canvas.width
            left = 0
            screenHeight = (canvas.width * ratio).toInt()
            top = (canvas.height - screenHeight) / 2
        } else {
            val ratio = outputBitmap.width.toFloat() / outputBitmap.height
            screenHeight = canvas.height
            top = 0
            screenWidth = (canvas.height * ratio).toInt()
            left = (canvas.width - screenWidth) / 2
        }
        val right: Int = left + screenWidth
        val bottom: Int = top + screenHeight

        canvas.drawBitmap(
            outputBitmap, Rect(0, 0, outputBitmap.width, outputBitmap.height),
            Rect(left, top, right, bottom), null
        )
        surfaceView.holder.unlockCanvasAndPost(canvas)
    }
}

/**
 * Convert ImageProxy to rotated Bitmap based on camera orientation
 */
fun ImageProxy.toRotatedBitmap(isFrontCamera: Boolean = false): Bitmap {
    val yBuffer = planes[0].buffer
    val vuBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val vuSize = vuBuffer.remaining()

    val nv21 = ByteArray(ySize + vuSize)

    yBuffer.get(nv21, 0, ySize)
    vuBuffer.get(nv21, ySize, vuSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
    val imageBytes = out.toByteArray()
    val imageBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    val rotateMatrix = Matrix()
    
    // Front camera needs different rotation handling
    if (isFrontCamera) {
        // Rotate 270 degrees for front camera (instead of 90)
        rotateMatrix.postRotate(270.0f)
        // Apply horizontal flip
        rotateMatrix.postScale(-1f, 1f)
    } else {
        // Back camera rotation
        rotateMatrix.postRotate(90.0f)
    }

    return Bitmap.createBitmap(
        imageBitmap, 0, 0, this.width, this.height,
        rotateMatrix, false
    )
}

/**
 * Utilities for visualizing pose estimation results
 */
object VisualizationUtils {
    /** Radius of circle used to draw keypoints. */
    private const val CIRCLE_RADIUS = 6f

    /** Width of line used to connected two keypoints. */
    private const val LINE_WIDTH = 4f

    /** Pair of keypoints to draw lines between. */
    private val bodyJoints = listOf(
        Pair(BodyPart.NOSE, BodyPart.LEFT_EYE),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_EYE),
        Pair(BodyPart.LEFT_EYE, BodyPart.LEFT_EAR),
        Pair(BodyPart.RIGHT_EYE, BodyPart.RIGHT_EAR),
        Pair(BodyPart.NOSE, BodyPart.LEFT_SHOULDER),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_WRIST),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )

    /**
     * Draw lines and points to indicate body pose
     */
    fun drawBodyKeypoints(input: Bitmap, person: Person): Bitmap {
        val paintCircle = Paint().apply {
            strokeWidth = CIRCLE_RADIUS
            color = Color.RED
            style = Paint.Style.FILL
        }
        val paintLine = Paint().apply {
            strokeWidth = LINE_WIDTH
            color = Color.RED
            style = Paint.Style.FILL
        }

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val originalSizeCanvas = Canvas(output)
        bodyJoints.forEach {
            val pointA = person.keyPoints[it.first.position].coordinate
            val pointB = person.keyPoints[it.second.position].coordinate
            originalSizeCanvas.drawLine(pointA.x, pointA.y, pointB.x, pointB.y, paintLine)
        }

        person.keyPoints.forEach { point ->
            originalSizeCanvas.drawCircle(
                point.coordinate.x,
                point.coordinate.y,
                CIRCLE_RADIUS,
                paintCircle
            )
        }
        return output
    }
}