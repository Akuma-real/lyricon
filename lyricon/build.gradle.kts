import com.android.build.api.dsl.ApplicationExtension
import groovy.json.JsonOutput
import groovy.util.Node
import groovy.xml.XmlParser

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

configure<ApplicationExtension> {
    namespace = rootProject.extra["appPackageName"] as String

    compileSdk {
        version = release(rootProject.extra.get("compileSdkVersion") as Int)
    }

    defaultConfig {
        applicationId = rootProject.extra["appPackageName"] as String
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("RELEASE_STORE_FILE") ?: "release.jks")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":app"))
    implementation(project(":xposed"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register("exportLicensesJson") {
    group = "verification"
    description = "解析 POM 元数据并生成按项目名称排序的开源许可证 JSON 报告"

    val configurationName = "debugRuntimeClasspath"
    val runtimeConfig = configurations.named(configurationName)

    val componentIdsProvider = runtimeConfig.map { config ->
        config.incoming.resolutionResult.allComponents.map { it.id }
    }

    inputs.property("componentIds", componentIdsProvider.map { ids -> ids.map { it.displayName } })
    val outputFile =
        layout.projectDirectory.file("src/main/assets/open_source_licenses.json").asFile
    outputs.file(outputFile)

    doLast {
        val ids = componentIdsProvider.get()
        val results = mutableListOf<OpenSourceProject>() // 使用模型列表
        val parser = XmlParser(false, false)

        val queryResult = project.dependencies.createArtifactResolutionQuery()
            .forComponents(ids)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
            .execute()

        fun findNode(parent: Node, tag: String): Node? {
            val nsTag = "{http://maven.apache.org/POM/4.0.0}$tag"
            return (parent.children() ?: emptyList<Any>()).filterIsInstance<Node>().firstOrNull {
                val name = it.name().toString()
                name == tag || name == nsTag
            }
        }

        fun findText(parent: Node, tag: String): String =
            findNode(parent, tag)?.text()?.trim() ?: ""

        queryResult.resolvedComponents.forEach { component ->
            val id = component.id as? ModuleComponentIdentifier ?: return@forEach
            val pomArtifact = component.getArtifacts(MavenPomArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>()
                .firstOrNull() ?: return@forEach

            try {
                val pom = parser.parse(pomArtifact.file)

                // 1. 坐标处理
                val artifactId = findText(pom, "artifactId")
                var groupId = findText(pom, "groupId")
                var version = findText(pom, "version")
                val parentNode = findNode(pom, "parent")
                if (parentNode != null) {
                    if (groupId.isEmpty()) groupId = findText(parentNode, "groupId")
                    if (version.isEmpty()) version = findText(parentNode, "version")
                }

                // 2. 开发者
                val developers = mutableListOf<String>()
                findNode(pom, "developers")?.children()?.filterIsInstance<Node>()?.forEach { dev ->
                    val dName = findText(dev, "name")
                    if (dName.isNotEmpty()) developers.add(dName)
                }

                // 3. 许可证
                val licenses = mutableListOf<LicenseModel>()
                findNode(pom, "licenses")?.children()?.filterIsInstance<Node>()?.forEach { lic ->
                    val lName = findText(lic, "name")
                    val lUrl = findText(lic, "url")
                    if (lName.isNotEmpty()) {
                        licenses.add(LicenseModel(lName, lUrl))
                    }
                }

                // 4. 组装模型类
                results.add(
                    OpenSourceProject(
                        project = findText(pom, "name").ifEmpty { artifactId },
                        description = findText(pom, "description").takeIf { it.isNotEmpty() },
                        version = version,
                        developers = developers.ifEmpty { listOf("The Android Open Source Project") },
                        url = findText(pom, "url").takeIf { it.isNotEmpty() },
                        year = findText(pom, "inceptionYear").takeIf { it.isNotEmpty() },
                        licenses = licenses,
                        dependency = "${id.group}:${id.module}" // 用于去重，不带版本号
                    )
                )
            } catch (_: Exception) {
            }
        }

        // [去重与排序]
        // 1. 根据 dependency (group:artifact) 去重，保留版本较新或第一个发现的
        // 2. 根据项目名称 project 不区分大小写排序
        val finalResults = results
            .distinctBy { it.dependency }
            .sortedBy { it.project.lowercase() }

        // 输出
        outputFile.parentFile.mkdirs()
        outputFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(finalResults)))

        println("\n✨ 任务圆满完成！")
        println("📊 去重后共计 ${finalResults.size} 个开源条目")
        println("📍 路径: ${outputFile.absolutePath}")
    }
}

// 许可证模型
data class LicenseModel(
    val name: String,
    val url: String
)

// 开源项目模型
data class OpenSourceProject(
    val project: String,
    val description: String?,
    val version: String,
    val developers: List<String>,
    val url: String?,
    val year: String?,
    val licenses: List<LicenseModel>,
    val dependency: String // 用于去重的唯一标识（group:artifact）
)