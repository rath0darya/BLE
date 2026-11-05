# BLE Explorer

Android app for foreground Bluetooth Low Energy discovery, with an additional local Wi-Fi network and CCTV exposure audit.

## Features

### Bluetooth
- Manual foreground BLE scans; no background scanning.
- Advertised device names, signal strength, services, manufacturer data and raw advertisement details.
- Device classification, signal history, sharing and optional pairing / GATT inspection.

### Local network & CCTV audit
- Start a manual scan of the connected Wi-Fi IPv4 /24 subnet.
- Non-invasive ONVIF WS-Discovery and UPnP / SSDP discovery.
- Checks common camera, DVR/NVR and network service ports, including HTTP(S), RTSP, FTP, Telnet and common vendor ports.
- Camera candidates are labelled with a confidence reason and detected protocol evidence.
- Security review flags potentially risky exposed services, such as Telnet, FTP, unencrypted HTTP management and reachable RTSP.
- Results are advisory: an open port is not proof of a vulnerability, and no obvious finding is not a security certification.

## Security and privacy boundaries
- Only scan networks you own or are explicitly authorized to assess.
- Discovery is limited to the connected local Wi-Fi /24 subnet. It does not scan public IP ranges.
- No password guessing, brute force, login attempts, stream viewing, exploit execution or configuration changes.
- The app does not verify firmware CVEs, anonymous stream access, cloud exposure or device credentials. Validate those through authorized vendor administration and a proper security assessment.
- CCTV detection is heuristic unless a device responds to ONVIF discovery. VLANs, client isolation, multicast filtering and firewalls can hide devices.

## Build APKs

Pushes to `main` and manual workflow runs build debug and release APKs with GitHub Actions. Download them from the workflow run's **Artifacts** section (`BLE-Explorer-APKs`). Builds are not published to the GitHub Releases page.

The release build currently uses the repository's debug signing configuration. It is suitable for testing, not as a permanent production signing identity.
