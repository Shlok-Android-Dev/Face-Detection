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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import com.example.facedetector.R.color.green
import com.example.facedetector.R.color.purple
import com.example.facedetector.R.color.red
import com.example.facedetector.R.color.skyblue
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

    val TAG = "MainActivity"
    private var isFlashOn = false // Keep track of flash state
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraId: String
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
    private lateinit var flashlightToggle: ImageView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logout = findViewById(R.id.logout)
        flipCam = findViewById(R.id.flip)
        flashlightToggle = findViewById(R.id.flash)
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

        // Initialize CameraManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager


        flashlightToggle.setOnClickListener {
            try {
                // Get the camera ID that has a flashlight (usually back-facing camera)
                cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: throw Exception("Flashlight not available on this device")
            } catch (e: Exception) {
                Log.e("MainActivity", "Flashlight is not available", e)
                flashlightToggle.visibility = View.GONE // Hide the toggle if no flash
            }

            // Set a click listener to toggle the flashlight on and off
            flashlightToggle.setOnClickListener {
                toggleFlashlight()
            }

        }


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
//                flashlightToggle.visibility = View.VISIBLE

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
            // Hide the flashlight toggle and turn off the flashlight when using the front camera
            flashlightToggle.visibility = View.GONE
            if (isFlashOn) {
                toggleFlashlight() // Ensure flashlight is turned off when switching to the front camera
            }
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            // Show the flashlight toggle when using the back camera
            flashlightToggle.visibility = View.VISIBLE
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

    // Function to toggle the flashlight
    private fun toggleFlashlight() {
        try {
            isFlashOn = !isFlashOn
            cameraManager.setTorchMode(cameraId, isFlashOn) // Turn the flashlight on/off
            updateFlashlightIcon()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to toggle flashlight", e)
        }
    }


    // Update the icon based on flash state
    private fun updateFlashlightIcon() {
        if (isFlashOn) {
            flashlightToggle.setImageResource(R.drawable.ic_flash_on) // Update icon to 'on'
        } else {
            flashlightToggle.setImageResource(R.drawable.ic_flash_off) // Update icon to 'off'
        }
    }

    // work properly but only functionality is qr code for text, url , or row data
    /*
        private fun detectQrCode(image: InputImage) {
            val scanner = BarcodeScanning.getClient()
            scanner.process(image).addOnSuccessListener { barcodes ->

                // Process each barcode found
                for (barcode in barcodes) {
                    Log.d("detectQrCode", "detectQrCode valueType: ${barcode.valueType}")
                    /*
                    Log.d("detectQrCode", "detectQrCode url: ${barcode.url}")
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
    */


    private fun detectQrCode(image: InputImage) {
        val scanner = BarcodeScanning.getClient()
        scanner.process(image).addOnSuccessListener { barcodes ->

            // Process each barcode found
            for (barcode in barcodes) {
                // Log all barcode properties
                Log.d("detectQrCode", "valueType: ${barcode.valueType}")
                Log.d("detectQrCode", "url: ${barcode.url}")
                Log.d("detectQrCode", "boundingBox: ${barcode.boundingBox}")
                Log.d("detectQrCode", "rawValue: ${barcode.rawValue}")
                Log.d("detectQrCode", "contactInfo: ${barcode.contactInfo}")
                Log.d("detectQrCode", "email: ${barcode.email}")
                Log.d("detectQrCode", "phone: ${barcode.phone}")
                Log.d("detectQrCode", "calendarEvent: ${barcode.calendarEvent}")
                Log.d("detectQrCode", "cornerPoints: ${barcode.cornerPoints}")
                Log.d("detectQrCode", "displayValue: ${barcode.displayValue}")
                Log.d("detectQrCode", "driverLicense: ${barcode.driverLicense}")
                Log.d("detectQrCode", "format: ${barcode.format}")
                Log.d("detectQrCode", "geoPoint: ${barcode.geoPoint}")
                Log.d("detectQrCode", "rawBytes: ${barcode.rawBytes}")
                Log.d("detectQrCode", "sms: ${barcode.sms}")
                Log.d("detectQrCode", "wifi: ${barcode.wifi}")

                // Handle each barcode type
                when (barcode.valueType) {
                    Barcode.TYPE_URL -> {
                        val url = barcode.url?.url
                        if (url != null) {

                            showToast("Click here to open Url")
                            runOnUiThread {
                                tvScannedData.text = url
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
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                    Barcode.TYPE_TEXT -> {
                        val text = barcode.rawValue
                        showToast("click here to copy text")
                        runOnUiThread {
                            tvScannedData.text = text
                            icYesPeople.visibility = View.GONE
                            icScan.visibility = View.VISIBLE

                            tvScannedData.setOnClickListener {
                                text?.let { it1 -> copyToClipboard(it1) }
                            }
                        }
                    }
                    Barcode.TYPE_CONTACT_INFO -> {
                      //  val contactInfo = barcode.contactInfo
                        val contactInfo = barcode.rawValue

                        /*val info = contactInfo?.let {
                            "Name: ${it.name?.formattedName}\n" +
                                    "Phone: ${it.phones?.firstOrNull()?.number}\n" +
                                    "Email: ${it.emails?.firstOrNull()?.address}"
                        } ?: barcode.rawValue*/

                        runOnUiThread {
                            ///tvScannedData.text = info
                            tvScannedData.text = contactInfo
                            icYesPeople.visibility = View.GONE
                            icScan.visibility = View.VISIBLE
                            tvScannedData.setBackgroundColor(getColor(R.color.white))
                            tvScannedData.setTextColor(
                                ContextCompat.getColor(
                                    this,
                                    yellow
                                )
                            )
                            showToast("click here to copy contact Info")
                            tvScannedData.setOnClickListener {
                                //     copyToClipboard(info ?: "")
                                copyToClipboard(contactInfo ?: "")
                            }
                        }
                    }
                    Barcode.TYPE_EMAIL -> {
                        val email = barcode.email
                        email?.let {
                            val emailAddress = it.address
                            showToast("Click here to redirect gmail")
                            runOnUiThread {
                                tvScannedData.text = emailAddress
                                icYesPeople.visibility = View.GONE
                                icScan.visibility = View.VISIBLE
                                tvScannedData.setBackgroundColor(getColor(R.color.white))
                                tvScannedData.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        red
                                    )
                                )

                                tvScannedData.setOnClickListener {
                                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$emailAddress"))
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                    Barcode.TYPE_PHONE -> {
                        val phone = barcode.phone?.number
                        if (phone != null) {
                            showToast("Click here to make a phone call!")
                            runOnUiThread {
                                tvScannedData.text = phone
                                icYesPeople.visibility = View.GONE
                                icScan.visibility = View.VISIBLE

                                tvScannedData.setBackgroundColor(getColor(R.color.white))
                                tvScannedData.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        green
                                    )
                                )

                                tvScannedData.setOnClickListener {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                    Barcode.TYPE_WIFI -> {
                        val wifi = barcode.wifi
                        if (wifi != null) {
                            showToast("Click here to open wifi")

                            val ssid = wifi.ssid
                            val password = wifi.password
                            val encryptionType = wifi.encryptionType
                            val securityType = when (encryptionType) {
                                Barcode.WiFi.TYPE_WEP -> "WEP"
                                Barcode.WiFi.TYPE_WPA -> "WPA"
                                else -> "Open"

                            }
                            runOnUiThread {
                                tvScannedData.text = "SSID: $ssid\nPassword: $password\nSecurity: $securityType"

                                icYesPeople.visibility = View.GONE
                                icScan.visibility = View.VISIBLE

                                tvScannedData.setBackgroundColor(getColor(R.color.white))
                                tvScannedData.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        purple
                                    )
                                )

                                tvScannedData.setOnClickListener {
                                    // Open Wi-Fi settings to allow the user to manually connect
                                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                    Barcode.TYPE_SMS -> {
                        val sms = barcode.sms
                        if (sms != null) {

                            showToast("Click here to open SMS")
                            val message = sms.message
                            val phoneNumber = sms.phoneNumber
                            runOnUiThread {
                                tvScannedData.text = "Send SMS to: $phoneNumber"
                                icYesPeople.visibility = View.GONE
                                icScan.visibility = View.VISIBLE
                                tvScannedData.setBackgroundColor(getColor(R.color.white))
                                tvScannedData.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        green
                                    )
                                )
                                tvScannedData.setOnClickListener {
                                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("smsto:$phoneNumber")
                                        putExtra("sms_body", message)
                                    }
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                    Barcode.TYPE_GEO -> {
                        val geoPoint = barcode.geoPoint
                        if (geoPoint != null) {

                            showToast("Click here to open location")
                            val lat = geoPoint.lat
                            val lng = geoPoint.lng
                            runOnUiThread {
                                tvScannedData.text = "Location: Lat: $lat, Lng: $lng"
                                icYesPeople.visibility = View.GONE
                                icScan.visibility = View.VISIBLE

                                tvScannedData.setBackgroundColor(getColor(R.color.white))
                                tvScannedData.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        green
                                    )
                                )

                                tvScannedData.setOnClickListener {
                                    val uri = Uri.parse("geo:$lat,$lng")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                    Barcode.TYPE_CALENDAR_EVENT -> {
                        val calendarEvent = barcode.calendarEvent
                        if (calendarEvent != null) {
                            showToast("Click here to open calendar event")
                            val title = calendarEvent.summary
                            val start = calendarEvent.start?.day.toString()
                            runOnUiThread {
                                tvScannedData.text = "Event: $title on $start"

                                icYesPeople.visibility = View.GONE
                                icScan.visibility = View.VISIBLE
                                tvScannedData.setBackgroundColor(getColor(R.color.white))
                                tvScannedData.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        purple
                                    )
                                )
                            }
                        }
                    }
                    Barcode.TYPE_DRIVER_LICENSE -> {
                        val driverLicense = barcode.driverLicense
                        if (driverLicense != null) {
                            showToast("Click here to check driver license")
                            val name = driverLicense.firstName + " " + driverLicense.lastName
                            runOnUiThread {
                                tvScannedData.text = "Driver's License for: $name"
                                icYesPeople.visibility = View.GONE
                                icScan.visibility = View.VISIBLE

                                tvScannedData.setBackgroundColor(getColor(R.color.white))
                                tvScannedData.setTextColor(
                                    ContextCompat.getColor(
                                        this,
                                        skyblue
                                    )
                                )

                            }
                        }
                    }
                    else -> {
                        Log.d("detectQrCode", "Unhandled QR code type detected")
                    }
                }
            }
        }.addOnFailureListener { e ->
            Log.e("detectQrCode", "QR code detection failed", e)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Scanned QR Code", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()

    }



}


/**
 *  version 3 camera detect face and capture image but mini preview is very bad
 */
/*
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
 */
/*package com.example.facedetector

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
 */
/*
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
