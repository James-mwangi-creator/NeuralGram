package com.neuralgram.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NeuralGram"
        private const val REQUEST_CAMERA = 100

        init {
            try {
                System.loadLibrary("neuralgram")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var btnFavorite: Button
    private lateinit var btnStats: Button
    private lateinit var btnClear: Button
    private lateinit var tvScene: TextView
    private lateinit var tvStats: TextView
    private lateinit var seekLearning: SeekBar

    // Camera
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var lastCapturedBitmap: Bitmap? = null

    // JNI Bridge
    external fun initPersonality()
    external fun processImage(input: ByteArray, output: ByteArray, width: Int, height: Int)
    external fun addFavorite(image: ByteArray, width: Int, height: Int,
                             exposure: Float, contrast: Float, saturation: Float,
                             sharpness: Float, warmth: Float)
    external fun getPersonalityStats(): String
    external fun getFavoriteCount(): Int
    external fun setLearningStrength(strength: Int)
    external fun clearPersonality()
    external fun testSystem()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        
        // Test JNI connection
        try {
            testSystem()
            Log.d(TAG, "JNI test successful")
        } catch (e: Exception) {
            Log.e(TAG, "JNI test failed", e)
            toast("Native library error: ${e.message}")
        }

        initPersonality()
        setupListeners()

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnStats = findViewById(R.id.btnStats)
        btnClear = findViewById(R.id.btnClear)
        tvScene = findViewById(R.id.tvScene)
        tvStats = findViewById(R.id.tvStats)
        seekLearning = findViewById(R.id.seekLearning)
    }

    private fun setupListeners() {
        btnCapture.setOnClickListener { takePhoto() }

        btnFavorite.setOnClickListener {
            val bmp = lastCapturedBitmap
            if (bmp == null) {
                toast("Capture a photo first!")
                return@setOnClickListener
            }
            val bytes = bitmapToRgbBytes(bmp)
            addFavorite(bytes, bmp.width, bmp.height,
                1.1f, 1.15f, 1.2f, 0.08f, 0.5f)
            toast("⭐ Favorite added! (${getFavoriteCount()} total)")
            refreshStats()
        }

        btnStats.setOnClickListener { refreshStats() }

        btnClear.setOnClickListener {
            clearPersonality()
            toast("Memory cleared")
            refreshStats()
        }

        seekLearning.max = 100
        seekLearning.progress = 75
        seekLearning.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                setLearningStrength(progress)
                tvScene.text = "Learning strength: $progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture
                )
                
                Log.d(TAG, "Camera started successfully")
                runOnUiThread { tvScene.text = "📷 Camera ready" }
                
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                runOnUiThread { tvScene.text = "❌ Camera error: ${e.message}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            toast("Camera not ready")
            return
        }

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                Log.d(TAG, "Image captured. Format: ${image.format}, Size: ${image.width}x${image.height}")
                
                val bmp = imageProxyToBitmap(image)
                image.close()

                if (bmp != null) {
                    processCapturedImage(bmp)
                } else {
                    runOnUiThread { 
                        toast("Failed to convert image")
                        tvScene.text = "❌ Image conversion failed"
                    }
                }
            }

            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Capture error", exc)
                runOnUiThread { 
                    toast("Capture failed: ${exc.message}")
                    tvScene.text = "❌ Capture failed"
                }
            }
        })
    }

    private fun processCapturedImage(bmp: Bitmap) {
        try {
            runOnUiThread {
                tvScene.text = "⚙️ Processing image..."
            }

            // Run NeuralGram enhancement
            val input = bitmapToRgbBytes(bmp)
            val output = ByteArray(input.size)
            
            Log.d(TAG, "Processing image: ${input.size} bytes")
            
            processImage(input, output, bmp.width, bmp.height)

            val enhanced = rgbBytesToBitmap(output, bmp.width, bmp.height)
            lastCapturedBitmap = enhanced

            runOnUiThread {
                tvScene.text = "✅ Photo enhanced by AI!"
                refreshStats()
                toast("Enhancement complete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Processing error", e)
            runOnUiThread {
                tvScene.text = "❌ Processing failed: ${e.message}"
                toast("Processing error")
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return when (image.format) {
            ImageFormat.JPEG -> imageProxyJpegToBitmap(image)
            else -> imageProxyYuvToBitmap(image)  // Handle YUV_420_888 format
        }
    }

    private fun imageProxyJpegToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "JPEG to bitmap failed", e)
            null
        }
    }

    private fun imageProxyYuvToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val yData = ByteArray(ySize)
            val uData = ByteArray(uSize)
            val vData = ByteArray(vSize)

            yBuffer.get(yData)
            uBuffer.get(uData)
            vBuffer.get(vData)

            val rgbaData = convertYuv420ToRgba(yData, uData, vData, image.width, image.height)
            Bitmap.createBitmap(rgbaData, image.width, image.height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "YUV to bitmap failed", e)
            null
        }
    }

    private fun convertYuv420ToRgba(yData: ByteArray, uData: ByteArray, vData: ByteArray, 
                                    width: Int, height: Int): IntArray {
        val rgba = IntArray(width * height)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIndex = y * width + x
                
                // U and V are subsampled (typically 4:2:0)
                val uvIndex = (y / 2) * (width / 2) + (x / 2)
                
                val yVal = yData[yIndex].toInt() and 0xFF
                val uVal = uData[uvIndex].toInt() and 0xFF
                val vVal = vData[uvIndex].toInt() and 0xFF

                // YUV to RGB conversion (standard BT.601)
                val r = (yVal + 1.402 * (vVal - 128)).toInt().coerceIn(0, 255)
                val g = (yVal - 0.34414 * (uVal - 128) - 0.71414 * (vVal - 128)).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772 * (uVal - 128)).toInt().coerceIn(0, 255)

                rgba[yIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return rgba
    }

    private fun bitmapToRgbBytes(bmp: Bitmap): ByteArray {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val bytes = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            bytes[i * 3] = ((pixels[i] shr 16) and 0xFF).toByte()
            bytes[i * 3 + 1] = ((pixels[i] shr 8) and 0xFF).toByte()
            bytes[i * 3 + 2] = (pixels[i] and 0xFF).toByte()
        }
        return bytes
    }

    private fun rgbBytesToBitmap(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = bytes[i * 3].toInt() and 0xFF
            val g = bytes[i * 3 + 1].toInt() and 0xFF
            val b = bytes[i * 3 + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun refreshStats() {
        try {
            tvStats.text = getPersonalityStats()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stats", e)
            tvStats.text = "Stats unavailable: ${e.message}"
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { 
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                toast("Camera permission required")
                tvScene.text = "❌ Camera permission denied"
            }
        }
    }
}
