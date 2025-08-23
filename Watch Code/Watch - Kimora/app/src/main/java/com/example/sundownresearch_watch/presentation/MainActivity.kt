package com.example.sundownresearch_watch.presentation

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.sundownresearch_watch.R

class MainActivity : ComponentActivity() {

    //launches for permission requests
    private lateinit var sensorPermsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundSensorLauncher: ActivityResultLauncher<String>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private lateinit var connectedDevicePermsLauncher: ActivityResultLauncher<Array<String>>

    //optional dialog for info/warnings
    private var infoDialog: AlertDialog? = null

    //list of runtime permissions for sensors
    private val sensorPermissions = mutableListOf<String>().apply {
        add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private val connectedDevicePermissions = listOf(
        Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupLaunchers()
        //delay permission request slightly so UI is ready
        Handler(Looper.getMainLooper()).post {
            requestSensorPermission()
        }
    }

    override fun onPause() {
        super.onPause()
        //dismisses dialog if activity is paused
        infoDialog?.takeIf { it.isShowing }?.dismiss()
    }

    //when called cleans up dialog reference
    override fun onDestroy() {
        infoDialog?.takeIf { it.isShowing }?.dismiss()
        infoDialog = null
        super.onDestroy()
    }

    //initializes permission request launcher
    private fun setupLaunchers() {
        //request multiple permissions for sensors
        sensorPermsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.values.all { it }) {
                //if all granted, request background sensor permission
                requestBackgroundSensorPermission()
            } else {
                //if denied, explain following message to user
                showPermissionRationale("Sensor access is required for heart rate monitoring") {
                    sensorPermsLauncher.launch(sensorPermissions.toTypedArray())
                }
            }
        }

        //requests permission for background sensor
        backgroundSensorLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                requestNotificationPermission()
            } else {
                //if denied, guides user to enable manually
                showSettingsDialog(
                    "Allow sensors 'All the time' in settings for uninterrupted tracking."
                )
            }
        }

        //requests permission for posting notifications (applies to Android 13+)
        notificationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            requestConnectedDevicePermissions()
        }

        connectedDevicePermsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.values.all { it }) {
                startForegroundServiceSafely()
            } else {
                showPermissionRationale("Bluetooth permissions are needed for device connectivity") {
                    connectedDevicePermsLauncher.launch(connectedDevicePermissions.toTypedArray())
                }
            }
        }
    }


    //checks for and requests missing sensor permissions
    private fun requestSensorPermission() {
        val missing = sensorPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            sensorPermsLauncher.launch(missing.toTypedArray())
        } else {
            requestBackgroundSensorPermission()
        }
    }

    //requests background sensors if needed
    private fun requestBackgroundSensorPermission() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS_BACKGROUND
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundSensorLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
        } else {
            requestNotificationPermission()
        }
    }

    //request notification permissions if needed (Android 13+)
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestConnectedDevicePermissions()
        }
    }

    //request connected device permissions
    private fun requestConnectedDevicePermissions() {
        val missing = connectedDevicePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            connectedDevicePermsLauncher.launch(missing.toTypedArray())
        } else {
            startForegroundServiceSafely()
        }
    }

    //starts the senor data collection service
    private fun startForegroundServiceSafely() {
        informBackgroundActivityIfNeeded()

        ContextCompat.startForegroundService(
            this,
            Intent(this, ForegroundWorker::class.java)
        )

        finish()
    }

    //displays a warning dialog
    private fun informBackgroundActivityIfNeeded() {
        if (Build.MANUFACTURER.equals("Samsung", ignoreCase = true)) {
            val builder = AlertDialog.Builder(this)
                .setTitle("Enable Background Activity")
                .setMessage("To keep tracking when the screen is off, please allow background activity...")
                .setPositiveButton("OK", null)

            infoDialog = builder.create()
            if (!isFinishing && !isDestroyed) {
                infoDialog?.show()
            }
        }
    }

    //shows a dialog on why permissions are needed and offers a retry
    private fun showPermissionRationale(message: String, onRetry: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Grant") { _, _ -> onRetry.invoke() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    //shows a dialog redirecting users to app's settings to manually allow permissions
    private fun showSettingsDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Needed")
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}


