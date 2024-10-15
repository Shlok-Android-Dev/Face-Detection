//
package com.example.facedetector

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.facedetector.R.color.blue
import com.example.facedetector.R.color.yellow
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var isFrontCamera = true
    private var imageCaptured = false // To capture image only once
    private lateinit var logout: ImageView
    private lateinit var flipCam: ImageView
    private lateinit var cameraPreview: PreviewView
    private lateinit var miniPreview: ImageView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var icYesPeople: ImageView
    private lateinit var icScan: ImageView
    private lateinit var tvScannedData: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logout = findViewById(R.id.logout)
        flipCam = findViewById(R.id.flip)
        cameraPreview = findViewById(R.id.cameraPreview)
        miniPreview = findViewById(R.id.miniPreview)
        icYesPeople = findViewById(R.id.ic_yes_people)
        icScan = findViewById(R.id.ic_scan)
        tvScannedData = findViewById(R.id.tvScannedData)

        // Request camera permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        logout.setOnClickListener {
            finish()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Log.e("MainActivity", "Camera permission not granted")
        }
    }

    private fun allPermissionsGranted() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider) // Initial binding to the camera

            // Flip camera on button click
            flipCam.setOnClickListener {
                isFrontCamera = !isFrontCamera // Toggle between front and back camera
                cameraProvider.unbindAll() // Unbind all use cases
                bindCameraUseCases(cameraProvider) // Bind the camera with the new selector
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(cameraPreview.surfaceProvider)
        }

        // Select front or back camera based on the toggle state
        val cameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        try {
            // Bind the camera selector, preview, and image analysis to the lifecycle
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Use case binding failed", e)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage =
                InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detectFace(inputImage, imageProxy)

            // Detect QR codes
            detectQrCode(inputImage)
        }
    }

    private fun detectFace(image: InputImage, imageProxy: ImageProxy) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE).build()


        val detector = FaceDetection.getClient(options)
        detector.process(image).addOnSuccessListener { faces ->
            if (faces.size == 1 && !imageCaptured) { // Capture image only for one face

                runOnUiThread {
                    icYesPeople.visibility = View.VISIBLE
                    icScan.visibility = View.GONE
                }
                captureFace(faces.first(), imageProxy)
            } else {
                imageProxy.close() // Close if not a single face
            }
        }.addOnFailureListener { e ->
            Log.e("MainActivity", "Face detection failed", e)
            imageProxy.close()
        }
    }

    private fun detectQrCode(image: InputImage) {
        val scanner = BarcodeScanning.getClient()
        scanner.process(image).addOnSuccessListener { barcodes ->

            // Process each barcode found
            for (barcode in barcodes) {
                Log.d("detectQrCode", "detectQrCode valueType: ${barcode.valueType}")
                /*Log.d("detectQrCode", "detectQrCode url: ${barcode.url}")
                Log.d("detectQrCode", "detectQrCode boundingBox: ${barcode.boundingBox}")
                Log.d("detectQrCode", "detectQrCode raw rawValue: ${barcode.rawValue}")
                Log.d("detectQrCode", "detectQrCode contactInfo: ${barcode.contactInfo}")
                Log.d("detectQrCode", "detectQrCode email: ${barcode.email}")
                Log.d("detectQrCode", "detectQrCode phone: ${barcode.phone}")
                Log.d("detectQrCode", "detectQrCode calendar Event: ${barcode.calendarEvent}")
                Log.d("detectQrCode", "detectQrCode corner Points: ${barcode.cornerPoints}")
                Log.d("detectQrCode", "detectQrCode display Value: ${barcode.displayValue}")
                Log.d("detectQrCode", "detectQrCode driving License: ${barcode.driverLicense}")
                Log.d("detectQrCode", "detectQrCode format: ${barcode.format}")
                Log.d("detectQrCode", "detectQrCode geoPoint: ${barcode.geoPoint}")
                Log.d("detectQrCode", "detectQrCode raw Bytes: ${barcode.rawBytes}")
                Log.d("detectQrCode", "detectQrCode sma: ${barcode.sms}")
                Log.d("detectQrCode", "detectQrCode wifi : ${barcode.wifi}")*/
                when (barcode.valueType) {
                            Barcode.TYPE_URL -> {
                        val url = barcode.url?.url
                        Log.d("MainActivity", "QR Code URL: $url")

                        if (url != null) {
                            // Update TextView with URL
                            runOnUiThread {
                                icYesPeople.visibility = View.GONE
                                icScan.visibility = View.VISIBLE
                                tvScannedData.text = url // Update with new URL
                                tvScannedData.setBackgroundColor(getColor(R.color.white))
                                tvScannedData.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        blue
                                    )
                                )

                                // Set click listener for launching the URL in the browser
                                tvScannedData.setOnClickListener {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse(url)
                                    }
                                    startActivity(intent)
                                }
                            }
                        }
                    }

                    Barcode.TYPE_TEXT -> {
                        val scannedData = barcode.rawValue
                        // Update TextView with scanned text
                        runOnUiThread {
                            icYesPeople.visibility = View.GONE
                            icScan.visibility = View.VISIBLE
                            tvScannedData.text = scannedData // Display the scanned text
                        }
                    }

                    Barcode.TYPE_CONTACT_INFO -> {
                        val scannedData = barcode.rawValue
                        // Update TextView with scanned text
                        /*runOnUiThread {
                            icYesPeople.visibility = View.GONE
                            icScan.visibility = View.VISIBLE
                            tvScannedData.text = scannedData // Display the scanned text
                        }*/

                        runOnUiThread {
                            icYesPeople.visibility = View.GONE
                            icScan.visibility = View.VISIBLE

                            tvScannedData.setBackgroundColor(getColor(R.color.white))
                            tvScannedData.setTextColor(
                                ContextCompat.getColor(
                                    this,
                                    yellow
                                )
                            )
                            tvScannedData.text = scannedData // Display the scanned text

                            tvScannedData.setOnClickListener {

                                // Copy the scanned text to the clipboard
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Scanned QR Code", scannedData)
                                clipboard.setPrimaryClip(clip)

                                // Show a toast message indicating the text has been copied
                                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()

                                // Open the URL if it is a valid URL
                                if (Patterns.WEB_URL.matcher(scannedData).matches()) {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse(scannedData)
                                    }
                                    startActivity(intent)
                                }
                            }

                        }
                    }

                    else -> {
                        Log.d("MainActivity", "Other QR code type detected")
                    }
                }
            }
        }.addOnFailureListener { e ->
            Log.e("MainActivity", "QR code detection failed", e)
        }
    }


    @OptIn(ExperimentalGetImage::class)
    private fun captureFace(face: Face, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val bitmap = imageProxyToBitmap(imageProxy)
            val boundingBox: Rect = face.boundingBox

            val left = boundingBox.left.coerceAtLeast(0)
            val top = boundingBox.top.coerceAtLeast(0)
            val right = (boundingBox.left + boundingBox.width()).coerceAtMost(bitmap.width)
            val bottom = (boundingBox.top + boundingBox.height()).coerceAtMost(bitmap.height)

            val faceBitmap = Bitmap.createBitmap(
                bitmap, left, top, right - left, bottom - top
            )

            runOnUiThread {
                miniPreview.setImageBitmap(faceBitmap) // UI update on main thread
            }

            imageCaptured = true // Set flag to prevent further captures
            imageProxy.close()
            resetImageCapture()
        } else {
            imageProxy.close()
        }
    }


    private fun resetImageCapture() {
        // Reset the capture flag after a delay to allow for new face detection
        cameraExecutor.execute {
            Thread.sleep(1000) // Delay before allowing another capture
            imageCaptured = false
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        // Convert ImageProxy to Bitmap
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        uBuffer.get(nv21, ySize, uSize)
        vBuffer.get(nv21, ySize + uSize, vSize)

        // Create a YUV image and convert it to a JPEG for better color representation
        val yuvImage = android.graphics.YuvImage(
            nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()

        // Decode the JPEG bytes to a Bitmap
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)!!
            .rotate(imageProxy.imageInfo.rotationDegrees.toFloat())
    }

    private fun Bitmap.rotate(degrees: Float): Bitmap {
        // Rotate the bitmap based on the degrees
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}


/**
 *  version 3 camera detect face and capture image but mini preview is very bad
 *//*
package com.example.facedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: PreviewView
    private lateinit var miniPreview: ImageView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCaptured = false // To capture image only once

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.cameraPreview)
        miniPreview = findViewById(R.id.miniPreview)

        // Request camera permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Log.e("MainActivity", "Camera permission not granted")
        }
    }

    private fun allPermissionsGranted() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detectFace(inputImage, imageProxy)
        }
    }

    private fun detectFace(image: InputImage, imageProxy: ImageProxy) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()

        val detector = FaceDetection.getClient(options)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.size == 1 && !imageCaptured) { // Capture image only for one face
                    captureFace(faces.first(), imageProxy)
                } else {
                    imageProxy.close() // Close if not a single face
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Face detection failed", e)
                imageProxy.close()
            }
    }

    private fun captureFace(face: Face, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Convert the image to a Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)

            // Get the bounding box of the detected face
            val boundingBox: Rect = face.boundingBox

            // Ensure the bounding box coordinates are within valid bounds
            val left = boundingBox.left.coerceAtLeast(0)
            val top = boundingBox.top.coerceAtLeast(0)
            val right = (boundingBox.left + boundingBox.width()).coerceAtMost(bitmap.width)
            val bottom = (boundingBox.top + boundingBox.height()).coerceAtMost(bitmap.height)

            // Crop the bitmap to the detected face
            val faceBitmap = Bitmap.createBitmap(
                bitmap,
                left,
                top,
                right - left,
                bottom - top
            )

            // Set the cropped face bitmap to the mini preview
            miniPreview.setImageBitmap(faceBitmap)

            imageCaptured = true // Set flag to prevent further captures

            // Reset imageCaptured after a short delay to allow for new face detection
            imageProxy.close() // Close the current image proxy
            resetImageCapture()
        } else {
            imageProxy.close()
        }
    }


    private fun resetImageCapture() {
        cameraExecutor.execute {
            Thread.sleep(1000) // Delay for 1 second before resetting
            imageCaptured = false // Reset the flag to allow for new captures
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        // Convert ImageProxy to Bitmap
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        uBuffer.get(nv21, ySize, uSize)
        vBuffer.get(nv21, ySize + uSize, vSize)

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)!!
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
*/


/**
 *version 2 this can capture image flawless only one face at a time
 *//*package com.example.facedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: PreviewView
    private lateinit var miniPreview: ImageView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCaptured = false // To capture image only once

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.cameraPreview)
        miniPreview = findViewById(R.id.miniPreview)

        // Request camera permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Log.e("MainActivity", "Camera permission not granted")
        }
    }

    private fun allPermissionsGranted() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!imageCaptured) {
                    processImageProxy(imageProxy) // Process only if image hasn't been captured yet
                } else {
                    imageProxy.close() // Close imageProxy if already captured
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detectFace(inputImage, imageProxy)
        }
    }

    private fun detectFace(image: InputImage, imageProxy: ImageProxy) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()

        val detector = FaceDetection.getClient(options)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.size == 1 && !imageCaptured) { // Capture image only for one face
                    captureFace(faces.first(), imageProxy)
                } else {
                    imageProxy.close() // Close if not a single face
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Face detection failed", e)
                imageProxy.close()
            }
    }

    private fun captureFace(face: Face, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Convert the image to a Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)

            // Get the bounding box of the detected face
            val boundingBox: Rect = face.boundingBox

            // Crop the bitmap to the detected face
            val faceBitmap = Bitmap.createBitmap(
                bitmap,
                boundingBox.left.coerceAtLeast(0),
                boundingBox.top.coerceAtLeast(0),
                boundingBox.width().coerceAtMost(bitmap.width - boundingBox.left),
                boundingBox.height().coerceAtMost(bitmap.height - boundingBox.top)
            )

            // Set the cropped face bitmap to the mini preview
            miniPreview.setImageBitmap(faceBitmap)
            imageCaptured = true // Set flag to prevent further captures
        }
        imageProxy.close()
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        // Convert ImageProxy to Bitmap
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        uBuffer.get(nv21, ySize, uSize)
        vBuffer.get(nv21, ySize + uSize, vSize)

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)!!
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}*/


/**
 * version 1 only camera work properly
 *//*
package com.example.facedetector

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: PreviewView
    private lateinit var miniPreview: ImageView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCaptured = false // Flag to capture image only once

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.cameraPreview)
        miniPreview = findViewById(R.id.miniPreview)

        // Request camera permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Log.e("MainActivity", "Camera permission not granted")
        }
    }

    private fun allPermissionsGranted() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, { imageProxy ->
                if (!imageCaptured) {
                    processImageProxy(imageProxy) // Process only if the image hasn't been captured yet
                } else {
                    imageProxy.close() // Close imageProxy if already captured
                }
            })

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detectFace(inputImage, imageProxy)
        }
    }

    private fun detectFace(image: InputImage, imageProxy: ImageProxy) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()

        val detector = FaceDetection.getClient(options)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.size == 1 && !imageCaptured) { // Detect only one face and capture image
                    captureFace(faces.first(), imageProxy)
                } else {
                    imageProxy.close()
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "Face detection failed", e)
                imageProxy.close()
            }
    }

    private fun captureFace(face: Face, imageProxy: ImageProxy) {
        val bitmap = cameraPreview.bitmap
        if (bitmap != null) {
            val boundingBox = face.boundingBox

            // Adjust the bounding box to make sure it's within image bounds
            val faceBitmap = Bitmap.createBitmap(
                bitmap, boundingBox.left.coerceAtLeast(0), boundingBox.top.coerceAtLeast(0),
                boundingBox.width().coerceAtMost(bitmap.width - boundingBox.left),
                boundingBox.height().coerceAtMost(bitmap.height - boundingBox.top)
            )

            miniPreview.setImageBitmap(faceBitmap)
            imageCaptured = true // Set flag to prevent further captures
        }
        imageProxy.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
*/
