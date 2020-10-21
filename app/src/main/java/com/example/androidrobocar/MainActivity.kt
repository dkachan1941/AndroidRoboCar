package com.example.androidrobocar

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.google.android.things.contrib.driver.motorhat.MotorHat
import android.net.wifi.WifiManager
import android.text.format.Formatter
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import java.io.IOException
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import io.ktor.application.install
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import java.nio.charset.Charset
import java.time.Duration
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

        setUpWebSockets()
        logDeviceIp()
    }

    private fun setUpWebSockets() {
        embeddedServer(CIO, 4444) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(60) // Disabled (null) by default
                timeout = Duration.ofSeconds(30)
                maxFrameSize = Long.MAX_VALUE // Disabled (max value). The connection will be closed if surpassed this length.
                masking = false
            }
            routing {
                webSocket("/") {
                    for (frame in incoming) {
                        when (frame.data.toString(Charset.forName("UTF-8"))) {
                            "left" -> {
                                turnLeft()
                            }
                            "right" -> {
                                turnRight()
                            }
                            "forward" -> {
                                moveForward()
                            }
                            "back" -> {
                                moveBack()
                            }
                            "stop" -> {
                                stopMove()
                            }
                            "bark" -> {
                                playBark()
                            }
                            "bark2" -> {
                                playBark2()
                            }
                            else -> {

                            }
                        }
                    }
                }
            }
        }.start()
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

    private fun playBark() {
        val mp = MediaPlayer.create(this, R.raw.bark1)
        mp.start()
    }

    private fun playBark2() {
        val mp = MediaPlayer.create(this, R.raw.bark2)
        mp.start()
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
