package edu.westminsteru.capstone.cameraxapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.readBytes

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private val numPictures = 0

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
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.uploadButton.setOnClickListener { startListening() }
        viewBinding.interval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
//                viewBinding.intervalText.text = "Interval: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        cameraExecutor = Executors.newCachedThreadPool()
    }


    private fun startListening() {
        val t = Thread {
            CookieHandler.setDefault(CookieManager())
            val getCSRF = URL("http://192.168.86.153:8000/csrf/").openConnection()
                    as HttpURLConnection
            getCSRF.doInput = true
            //getCSRF.requestMethod = "GET"
            getCSRF.setRequestProperty("Connection", "Keep-Alive")

            val loginConnection = URL("http://192.168.86.153:8000/").openConnection()
                    as HttpURLConnection
            loginConnection.doOutput = true
            loginConnection.doInput = true
            loginConnection.useCaches = false
            loginConnection.setRequestMethod("POST")
            loginConnection.setRequestProperty("Connection", "Keep-Alive")

            val getInstructions = URL("http://192.168.86.153:8000/receive/").openConnection()
                    as HttpURLConnection
            getInstructions.doInput = true
            getInstructions.requestMethod = "GET"
            getInstructions.setRequestProperty("Connection", "Keep-Alive")

            try {
                getCSRF.connect()
                val out = InputStreamReader(getCSRF.inputStream, "UTF-8")
                var csrfToken = ""
                for (i in out.readLines()) {
                    if (i.lowercase().contains("csrftoken")) {
                        Log.d(TAG, "Got csrftoken: $i")
                        csrfToken = i.substring(i.indexOf(":") + 3, i.length - 2)
                    }
                }
                out.close()
                getCSRF.disconnect()

                Log.d(TAG, "CSRF Token: $csrfToken")

                loginConnection.connect()
                val login: OutputStream = loginConnection.outputStream
                login.write("csrfmiddlewaretoken=${csrfToken}&username=zoe&password=password".toByteArray())
                login.flush()
                CookieManager().cookieStore.cookies.forEach {
                    Log.d(TAG, "cookie: $it")
                    if (it.name.equals("csrftoken")) {
                        getInstructions.setRequestProperty("Cookie", "csrftoken=${it.value}")
                    } else if (it.name.equals("sessionid")) {
                        getInstructions.setRequestProperty("Cookie", "sessionid=${it.value}")
                    }
                }
                login.close()
                val loginStream = InputStreamReader(loginConnection.inputStream, "UTF-8")
//                Log.d(TAG, "Login Output:")
//                for (i in loginStream.readLines()) {
//                    Log.d(TAG, i)
//                }
                loginStream.close()
                loginConnection.disconnect()

                getInstructions.connect()
                val receive = InputStreamReader(getInstructions.inputStream, "UTF-8")

                Log.w(TAG, "Listening!")

                receive.readLines().forEach { line ->
                    when (line) {
                        "photo" -> takePhoto()
//                        "upload" -> uploadPhoto()
                        else -> {
                            Log.d(TAG, "Unknown command: $line")
                        }
                    }
                }
                receive.close()

            } catch (e: Exception) {
                Log.e(TAG, "Error: $e")
            } finally {
                getInstructions.disconnect()
            }
        }

        t.start()
    }


    @SuppressLint("ResourceType")
    private fun uploadPhoto(photo: ByteArray) {
        CookieHandler.setDefault(CookieManager())
        val BOUNDARY = "---------------------------" + System.currentTimeMillis()

        val getCSRF = URL("http://192.168.86.153:8000/csrf/").openConnection()
                as HttpURLConnection
        getCSRF.doInput = true
        getCSRF.setRequestProperty("Connection", "Keep-Alive")

        val loginConnection = URL("http://192.168.86.153:8000/").openConnection()
                as HttpURLConnection
        loginConnection.doOutput = true
        loginConnection.doInput = true
        loginConnection.useCaches = false
        loginConnection.setRequestMethod("POST")
        loginConnection.setRequestProperty("Connection", "Keep-Alive")

        val url = URL("http://192.168.86.153:8000/upload/")
        val urlConnection = url.openConnection() as HttpURLConnection
        urlConnection.doOutput = true
        urlConnection.doInput = true
        urlConnection.useCaches = false
        urlConnection.setRequestMethod("POST")
        urlConnection.setRequestProperty("Content-Type", "multipart/form-data;boundary=${BOUNDARY}")
        urlConnection.setRequestProperty("Connection", "Keep-Alive")

        val thread = Thread {
            Looper.prepare()

            try {
                getCSRF.connect()
                val out = InputStreamReader(getCSRF.inputStream, "UTF-8")
                var csrfToken = ""
                for (i in out.readLines()) {
                    if (i.lowercase().contains("csrftoken")) {
                        Log.d(TAG, "Got csrftoken: $i")
                        csrfToken = i.substring(i.indexOf(":") + 3, i.length - 2)
//                        loginConnection.setRequestProperty("csrftoken", csrfToken)
//                        urlConnection.setRequestProperty("csrftoken", csrfToken)
                    }
                }
                out.close()
                getCSRF.disconnect()

                Log.d(TAG, "CSRF Token: $csrfToken")

                loginConnection.connect()
                val login: OutputStream = loginConnection.outputStream
                login.write("csrfmiddlewaretoken=${csrfToken}&username=zoe&password=password".toByteArray())
                login.flush()
                CookieManager().cookieStore.cookies.forEach {
                    Log.d(TAG, "cookie: $it")
                    if (it.name.equals("csrftoken")) {
                        urlConnection.setRequestProperty("Cookie", "csrftoken=${it.value}")
                    } else if (it.name.equals("sessionid")) {
                        urlConnection.setRequestProperty("Cookie", "sessionid=${it.value}")
                    }
                }
                login.close()
                val loginStream = InputStreamReader(loginConnection.inputStream, "UTF-8")
//                Log.d(TAG, "Login Output:")
//                for (i in loginStream.readLines()) {
//                    Log.d(TAG, i)
//                }
                loginStream.close()
                loginConnection.disconnect()

                    //val file = File(Environment.DIRECTORY_DCIM, path)

                urlConnection.connect()
                val stream: OutputStream = urlConnection.outputStream
                stream.write("--${BOUNDARY}\r\n".toByteArray())
                stream.write("Content-Disposition: form-data; name=\"csrfmiddlewaretoken\"\r\n\r\n"
                    .toByteArray())
                stream.write("${csrfToken}\r\n".toByteArray())
                stream.write("--${BOUNDARY}\r\n".toByteArray())
                stream.write("Content-Disposition: form-data; name=\"user\"\r\n\r\n".toByteArray())
                stream.write("1\r\n".toByteArray())
                stream.write("--${BOUNDARY}\r\n".toByteArray())
                stream.write(("Content-Disposition: form-data; name=\"image\"; " +
                        "filename=\"" + photo.lastIndex + ".jpg\"\r\n\r\n").toByteArray()) //file.fileName
                stream.write(photo.inputStream().readBytes())
                stream.write("\r\n".toByteArray())
                stream.write("--${BOUNDARY}--\r\n".toByteArray())
                stream.flush()
                stream.close()
                val input = InputStreamReader(urlConnection.inputStream, "UTF-8")
//                print("output:")
//                for (i in input.readLines()) {
//                    Log.d(TAG, i)
//                }
                input.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                loginConnection.disconnect()
                urlConnection.disconnect()
            }
        }

        thread.start()
    }

    private fun takePhotoMultiple() {
        val t = Thread {
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
                put(Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                Images.Media.EXTERNAL_CONTENT_URI,
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
                        onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                    contentResolver.openInputStream(output.savedUri!!)?.use {
                        uploadPhoto(it.readBytes())
                    }
                    contentResolver.delete(output.savedUri!!, null, null)
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
        private val TAG: String = MainActivity::class.java.simpleName
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
