package io.github.whywood.superpi.sdk.ai

import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.ai.http.okHttpTransport

fun defaultHttpTransport(): HttpTransport = okHttpTransport()

fun createDefaultModels(): Models = createModels(defaultHttpTransport())
