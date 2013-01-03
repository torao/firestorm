/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.server

import org.koiroha.firestorm.ServerConfig
import org.koiroha.firestorm.echo.Echo

/**
 * Created with IntelliJ IDEA.
 * User: torao
 * Date: 2013/01/03
 * Time: 6:54
 * To change this template use File | Settings | File Templates.
 */
object EchoServer {
	def main(args:Array[String]):Unit = {
		val server = new ServerConfig().withId("echo").withJMX(true).bind(5007).bind(Echo)
		server.startup()
	}

}
