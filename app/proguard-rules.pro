# --- Model danych aplikacji ---
# Wszystkie data classy/parsery offsetowe i klasy serializowane przez Gson trzymamy w całości.
# To tani koszt (kod aplikacji jest mały — rozmiar APK robią biblioteki), a eliminuje crashe
# typu "release crashuje, debug nie" wynikające z agresywnej optymalizacji R8
# (proguard-android-optimize.txt) na klasach używanych przez refleksję/Gson/ViewBinding.
-keep class com.test.bafangcon.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# --- Gson (presety: AssistPreset, List<AssistPreset>) ---
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- MPAndroidChart (krzywe wspomagania) ---
-keep class com.github.mikephil.charting.** { *; }
