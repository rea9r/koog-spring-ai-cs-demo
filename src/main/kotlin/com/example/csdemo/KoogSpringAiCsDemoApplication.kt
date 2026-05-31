package com.example.csdemo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(HistoryCompressionConfig::class, ChatMemoryWindowProperties::class)
class KoogSpringAiCsDemoApplication

fun main(args: Array<String>) {
	runApplication<KoogSpringAiCsDemoApplication>(*args)
}
