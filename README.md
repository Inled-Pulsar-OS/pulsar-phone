# Pulsar Phone

Android companion client for Pulsar OS that seamlessly integrates your mobile phone with your desktop over local Wi-Fi or Tailscale VPN.

## Features

- Pairing and Discovery: Connect via QR code scan, local network mDNS discovery, or manual IP and authentication token.
- PC to Phone Desktop Streaming: Stream your Linux desktop display in real time with multi-monitor selection and ultra-low-latency 48kHz stereo PCM audio rendered through native Android AudioTrack.
- Phone to PC Mirroring: Mirror and control your Android screen and sound on your desktop using scrcpy integration.
- Desktop App Launcher: Launch native PC graphical applications inside individual Broadway sandboxed instances without window overlaps.
- Remote File Manager: Browse, inspect, and download files directly from the PC filesystem.
- Notification Forwarding: Relay incoming phone notifications directly to the Pulsar OS desktop notification daemon.
- Clipboard Sync: Synchronize text clipboard seamlessly between the mobile device and the computer.
- Power and Remote Access: Remotely inhibit desktop sleep and idle suspension so the computer remains accessible 24/7.
- Internationalization: Full interface translation support in Spanish and English.

## Requirements

- Android 8.0 (API level 26) or higher.
- A running Pulsar OS host with pulsar-bridge.service (pulsaros-vpn) active on the same local network or connected via Tailscale.
- Android SDK (build-tools, platform 34 or compatible) and JDK 17+ for building.

## Building from Source

Clone the repository:

```bash
git clone https://github.com/Inled-Pulsar-OS/pulsar-phone.git
cd pulsar-phone
```

Compile the debug APK using the Gradle wrapper:

```bash
./gradlew assembleDebug
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

To compile a release build:

```bash
./gradlew assembleRelease
```

## Testing and Installation

1. Connect your Android device via USB with USB Debugging enabled, or connect wirelessly using ADB:

```bash
adb connect <phone-ip>:5555
```

2. Install the APK to the connected device:

```bash
./gradlew installDebug
# or directly with ADB:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

3. Ensure the Pulsar Bridge daemon is running on your PC:

```bash
systemctl --user status pulsar-bridge.service
```

4. Launch the Pulsar Phone app on your device:
- Scan the pairing QR code from the Pulsar VPN desktop app (pulsar-vpn), or select the discovered PC on the local network.
- Once paired, you can view the live desktop stream with synchronized sound, launch PC apps, browse PC storage, and share notifications.

## Architecture

- UI and Navigation: Material Components 3, ViewBinding, Kotlin Coroutines, and Android Jetpack.
- Video Playback: Hardware-accelerated WebView rendering low-delay MJPEG stream.
- Audio Playback: Real-time 48kHz 16-bit stereo PCM stream consumer powered by AudioTrack in low-latency mode.
- Communication: RESTful HTTP and streaming endpoints communicating with bridge_daemon.py on port 8989.

## License

Part of the Pulsar OS ecosystem by Inled Group.
