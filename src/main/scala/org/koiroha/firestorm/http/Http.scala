/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * org.koiroha.firestorm.http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.http

import org.koiroha.firestorm.{EventLog, Endpoint, Protocol}

/**
 *
 * @param charset character set of header
 */
case class Http(charset:String = "UTF-8") extends Protocol[Handler]{

	val name:String = "http"

	def open(session:Endpoint[Handler]):Unit = {
		EventLog.trace("open(%s)".format(session))
		session.session = Some(new Handler(charset))
	}

	def close(session:Endpoint[Handler]):Unit = {
		EventLog.trace("close(%s)".format(session))
		session.session = None
	}

	def shutdown(session:Endpoint[Handler]):Unit = {
		EventLog.trace("shutdown(%s)".format(session))
		session.close()
	}

	def receive(session:Endpoint[Handler]):Unit = {
		EventLog.trace("receive(%s)".format(session))
		session.in.consume { (buffer, offset, length) =>
			val request = new String(buffer, offset, length)
			if (request.endsWith("\r\n\r\n") || request.endsWith("\n\n")){
				System.out.println(request)
				session.out
					.write(("HTTP/1.0 200 Ok\r\n" +
					"Connection: close\r\n" +
					"Content-Type: text/plain\r\n" +
					"\r\n" +
					"hello, world").getBytes)
					.andThen{
						session.close()
					}
				length
			} else {
				0
			}
		}
	}

}

private[http] class Handler(charset:String) {
	var status:String = _
	var headers = List[(String,String)]()
	var read:(Array[Byte],Int,Int)=>Int = readStatus

	/**
	 * Read status line from specified buffer.
	 * @return
	 */
	def readStatus(buffer:Array[Byte], offset:Int, length:Int):Int = {
		// "¥r" not supported because separated "¥r¥n" is recognized 2 lines
		Protocol.findUTF8Sequence(buffer, offset, length, "\r\n", "\n") match {
			case Some((i, lf)) =>
				status = new String(buffer, offset, i, charset)
				read = readHeader
				i + lf.length
			case None => 0
		}
	}

	def readHeader(buffer:Array[Byte], offset:Int, length:Int):Int = {
		// "¥r" not supported because separated "¥r¥n" is recognized 2 lines
		Protocol.findUTF8Sequence(buffer, offset, length, "\r\n", "\n") match {
			case Some((i, lf)) =>
				status = new String(buffer, offset, i, charset)
				if(status.length == 0){

				}
				i + lf.length
			case None => 0
		}
	}
}
