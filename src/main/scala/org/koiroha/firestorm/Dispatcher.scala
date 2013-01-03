/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */

package org.koiroha.firestorm

import java.nio.channels._
import java.nio.ByteBuffer
import java.util.concurrent.{LinkedBlockingQueue, ArrayBlockingQueue}
import scala.collection.JavaConversions._
import java.io.IOException

/**
 *
 */
private[firestorm] class Dispatcher[T](id:String, readBufferSize:Int) extends Thread {
	setName("FirestormServer[%s]".format(id))
	setDaemon(false)

	/**
	 * Channel selector on this dispatcher.
	 */
	val selector = Selector.open()

	/**
	 * Shared internal buffer to read data from all socket. This instance is never shared between
	 * threads.
 	 */
	val readBuffer = ByteBuffer.allocateDirect(readBufferSize)

	val joinQueue = new ArrayBlockingQueue[()=>Unit](100)

	def add(channel:SocketChannel){
		execEventLoop { () => join(channel) }
	}

	def add(channel:ServerSocketChannel){
		execEventLoop { () =>
			channel.configureBlocking(false)
			channel.register(selector, SelectionKey.OP_ACCEPT)
		}
	}

	private[this] def execEventLoop(job:()=>Unit){
		joinQueue.put(job)
		selector.wakeup()
	}

	override def run():Unit = try {
		onStartup()

		// loop while reject ServerSocketChannel from selector
		while (! this.isInterrupted) {
			val count = selector.select()

			while(! joinQueue.isEmpty){
				joinQueue.take()()
			}

			if(count > 0){
				val selectedKeys = selector.selectedKeys()
				selectedKeys.toList.foreach {   // ConcurrentModificationException
					key =>
						selectedKeys.remove(key)
						selected(key)
				}
			}
		}
	} catch {
		case ex:Throwable =>
			if (ex.isInstanceOf[ThreadDeath]){
				throw ex
			}
			EventLog.fatal(ex, "")
	} finally {
		onShutdown()
	}

	/**
	 * Handle single selection key.
	 * @param key selected key
	 */
	private[this] def selected(key:SelectionKey):Unit = {
		// *** catch exception and close individual connection only read or write
		try {

			// read from channel
			if (key.isReadable) {
				val endpoint = key.attachment().asInstanceOf[EndpointImpl]
				if(! endpoint.in.internalRead(readBuffer)){
					EventLog.debug("connection reset by peer: %s".format(endpoint))
					closeSelectionKey(key)
				}
				return
			}

			// write to channel
			if (key.isWritable) {
				val endpoint = key.attachment().asInstanceOf[EndpointImpl]
				endpoint.out.internalWrite()
				return
			}

		} catch {
			case ex:Throwable =>
				if (ex.isInstanceOf[ThreadDeath]){
					throw ex
				}
				onError(key.attachment().asInstanceOf[Endpoint[T]], ex)
				closeSelectionKey(key)
				return
		}

		// accept new connection (server behaviour)
		if (key.isAcceptable) {
			val socket = key.channel().asInstanceOf[ServerSocketChannel].accept()
			EventLog.debug("\"%s\" accepts new connection from %s on port %d".format(
				id,
				socket.socket().getInetAddress.getHostAddress, socket.socket().getPort))
			onAccept(socket)
			join(socket)
		}
	}

	/**
	 * Join new client channel to this dispatcher.
	 * @param channel new channel
	 */
	private[this] def join(channel:SocketChannel){
		channel.configureBlocking(false)
		val selectionKey = channel.register(selector, SelectionKey.OP_READ)
		val endpoint = new EndpointImpl(selectionKey)
		selectionKey.attach(endpoint)
		onOpen(endpoint)
	}

	def onStartup():Unit = { }

	def onShutdown():Unit = { }

	def onAccept(channel:SocketChannel):Unit = { }

	def onOpen(endpoint:Endpoint[T]):Unit = { }

	def onClosing(endpoint:Endpoint[T]):Unit = { }

	def onClosed(endpoint:Endpoint[T]):Unit = { }

	def onRead(endpoint:Endpoint[T], length:Int):Unit = { }

	def onWrite(endpoint:Endpoint[T], length:Int):Unit = { }

	def onError(endpoint:Endpoint[T], ex:Throwable):Unit = { }

	/**
	 * Stop to accept new connection and request to close all processing connections.
	 * @param waittime gracefully close in milliseconds
	 */
	def shutdown(waittime:Long):Unit = {

		// stop to accept new connection
		selector.keys().filter{ _.channel().isInstanceOf[ServerSocketChannel] }.foreach { key =>
			key.cancel()
			EventLog.debug("closing \"%s\" server channel %s on port %d".format(
				id,
				key.channel().asInstanceOf[ServerSocketChannel].socket().getInetAddress.getHostAddress,
				key.channel().asInstanceOf[ServerSocketChannel].socket().getLocalPort
			))
			key.channel().close()
		}

		// request to close all sessions
		selector.keys().filter{ ! _.channel().isInstanceOf[ServerSocketChannel] }.foreach { key =>
			onClosing(key.attachment().asInstanceOf[Endpoint[T]])
		}

		// wait to gracefully close for all channels
		val start = System.nanoTime()
		while((System.nanoTime() - start) * 1000 * 1000 < waittime && selector.keys().size() > 0){
			Thread.sleep(300)
		}

		// close all channels forcibly
		selector.keys().foreach { key =>
			closeSelectionKey(key)
		}

		if(Thread.currentThread().isDaemon){
			EventLog.warn("shutdown is processing in daemon thread")
			EventLog.warn("note that jvm will exit immediately in case it becomes only daemon threads by the end of this server")
		}

		// request to stop server thread
		this.interrupt()
		selector.wakeup()
		this.join()

		// close selector
		selector.close()
	}

	/**
	 * Detach specified selection key and close channel. This method is for client channel only.
	 * @param key selection key to close
	 */
	private[this] def closeSelectionKey(key:SelectionKey){
		key.cancel()
		key.channel().close()
		if(key.attachment().isInstanceOf[Endpoint[T]]){
			onClosing(key.attachment().asInstanceOf[Endpoint[T]])
		}
	}

	private[firestorm] class EndpointImpl(selectionKey:SelectionKey) extends Endpoint[T]{

		/**
		 * I/O channel of this endpoint.
		 */
		val channel = selectionKey.channel().asInstanceOf[SocketChannel]

		/**
		 * Protocol specified session.
		 */
		var session:Option[T] = None

		/**
		 * Finish all pending writing buffer and close this endpoint.
		 */
		def close():Unit = {
			EventLog.debug("closing connection by protocol")
			channel.shutdownInput()
			onClosing(this)
			out.close()
			onClosed(this)
		}

		override def toString():String = {
			"%s on port %d".format(
				channel.socket().getInetAddress.getHostAddress, channel.socket().getPort)
		}

		val in = new ReadableStreamBuffer() {
			val initialSize:Int = 4 * 1024
			val factor:Double = 1.5

			/**
			 * Internal buffer that automatically expand. This is not overwritten for available data block
			 * because these are shared by chunked ByteBuffer.
			 */
			private[this] var buffer = new Array[Byte](initialSize)

			/**
			 * Available data length in `buffer` from `consumed`.
			 */
			var available:Int = 0

			/**
			 * Available data offset from head of `buffer`.
			 */
			var consumed:Int = 0

			/**
			 * Available data length contained in this steam buffer.
			 */
			def length:Int = available

			/**
			 * Read from channel by use of specified buffer and append internal buffer, notify to receive
			 * to protocol.
			 * @param buffer commonly used read buffer
			 * @return false if channel reaches end-of-stream
			 */
			def internalRead(buffer:ByteBuffer):Boolean = {
				val length = channel.read(buffer)
				if(length < 0){
					false
				} else {
					if(length > 0){
						buffer.flip()
						append(buffer, length)
						onRead(EndpointImpl.this, length)
						buffer.clear()
					}
					true
				}
			}

			def consume(trimmer:(Array[Byte], Int, Int)=>Int):Option[ByteBuffer] = synchronized{
				val len = trimmer(buffer, consumed, available)
				assert(len <= available)
				if(len <= 0){
					None
				} else {
					val buf = ByteBuffer.wrap(buffer, consumed, len)
					consumed += len
					available -= len
					Some(buf)
				}
			}

			private[this] def append(buf:ByteBuffer, length:Int):Unit = synchronized{

				// expand internal buffer if remaining area is too small
				if(buffer.length - consumed - available < buf.remaining()){
					val newSize = math.max((available + buf.remaining()) * factor, initialSize).toInt
					val temp = new Array[Byte](newSize)
					System.arraycopy(buffer, consumed, temp, 0, available)
					buffer = temp
					consumed = 0
				}

				// append specified data to internal buffer
				buf.get(buffer, consumed + available, length)
				this.available += length
			}

		}

		val out = new WritableStreamBuffer() {

			/**
			 * Flag to specify that write operation is invalid.
			 */
			private[this] var writeOperationClosed = false

			var length = 0

			/**
			 * Write pending queue that may contain ByteBuffers or other operations.
			 */
			private[this] val writeQueue = new LinkedBlockingQueue[()=>Boolean](Int.MaxValue)

			def andThen(callback: =>Unit):WritableStreamBuffer = writeQueue.synchronized {
				if(writeOperationClosed){
					throw new IOException("channel closed")
				}
				post {() =>
					callback
					true
				}
				this
			}

			def write(buffer:ByteBuffer):WritableStreamBuffer = writeQueue.synchronized {
				if(writeOperationClosed){
					throw new IOException("channel closed")
				}
				post {() =>
					val len = channel.write(buffer)
					length -= len
					onWrite(EndpointImpl.this, len)
					(buffer.remaining() == 0)
				}
				length += buffer.remaining()
				this
			}

			def close():Unit = writeQueue.synchronized {
				writeOperationClosed = true
			}

			private[this] def post(f:()=>Boolean){
				writeQueue.put(f)
				selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE)
			}

			/**
			 * Write buffered data internally.
			 * This take job from queue and execute it
			 */
			def internalWrite():Unit = writeQueue.synchronized {
				val callback = writeQueue.peek()
				if(callback()){
					writeQueue.remove()
					if(writeQueue.isEmpty){
						if (writeOperationClosed){
							selectionKey.cancel()
							channel.close()
							EventLog.debug("connection closed by protocol")
						} else if(selectionKey.isValid){
							selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE)
						}
					}
				}
			}

		}
	}

}
