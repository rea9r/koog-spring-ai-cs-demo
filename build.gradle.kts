plugins {
	kotlin("jvm") version "2.3.10"
	kotlin("plugin.spring") version "2.3.10"
	kotlin("plugin.serialization") version "2.3.10"
	id("org.springframework.boot") version "3.5.14"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		// 公式サンプルは 21。手元の Corretto 17 で動作確認するため 17 に。
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

// Koog が要求する kotlinx の版に固定（公式サンプル準拠・classpath 衝突回避）
configurations.all {
	resolutionStrategy {
		eachDependency {
			if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")) {
				useVersion("1.10.2")
			}
			if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization")) {
				useVersion("1.10.0")
			}
		}
	}
}

extra["springAiVersion"] = "1.1.4"

dependencies {
	// Koog core + Spring AI chat ブリッジ（最小疎通はこの2つで足りる）
	implementation("ai.koog:koog-agents-jvm:0.8.0")
	implementation("ai.koog:koog-spring-ai-starter-model-chat:0.8.0")

	// Spring AI OpenAI（version は BOM 管理なので無指定）
	implementation("org.springframework.ai:spring-ai-starter-model-openai")

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
