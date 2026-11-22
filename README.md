# Translogic II Android app project

This is a simple Android WebView wrapper for the Translogic II URL supplied by the user.

## Build an APK
1. Install Android Studio on a computer.
2. Open this `TranslogicII` folder in Android Studio.
3. Allow Gradle sync and install the Android SDK platform requested by the project (API 35).
4. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Android Studio will show the APK location, normally `app/build/outputs/apk/debug/app-debug.apk`.
6. Transfer the APK to the Android phone and install it. Android may ask you to allow installation from that file manager.

## Notes
- The app opens the supplied HTTP URL; it does not bypass Kenya Railways network/VPN restrictions or authentication.
- The project allows cleartext HTTP because the supplied address uses `http://`. HTTP is not encrypted, so avoid using it on untrusted networks and ask Kenya Railways IT whether an HTTPS address is available.
- No username or password is stored in the app.
