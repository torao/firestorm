/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

package object echo {

	object Echo extends Protocol[Any] {

		val name:String = "echo"

		def open(session:Endpoint[Any]):Unit = { }

		def close(session:Endpoint[Any]):Unit = { }

		def receive(session:Endpoint[Any]):Unit = {
			session.in.consume { (buffer, offset, length) => length } match {
				case Some(buffer) =>
					session.out.write(buffer)
				case None => None
			}
		}
	}

}
