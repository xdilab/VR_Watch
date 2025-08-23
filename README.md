# Project Documentation

## Code 1: `AndroidManifest.xml`

### Overview
This manifest file defines essential app components, permissions, and the service responsible for collecting heart rate and activity recognition data. It ensures that the app requests necessary runtime permissions and declares a foreground service to keep tracking active.

### Key Features
- Declares required permissions for **BODY_SENSORS**, **ACTIVITY_RECOGNITION**, and **Bluetooth**.
- Registers `ForegroundWorker` as a foreground service.
- Handles background sensors and notification requirements for Android 10+.

### Usage
This file should be placed in the app’s `src/main/AndroidManifest.xml`.  
It works together with `MainActivity.kt` and `ForegroundWorker.kt`.

---

## Code 2: `ForegroundWorker.kt`

### Overview
`ForegroundWorker` is a `ForegroundService` that collects heart rate and accelerometer data. It writes sensor readings into a CSV file in the app’s internal storage for later processing. The current measurements include:  
- Heart rate  
- Heart rate variability (HRV) via RMSSD and SDNN  
- Stress levels  
- Accelerometer (x, y, and z position of the watch)  

### Key Features
- Starts as a Foreground Service with a persistent notification.
- Collects heart rate and accelerometer data using `SensorManager`.
- Logs data every second into `sensor_data.csv`.
- Ensures continuous monitoring even when the app is minimized.

### Usage
The service is started by `MainActivity` after all required permissions are granted.  
It runs in the background until explicitly stopped by the system or user.

---

## Code 3: `MainActivity.kt`

### Overview
`MainActivity` is the entry point of the app. It manages runtime permission requests (sensors, Bluetooth, notifications) and ensures the `ForegroundWorker` service starts safely once all permissions are granted.

### Key Features
- Requests runtime permissions for sensors, Bluetooth, and notifications.
- Handles permission rationales and redirects users to Settings if needed.
- Ensures `ForegroundWorker` starts only after all requirements are met.
- Shows dialogs for permission rationales and background activity requirements (Samsung devices).

### Usage
`MainActivity` initializes the app by requesting permissions.  
Once all permissions are granted, it launches `ForegroundWorker` for background tracking.  
This ensures proper functioning of the health monitoring features.

---

## For More Information
Please see the inline documentation in code, which contains a breakdown of each function in all modules.

