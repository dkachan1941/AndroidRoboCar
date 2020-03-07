package com.example.androidrobocar

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.ImageReader
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.google.android.things.contrib.driver.motorhat.MotorHat
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.text.format.Formatter
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager

import io.ktor.application.call
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.opencv.android.*
import org.opencv.core.CvType
import java.util.*

@KtorExperimentalAPI
class MainActivity : AppCompatActivity(), SensorEventListener {
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.i("Robocar", "sensor accuracy changed: $accuracy")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        Log.i(
            "Robocar",
            String.format(Locale.getDefault(), "sensor changed: [%f]", event?.values?.getOrNull(0))
        )
    }

    private val firstWheelIndex = 3
    private val secondWheelIndex = 2
    private val thirdWheelIndex = 1
    private val forthWheelIndex = 0

    private var motorHat: MotorHat? = null

    private var mCameraThread: HandlerThread? = null
    private var mCameraHandler: Handler? = null
    private var mCamera: RoboCamera? = null
    private lateinit var textureView: TextureView
    private var previewSurface: Surface? = null
    private var imageWidth: Int = 150
    private var imageHeight: Int = 100

    private lateinit var mProximitySensorDriver: Hcsr04SensorDriver
    private lateinit var mSensorManager: SensorManager

    private val mDynamicSensorCallback = object : SensorManager.DynamicSensorCallback() {
        override fun onDynamicSensorConnected(sensor: Sensor) {
            if (sensor.type == Sensor.TYPE_PROXIMITY) {
                Log.i("Robocar", "Proximity sensor connected")
                mSensorManager.registerListener(
                    this@MainActivity,
                    sensor, SensorManager.SENSOR_DELAY_NORMAL
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        motorHat = MotorHat("I2C1")
        motorHat?.setMotorSpeed(0, 400)
        motorHat?.setMotorSpeed(1, 400)
        motorHat?.setMotorSpeed(2, 400)
        motorHat?.setMotorSpeed(3, 400)

        setUpWebServer()
        logDeviceIp()

        Hcsr04SensorDriver("BCM18", "BCM24")

//        button.setOnClickListener { mCamera?.triggerImageCapture() }
//        fixedRateTimer("timer", false, 3000, 400) {
//            this@MainActivity.runOnUiThread {
//                moveForward()
//                mCamera?.triggerImageCapture()
//            }
//        }

        mCameraThread = HandlerThread("CameraBackground")
        mCameraThread!!.start()
        mCameraHandler = Handler(mCameraThread!!.looper)
        mCamera = RoboCamera.getInstance()

        textureView = findViewById(R.id.surfaceView)

        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mSensorManager.registerDynamicSensorCallback(mDynamicSensorCallback)
        try {
            mProximitySensorDriver = Hcsr04SensorDriver("BCM17", "BCM4")
            mProximitySensorDriver.registerProximitySensor()
        } catch (e: IOException) {
            Log.e("Robocar", "Error configuring sensor", e)
        }

    }

    private val mLoaderCallback = object : BaseLoaderCallback(this) {}

    override fun onResume() {
        super.onResume()
        if (!OpenCVLoader.initDebug()) {
            Log.d(
                "TAG", "Internal OpenCV library not found. Using OpenCV Manager for initialization"
            )
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback)
        } else {
            Log.d("TAG", "OpenCV library found inside package. Using it!")
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        }


        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        textureView.surfaceTextureListener = surfaceTextureListener
    }

    private var surfaceTextureListener: TextureView.SurfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                val surfaceTexture1 = textureView.surfaceTexture
                surfaceTexture1.setDefaultBufferSize(imageWidth, imageHeight)
                previewSurface = Surface(surfaceTexture)
                mCamera?.initializeCamera(
                    applicationContext,
                    mCameraHandler,
                    mOnImageAvailableListener,
                    previewSurface
                )
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {

            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {

            }
        }


    private val mOnImageAvailableListener =
        ImageReader.OnImageAvailableListener { reader ->
            val image = reader.acquireLatestImage()
            val imageBuf = image.planes[0].buffer
            val imageBytes = ByteArray(imageBuf.remaining())
            imageBuf.get(imageBytes)
            image.close()
            onPictureTaken(imageBytes)
        }

    private fun onPictureTaken(imageBytes: ByteArray?) {
        val camBmpTemp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes?.size!!)
        val camBmp = Bitmap.createScaledBitmap(camBmpTemp, imageWidth, imageHeight, false)

        val mat1 = Mat(camBmp.height, camBmp.width, CvType.CV_8U/*.CV_8UC1*/)
        val result = Mat(camBmp.height, camBmp.width, COLOR_BGR2GRAY)
        Utils.bitmapToMat(camBmp, mat1)
        cvtColor(mat1, mat1, COLOR_BGR2GRAY)

        adaptiveThreshold(mat1, result, 250.0, ADAPTIVE_THRESH_MEAN_C, THRESH_BINARY, 171, 22.0)

        val bmp = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, bmp)

        runOnUiThread { imageView.setImageBitmap(bmp) }

        bmp?.run { parseImage(bmp) }
    }

    private fun parseImage(bmp: Bitmap) {
        var blackRowsCount = 0
        for (i in imageWidth / 4 until imageWidth / 4 * 3) {
            var blackPixelsInARow = 0
            for (j in 0 until 20) {
                if (isColorDark(bmp.getPixel(i, j))) {
                    blackPixelsInARow++
                }
            }
            if (blackPixelsInARow > 3) {
                blackRowsCount++
            }
        }
        if (blackRowsCount > 3) {
            turnRight()
        } else {
            moveForward()
        }
    }

    private fun isColorDark(color: Int): Boolean {
        val darkness =
            1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            motorHat?.close()
        } catch (e: IOException) {
            // error closing Motor Hat
        }

        mSensorManager.unregisterDynamicSensorCallback(mDynamicSensorCallback)
        mSensorManager.unregisterListener(this)
        mProximitySensorDriver.unregisterProximitySensor()
        try {
            mProximitySensorDriver.close()
        } catch (e: IOException) {
            Log.e("Robocar", "Error closing sensor", e)
        }
    }

    private fun logDeviceIp() {
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        Log.d("Robocar", "device ip = ${Formatter.formatIpAddress(wm.connectionInfo.ipAddress)}")
    }

    private fun setUpWebServer() {
        embeddedServer(CIO, port = 8080) {
            routing {
                get("/move/forward/") {
                    moveForward()
                    call.respond(mapOf("message" to "ok"))
                }
                get("/move/back/") {
                    moveBack()
                    call.respond(mapOf("message" to "ok"))
                }
                get("/turn/right/") {
                    turnRight()
                    call.respond(mapOf("message" to "ok"))
                }
                get("/turn/left/") {
                    turnLeft()
                    call.respond(mapOf("message" to "ok"))
                }
                get("/move/stop/") {
                    stopMove()
                    call.respond(mapOf("message" to "ok"))
                }
            }
        }.start()
    }

    private fun moveBack() {
        motorHat?.setMotorState(firstWheelIndex, MotorHat.MOTOR_STATE_CW)
        motorHat?.setMotorState(secondWheelIndex, MotorHat.MOTOR_STATE_CCW)
        motorHat?.setMotorState(thirdWheelIndex, MotorHat.MOTOR_STATE_CW)
        motorHat?.setMotorState(forthWheelIndex, MotorHat.MOTOR_STATE_CCW)
    }

    private fun moveForward() {
        motorHat?.setMotorState(firstWheelIndex, MotorHat.MOTOR_STATE_CCW)
        motorHat?.setMotorState(secondWheelIndex, MotorHat.MOTOR_STATE_CW)
        motorHat?.setMotorState(thirdWheelIndex, MotorHat.MOTOR_STATE_CCW)
        motorHat?.setMotorState(forthWheelIndex, MotorHat.MOTOR_STATE_CW)
    }

    private fun turnLeft() {
        motorHat?.setMotorState(firstWheelIndex, MotorHat.MOTOR_STATE_CW)
        motorHat?.setMotorState(secondWheelIndex, MotorHat.MOTOR_STATE_CW)
        motorHat?.setMotorState(thirdWheelIndex, MotorHat.MOTOR_STATE_CW)
        motorHat?.setMotorState(forthWheelIndex, MotorHat.MOTOR_STATE_CW)
    }

    private fun turnRight() {
        motorHat?.setMotorState(firstWheelIndex, MotorHat.MOTOR_STATE_CCW)
        motorHat?.setMotorState(secondWheelIndex, MotorHat.MOTOR_STATE_CCW)
        motorHat?.setMotorState(thirdWheelIndex, MotorHat.MOTOR_STATE_CCW)
        motorHat?.setMotorState(forthWheelIndex, MotorHat.MOTOR_STATE_CCW)
    }

    private fun stopMove() {
        motorHat?.setMotorState(firstWheelIndex, MotorHat.MOTOR_STATE_RELEASE)
        motorHat?.setMotorState(secondWheelIndex, MotorHat.MOTOR_STATE_RELEASE)
        motorHat?.setMotorState(thirdWheelIndex, MotorHat.MOTOR_STATE_RELEASE)
        motorHat?.setMotorState(forthWheelIndex, MotorHat.MOTOR_STATE_RELEASE)
    }
}
