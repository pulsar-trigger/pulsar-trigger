# Add project specific ProGuard rules here.
-keepclassmembers class com.ehrocha.pulsar.ble.** { *; }

# Tink (pulled in by androidx.security:security-crypto) references
# errorprone annotations at compile time but those classes aren't
# packaged at runtime. They're metadata-only — safe to strip.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
