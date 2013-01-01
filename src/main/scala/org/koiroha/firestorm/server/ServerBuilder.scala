/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.server

import java.nio.channels.ServerSocketChannel
import java.net.ServerSocket

/**
 * Server configuration.
 */
class ServerBuilder {
	var port:Int = 8085

	val readBufferSize = 8 * 1024

	def createServerChannel():ServerSocketChannel = {
		new ServerSocket(port).getChannel
	}

}
