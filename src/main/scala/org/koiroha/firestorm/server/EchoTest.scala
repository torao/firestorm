/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.server

import org.koiroha.firestorm.{Endpoint, Server, Dispatcher}
import java.nio.ByteBuffer

/**
 * Created with IntelliJ IDEA.
 * User: torao
 * Date: 2013/01/03
 * Time: 11:13
 * To change this template use File | Settings | File Templates.
 */
object EchoTest {
	def main(args:Array[String]):Unit = {

		val dispatcher1 = new Dispatcher("echo-server")
		val dispatcher2 = new Dispatcher("echo-client")

		val server = Server[Int](dispatcher1).onAccept { (_, ep) =>
			printf("S: onAccept()%n")
			ep.onArrivalBufferedIn { e =>
				e.in.consume { (_,_,length) => 4 } match {
					case Some(buffer) =>
						val value = buffer.getInt
						if (value != e.session.getOrElse(0)){
							throw new Exception("値が違います: %d != %d".format(value, e.session.getOrElse(0)))
						}
						e.session = Some(value + 1)
						buffer.position(buffer.position() - 4)
						e.out.write(buffer)
					case None => None
				}
			}
		}.listen(5007)

		val buffer = ByteBuffer.wrap(("hello, world" * 1).getBytes).asReadOnlyBuffer()
		val endpoints = for (i <- 1 to 1) yield {
			Endpoint[Int](dispatcher2).onConnect { ep =>
				printf("C: onConnect()%n")
			}.onArrivalBufferedIn { ep =>
				ep.in.consume { (_,_,length) => 4 } match {
					case Some(buffer) =>
						val value = buffer.getInt
						if ((value % 10000) == 0){
							printf("C: onArrivalBufferIn(%d)%n", value)
						}
					case None => None
				}
			}.onDepartureBufferedOut { ep =>
//				printf("C: onDepartureBufferOut()%n")
				val buf = ByteBuffer.allocate(4).putInt(ep.session.getOrElse(0))
				buf.flip()
				ep.out.write(buf)
				ep.session = Some(ep.session.getOrElse(0) + 1)
			}.connect("localhost", 5007)
		}
	}

}
