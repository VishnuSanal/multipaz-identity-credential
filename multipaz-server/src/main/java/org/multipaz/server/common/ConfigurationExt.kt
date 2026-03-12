package org.multipaz.server.common

import org.multipaz.rpc.backend.Configuration

val Configuration.serverHost: String? get() {
    return getValue("server_host")
}

// must be set in default configuration
val Configuration.serverPort: Int get() =
    getValue("server_port")!!.toInt()

// derived from serverPort and serverHost if not set
val Configuration.baseUrl: String get() = "http://192.168.1.7:8007"

val Configuration.enrollmentServerUrl: String? get() = getValue("enrollment_server_url")