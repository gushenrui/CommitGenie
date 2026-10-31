plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    // 版本由 settings.gradle.kts 中的 platform.settings 插件统一管理
    id("org.jetbrains.intellij.platform")
}

group = "com.gushenrui.commitgenie"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        // 复用本机已安装的 Android Studio，避免下载完整 IDE 发行包
        local("/Applications/Android Studio.app")
        bundledPlugin("Git4Idea")
        // local() 默认只暴露平台 API 层 jar，patch 构建、VCS 日志等实现层模块需显式声明
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.log")
        bundledModule("intellij.platform.vcs.log.graph")
        bundledModule("intellij.platform.vcs.log.impl")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
    }
}

kotlin {
    // 不启用 toolchain：构建本身已由 JBR 25 启动（见 install.sh 设置的 JAVA_HOME），
    // 启用 toolchain 会触发 JDK 探测甚至自动下载，徒增构建时间与失败面
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

intellijPlatform {
    pluginConfiguration {
        id = "com.gushenrui.commitgenie"
        name = "CommitGenie"
        version = project.version.toString()
        description = """
            <p>使用 OpenAI 兼容（/chat/completions）或 Anthropic（/messages）接口的模型生成 Git 提交信息。</p>
            <p>在提交面板的消息输入框工具栏点击按钮，或按 ⌃\\ 快捷键，即可根据当前勾选的变更生成提交信息。</p>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }

    buildSearchableOptions = false
    instrumentCode = false
}
