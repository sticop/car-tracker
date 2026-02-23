# Car Tracker

An Android app that tracks your car's movement, speed, and routes with GPS precision.

## Features

- **Real-time GPS tracking** with speed monitoring
- **Automatic trip detection** - starts recording when you drive, stops when parked
- **Speed-colored routes** on Google Maps (green=slow, yellow=moderate, red=fast)
- **Trip history** with filtering (1h, 6h, 24h, 3 days, 7 days, 30 days)
- **Live speedometer dashboard** with animated gauge
- **Speed evidence log** - prove your actual speed during traffic stops
- **Background tracking** with battery optimization when parked
- **30-day data retention** with automatic cleanup
- **Auto-start** on device boot
- **Power-aware** - detects car power connection

## Architecture

- **Kotlin** with Jetpack Compose for UI
- **Google Maps** for route visualization
- **Room Database** for offline data storage
- **Foreground Service** for reliable background tracking
- **MVVM** architecture with StateFlow

## Setup

1. Get a Google Maps API key from [Google Cloud Console](https://console.cloud.google.com/)
2. Replace `YOUR_GOOGLE_MAPS_API_KEY` in `AndroidManifest.xml`
3. Build and install on your Android tablet

## How It Works

- The app monitors GPS speed continuously
- When speed exceeds 3 km/h, a new trip begins recording
- Location points are saved every 1-3 seconds while driving
- When speed drops below 3 km/h for 2+ minutes, the trip ends
- In parking mode, GPS polling drops to every 15 seconds (battery saving)
- All data older than 30 days is automatically deleted

## Permissions Required

- Location (Fine + Background)
- Foreground Service
- Notifications
- Boot Complete (auto-restart)
