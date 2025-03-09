package dev.techtitans.pose_estimation.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import dev.techtitans.pose_estimation.R
import dev.techtitans.pose_estimation.camera.FrameProcessorListener
import dev.techtitans.pose_estimation.camera.PoseEstimationAnalyzer
import dev.techtitans.pose_estimation.camera.VideoRecorder
import dev.techtitans.pose_estimation.data.Device
import dev.techtitans.pose_estimation.databinding.FragmentPoseEstimationBinding
import dev.techtitans.pose_estimation.ml.MoveNet
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main activity for the app
 */
class MainActivity : AppCompatActivity() {
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // keep screen on while app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Get the NavController
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainerViewMain) as NavHostFragment
        navController = navHostFragment.navController
        
        Log.d("MainActivity", "Navigation controller initialized with graph: ${navController.graph}")
    }
}

/**
 * Fragment for camera-based pose estimation
 */
class PoseEstimationFragment : Fragment(), FrameProcessorListener {

    private lateinit var binding: FragmentPoseEstimationBinding
    private lateinit var cameraExecutor: ExecutorService
    private var videoRecorder: VideoRecorder? = null
    private var isRecording = false
    private var videoUri: Uri? = null
    private var currentVideoFile: File? = null
    
    // Store preview dimensions to use for video recording
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    
    private val poseDetector by lazy {
        MoveNet.create(requireContext(), Device.CPU)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentPoseEstimationBinding.inflate(inflater, container, false)
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Setup recording button
        setupRecordingButton()
        
        return binding.root
    }
    
    private fun setupRecordingButton() {
        // Use the FloatingActionButton from the layout
        binding.recordButton?.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        } ?: Log.e("PoseEstimation", "Record button not found in layout")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        Log.d("PoseEstimation", "onViewCreated called")
        
        // Debug the SurfaceView
        binding.surfaceView2?.also { surfaceView ->
            Log.d("PoseEstimation", "SurfaceView reference: $surfaceView")
            
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d("PoseEstimation", "Surface created")
                }
    
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.d("PoseEstimation", "Surface changed: $width x $height")
                    // Store the preview dimensions for later use in video recording
                    previewWidth = width
                    previewHeight = height
                }
    
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d("PoseEstimation", "Surface destroyed")
                }
            })
        } ?: Log.e("PoseEstimation", "SurfaceView is null")
        
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        Log.d("PoseEstimation", "Checking camera permissions")
        
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        // Add storage permissions based on SDK version
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // For Android 9 (Pie) and lower
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For Android 13+
            requiredPermissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        
        if (permissionsToRequest.isEmpty()) {
            // All permissions already granted
            Log.d("PoseEstimation", "All permissions already granted")
            startCamera()
        } else {
            Log.d("PoseEstimation", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsToRequest,
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && 
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("PoseEstimation", "All permissions granted")
                startCamera()
            } else {
                Log.e("PoseEstimation", "Some permissions denied")
                Toast.makeText(
                    context,
                    "Camera and storage permissions are required",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startCamera() {
        Log.d("PoseEstimation", "Starting camera...")
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                
                Log.d("PoseEstimation", "Camera provider ready")
                
                // Configure the image analyzer
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Important!
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, PoseEstimationAnalyzer(
                            poseDetector,
                            binding.surfaceView2,
                            isFrontCamera = true,
                            frameListener = this)
                        )
                    }
                
                // Select front camera
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                
                try {
                    // Unbind any bound use cases
                    cameraProvider.unbindAll()
                    
                    // Bind the camera to lifecycle
                    val camera = cameraProvider.bindToLifecycle(
                        viewLifecycleOwner,  // Use viewLifecycleOwner instead of this
                        cameraSelector,
                        imageAnalyzer
                    )
                    
                    Log.d("PoseEstimation", "Camera bound successfully")
                    
                } catch(e: Exception) {
                    Log.e("PoseEstimation", "Camera binding failed", e)
                    Toast.makeText(
                        context,
                        "Failed to bind camera: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch(e: Exception) {
                Log.e("PoseEstimation", "Camera provider failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }
    
    // Calculate dimensions that match the preview aspect ratio but are divisible by 16
    // (required by most video encoders)
    private fun calculateVideoDimensions(): Pair<Int, Int> {
        // Default fallback values
        if (previewWidth <= 0 || previewHeight <= 0) {
            return Pair(720, 1280)  // Default dimensions
        }
        
        // Calculate aspect ratio
        val aspectRatio = previewWidth.toFloat() / previewHeight.toFloat()
        
        // We want the width to be divisible by 16, and height to maintain aspect ratio
        // Start with a width that's a multiple of 16
        var targetWidth = (previewWidth / 16) * 16
        // Limit max width to 1920 for performance
        targetWidth = minOf(targetWidth, 1920)
        
        // Calculate height based on width and aspect ratio
        var targetHeight = (targetWidth / aspectRatio).toInt()
        // Make height divisible by 16 too
        targetHeight = (targetHeight / 16) * 16
        
        Log.d(TAG, "Preview dimensions: $previewWidth x $previewHeight")
        Log.d(TAG, "Video dimensions: $targetWidth x $targetHeight")
        
        return Pair(targetWidth, targetHeight)
    }
    
    private fun startRecording() {
        val outputFile = createVideoFile()
        currentVideoFile = outputFile
        
        try {
            // Calculate dimensions based on the actual preview
            val (width, height) = calculateVideoDimensions()
            
            videoRecorder = VideoRecorder(
                width = width,
                height = height,
                frameRate = 30,
                outputFile = outputFile,
                onError = { e ->
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Recording error: ${e.message}", Toast.LENGTH_SHORT).show()
                        isRecording = false
                        binding.recordButton?.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            )
            
            videoRecorder?.prepare()
            isRecording = true
            binding.recordButton?.setImageResource(android.R.drawable.ic_media_pause)
            Toast.makeText(context, "Recording started", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("PoseEstimation", "Recording error", e)
        }
    }
    
    private fun stopRecording() {
        try {
            videoRecorder?.stop()
            videoRecorder = null
            isRecording = false
            binding.recordButton?.setImageResource(android.R.drawable.ic_media_play)
            
            // Add the video to the gallery
            addVideoToGallery()
            
        } catch (e: Exception) {
            Log.e("PoseEstimation", "Error stopping recording", e)
            Toast.makeText(context, "Error saving recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun addVideoToGallery() {
        currentVideoFile?.let { videoFile ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // For Android 10+, use MediaStore
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PoseEstimation")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    
                    val resolver = requireContext().contentResolver
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    
                    uri?.let {
                        resolver.openOutputStream(it)?.use { os ->
                            videoFile.inputStream().use { input ->
                                input.copyTo(os)
                            }
                        }
                        
                        // Now that the file is ready, clear the pending flag
                        contentValues.clear()
                        contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                        
                        Toast.makeText(context, "Video saved to gallery", Toast.LENGTH_SHORT).show()
                    } ?: run {
                        Toast.makeText(context, "Failed to create media entry", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // For older Android versions
                    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    val destDir = File(publicDir, "PoseEstimation")
                    if (!destDir.exists()) {
                        destDir.mkdirs()
                    }
                    
                    val destFile = File(destDir, videoFile.name)
                    videoFile.copyTo(destFile, overwrite = true)
                    
                    // Scan the file so it appears in the gallery
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destFile.toString()),
                        arrayOf("video/mp4"),
                        null
                    )
                    
                    Toast.makeText(context, "Video saved to gallery", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding video to gallery", e)
                Toast.makeText(context, "Could not add video to gallery: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(context, "No video file to save", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createVideoFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "POSE_$timestamp.mp4"
        
        // Always save to app-specific directory first for reliability
        val storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (storageDir?.exists() != true) {
            storageDir?.mkdirs()
        }
        
        return File(storageDir, fileName).apply {
            if (exists()) {
                delete()
            }
        }
    }

    // Helper method to view saved videos in the gallery
    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    // FrameProcessorListener implementation
    override fun onFrameProcessed(bitmap: Bitmap) {
        if (isRecording && videoRecorder != null) {
            try {
                videoRecorder?.drawFrame(bitmap)
            } catch (e: Exception) {
                Log.e("PoseEstimation", "Error in drawFrame", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (isRecording) {
            stopRecording()
        }
        poseDetector.close()
    }
    
    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
        private const val TAG = "PoseEstimationFragment"
    }
}