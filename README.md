# CSHCEPaycard

Android research application for **NFC card exploration** and **Host Card Emulation (HCE)** payment-profile simulation.

---

## Important Disclaimer (Read First)

> **Research and educational use only.**
>
> This project is designed for protocol analysis, interoperability testing, and security research in controlled environments.
>
> It is **not** intended, certified, or suitable for:
> - real-world payment processing,
> - production POS acceptance,
> - cloning real payment cards,
> - storing sensitive financial data in compliance contexts.
>
> The app may appear to "work" in specific test scenarios, but it does **not** implement production-grade EMV security controls (e.g., dynamic cryptograms, issuer authentication, tokenization lifecycle, secure element controls, PCI compliance requirements).

Use this software responsibly and only where you are legally authorized to test.

---

## Overview

CSHCEPaycard combines two capabilities in one Android app:

1. **NFC card/tag reader workflow**
   - scan nearby NFC tags/cards,
   - inspect technical details (UID, technologies, NDEF data, PPSE/AID responses when available),
   - persist scan profiles locally.

2. **HCE payment-profile emulation workflow**
   - choose a stored profile,
   - map that profile into the app's emulated swipe/track payload,
   - answer a fixed APDU sequence for Visa MSD-style demonstration traffic.

This enables a practical lab setup for studying terminal-to-phone APDU exchanges and app-level HCE behavior.

---

## Core Principles

### 1) Protocol Transparency
The app exposes and logs key NFC/APDU interactions to make behavior observable for debugging and learning.

### 2) Reproducible Test Profiles
Scanned cards can be stored, reviewed, selected, and reused as deterministic emulation inputs.

### 3) Clear Separation Between Research and Real Payments
The app intentionally avoids claiming production payment capability and highlights security boundaries.

### 4) Local-Only Simplicity
Data is kept in local SharedPreferences for quick experimentation (not secure storage for real cardholder data).

---

## Feature Set

### NFC Reader Features
- Start/stop NFC reader mode from the Dashboard.
- Parse and display:
  - Tag UID
  - NFC technology list (IsoDep, Ndef, etc.)
  - NDEF payload (if present)
  - PPSE response/APDU output for IsoDep-compatible cards
  - Detected AIDs and app labels when available
- Generate candidate swipe data from scanned content when possible.

### Card Profile Management
- Save scanned card metadata with custom card names.
- Store/edit swipe data used for emulation.
- List all stored profiles.
- View full details for selected profile.
- Delete selected profile or clear all profiles.

### HCE Emulation Features
- Set app as default payment service.
- Activate a selected stored profile for emulation.
- Host APDU service (`MyHostApduService`) responds to:
  1. PPSE SELECT
  2. Visa AID SELECT
  3. GPO
  4. READ RECORD
- READ RECORD payload is dynamically constructed from selected swipe data.

---

## Architecture

### Main Components

- `Dashboard`  
  Main UI and orchestration layer:
  - NFC reader mode control,
  - scan parsing,
  - local profile CRUD,
  - selecting active profile for HCE service.

- `MyHostApduService`  
  Android `HostApduService` implementation:
  - receives inbound APDU commands from external readers/POS,
  - returns static/demo APDU responses for protocol steps,
  - builds Track2-equivalent response from configured swipe data.

- `Constants`  
  Preference keys and default swipe payload.

- `Util`  
  Byte/hex conversions used throughout APDU and scan handling.

### Data Flow Summary

1. User scans NFC tag/card in Dashboard.
2. App extracts technical metadata and optional candidate values.
3. User saves profile locally.
4. User selects profile and activates it for payment emulation.
5. `MyHostApduService` reads active swipe payload from SharedPreferences.
6. Terminal/reader APDU requests are answered using demo Visa MSD flow.

---

## APDU Demonstration Flow (Current Implementation)

The service currently models a simplified Visa MSD-style path:

1. `SELECT PPSE` (`2PAY.SYS.DDF01`)
2. `SELECT AID` (`A0000000031010`)
3. `GET PROCESSING OPTIONS (GPO)`
4. `READ RECORD`

Unknown commands return `6F00`.

> This is intentionally limited and should be treated as a test harness, not a complete EMV contactless stack.

---

## Platform Requirements

- Android device with:
  - NFC hardware,
  - HCE support (`HostApduService`),
  - Android 5.0+ (minSdk 21).
- For development/build:
  - Android Studio (latest stable recommended),
  - JDK compatible with Android Gradle Plugin 8.x,
  - Android SDK Platform 34 / Build Tools 34.0.0.

---

## Build Instructions

### Debug build

```bash
./gradlew clean assembleDebug
```

Generated APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## Installation & Usage

1. Install the debug APK on an NFC-capable Android device.
2. Open app and allow it to become the default wallet/payment app when prompted.
3. Tap **Scan NFC card** and place a card/tag near the device.
4. Review scanned details in the app.
5. Set a card name and swipe data, then tap **Save scanned card**.
6. Select a stored card and tap **Use selected for payment**.
7. Present phone to test terminal/reader to observe APDU exchange.

---

## Security, Compliance, and Legal Notes

- Local storage is not hardened for sensitive production secrets.
- No PCI DSS controls or attestation workflow is implemented.
- No issuer integration, token provisioning, or cryptogram generation exists.
- Do not store or process real cardholder data outside authorized lab conditions.
- Ensure all testing is compliant with local law, card network rules, and organizational policy.

---

## Known Limitations

- Not a full EMV L2 implementation.
- No dynamic transaction cryptography (ARQC/CVC3 style flows not implemented).
- No secure element-backed card emulation.
- Behavior varies by terminal firmware, reader capabilities, and Android device vendor stack.

---

## Repository Structure

```text
app/src/main/java/cz/csas/android/hcepaycard/app/
  ├── Dashboard.java
  ├── Constants.java
  └── Util.java

app/src/main/java/services/
  └── MyHostApduService.java

app/src/main/res/
  ├── layout/activity_dashboard.xml
  ├── values/strings.xml
  └── xml/apduservice.xml
```

---

## Intended Audience

- Mobile payment researchers
- NFC / APDU protocol engineers
- QA/security teams validating lab transaction flows
- Students learning Android HCE fundamentals

---

## Final Reminder

This project is a **research tool**.  
It is not a consumer wallet, not a bank-grade card emulator, and not approved for production payment usage.
