plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.spring)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.spring.boot)
	alias(libs.plugins.spring.dependency.management)
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

dependencies {
	// Koog core + Spring AI chat ブリッジ（最小疎通はこの2つで足りる）
	implementation(libs.koog.agents)
	implementation(libs.koog.spring.ai.chat)
	// Spring AI の ChatMemoryRepository を Koog の ChatHistoryProvider に橋渡しする starter
	implementation(libs.koog.spring.ai.chat.memory)
	// Spring AI の VectorStore を Koog 側 SearchStorage/WriteStorage に橋渡しする starter
	implementation(libs.koog.spring.ai.vector.store)

	// Spring AI OpenAI（version は BOM 管理）
	implementation(libs.spring.ai.openai)
	// Spring AI VectorStore 本体（VectorStore interface / SearchRequest 等）
	implementation(libs.spring.ai.vector.store)
	// Spring AI PgVectorStore auto-config + JdbcTemplate 等を巻き込んでくる
	implementation(libs.spring.ai.pgvector)
	// Postgres 向け JDBC ドライバ。実 DB 接続は runtime のみで OK
	runtimeOnly(libs.postgresql)

	implementation(libs.spring.boot.starter.web)
	implementation(libs.jackson.module.kotlin)
	implementation(libs.kotlin.reflect)

	testImplementation(libs.spring.boot.starter.test)
	testImplementation(libs.kotlin.test.junit5)
	testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyManagement {
	imports {
		mavenBom(libs.spring.ai.bom.get().toString())
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
