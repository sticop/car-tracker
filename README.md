# Car Tracker

An Android app that tracks your car's movement, speed, and routes with GPS precision.

## Features

- **Real-time GPS tracking** with speed monitoring
- **Automatic trip detection** - starts recording when you drive, stops when parked
- **Speed-colored routes** on OpenStreetMap (green=slow, yellow=moderate, red=fast) - no API key needed
- **Trip history** with filtering (1h, 6h, 24h, 3 days, 7 days, 30 days)
- **Live speedometer dashboard** with animated gauge
- **Speed evidence log** - prove your actual speed during traffic stops
- **Background tracking** with battery optimization when parked
- **30-day data retention** with automatic cleanup
- **Auto-start** on device boot
- **Power-aware** - detects car power connection

## Architecture

- **Kotlin** with Jetpack Compose for UI
- **OpenStreetMap (osmdroid)** for route visualization - no API key required
- **Room Database** for offline data storage
- **Foreground Service** for reliable background tracking
- **MVVM** architecture with StateFlow

## Setup

1. Clone the repository
2. Build and install on your Android tablet
3. No API keys needed - uses free OpenStreetMap tiles

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
