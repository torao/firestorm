/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.http

import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, ThreadPoolExecutor}
import org.koiroha.firestorm.core.{Context, Endpoint, Server}
import scala.annotation.tailrec

case class HttpServer(context:Context) {
	private val server = Server[Request](context).onAccept {
		(server, endpoint) =>
			endpoint.onArrivalBufferedIn(arrivalBufferedIn)
			endpoint.onClose {
				_.state.foreach {
					_.close()
				}
			}
	}
	private val pool = new ThreadPoolExecutor(20, 20, 10, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable]())

	var maxStatusLineSize = 4 * 1024

	def arrivalBufferedIn(e:Endpoint[Request]):Unit = try {
		while (true) {

			e.state match {
				case None => HTTP.readLine(e) match {
					case Some(line) =>
						// ステータス行を読み出し
						val req = new Request(line)
						if (req.isBad) {
							throw new HTTP.BadRequest(line)
						}
						e.state = Some(req)
					case None =>
						if (e.in.length > maxStatusLineSize) {
							throw new HTTP.RequestURITooLong()
						}
						// ステータス行読み出し未完了
						return
				}
				case Some(request) => request.rawHeader match {
					case Some(str) =>
						// リクエストボディを設定
						request.sink.write(e.in.slice {
							_.remaining()
						}.get)
						return
					case None => HTTP.readHeader(e) match {
						case Some(h) =>
							request.rawHeader = Some(h)
							// 処理の開始
							pool.execute(new Runnable() {
								def run() = exec(e)
							})
						case None =>
							// ヘッダ読み出し未完了
							return
					}
				}
			}
		}
	} catch {
		case ex:HTTP.Exception =>
			e.out.write(ex.toByteArray)
			e.close()
	}

	private def exec(endpoint:Endpoint[Request]) {
		endpoint.out.write(("HTTP/1.1 200 OK\r\n" +
			"Connection: close\r\n" +
			"Content-Type: text/plain\r\n" +
			"\r\n\r\n" +
			"hello, world").getBytes("iso-8859-1"))
		endpoint.close()
	}

	/**
	 * 指定されたポート上で Listen するサーバを構築します。
	 * @param port
	 */
	def listen(port:Int):HttpServer = {
		server.listen(port)
		this
	}

	/**
	 * 指定されたアドレス上で Listen するサーバを構築します。
	 * @param local
	 * @param backlog
	 */
	def listen(local:SocketAddress, backlog:Int):HttpServer = {
		server.listen(local, backlog)
		this
	}

	def close():Unit = server.close()
}

/**
 * {{{
 *   val server = Server(context).onAccept(HttpProtocol.accept)
 * }}}
 */
object HttpProtocol {
}

case class Header(name:String, value:String)

class Headers private[http](var header:List[Header]) {
	def apply(name:String):Option[String] = {
		header.find {
			_.name.equalsIgnoreCase(name)
		} match {
			case Some(h) => Some(h.value)
			case None => None
		}
	}

	def update(name:String, value:String):Unit = {
		header ::= Header(name, value)
	}
}

class Request private[http](status:String) {
	val (method, uri, version) = HTTP.parseStatusLine(status)
	lazy val header = new Headers(rawHeader.get.split("\r\n?|\n").filter {
		_.trim().length() > 0
	}.foldLeft(List[String]()) {
		(header, line) =>
			if (header.length > 0 && Character.isWhitespace(line.charAt(0))) {
				val (head, tail) = header.splitAt(header.length - 1)
				head :+ (tail(0) + "\r\n" + line)
			} else {
				header :+ line
			}
	}.map {
		line =>
			val sep = line.indexOf(':')
			if (sep < 0) {
				Header("", line.trim())
			} else {
				Header(line.substring(sep).trim(), line.substring(sep).trim())
			}
	})

	def isBad:Boolean = {
		method == ""
	}

	private[http] var rawHeader:Option[String] = None
	private lazy val pipe:Pipe = Pipe.open()
	private[http] lazy val sink = pipe.sink()
	private[http] lazy val source = pipe.source()

	def close() {
		sink.close()
	}
}

private[http] object HTTP {
	val REQUEST_STATUS_LINE = "(\\S+)\\s+(\\S+)\\s+(\\S+)".r

	def parseStatusLine(line:String):(String, String, String) = {
		line.trim() match {
			case REQUEST_STATUS_LINE(method, uri, version) => (method, uri, version)
			case _ => ("", "", "")
		}
	}

	case class Exception(code:Int, message:String, content:String) extends java.lang.Exception {
		lazy val toByteArray:Array[Byte] = ("HTTP/1.1 %1$s %2$s\r\n" +
			"Connection: close\r\n" +
			"Content-Type: text/plain\r\n" +
			"Cache-Control: no-cache\r\n" +
			"\r\n" +
			"%3$s").format(code, message, content.replaceAll("<", "&lt;")).getBytes("iso-8859-1")
	}

	class BadRequest(content:String) extends Exception(400, "Bad Request", content)

	class RequestURITooLong extends Exception(414, "Request-URI Too Long", "")

	def readLine(e:Endpoint[Request]):Option[String] = {
		@tailrec
		def h(i:Int, buffer:ByteBuffer):Int = {
			if (i >= buffer.remaining()) {
				-1
			} else if (buffer.get(buffer.position() + i) == '\n') {
				i + 1
			} else {
				h(i + 1, buffer)
			}
		}
		e.in.slice {
			buffer => h(0, buffer)
		} match {
			case Some(buffer) =>
				val array = new Array[Byte](buffer.remaining())
				buffer.get(array)
				Some(new String(array, "iso-8859-1"))
			case None => None
		}
	}

	def readHeader(e:Endpoint[Request]):Option[String] = {
		@tailrec
		def h(i:Int, buffer:ByteBuffer):Int = {
			if (i >= buffer.remaining()) {
				-1
			} else if (buffer.get(buffer.position() + i) == '\n'
				&& i + 2 < buffer.remaining()
				&& buffer.get(buffer.position() + i + 1) == '\r'
				&& buffer.get(buffer.position() + i + 2) == '\n') {
				i + 2
			} else if (buffer.get(buffer.position() + i) == '\n'
				&& i + 1 < buffer.remaining()
				&& buffer.get(buffer.position() + i + 1) == '\n') {
				i + 1
			} else {
				h(i + 1, buffer)
			}
		}
		e.in.slice {
			buffer => h(0, buffer)
		} match {
			case Some(buffer) =>
				val array = new Array[Byte](buffer.remaining())
				buffer.get(array)
				Some(new String(array, "iso-8859-1"))
			case None => None
		}
	}
}