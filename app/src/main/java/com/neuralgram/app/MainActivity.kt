package com.neuralgram.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
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
            System.loadLibrary("neuralgram")
        }
    }

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var btnFavorite: Button
    private lateinit var btnStats: Button
    private lateinit var btnClear: Button
    private lateinit var tvScene: TextView
    private lateinit var tvStats: TextView
    private lateinit var seekLearning: SeekBar

    // ── Camera ────────────────────────────────────────────────────────────
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var lastCapturedBitmap: Bitmap? = null

    // ── JNI Bridge ────────────────────────────────────────────────────────
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

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
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

    // ── View Binding ──────────────────────────────────────────────────────
    private fun bindViews() {
        previewView  = findViewById(R.id.previewView)
        btnCapture   = findViewById(R.id.btnCapture)
        btnFavorite  = findViewById(R.id.btnFavorite)
        btnStats     = findViewById(R.id.btnStats)
        btnClear     = findViewById(R.id.btnClear)
        tvScene      = findViewById(R.id.tvScene)
        tvStats      = findViewById(R.id.tvStats)
        seekLearning = findViewById(R.id.seekLearning)
    }

    // ── Listeners ─────────────────────────────────────────────────────────
    private fun setupListeners() {
        btnCapture.setOnClickListener { takePhoto() }

        btnFavorite.setOnClickListener {
            val bmp = lastCapturedBitmap
            if (bmp == null) {
                toast("Capture a photo first!")
                return@setOnClickListener
            }
            val bytes = bitmapToRgbBytes(bmp)
            // Use neutral "liked" parameters — AI already learned from signature
            addFavorite(bytes, bmp.width, bmp.height,
                        1.1f, 1.15f, 1.2f, 0.08f, 0.5f)
            toast("⭐ Favourite added! (${getFavoriteCount()} total)")
            refreshStats()
        }

        btnStats.setOnClickListener { refreshStats() }

        btnClear.setOnClickListener {
            clearPersonality()
            toast("Memory cleared")
            refreshStats()
        }

        seekLearning.max      = 100
        seekLearning.progress = 75
        seekLearning.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                setLearningStrength(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Camera ────────────────────────────────────────────────────────────
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = imageProxyToBitmap(image)
                image.close()

                if (bmp != null) {
                    // Run NeuralGram enhancement
                    val input  = bitmapToRgbBytes(bmp)
                    val output = ByteArray(input.size)
                    processImage(input, output, bmp.width, bmp.height)

                    val enhanced = rgbBytesToBitmap(output, bmp.width, bmp.height)
                    lastCapturedBitmap = enhanced

                    runOnUiThread {
                        tvScene.text = "✅ Photo enhanced by AI!"
                        refreshStats()
                    }
                }
            }

            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Capture error", exc)
                runOnUiThread { toast("Capture failed: ${exc.message}") }
            }
        })
    }

    // ── Image Helpers ─────────────────────────────────────────────────────
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun bitmapToRgbBytes(bmp: Bitmap): ByteArray {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val bytes = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            bytes[i * 3]     = ((pixels[i] shr 16) and 0xFF).toByte()
            bytes[i * 3 + 1] = ((pixels[i] shr 8)  and 0xFF).toByte()
            bytes[i * 3 + 2] = (pixels[i]           and 0xFF).toByte()
        }
        return bytes
    }

    private fun rgbBytesToBitmap(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = bytes[i * 3].toInt()     and 0xFF
            val g = bytes[i * 3 + 1].toInt() and 0xFF
            val b = bytes[i * 3 + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // ── UI Helpers ────────────────────────────────────────────────────────
    private fun refreshStats() {
        tvStats.text = getPersonalityStats()
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ── Permissions ───────────────────────────────────────────────────────
    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA),
                                          REQUEST_CAMERA)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else
            toast("Camera permission required")
    }
}
