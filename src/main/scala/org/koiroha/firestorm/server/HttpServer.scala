/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * org.koiroha.firestorm.http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.server

import org.koiroha.firestorm.ServerConfig
import org.koiroha.firestorm.http.Http

object HttpServer {

	def main(args:Array[String]):Unit = {
		val server = new ServerConfig().withId("http").withJMX(true).bind(8080).bind(Http())
		server.startup()
		Runtime.getRuntime.addShutdownHook(new Thread(){
			override def run(){
				server.shutdown(5 * 1000)
			}
		})
	}
}
