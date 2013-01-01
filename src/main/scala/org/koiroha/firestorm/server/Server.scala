/*
 * Copyright (c) 2012 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm.server

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, ServerSocketChannel, SocketChannel, Selector}
import scala.collection.JavaConversions._
import org.koiroha.firestorm.EventLog

/**
 * Generic asynchronous server.
 * @param config server configuration
 */
class Server(config:ServerBuilder, protocol:Protocol) {

	/**
	 * Server connection state that have ServerSocket.
	 */
	var state:Option[State] = None

	/**
	 * Startup server.
	 */
	def startup():Unit = synchronized{
		state = Some(new State(config.createSocket))
		state.foreach{ s =>
			s.synchronized{
				s.start()
				s.wait()
			}
		}
	}

	/**
	 * Shutdown server.
	 */
	def shutdown():Unit = synchronized{
		state.foreach { close }
		state = None
	}

	private[firestorm] class Peer(selector:Selector, channel:SocketChannel) extends Session{
		socket.configureBlocking(false)
		private[this] val selectionKey = channel.register(selector, SelectionKey.OP_READ, this)

		var closeWhenAllBufferWritten = false

		private[this] var readBuffer:Array[Byte] = Array[Byte](1024)
		private[this] var readLength:Int = 0
		def read(buffer:ByteBuffer):Unit = {
			val length = channel.read(buffer)
			if (readLength + length > readBuffer.length) {
				val temp = Array[Byte](((readBuffer.length + length) * 1.2).toInt)
				System.arraycopy(readBuffer, 0, temp, 0, readLength)
				readBuffer = temp
			}
			buffer.flip()
			buffer.put(readBuffer, readLength, length)
			readLength += length

			val consume = protocol.parse(readBuffer, 0, readLength)
			if (consume > 0){

			}
		}

		def consume(f:(Array[Byte],Int,Int)=>Int):Unit = {
			val length = f(readBuffer, 0, readLength)
			if (length > 0){
				System.arraycopy(readBuffer, length, readBuffer, 0, readLength - length)
				readBuffer -= length
			}
		}

		private[this] val writeBuffer = new ByteArrayOutputStream()
		private[this] var writeBuffer2 = ByteBuffer.allocate(0)
		private[this] var writeLength = 0
		def write(buffer:Array[Byte], offset:Int, length:Int):Unit = {
			writeBuffer.write(buffer, offset, length)
			if (writeLength >= writeBuffer2.limit()){
				writeBuffer2 = ByteBuffer.wrap(writeBuffer.toByteArray)
				writeBuffer.reset()
				writeLength = 0
				selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE)
			}
		}
		private[firestorm] def write():Unit = {
			val length = channel.write(writeBuffer2)
			writeLength += length
			if (isWriteBufferEmpty){
				if (! closeInternal()){
					selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE)
				}
			}
		}

		def close():Unit = {
			closeWhenAllBufferWritten = true
			closeInternal()
		}

		private[this] def isWriteBufferEmpty:Boolean = writeLength == writeBuffer2.limit() && writeBuffer.size() == 0
		private[this] def closeInternal():Boolean = {
			if (closeWhenAllBufferWritten && isWriteBufferEmpty){
				selectionKey.cancel()
				channel.close()
			} else {
				selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE)
			}
		}
	}

	/**
	 *
	 * @param channel
	 */
	private[firestorm] class State(channel:ServerSocketChannel) extends Thread {
		val selector = Selector.open()
		channel.register(selector, SelectionKey.OP_ACCEPT)

		override def run():Unit = try {
			EventLog.info("startup server")

			// shared read-buffer to read data from all socket
			val readBuffer = ByteBuffer.allocateDirect(config.readBufferSize)

			// notify startup
			synchronized{
				notify()
			}

			while (selector.isOpen) {
				selector.select()
				selector.selectedKeys().foreach {
					key =>
						if (key.isReadable) {
							if (key.channel().asInstanceOf[SocketChannel].isOpen) {
								val session = key.attachment().asInstanceOf[Session]
								session.read(readBuffer)
							} else {
								key.cancel()
							}
						}
						if (key.isWritable) {
							val session = key.attachment().asInstanceOf[Session]
							session.write()
						}
						if (key.isAcceptable) {
							val socket = key.cancel().asInstanceOf[ServerSocketChannel].accept()
							val client = new Peer(selector, socket)
							protocol.open(client)
						}
				}
			}
		} catch {
			case ex:Throwable =>
				EventLog.fatal(ex, "")
				if (ex.isInstanceOf[ThreadDeath]){
					throw ex
				}
		} finally {
			EventLog.info("shutdown server")
		}

		/**
		 * Close server state.
		 * @param waittime gracefully close in milliseconds
		 */
		def close(waittime:Long):Unit = {

			// reject acceptable channel to deny new connection
			val key = channel.keyFor(selector)
			key.cancel()
			channel.close()

			// request to close all sessions
			selector.keys().foreach { key =>
				try{
					protocol.shutdown(key.attachment().asInstanceOf[Session])
				} catch {
					case ex:Exception =>
						EventLog.warn(ex, "exception caught while shutting-down socket: " + protocol.name)
				}
			}

			// wait to gracefully close for all channels
			val start = System.nanoTime()
			while((System.nanoTime() - start) * 1000 * 1000 < waittime && selector.keys().size() > 0){
				Thread.sleep(300)
			}

			// close all channels forcibly
			selector.keys().foreach { key =>
				key.channel().close()
				key.cancel()
			}

			// close selector
			selector.wakeup()
			selector.close()
			EventLog.info("shutdown server")
		}

		override def toString():String = {
			val socket = channel.socket()
			"[%s:%d]".format(socket.getInetAddress.getAddress, socket.getLocalPort)
		}

	}

}
