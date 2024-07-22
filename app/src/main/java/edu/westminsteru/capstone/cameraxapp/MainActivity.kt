package edu.westminsteru.capstone.cameraxapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import edu.westminsteru.capstone.cameraxapp.databinding.ActivityMainBinding
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.PasswordAuthentication
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhotoMultiple() }
        viewBinding.uploadButton.setOnClickListener { uploadPhoto() }
        viewBinding.interval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
//                viewBinding.intervalText.text = "Interval: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    @SuppressLint("ResourceType")
    private fun uploadPhoto() {
        CookieHandler.setDefault(CookieManager())

        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication("zoe", "password".toCharArray())
            }
        })

//        val urlConnect = URL("http://192.168.86.153:8000/upload/").openConnection()
//                as HttpURLConnection
//        urlConnect.useCaches = true
//        urlConnect.setRequestProperty("Connection", "Keep-Alive")

        val url = URL("http://192.168.86.153:8000/upload/")
        val urlConnection = url.openConnection() as HttpURLConnection
        urlConnection.doOutput = true
        urlConnection.useCaches = true
        urlConnection.setRequestMethod("POST")
        urlConnection.setRequestProperty("Content-Type", "image/png")
        urlConnection.setRequestProperty("Connection", "Keep-Alive")

        val thread = Thread {
            Looper.prepare()
//            Toast.makeText(baseContext, "running", Toast.LENGTH_SHORT).show()

            try {
//                Authenticator.getPasswordAuthentication()
//                urlConnect.getContent()
//                CookieManager().cookieStore.cookies.forEach {
//                    Log.d(TAG, "cookie: $it")
//                    if (it.name.equals("csrftoken")) {
//                        urlConnect.setRequestProperty("Cookie", "csrftoken=${it.value}")
//                        urlConnection.setRequestProperty("Cookie", "csrftoken=${it.value}")
//                    }
//                }
//                val inget: InputStream = urlConnect.inputStream
//                inget.close()
//                urlConnect.disconnect()
                val file = resources.openRawResourceFd(R.drawable.testing)

                urlConnection.connect()
                val stream: OutputStream = urlConnection.outputStream
                val input: InputStream = urlConnection.inputStream
                CookieManager().cookieStore.cookies.forEach {
                    Log.d(TAG, "cookie: $it")
                    if (it.name.contains("csrftoken")) {
                        urlConnection.setRequestProperty("Cookie", "csrftoken=${it.value}")
                    }
                }
                stream.write(file.createInputStream().readBytes())
                stream.close()
                input.close()
                file.close()
                Toast.makeText(baseContext, "Uploaded photo", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        thread.start()
        thread.join()
        urlConnection.disconnect()
    }

    private fun takePhotoMultiple() {
        val t = Thread {
            Looper.prepare()
            for (i in 1..5) {
                takePhoto()
                Thread.sleep(5000)
            }
        }
        t.start()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
