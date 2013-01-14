/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.server

import org.koiroha.firestorm.core.{Server, Context}
import org.koiroha.firestorm.http.{HttpServer, HttpProtocol}

/**
 * Created with IntelliJ IDEA.
 * User: torao
 * Date: 2013/01/14
 * Time: 21:59
 * To change this template use File | Settings | File Templates.
 */
object HttpTest {

	def main(args:Array[String]):Unit = {
		val context = new Context("http")
		val server = HttpServer(context).listen(8085)
	}

}
