package com.example.sundownresearch_watch.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.sundownresearch_watch.R
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.pow
import kotlin.math.sqrt
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ForegroundWorker : Service(), SensorEventListener {

    companion object {
        private const val TAG = "ForegroundWorker"
        private const val NOTIF_ID = 1001
        private const val MAX_RR = 300
        private const val FOREGROUND_TYPES = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        private const val WRITE_INTERVAL_MS = 10_000L
        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"  //standard SPP UUID
        private const val FILENAME = "sundown_research_sensor_data.csv"
    }

    //sensor related variables
    private lateinit var sensorManager: SensorManager
    private var hrSensor: Sensor? = null
    private var accelSensor: Sensor? = null

    //last collected values; all have a null/default value in case of missing information
    private var lastHr: Float? = null
    private var lastRmssd: Double? = null
    private var lastSdnn: Double? = null
    private var lastStress = "null"
    private var lastAccel = floatArrayOf(0f, 0f, 0f)

    //RR interval data for heart rate variability (HRV)
    private val rrIntervals = mutableListOf<Long>()
    private var lastRrTimestamp: Long? = null

    //I/O handler for background writing
    private val ioThread = HandlerThread("io_thread").apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss") //format of timestemp
    private lateinit var wakeLock: PowerManager.WakeLock

    private val writeRunnable = Runnable { writeDataToCsv() }

    //determines whether or not a new file is needed; new file is created with every start of app
    private var fileInitialized = false

    /**
     * spins up upon creation of app
     * initializes important steps of setup such as
     * checking for permissions and starting/registering listeners
     * for sensors
     */
    override fun onCreate() {
        super.onCreate()

        //get sensor services
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        //stops if sensors aren't available
        if (hrSensor == null || accelSensor == null) {
            Log.e(TAG, "Required sensors missing.")
            stopSelf()
            return
        }

        //starts a wake lock to keep the CPU running
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:WakeLock")
        wakeLock.acquire(5 * 60 * 1000L) //auto-releases after 5 minutes

        startForegroundServiceCompat()
        registerSensors()

        //begins periodic file writing (every 10 secs)
        if (ioThread.isAlive) {
            ioHandler.postDelayed(writeRunnable, WRITE_INTERVAL_MS)
        }
    }

    //starts foreground activity with a notification
    private fun startForegroundServiceCompat() {
        val chanId = "sensor_data_service"
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(NotificationChannel(chanId, "Sensor Data", NotificationManager.IMPORTANCE_LOW))
            NotificationChannel(
                chanId, "Sensor Data",
                NotificationManager.IMPORTANCE_LOW
            )
        }

        //creates and shows notification
        val notif = NotificationCompat.Builder(this, chanId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Collecting sensor data")
            .setContentText("Heart & accelerometer active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(this, NOTIF_ID, notif, FOREGROUND_TYPES)
    }

    //registers heart rate and accelerometer sensors
    private fun registerSensors() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            sensorManager.registerListener(this, hrSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /**
     * handles new sensor data
     * also calls for the calculations used for HRV and stress levels
     * via RR Intervals using the RMSSD and SDNN formula
     */
    override fun onSensorChanged(event: SensorEvent) {
        val nowMillis = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                event.values.firstOrNull()?.let { hr ->
                    lastHr = hr
                    lastRrTimestamp?.let { prev ->
                        val delta = nowMillis - prev
                        rrIntervals.add(delta)
                        if (rrIntervals.size > MAX_RR) rrIntervals.removeAt(0)
                        val rm = calculateRMSSD(rrIntervals)
                        val sd = calculateSDNN(rrIntervals)
                        lastRmssd = rm
                        lastSdnn = sd
                        lastStress = estimateStressLevel(rm)
                    }
                    lastRrTimestamp = nowMillis
                }
            }
            Sensor.TYPE_ACCELEROMETER -> lastAccel = event.values.clone()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * writes the data to a CSV file
     * if the app is starting up it will start a new file with headers
     * if the app has already been started then it will append the new data
     * to the existing file
     */
    private fun writeDataToCsv() {
        ioHandler.removeCallbacks(writeRunnable)
        synchronized(this) {
            val now = LocalTime.now().format(timeFormatter)
            val fHr = lastHr ?: 0f
            val fRm = lastRmssd ?: 0.0
            val fSd = lastSdnn ?: 0.0
            val fSt = lastStress
            val fAc = lastAccel

            Log.d(
                TAG,
                "Writing data: $now, HR=$fHr, RMSSD=$fRm, SDNN=$fSd, Stress=$fSt, Accel=$fAc"
            )

            try {
                val mode = if (!fileInitialized) {
                    fileInitialized = true
                    Context.MODE_PRIVATE  //overwrites file on first write
                } else {
                    Context.MODE_APPEND
                }

                openFileOutput(FILENAME, mode).use { fos ->
                    val writer = fos.writer().buffered()
                    if (mode == Context.MODE_PRIVATE) {
                        writer.append("Timestamp,HeartRate,RMSSD,SDNN,StressLevel,AccelX,AccelY,AccelZ\n")
                    }
                    writer.apply {
                        append("$now,$fHr,$fRm,$fSd,$fSt,${fAc[0]},${fAc[1]},${fAc[2]}\n")
                        flush()
                    }
                    fos.fd.sync()
                }
                val file = File(filesDir, FILENAME)
                Log.d(TAG, "File size now: ${file.length()} bytes @${file.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "Write CSV failed", e)
            }
        }

        //schedule next write
        if (ioThread.isAlive) {
            ioHandler.postDelayed(writeRunnable, WRITE_INTERVAL_MS)
        }
    }

    //HRV: RMSSD calculation
    private fun calculateRMSSD(rr: List<Long>) = sqrt(rr.zipWithNext { a, b ->
        (b - a).toDouble().pow(2)
    }.average())

    //standard deviation of NN intervals (SDNN) calculation
    private fun calculateSDNN(rr: List<Long>): Double {
        val avg = rr.map(Long::toDouble).average()
        return sqrt(rr.map { (it - avg).pow(2) }.average())
    }

    //estimates stress levels from RMSSD
    private fun estimateStressLevel(rmssd: Double) = when {
        rmssd > 50 -> "Low Stress"
        rmssd in 30.0..50.0 -> "Moderate Stress"
        else -> "High Stress"
    }

    //acquires CPU wake lock
    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(10 * 60 * 1000L) //extend up to 10 minutes
    }

    //releases wake lock
    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    //sends CSV via RFCOMM server to paired Bluetooth devices
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun sendCsvViaRfcommBlocking() {
        acquireWakeLock()
        try {
            val file = File(filesDir, FILENAME)
            if (!file.exists()) {
                Log.e(TAG, "RFCOMM: CSV missing")
                return
            }

            val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (btAdapter?.isEnabled != true) {
                Log.e(TAG, "RFCOMM: Bluetooth disabled")
                return
            }

            btAdapter.bondedDevices.forEach { Log.d(TAG, "Paired: ${it.name} @ ${it.address}") }

            for (dev in btAdapter.bondedDevices) {
                Log.d(TAG, "RFCOMM: Attempting device '${dev.name}'")
                btAdapter.cancelDiscovery()
                Thread.sleep(300)

                val sock = createRfcommSocket(dev) ?: continue
                var connected = false
                var retryDelay = 500L

                repeat(5) { attempt ->
                    try {
                        sock.connect()
                        connected = true
                        Log.d(TAG, "RFCOMM: Connected to ${dev.name}")
                        return@repeat
                    } catch (ex: IOException) {
                        Log.e(TAG, "CONNECT failed on attempt $attempt for ${dev.name}", ex)
                        Thread.sleep(retryDelay)
                        retryDelay *= 2
                    }
                }

                if (!connected) {
                    sock.close()
                    continue
                }

                try {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(4096)
                        var total = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            sock.outputStream.write(buffer, 0, bytesRead)
                            total += bytesRead
                        }
                        sock.outputStream.flush()
                        Log.d(TAG, "RFCOMM: Sent $total bytes successfully")
                    }
                    sock.close()
                    return  // stop after first successful device
                } catch (ex: IOException) {
                    Log.e(TAG, "Send failed, retrying next device", ex)
                } finally {
                    try {
                        sock.close()
                    } catch (_: IOException) {}
                }

                Thread.sleep(300)
            }

            Log.e(TAG, "RFCOMM: Unable to send CSV to any device")
        } finally {
            releaseWakeLock()
        }
    }


    //creates a Bluetooth RFCOMM socket
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket? {
        val uuid = UUID.fromString(SPP_UUID)
        return try {
            device.createRfcommSocketToServiceRecord(uuid)
        } catch (e: Exception) {
            Log.w(TAG, "Standard socket failed", e)
            try {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            } catch (e2: Exception) {
                Log.w(TAG, "Insecure socket failed", e2)
                try {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, 1) as BluetoothSocket
                } catch (e3: Exception) {
                    Log.e(TAG, "Reflection socket failed", e3)
                    null
                }
            }
        }
    }

    //called when task is removed (cleared from recent); cleans up and sends CSV file
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed — writing and sending CSV")
        sensorManager.unregisterListener(this)
        ioHandler.removeCallbacks(writeRunnable)
        ioThread.quitSafely()
        ioThread.join()

        writeDataToCsv()
        sendCsvViaRfcommBlocking()

        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?) = null
}
