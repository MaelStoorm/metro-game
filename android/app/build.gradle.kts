import java.util.Properties

plugins {
    id("com.android.application")
}

// Depo kökü (android/ klasörünün bir üstü): oyunun kendisi (index.html) ve admob.properties orada durur.
val repoRoot: File = rootDir.parentFile

// ---------- AdMob kimlikleri ----------
// Kökteki admob.properties dosyasından okunur (appId=, rewardedId=).
// Boş bırakılırsa Google'ın resmi TEST kimlikleri kullanılır (test reklamı görünür, gelir getirmez).
// Boş olmayan ama biçimi bozuk bir değer derlemeyi açık bir hata mesajıyla durdurur.
val admobProps = Properties().apply {
    val f = repoRoot.resolve("admob.properties")
    if (f.isFile) f.reader(Charsets.UTF_8).use { load(it) }
}

val testAppId = "ca-app-pub-3940256099942544~3347511713"
val testRewardedId = "ca-app-pub-3940256099942544/5224354917"

fun admobValue(key: String, example: String, format: Regex): String? {
    val value = admobProps.getProperty(key, "").trim()
    if (value.isEmpty()) return null
    if (!format.matches(value)) {
        throw GradleException(
            "admob.properties: '$key' değeri geçersiz: \"$value\"\n" +
                "Beklenen biçim: $example (AdMob panelinden kopyala) ya da test reklamları için boş bırak."
        )
    }
    return value
}

val realAppId = admobValue("appId", "ca-app-pub-1234567890123456~1234567890", Regex("""ca-app-pub-\d{16}~\d{10}"""))
val realRewardedId = admobValue("rewardedId", "ca-app-pub-1234567890123456/1234567890", Regex("""ca-app-pub-\d{16}/\d{10}"""))

if (realRewardedId != null && realAppId == null) {
    throw GradleException("admob.properties: rewardedId dolu ama appId boş. Gerçek reklam birimi için AdMob uygulama kimliği (appId) de gerekir.")
}
if (realAppId != null && realRewardedId != null &&
    realAppId.substringBefore('~') != realRewardedId.substringBefore('/')
) {
    throw GradleException("admob.properties: appId ile rewardedId farklı AdMob hesaplarına ait görünüyor (ca-app-pub-... kısmı aynı olmalı).")
}

val admobAppId = realAppId ?: testAppId
val admobRewardedId = realRewardedId ?: testRewardedId
if (realAppId == null || realRewardedId == null) {
    logger.warn("UYARI: admob.properties boş, Google'ın TEST reklam kimlikleri kullanılıyor. Bu reklamlar gelir getirmez.")
}

// ---------- oyun dosyası ----------
/**
 * Kökteki index.html'i (web sürümüyle aynı oyun) derleme sırasında uygulamanın assets klasörüne koyar.
 * Google Fonts bağlantıları kaldırılır, yerine fonts.css (uygulamanın kendi içinde taşıdığı yazı tipleri,
 * assets/fonts/) sayfaya gömülür: yazı tipleri internetsiz de görünür. sw.js WebView'de gerekmediği için kopyalanmaz.
 */
abstract class GameAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gameHtml: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fontsCss: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyGame() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val fontsLink = "<style>\n" + fontsCss.get().asFile.readText(Charsets.UTF_8).trim() + "\n</style>"
        val cssLink = Regex("""<link\b[^>]*fonts\.googleapis\.com/css[^>]*>""")
        val otherFontLinks = Regex("""[ \t]*<link\b[^>]*fonts\.(googleapis|gstatic)\.com[^>]*>[ \t]*\r?\n?""")
        var html = gameHtml.get().asFile.readText(Charsets.UTF_8)
        if (!html.contains("AndroidTTS")) {
            throw GradleException("index.html içinde AndroidTTS köprüsü bulunamadı; oyun dosyası beklenenden farklı.")
        }
        // Regex.replaceFirst yerine düz metin: CSS içindeki $ ve \\ işaretleri yer tutucu sanılmasın.
        val found = cssLink.find(html)
        if (found != null) html = html.replaceRange(found.range, fontsLink)
        html = otherFontLinks.replace(html, "")
        if (!html.contains(fontsLink)) {
            html = when {
                html.contains("</head>") -> html.replaceFirst("</head>", "$fontsLink\n</head>")
                html.contains("<style") -> html.replaceFirst("<style", "$fontsLink\n<style")
                else -> "$fontsLink\n$html"
            }
        }
        out.resolve("index.html").writeText(html, Charsets.UTF_8)
    }
}

android {
    namespace = "io.github.maelstoorm.metroistanbul"
    compileSdk = 36

    defaultConfig {
        // Önceki APK (sürüm 3.9, versionCode 30) ile aynı paket adı: aynı uygulama.
        applicationId = "io.github.maelstoorm.metroistanbul"
        minSdk = 24
        targetSdk = 36
        versionCode = 40
        versionName = "3.9"
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_REWARDED_ID", "\"$admobRewardedId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // İmza ayarı yok: AAB imzasız çıkar, Play'e yüklemeden önce yükleme anahtarıyla imzalanır.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val task = project.tasks.register<GameAssetsTask>("copyGameHtml$suffix") {
            gameHtml.set(repoRoot.resolve("index.html"))
            fontsCss.set(project.layout.projectDirectory.file("fonts.css"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, GameAssetsTask::outputDir)
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
