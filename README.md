# BLE Explorer

Android application for nearby Bluetooth discovery and local network security review, with a separate nearby Wi-Fi access-point scan.

## Nearby CCTV discovery — what the app can actually detect

- **BLE advertisements:** classifies camera/CCTV-related broadcast names and manufacturer/service data when a camera exposes them over Bluetooth LE.
- **Nearby Wi-Fi access points:** performs an Android Wi-Fi scan and shows SSID, BSSID, signal strength, frequency and security capabilities. SSID matches are hints only.\n- **Nearby Wi-Fi Direct / UPnP:** independently discovers nearby Wi-Fi Direct peers and their advertised UPnP service identifiers. This can work without joining the peer device’s router network, but only when the peer exposes Wi-Fi Direct discovery/service advertisements.\n- **Local camera identification:** for reachable ONVIF devices, attempts the standard unauthenticated GetDeviceInformation operation and displays manufacturer, model, firmware and hardware details when the camera permits it. For UPnP devices, reads the advertised device description for friendly name, manufacturer and model.
- **Local-network audit:** performs ONVIF WS-Discovery and UPnP/SSDP discovery plus common TCP service checks on the connected Wi-Fi subnet, including HTTP(S), RTSP, FTP, Telnet and common camera/DVR ports.

## Important detection limits

Nearby Wi-Fi scanning lists **access points**, not the client devices connected to them. It cannot enumerate cameras connected behind a stranger's router, identify a camera using an ordinary SSID, or detect a wired camera. A camera can only be identified off-LAN when it emits a detectable BLE advertisement or exposes Wi-Fi Direct / UPnP service discovery. Many Wi-Fi cameras do not advertise over Wi-Fi Direct, so they will not appear in that mode. BLE camera classification, Wi-Fi peer names and SSID-name matches are heuristic indicators, not proof of CCTV. The app cannot enumerate clients behind an unrelated router.

Android requires Location permission and enabled Location services to expose nearby Wi-Fi scan results, and the OS may throttle scans. BLE scanning requires Bluetooth permissions and only finds devices that advertise during the scan window.

## Protocol and security review

The local audit can identify reachable service indicators such as HTTP, HTTPS, RTSP, ONVIF, SSDP, FTP and Telnet, and provide configuration review guidance. An open port is not proof of a vulnerability. The app does not verify firmware CVEs, credentials, anonymous stream access, cloud exposure or exploitability. “No obvious risk” is not a security certification.

## Authorized use

Use network scanning only on networks you own or are explicitly authorized to assess. No password guessing, brute force, login attempts, stream viewing, exploit execution or configuration changes are performed.

## Build and releases

GitHub Actions builds the debug and release variants for pushes to `main`. The installable release APK is signed only with the persistent keystore supplied through the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` repository Actions secrets. If any secret is missing, the release job fails closed and does not publish a debug-signed substitute. The Android application ID remains `com.anonvox.bleexplorer`; the version code increases with each workflow run. Only the persistent-key-signed `BLE-Explorer.apk` is attached to GitHub Releases. Do not change or replace the persistent keystore: Android requires the same signing certificate to install updates over an existing installation.
