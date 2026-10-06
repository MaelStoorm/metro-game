# Uygulamanın kendi kodu çok küçük; olduğu gibi kalsın.
# Oyun (JavaScript) Java köprüsünü (window.AndroidTTS) adlarıyla çağırır, bu yüzden adlar değişmemeli.
-keep class io.github.maelstoorm.metroistanbul.** { *; }
-keepattributes *Annotation*,JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
