package com.example.core.storage

enum class ProjectTemplateType(
  val displayName: String,
  val primaryLanguage: String,
  val defaultEntryFile: String,
) {
  ANDROID_KOTLIN("Android (Kotlin + Compose)", "Kotlin", "app/src/main/java/MainActivity.kt"),
  ANDROID_JAVA("Android (Java)", "Java", "app/src/main/java/MainActivity.java"),
  KOTLIN_CLI("Kotlin CLI", "Kotlin", "src/main/kotlin/Main.kt"),
  JAVA_CLI("Java CLI", "Java", "src/Main.java"),
  FLUTTER("Flutter App", "Dart", "lib/main.dart"),
  WEB_APP("Web (HTML / CSS / JS)", "HTML", "index.html"),
}

object ProjectTemplates {

  fun generateFiles(
    projectName: String,
    packageName: String,
    template: ProjectTemplateType,
  ): Map<String, String> {
    val pkgPath = packageName.replace('.', '/')
    return when (template) {
      ProjectTemplateType.ANDROID_KOTLIN ->
        mapOf(
          "settings.gradle.kts" to
            """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            rootProject.name = "$projectName"
            include(":app")
            """.trimIndent(),
          "build.gradle.kts" to
            """
            plugins {
                id("com.android.application") version "8.7.2" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
            }
            """.trimIndent(),
          "app/build.gradle.kts" to
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "$packageName"
                compileSdk = 35

                defaultConfig {
                    applicationId = "$packageName"
                    minSdk = 24
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation(platform("androidx.compose:compose-bom:2024.10.00"))
                implementation("androidx.compose.material3:material3")
            }
            """.trimIndent(),
          "app/src/main/AndroidManifest.xml" to
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="$projectName"
                    android:supportsRtl="true">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent(),
          "app/src/main/java/$pkgPath/MainActivity.kt" to
            """
            package $packageName

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MaterialTheme {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(text = "Hello from $projectName")
                                }
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
          "app/src/main/res/values/strings.xml" to
            """
            <resources>
                <string name="app_name">$projectName</string>
            </resources>
            """.trimIndent(),
        )

      ProjectTemplateType.ANDROID_JAVA ->
        mapOf(
          "settings.gradle" to
            """
            rootProject.name = '$projectName'
            include ':app'
            """.trimIndent(),
          "app/build.gradle" to
            """
            plugins {
                id 'com.android.application'
            }

            android {
                namespace '$packageName'
                compileSdk 35
                defaultConfig {
                    applicationId "$packageName"
                    minSdk 24
                    targetSdk 35
                    versionCode 1
                    versionName "1.0"
                }
            }

            dependencies {
                implementation 'androidx.appcompat:appcompat:1.7.0'
            }
            """.trimIndent(),
          "app/src/main/AndroidManifest.xml" to
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="$projectName">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent(),
          "app/src/main/java/$pkgPath/MainActivity.java" to
            """
            package $packageName;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView label = new TextView(this);
                    label.setText("$projectName running on Android");
                    setContentView(label);
                }
            }
            """.trimIndent(),
        )

      ProjectTemplateType.KOTLIN_CLI ->
        mapOf(
          "build.gradle.kts" to
            """
            plugins {
                kotlin("jvm") version "2.0.21"
                application
            }

            repositories { mavenCentral() }

            application {
                mainClass.set("$packageName.MainKt")
            }
            """.trimIndent(),
          "src/main/kotlin/Main.kt" to
            """
            package $packageName

            fun greet(target: String): String {
                return "Hello, ${'$'}target from $projectName!"
            }

            fun main() {
                println(greet("DevForge AI"))
            }
            """.trimIndent(),
        )

      ProjectTemplateType.JAVA_CLI ->
        mapOf(
          "src/Main.java" to
            """
            package $packageName;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("$projectName initialized.");
                }
            }
            """.trimIndent(),
        )

      ProjectTemplateType.FLUTTER ->
        mapOf(
          "pubspec.yaml" to
            """
            name: ${projectName.lowercase().replace(Regex("[^a-z0-9_]"), "_")}
            description: Flutter project created in DevForge AI
            version: 1.0.0+1

            environment:
              sdk: ">=3.3.0 <4.0.0"

            dependencies:
              flutter:
                sdk: flutter

            flutter:
              uses-material-design: true
            """.trimIndent(),
          "lib/main.dart" to
            """
            import 'package:flutter/material.dart';

            void main() => runApp(const DevForgeFlutterApp());

            class DevForgeFlutterApp extends StatelessWidget {
              const DevForgeFlutterApp({super.key});

              @override
              Widget build(BuildContext context) {
                return MaterialApp(
                  title: '$projectName',
                  theme: ThemeData.dark(useMaterial3: true),
                  home: Scaffold(
                    appBar: AppBar(title: const Text('$projectName')),
                    body: const Center(
                      child: Text('Flutter workspace ready'),
                    ),
                  ),
                );
              }
            }
            """.trimIndent(),
        )

      ProjectTemplateType.WEB_APP ->
        mapOf(
          "index.html" to
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>$projectName</title>
              <link rel="stylesheet" href="styles.css" />
            </head>
            <body>
              <main class="container">
                <h1>$projectName</h1>
                <p id="status">Interactive web runtime active.</p>
                <button id="ping-btn" type="button">Run Check</button>
              </main>
              <script src="app.js"></script>
            </body>
            </html>
            """.trimIndent(),
          "styles.css" to
            """
            :root {
              color-scheme: dark;
              font-family: system-ui, -apple-system, sans-serif;
            }
            body {
              margin: 0;
              min-height: 100vh;
              display: grid;
              place-items: center;
              background: #0d1117;
              color: #e6edf3;
            }
            .container {
              padding: 24px;
              border: 1px solid #30363d;
              border-radius: 8px;
              background: #161b22;
              max-width: 420px;
            }
            button {
              background: #38bdf8;
              color: #04263a;
              border: none;
              padding: 10px 16px;
              border-radius: 6px;
              font-weight: 600;
            }
            """.trimIndent(),
          "app.js" to
            """
            const statusEl = document.getElementById("status");
            const btn = document.getElementById("ping-btn");
            let count = 0;

            btn?.addEventListener("click", () => {
              count += 1;
              const msg = `Execution check #${'$'}{count} passed at ${'$'}{new Date().toLocaleTimeString()}`;
              if (statusEl) statusEl.textContent = msg;
              console.log(msg);
            });
            """.trimIndent(),
        )
    }
  }
}
