# BLE Explorer

Android application for nearby Bluetooth discovery and local network security review, with a separate nearby Wi-Fi access-point scan.

## Nearby CCTV discovery — what the app can actually detect

- **BLE advertisements:** classifies camera/CCTV-related broadcast names and manufacturer/service data when a camera exposes them over Bluetooth LE.
- **Nearby Wi-Fi access points:** performs an Android Wi-Fi scan and flags SSIDs containing known camera/NVR/DVR vendor or product terms. It shows BSSID, signal strength, frequency and advertised network security capabilities.
- **Local-network audit:** performs ONVIF WS-Discovery and UPnP/SSDP discovery plus common TCP service checks on the connected Wi-Fi subnet, including HTTP(S), RTSP, FTP, Telnet and common camera/DVR ports.

## Important detection limits

Nearby Wi-Fi scanning lists **access points**, not the client devices connected to them. It cannot enumerate cameras connected behind a stranger's router, identify a camera using an ordinary SSID, or detect a wired camera. A camera can only be identified off-LAN when it emits a detectable signal or otherwise exposes a discoverable radio service. BLE camera classification and SSID-name matches are heuristic indicators, not proof of CCTV.

Android requires Location permission and enabled Location services to expose nearby Wi-Fi scan results, and the OS may throttle scans. BLE scanning requires Bluetooth permissions and only finds devices that advertise during the scan window.

## Protocol and security review

The local audit can identify reachable service indicators such as HTTP, HTTPS, RTSP, ONVIF, SSDP, FTP and Telnet, and provide configuration review guidance. An open port is not proof of a vulnerability. The app does not verify firmware CVEs, credentials, anonymous stream access, cloud exposure or exploitability. “No obvious risk” is not a security certification.

## Authorized use

Use network scanning only on networks you own or are explicitly authorized to assess. No password guessing, brute force, login attempts, stream viewing, exploit execution or configuration changes are performed.

## Build and releases

GitHub Actions builds debug and release APKs for pushes to `main`. APKs are uploaded as the `BLE-Explorer-APKs` workflow artifact and attached to a versioned GitHub Release after a successful build. The release APK currently uses the repository's debug signing configuration and is intended for testing, not as a permanent production signing identity.
